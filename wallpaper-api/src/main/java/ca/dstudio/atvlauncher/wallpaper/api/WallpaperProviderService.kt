package ca.dstudio.atvlauncher.wallpaper.api

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.Surface
import org.xmlpull.v1.XmlPullParser

/**
 * Base class for a wallpaper plugin. Extend it, implement [onRequest], and declare it as a service
 * (see [WallpaperContract] for the manifest shape); serve the pictures with a
 * [WallpaperFileProvider]. A plugin of the surface kind draws instead, into the surfaces the
 * launcher lends it ([onSurfaceAttached]).
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
 * A plugin of the surface kind (`wallpaperKind="surface"` in its `wallpaper_info.xml`) is never asked
 * for pictures ([onRequest] is never called, and [push] drops what it is given):
 *
 * ```
 * attach(env, host) -> [ attachSurface(id) -> surfaceDrawn(id) | setMotion | event |
 *     detachSurface(id) -> surfaceReleased(id) ]* -> detach
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
 * **Surfaces.** The rules are [WallpaperContract]'s. The service keeps the ids: a surface ends
 * ([onSurfaceDetached]) before a new one replaces it, before [onDetach] and before an [onAttach]
 * again, so a plugin holds at most one, and hears of each end once. A call naming another id is
 * ignored.
 *
 * **Events.** Ask for the events this plugin declares (`wallpaperEvents`) and needs now with
 * [wantEvents] - in each [onAttach], which is a reset: until it asks, it hears none - and again whenever
 * what it needs changes; hear them in [onEvent]. Each ask brings the latest of every event asked for, as
 * soon as the launcher has it, and so does each surface lent; then each change ([WallpaperContract],
 * "Events"; what an event carries is [WallpaperEvent]'s). A launcher may send none.
 *
 * **Threading.** Every callback runs on the main thread, whatever thread the launcher called from.
 * Do slow work elsewhere: [push], [invalidate] and the calls to the launcher about surfaces and
 * events are safe from any thread.
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

    /** The surface the launcher lends now. Main thread only. */
    private val surfaces = SurfaceSlot()

    /**
     * The kind this service declares in its `wallpaper_info.xml` - the kind is the declaration - read
     * once from its own meta-data. One that cannot be read is [WallpaperProviderInfo.KIND_ITEMS].
     */
    private val declaredKind: Int by lazy { readDeclaredKind() }

    private val binder = object : IWallpaperProvider.Stub() {

        override fun attach(env: WallpaperEnvironment, host: IWallpaperHost) = guarded {
            // A reset: a surface lent before it ends, told to the host that lent it.
            endSurface()
            this@WallpaperProviderService.host = host
            environment = env
            onAttach(env)
        }

        override fun request(reason: Int) = guarded {
            if (declaredKind == WallpaperProviderInfo.KIND_SURFACE) {
                Log.w(TAG, "request($reason) dropped: a surface plugin is never asked for pictures")
            } else {
                onRequest(reason)
            }
        }

        override fun shown(itemId: String) = guarded { onShown(itemId) }

        override fun failed(itemId: String, why: Int) = guarded { onFailed(itemId, why) }

        override fun detach() = guarded {
            endSurface()
            onDetach()
            host = null
        }

        // Surface.CREATOR answers null for a surface it could not read.
        override fun attachSurface(surfaceId: Int, surface: Surface?, width: Int, height: Int) = guarded {
            if (surface == null) {
                Log.w(TAG, "attachSurface($surfaceId) dropped: no surface came with it")
            } else {
                surfaces.attach(surfaceId)?.let { replaced -> onSurfaceDetached(replaced) }
                onSurfaceAttached(surfaceId, surface, width, height)
            }
        }

        override fun detachSurface(surfaceId: Int) = guarded {
            if (surfaces.detach(surfaceId)) {
                onSurfaceDetached(surfaceId)
            } else {
                Log.i(TAG, "detachSurface($surfaceId) ignored: not the current surface")
            }
        }

        override fun setMotion(allowed: Boolean) = guarded { onMotionChanged(allowed) }

        override fun event(event: WallpaperEvent?) = guarded {
            if (event != null) onEvent(event)
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

    /** Main thread: the current surface, if any, ends as a [IWallpaperProvider.detachSurface] would end it. */
    private fun endSurface() {
        surfaces.end()?.let { onSurfaceDetached(it) }
    }

    final override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The launcher is ready: [env] describes the screen ([environment] holds it too). A request
     * follows, for the items kind. Main thread.
     */
    open fun onAttach(env: WallpaperEnvironment) {}

    /**
     * Answer with [push], now or later, from any thread - always, an empty batch for "nothing yet".
     * [reason] is one of the `REASON_` constants in [WallpaperContract]. Never called for a plugin of
     * the surface kind, which implements it empty. Main thread.
     */
    abstract fun onRequest(reason: Int)

    /** The launcher put this item on screen. Main thread. */
    open fun onShown(itemId: String) {}

    /**
     * The launcher could not use this item: [why] is [WallpaperContract.FAIL_UNREADABLE],
     * [WallpaperContract.FAIL_TOO_SMALL] or [WallpaperContract.FAIL_UNSUPPORTED]. Main thread.
     */
    open fun onFailed(itemId: String, why: Int) {}

    /**
     * The plugin was switched off, or the launcher let it go. A surface still attached has ended
     * first ([onSurfaceDetached]). [push] and the other calls to the launcher are no-ops after this.
     * Main thread.
     */
    open fun onDetach() {}

    /**
     * Surface kind: the launcher lends surface [surfaceId], [width] x [height] pixels. Draw into it
     * from a thread of your own and call [surfaceDrawn] after the first frame - within
     * [WallpaperContract.SURFACE_FIRST_FRAME_MS], or [WallpaperContract.SURFACE_FIRST_FRAME_COLD_MS]
     * for the first surface after [onAttach] - or [surfaceFailed] when this device cannot draw it.
     *
     * Draw at the size given: never change the buffers' geometry and never call
     * `Surface.setFrameRate`. A swap that fails is the surface gone: stop, and wait for the next
     * attach. Until [onMotionChanged] says otherwise, hold the picture still. The surface is yours
     * to release ([Surface.release]) once you stop drawing into it.
     *
     * Never called while another surface is attached: that one has ended first. Main thread.
     */
    open fun onSurfaceAttached(surfaceId: Int, surface: Surface, width: Int, height: Int) {}

    /**
     * Surface kind: surface [surfaceId] ends. Stop drawing into it, release it - your EGL surface and
     * the [Surface] - and then call [surfaceReleased], from the thread that released it. The launcher
     * waits a moment for that call. Also called, by the service, for a surface still attached when a
     * new one replaces it, before [onDetach] and before an [onAttach] again. Main thread.
     */
    open fun onSurfaceDetached(surfaceId: Int) {}

    /**
     * Whether the picture may move. While false, hold it still; a change you were asked for (a
     * setting) may still draw a frame. Motion starts off at [onAttach]: hold still until this says
     * `true`. Main thread.
     */
    open fun onMotionChanged(allowed: Boolean) {}

    /**
     * An event this plugin asked for ([wantEvents]): [WallpaperEvent.type] says which, and its KDoc what it
     * carries. The latest of each comes after each ask and each surface lent, as soon as the launcher has
     * it, then each change; nothing playing is an event with [WallpaperEvent.playing] false. Main thread.
     */
    open fun onEvent(event: WallpaperEvent) {}

    /**
     * Hands the launcher a new list; replaces the last one. Sends the first
     * [WallpaperContract.MAX_ITEMS] items, in order - all the launcher takes - and logs when the batch
     * has more. Logs and sends nothing for a plugin of the surface kind. No-op while detached. Any
     * thread.
     */
    fun push(batch: WallpaperBatch) {
        val host = this.host ?: return
        if (declaredKind == WallpaperProviderInfo.KIND_SURFACE) {
            Log.w(TAG, "push dropped: a surface plugin hands no pictures")
            return
        }
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

    /** Surface kind: the first frame on surface [surfaceId] is drawn. No-op while detached. Any thread. */
    fun surfaceDrawn(surfaceId: Int) = toHost("surfaceDrawn") { it.surfaceDrawn(surfaceId) }

    /**
     * Surface kind: this plugin let surface [surfaceId] go ([onSurfaceDetached]) and draws into it no
     * more. Sent from the calling thread, never hopped: call it from the thread that released the
     * surface. No-op while detached. Any thread.
     */
    fun surfaceReleased(surfaceId: Int) = toHost("surfaceReleased") { it.surfaceReleased(surfaceId) }

    /**
     * Surface kind: this plugin cannot draw on surface [surfaceId]. [code] is
     * [WallpaperContract.SURFACE_UNSUPPORTED] (this device cannot draw it) or
     * [WallpaperContract.SURFACE_ERROR]. No-op while detached. Any thread.
     */
    fun surfaceFailed(surfaceId: Int, code: Int) = toHost("surfaceFailed") { it.surfaceFailed(surfaceId, code) }

    /**
     * Tells the launcher which events this plugin needs now: [WallpaperProviderInfo.FLAG_EVENT_FOCUSED_TILE],
     * [WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING] and [WallpaperProviderInfo.FLAG_EVENT_HOME_MOVING], among the ones it declares in
     * `wallpaperEvents` (a flag it does not declare is ignored); `0` asks for none. It hears none until it
     * asks, and none after each [onAttach] until it asks again: call it there, and whenever what it needs
     * changes. Each ask is answered with the latest of every event asked for ([onEvent]). Any kind of
     * plugin may ask. No-op while detached. Any thread.
     */
    fun wantEvents(flags: Int) = toHost("wantEvents") { it.wantEvents(flags) }

    /** [call] on the host of the latest attach, on this thread; nothing while detached. */
    private inline fun toHost(what: String, call: (IWallpaperHost) -> Unit) {
        val host = this.host ?: return
        try {
            call(host)
        } catch (e: RemoteException) {
            Log.w(TAG, "$what failed", e)
        }
    }

    @Suppress("DEPRECATION") // getServiceInfo(ComponentName, ComponentInfoFlags) is API 33.
    private fun readDeclaredKind(): Int = try {
        val info = packageManager.getServiceInfo(ComponentName(this, javaClass), PackageManager.GET_META_DATA)
        val xmlRes = info.metaData?.getInt(WallpaperContract.META_WALLPAPER_INFO, 0) ?: 0
        if (xmlRes == 0) WallpaperProviderInfo.KIND_ITEMS else kindDeclaredIn(resources.getXml(xmlRes))
    } catch (e: Exception) {
        Log.w(TAG, "could not read this service's wallpaper_info.xml: taking the items kind", e)
        WallpaperProviderInfo.KIND_ITEMS
    }

    /** The kind the root `<wallpaper-provider>` element declares, read as the launcher reads it. */
    private fun kindDeclaredIn(xml: XmlResourceParser): Int {
        try {
            var event = xml.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && xml.name == WallpaperProviderInfo.Parser.ELEMENT) {
                    val parser = WallpaperProviderInfo.Parser(
                        packageName = packageName,
                        serviceClassName = javaClass.name,
                        defaultLabel = "",
                        resolveInteger = { id -> runCatching { resources.getInteger(id) }.getOrNull() },
                    )
                    for (i in 0 until xml.attributeCount) parser.attribute(xml.getAttributeName(i), xml.getAttributeValue(i))
                    return parser.build().kind
                }
                event = xml.next()
            }
            return WallpaperProviderInfo.KIND_ITEMS
        } finally {
            xml.close()
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

/**
 * Which surface a plugin holds, as its [WallpaperProviderService] sees it: the current one's id, or
 * none. A call naming another id is ignored; a new surface ends the one before it; the end of the
 * conversation ends the current one. So a plugin holds at most one surface and hears of each end
 * once. Pure, so it is JVM-tested; the service keeps one, on its main thread.
 */
internal class SurfaceSlot {

    /** The current surface's id, or null. */
    var current: Int? = null
        private set

    /** [surfaceId] is now the current surface. Answers the one it replaces, which ends first, or null. */
    fun attach(surfaceId: Int): Int? = current.also { current = surfaceId }

    /** True when [surfaceId] is the current surface, which ends; any other id changes nothing. */
    fun detach(surfaceId: Int): Boolean = (current == surfaceId).also { if (it) current = null }

    /** The conversation ends (`detach()`, or `attach()` again): answers the current surface, which ends, or null. */
    fun end(): Int? = current.also { current = null }
}
