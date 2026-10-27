package ca.dstudio.atvlauncher.wallpaper.api

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log

/**
 * Base class for a wallpaper plugin. Extend it, implement [onRequest], and declare it as a service
 * (see [WallpaperContract] for the manifest shape); serve the pictures with a
 * [WallpaperFileProvider].
 *
 * **Lifecycle.** The launcher binds the service while the plugin is switched on and a launcher
 * window is started, and drives it:
 *
 * ```
 * attach(env, host) -> request(REASON_START) -> push -> [ shown | failed |
 *     request(REASON_TIMER | REASON_SETTINGS_CLOSED | REASON_MANUAL | REASON_INVALIDATED) -> push ]*
 *     -> detach
 * ```
 *
 * [onAttach] may arrive **again** with no [onDetach] in between, when either side was rebound or a
 * process restarted. Treat it as a reset.
 *
 * **Answering.** Every [onRequest] is answered with one [push], from any thread, when the answer is
 * ready: the whole list, in the order it is shown, at most [WallpaperContract.MAX_ITEMS] items (the
 * launcher takes no more, and [push] sends only the first ones). An empty batch means "nothing
 * yet" - push one when the real answer takes a while, and the real one when it is ready. An item's
 * id names one picture's content: new content needs a new id. [WallpaperBatch.nextRequestMs] says
 * when to ask again. Call [invalidate] when the plugin's data changes between requests; the
 * launcher asks again, at most once per [WallpaperContract.MIN_REQUEST_INTERVAL_MS].
 *
 * **Threading.** Every callback runs on the main thread, whatever thread the launcher called from.
 * Do slow work elsewhere: [push] and [invalidate] are safe from any thread.
 *
 * **Security.** Every incoming call is checked against
 * [WallpaperContract.PERMISSION_BIND_WALLPAPER] before it reaches your code, so only the launcher
 * can drive the plugin. Nothing here lends the plugin any of the launcher's permissions.
 */
abstract class WallpaperProviderService : Service() {

    private val main = Handler(Looper.getMainLooper())

    /** The host of the latest attach, until detach. */
    @Volatile
    private var host: IWallpaperHost? = null

    /** The environment of the latest attach, or null before the first. */
    @Volatile
    var environment: WallpaperEnvironment? = null
        private set

    private val binder = object : IWallpaperProvider.Stub() {

        override fun attach(env: WallpaperEnvironment, host: IWallpaperHost) = guarded {
            this@WallpaperProviderService.host = host
            environment = env
            onAttach(env)
        }

        override fun request(reason: Int) = guarded { onRequest(reason) }

        override fun shown(itemId: String) = guarded { onShown(itemId) }

        override fun failed(itemId: String, why: Int) = guarded { onFailed(itemId, why) }

        override fun detach() = guarded {
            onDetach()
            host = null
        }
    }

    /**
     * Runs on the binder thread, and reads the caller before the hop: after a post,
     * `Binder.getCallingUid()` answers for nobody. The decision is [callerAllowed]'s. The permission
     * is checked with `checkPermission`,
     * not `checkCallingPermission`, which refuses a caller in the same process.
     */
    private inline fun guarded(crossinline block: () -> Unit) {
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val allowed = callerAllowed(uid, Process.myUid()) {
            checkPermission(WallpaperContract.PERMISSION_BIND_WALLPAPER, pid, uid) == PackageManager.PERMISSION_GRANTED
        }
        if (!allowed) {
            Log.w(TAG, "dropping call from uid $uid without ${WallpaperContract.PERMISSION_BIND_WALLPAPER}")
            return
        }
        main.post { block() }
    }

    final override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The launcher is ready: [env] describes the screen ([environment] holds it too). A request
     * follows. Main thread.
     */
    open fun onAttach(env: WallpaperEnvironment) {}

    /**
     * Answer with [push], now or later, from any thread - always, an empty batch for "nothing yet".
     * [reason] is one of the `REASON_` constants in [WallpaperContract]. Main thread.
     */
    abstract fun onRequest(reason: Int)

    /** The launcher put this item on screen. Main thread. */
    open fun onShown(itemId: String) {}

    /**
     * The launcher could not use this item: [why] is [WallpaperContract.FAIL_UNREADABLE],
     * [WallpaperContract.FAIL_TOO_SMALL] or [WallpaperContract.FAIL_UNSUPPORTED]. Main thread.
     */
    open fun onFailed(itemId: String, why: Int) {}

    /** The plugin was switched off, or the launcher let it go. [push] is a no-op after this. Main thread. */
    open fun onDetach() {}

    /**
     * Hands the launcher a new list; replaces the last one. Sends the first
     * [WallpaperContract.MAX_ITEMS] items, in order - all the launcher takes - and logs when the batch
     * has more. No-op while detached. Any thread.
     */
    fun push(batch: WallpaperBatch) {
        val host = this.host ?: return
        val sent = cappedToMaxItems(batch)
        if (sent !== batch) {
            Log.w(TAG, "push: ${batch.items.size} items, sending the first ${WallpaperContract.MAX_ITEMS}")
        }
        try {
            host.push(sent)
        } catch (e: RemoteException) {
            Log.w(TAG, "push failed", e)
        }
    }

    /** Tells the launcher the plugin's data changed, so it asks again. No-op while detached. Any thread. */
    fun invalidate() {
        try {
            host?.invalidate()
        } catch (e: RemoteException) {
            Log.w(TAG, "invalidate failed", e)
        }
    }

    private companion object {
        private const val TAG = "WallpaperProviderService"
    }
}

/**
 * Whether a call into the plugin may reach its code: one from the plugin's own uid ([ownUid], a
 * plugin bound from its own process) passes, and any other caller needs
 * [WallpaperContract.PERMISSION_BIND_WALLPAPER]. [permissionGranted] is asked only for another uid,
 * as the check always did. Pure, so both branches are JVM-tested; [WallpaperProviderService] reads
 * the caller on the binder thread and hands it over.
 */
internal inline fun callerAllowed(callerUid: Int, ownUid: Int, permissionGranted: () -> Boolean): Boolean =
    callerUid == ownUid || permissionGranted()

/**
 * [batch] as [WallpaperProviderService.push] sends it: the first [WallpaperContract.MAX_ITEMS]
 * items, in order, with its [WallpaperBatch.nextRequestMs]. A batch within the cap is returned as
 * it is, the same instance.
 */
internal fun cappedToMaxItems(batch: WallpaperBatch): WallpaperBatch =
    if (batch.items.size <= WallpaperContract.MAX_ITEMS) {
        batch
    } else {
        batch.copy(items = batch.items.take(WallpaperContract.MAX_ITEMS))
    }
