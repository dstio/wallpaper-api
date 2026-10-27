package ca.dstudio.atvlauncher.wallpaper.api

import android.os.Looper
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * A plugin that records each callback it gets - what, and whether it ran on the main thread - for
 * WallpaperProviderServiceDeviceTest. Two components in androidTest's manifest, each with its own
 * info file: [SurfaceRecordingService] declares the surface kind, [ItemsRecordingService] the items
 * kind, so the service's own reading of its declaration is what the tests see.
 */
abstract class RecordingService : WallpaperProviderService() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAttach(env: WallpaperEnvironment) = record("attach ${env.widthPx}x${env.heightPx}")

    override fun onRequest(reason: Int) = record("request $reason")

    override fun onDetach() = record("detach")

    override fun onSurfaceAttached(surfaceId: Int, surface: Surface, width: Int, height: Int) {
        surfaces[surfaceId] = surface
        record("attachSurface $surfaceId ${width}x$height")
    }

    override fun onSurfaceDetached(surfaceId: Int) = record("detachSurface $surfaceId")

    override fun onMotionChanged(allowed: Boolean) = record("motion $allowed")

    override fun onEvent(event: WallpaperEvent) {
        events.add(event)
        record("event ${event.type}")
    }

    private fun record(what: String) {
        calls.add(Call(what, onMain = Looper.myLooper() == Looper.getMainLooper()))
    }

    data class Call(val what: String, val onMain: Boolean)

    companion object {
        val calls = LinkedBlockingQueue<Call>()
        val events = LinkedBlockingQueue<WallpaperEvent>()
        val surfaces = ConcurrentHashMap<Int, Surface>()

        /** The running plugin, for the calls a plugin makes to the launcher. */
        @Volatile
        var instance: RecordingService? = null
    }
}

/** Declares `wallpaperKind="surface"` (res/xml/recording_surface_info.xml). */
class SurfaceRecordingService : RecordingService()

/** Declares `wallpaperKind="items"` (res/xml/recording_items_info.xml). */
class ItemsRecordingService : RecordingService()
