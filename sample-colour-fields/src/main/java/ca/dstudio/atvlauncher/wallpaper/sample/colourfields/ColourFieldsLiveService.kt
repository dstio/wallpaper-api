package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperContract
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperEnvironment
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperEvent
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderInfo
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderService

/**
 * Colour Fields Live, the sample of the surface kind (`wallpaper_info_live.xml`): a two-colour
 * gradient that turns slowly, drawn with [Surface.lockCanvas] on a thread of its own - a frame as soon
 * as it has a surface, then [FPS] a second while motion is allowed. It says `surfaceDrawn` after the
 * first frame. On `onSurfaceDetached` it stops, lets the surface go and answers `surfaceReleased` from
 * the thread that let it go, and waits for that thread.
 *
 * The turn keeps its place across surfaces and service instances (the launcher unbinds at every stop),
 * so the picture a return to Home resumes is the one it left. Debug builds have five test switches for
 * the launcher's surface host ([LiveSettings], on [LiveSettingsActivity]): Never draw, Crash,
 * Unsupported, Slow release and Ask for events.
 */
class ColourFieldsLiveService : WallpaperProviderService() {

    /** The surface it draws on now. Main thread. */
    private var drawing: LiveDrawing? = null

    /** Whether the picture may move: off at each attach until the launcher allows it. Main thread. */
    private var motion = false

    override fun onAttach(env: WallpaperEnvironment) {
        motion = false
        Log.i(TAG, "attach: screen ${env.widthPx}x${env.heightPx}")
        if (LiveSettings.read(this).askEvents) {
            // A debug build's Ask for events: the launcher sends a plugin of another package none.
            Log.i(TAG, "Ask for events is on: asking for the focused tile and now playing")
            wantEvents(WallpaperProviderInfo.FLAG_EVENT_FOCUSED_TILE or WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING)
        }
    }

    override fun onEvent(event: WallpaperEvent) {
        Log.i(TAG, "event: $event")
    }

    /** Never called: a plugin of the surface kind is never asked for pictures. */
    override fun onRequest(reason: Int) {}

    override fun onSurfaceAttached(surfaceId: Int, surface: Surface, width: Int, height: Int) {
        // The service ends a surface before lending the next; stopping here too keeps two producers off
        // one window whatever happens.
        drawing?.stop()
        val settings = LiveSettings.read(this)
        Log.i(TAG, "surface $surfaceId: ${width}x$height, motion $motion")
        val draws = when {
            settings.unsupported -> {
                Log.i(TAG, "surface $surfaceId: Unsupported is on")
                surfaceFailed(surfaceId, WallpaperContract.SURFACE_UNSUPPORTED)
                false
            }
            settings.neverDraw -> {
                Log.i(TAG, "surface $surfaceId: Never draw is on")
                false
            }
            else -> true
        }
        drawing = LiveDrawing(this, surfaceId, surface, width, height, draws, motion).also { it.start() }
    }

    override fun onSurfaceDetached(surfaceId: Int) {
        val ending = drawing?.takeIf { it.surfaceId == surfaceId } ?: return
        drawing = null
        ending.stop(slowRelease = LiveSettings.read(this).slowRelease)
        Log.i(TAG, "surface $surfaceId: released")
    }

    override fun onMotionChanged(allowed: Boolean) {
        motion = allowed
        drawing?.setMotion(allowed)
    }

    override fun onDetach() {
        motion = false
    }

    override fun onDestroy() {
        drawing?.stop()
        drawing = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "ColourFieldsLive"

        /** Frames a second while motion is allowed. */
        const val FPS = 15

        /** One full turn of the gradient, in milliseconds of motion: 6 degrees a second. */
        const val TURN_MS = 60_000L

        /** The two colours: the Warm palette's coral and the sample's deep blue. */
        val COLOURS = intArrayOf(0xFFFF7E5F.toInt(), 0xFF1B2A55.toInt())

        /** Slow release: how late a detach is answered, past the launcher's 100 ms wait. */
        const val SLOW_RELEASE_MS = 300L

        /** The turn's place, in milliseconds of motion, kept for the next surface and the next instance. */
        @Volatile
        var turnMs = 0L
    }
}

/**
 * One surface the launcher lent, drawn on a thread of its own. [start], [setMotion] and [stop] are
 * called on the main thread; everything else runs on the drawing thread.
 */
private class LiveDrawing(
    private val service: ColourFieldsLiveService,
    val surfaceId: Int,
    private val surface: Surface,
    private val width: Int,
    private val height: Int,
    /** False for Never draw and Unsupported: the surface is held, and released at the end, undrawn. */
    private val draws: Boolean,
    motion: Boolean,
) {
    private val thread = HandlerThread("colour-fields-live-$surfaceId").apply { start() }
    private val handler = Handler(thread.looper)
    private val paint = Paint()
    private val frame = Runnable { drawFrame() }

    // The drawing thread's own.
    private var moving = motion
    private var drawn = false
    private var gone = false

    /** When the current run of motion started (uptime), or -1 while still. */
    private var runStart = -1L

    /** Frames of the current run so far: the next is due at runStart + n / FPS. */
    private var frameIndex = 0L

    fun start() {
        handler.post {
            if (moving) beginRun()
            drawFrame()
        }
    }

    fun setMotion(allowed: Boolean) {
        handler.post {
            if (allowed == moving) return@post
            moving = allowed
            if (allowed) {
                beginRun()
                if (drawn) scheduleNext()
            } else {
                endRun()
                handler.removeCallbacks(frame)
            }
        }
    }

    /**
     * Main thread. The drawing thread stops, lets the surface go and answers `surfaceReleased` (later
     * with Slow release); this waits for it, so the next surface never meets a producer still connected.
     */
    fun stop(slowRelease: Boolean = false) {
        handler.post {
            handler.removeCallbacks(frame)
            if (moving) endRun()
            gone = true
            surface.release()
            if (slowRelease) SystemClock.sleep(ColourFieldsLiveService.SLOW_RELEASE_MS)
            service.surfaceReleased(surfaceId)
        }
        thread.quitSafely()
        thread.join(JOIN_MS)
    }

    private fun beginRun() {
        runStart = SystemClock.uptimeMillis()
        frameIndex = 0
    }

    private fun endRun() {
        ColourFieldsLiveService.turnMs += SystemClock.uptimeMillis() - runStart
        runStart = -1L
    }

    private fun degrees(): Float {
        val ms = ColourFieldsLiveService.turnMs + if (runStart >= 0) SystemClock.uptimeMillis() - runStart else 0L
        return (ms % ColourFieldsLiveService.TURN_MS) * 360f / ColourFieldsLiveService.TURN_MS
    }

    private fun drawFrame() {
        if (gone || !draws) return
        if (LiveSettings.read(service).crash) {
            Log.w(ColourFieldsLiveService.TAG, "Crash is on: killing the process at this frame")
            Process.killProcess(Process.myPid())
        }
        try {
            val canvas = surface.lockCanvas(null)
            try {
                FieldRenderer.drawGradient(canvas, width, height, degrees(), ColourFieldsLiveService.COLOURS, paint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: RuntimeException) {
            // IllegalArgumentException, OutOfResourcesException, IllegalStateException: a swap that fails
            // is the surface gone. Stop, and wait for the next attach.
            Log.w(ColourFieldsLiveService.TAG, "surface $surfaceId is gone", e)
            gone = true
            return
        }
        if (!drawn) {
            drawn = true
            service.surfaceDrawn(surfaceId)
        }
        if (moving) scheduleNext()
    }

    /** The next frame at the next slot of the run that is still ahead: a late frame skips, never bunches. */
    private fun scheduleNext() {
        val now = SystemClock.uptimeMillis()
        frameIndex = maxOf(frameIndex + 1, (now - runStart) * ColourFieldsLiveService.FPS / 1000 + 1)
        handler.postAtTime(frame, runStart + frameIndex * 1000 / ColourFieldsLiveService.FPS)
    }

    private companion object {
        /** The longest the main thread waits for the drawing thread to finish: a frame, and Slow release. */
        const val JOIN_MS = 1_000L
    }
}
