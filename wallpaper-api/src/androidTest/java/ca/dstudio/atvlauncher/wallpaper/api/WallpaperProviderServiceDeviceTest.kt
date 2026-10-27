package ca.dstudio.atvlauncher.wallpaper.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.thread

/**
 * [WallpaperProviderService] on a device, as the launcher drives it: [RecordingService]s bound in this
 * process, so every call passes the caller check as the plugin's own uid (the shell cannot bind a
 * plugin: it has no BIND_WALLPAPER). The binder is local - the calls run on this thread up to the
 * hop to main - so this checks the service's dispatch, not the wire format: ParcelFrameTest checks
 * that.
 */
@RunWith(AndroidJUnit4::class)
class WallpaperProviderServiceDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val connections = mutableListOf<ServiceConnection>()

    /** What reached the launcher's side, and on which thread. */
    private val heard = LinkedBlockingQueue<String>()

    private val host = object : IWallpaperHost.Stub() {
        override fun push(batch: WallpaperBatch?) = hear("push ${batch?.items?.size}")
        override fun invalidate() = hear("invalidate")
        override fun surfaceDrawn(surfaceId: Int) = hear("surfaceDrawn $surfaceId")
        override fun surfaceReleased(surfaceId: Int) = hear("surfaceReleased $surfaceId")
        override fun surfaceFailed(surfaceId: Int, code: Int) = hear("surfaceFailed $surfaceId $code")
        override fun wantEvents(flags: Int) = hear("wantEvents $flags")
    }

    private fun hear(call: String) {
        heard.add("$call on ${Thread.currentThread().name}")
    }

    private val texture = SurfaceTexture(0)
    private val surface = Surface(texture)

    @Before
    fun clear() {
        RecordingService.calls.clear()
        RecordingService.events.clear()
        RecordingService.surfaces.clear()
    }

    @After
    fun unbind() {
        connections.forEach(context::unbindService)
        surface.release()
        texture.release()
    }

    private fun bound(service: Class<out RecordingService>): IWallpaperProvider {
        val bound = LinkedBlockingQueue<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.add(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE))
        connections += connection
        val binder = bound.poll(10, SECONDS)
        assertNotNull(binder)
        return IWallpaperProvider.Stub.asInterface(binder)
    }

    /** Bound and attached, the attach seen; [plugin] is the running service. */
    private fun attached(service: Class<out RecordingService> = SurfaceRecordingService::class.java): IWallpaperProvider {
        val provider = bound(service)
        provider.attach(ENV, host)
        assertEquals(listOf("attach 1920x1080"), next(1))
        return provider
    }

    private val plugin: RecordingService
        get() = RecordingService.instance.also { assertNotNull(it) }!!

    /** The next [count] callbacks, each of which must have run on the main thread. */
    private fun next(count: Int): List<String> = List(count) {
        val call = RecordingService.calls.poll(5, SECONDS)
        assertNotNull("callback ${it + 1} of $count", call)
        assertTrue("${call!!.what} ran on the main thread", call.onMain)
        call.what
    }

    /** No callback within a moment. */
    private fun assertNoMoreCallbacks() = assertNull(RecordingService.calls.poll(300, MILLISECONDS))

    /**
     * Runs [block] as if called over binder by [uid]: on a local binder `Binder.getCallingUid()`
     * answers for this thread, and the calling identity restored here is what it reads.
     */
    private fun asCaller(uid: Int, block: () -> Unit) {
        val own = Binder.clearCallingIdentity()
        try {
            Binder.restoreCallingIdentity((uid.toLong() shl 32) or Process.myPid().toLong())
            assertEquals(uid, Binder.getCallingUid())
            block()
        } finally {
            Binder.restoreCallingIdentity(own)
        }
    }

    @Test
    fun theNewCallsReachTheirCallbacksOnMainInOrder() {
        val provider = attached()
        val event = WallpaperEvent(
            type = WallpaperContract.EVENT_NOW_PLAYING,
            packageName = "com.example.music",
            title = "Blue in Green",
            playing = true,
            colors = intArrayOf(0xFF3366CC.toInt()),
        )
        provider.attachSurface(1, surface, 1280, 720)
        provider.setMotion(true)
        provider.event(event)
        provider.setMotion(false)
        provider.detachSurface(1)
        assertEquals(
            listOf("attachSurface 1 1280x720", "motion true", "event nowPlaying", "motion false", "detachSurface 1"),
            next(5),
        )
        assertSame(surface, RecordingService.surfaces[1])
        assertEquals(event, RecordingService.events.poll(1, SECONDS))
        assertNoMoreCallbacks()
    }

    @Test
    fun aForeignCallerIsRefused() {
        val provider = attached()
        asCaller(SHELL_UID) {
            provider.attachSurface(1, surface, 1280, 720)
            provider.detachSurface(1)
            provider.setMotion(true)
            provider.event(WallpaperEvent(WallpaperContract.EVENT_FOCUSED_TILE))
            provider.detach()
        }
        // The plugin's own call, after them on the same main thread: the only one to reach it.
        provider.setMotion(false)
        assertEquals(listOf("motion false"), next(1))
        assertNoMoreCallbacks()
    }

    @Test
    fun aSurfacePluginIsNeverAskedAndItsPushIsDropped() {
        val provider = attached(SurfaceRecordingService::class.java)
        provider.request(WallpaperContract.REASON_START)
        provider.setMotion(true)
        assertEquals("the request never reached onRequest", listOf("motion true"), next(1))

        plugin.push(BATCH)
        plugin.wantEvents(WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING)
        assertEquals("only wantEvents reached the launcher", "wantEvents 2 on ${Thread.currentThread().name}", heard.poll(5, SECONDS))
        assertNull(heard.poll(300, MILLISECONDS))
    }

    /** The control: the same code declaring the items kind is asked, and its push goes out. */
    @Test
    fun anItemsPluginIsAskedAndItsPushIsSent() {
        val provider = attached(ItemsRecordingService::class.java)
        provider.request(WallpaperContract.REASON_START)
        assertEquals(listOf("request 0"), next(1))
        plugin.push(BATCH)
        assertEquals("push 1 on ${Thread.currentThread().name}", heard.poll(5, SECONDS))
    }

    @Test
    fun detachWithASurfaceAttachedEndsTheSurfaceFirst() {
        val provider = attached()
        provider.attachSurface(7, surface, 1280, 720)
        provider.detach()
        assertEquals(listOf("attachSurface 7 1280x720", "detachSurface 7", "detach"), next(3))
        provider.detachSurface(7) // stale: that surface has ended
        provider.setMotion(true)
        assertEquals(listOf("motion true"), next(1))
    }

    @Test
    fun aNewSurfaceEndsTheOneItReplacesAndAStaleIdIsIgnored() {
        val provider = attached()
        provider.attachSurface(1, surface, 1280, 720)
        provider.attachSurface(2, surface, 1920, 1080)
        provider.detachSurface(1) // stale
        provider.detachSurface(2)
        assertEquals(listOf("attachSurface 1 1280x720", "detachSurface 1", "attachSurface 2 1920x1080", "detachSurface 2"), next(4))
        assertNoMoreCallbacks()
    }

    @Test
    fun anAttachAgainEndsTheSurface() {
        val provider = attached()
        provider.attachSurface(3, surface, 1280, 720)
        provider.attach(ENV, host)
        assertEquals(listOf("attachSurface 3 1280x720", "detachSurface 3", "attach 1920x1080"), next(3))
    }

    @Test
    fun theCallsToTheLauncherLeaveFromTheCallingThread() {
        attached()
        val running = plugin
        thread(name = "plugin-render") {
            running.surfaceDrawn(4)
            running.surfaceFailed(4, WallpaperContract.SURFACE_ERROR)
            running.surfaceReleased(4)
            running.wantEvents(WallpaperProviderInfo.FLAG_EVENT_FOCUSED_TILE or WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING)
        }.join(5_000)
        assertEquals(
            listOf(
                "surfaceDrawn 4 on plugin-render",
                "surfaceFailed 4 2 on plugin-render",
                "surfaceReleased 4 on plugin-render",
                "wantEvents 3 on plugin-render",
            ),
            List(4) { heard.poll(5, SECONDS) },
        )
    }

    @Test
    fun afterDetachTheCallsToTheLauncherGoNowhere() {
        val provider = attached()
        provider.detach()
        assertEquals(listOf("detach"), next(1))
        instrumentation.runOnMainSync {} // the detach's block has finished
        plugin.surfaceDrawn(1)
        plugin.surfaceReleased(1)
        plugin.surfaceFailed(1, WallpaperContract.SURFACE_ERROR)
        plugin.wantEvents(WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING)
        assertNull(heard.poll(300, MILLISECONDS))
    }

    private companion object {
        val ENV = WallpaperEnvironment(1920, 1080, 320, "en-CA", WallpaperContract.API_VERSION)
        val BATCH = WallpaperBatch(listOf(WallpaperItem("colour-1b2a55", "#1B2A55", type = WallpaperContract.TYPE_COLOR)))

        /** adb shell, which holds no BIND_WALLPAPER. */
        const val SHELL_UID = 2_000
    }
}
