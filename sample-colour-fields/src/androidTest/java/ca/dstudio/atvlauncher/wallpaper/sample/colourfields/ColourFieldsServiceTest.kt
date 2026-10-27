package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ca.dstudio.atvlauncher.wallpaper.api.IWallpaperHost
import ca.dstudio.atvlauncher.wallpaper.api.IWallpaperProvider
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperBatch
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperContract
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.abs

/**
 * The services as the launcher drives them, from the app's own process (the shell cannot bind them:
 * it has no BIND_WALLPAPER). The binder is local here, so this checks the plugins, not the wire
 * format; wallpaper-api's ParcelFrameTest checks that.
 */
@RunWith(AndroidJUnit4::class)
class ColourFieldsServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = ColourFieldsSettings.prefs(context)
    private val pushes = LinkedBlockingQueue<WallpaperBatch>()
    private val drawn = LinkedBlockingQueue<Int>()
    private val released = LinkedBlockingQueue<Int>()
    private val failed = LinkedBlockingQueue<Pair<Int, Int>>()
    private val connections = mutableListOf<ServiceConnection>()

    /** Where the consumers of the live service's frames hear of them. */
    private val consumerThread = HandlerThread("colour-fields-test-consumer").apply { start() }
    private val consumer = Handler(consumerThread.looper)

    private val host = object : IWallpaperHost.Stub() {
        override fun push(batch: WallpaperBatch) {
            pushes.add(batch)
        }

        override fun invalidate() {}

        override fun surfaceDrawn(surfaceId: Int) {
            drawn.add(surfaceId)
        }

        override fun surfaceReleased(surfaceId: Int) {
            released.add(surfaceId)
        }

        override fun surfaceFailed(surfaceId: Int, code: Int) {
            failed.add(surfaceId to code)
        }

        override fun wantEvents(flags: Int) {}
    }

    @Before
    fun cool4() {
        prefs.edit().clear().putString(ColourFieldsSettings.KEY_PALETTE, "cool")
            .putInt(ColourFieldsSettings.KEY_COUNT, 4).commit()
        File(context.filesDir, "wallpapers").deleteRecursively()
    }

    @After
    fun unbind() {
        connections.forEach(context::unbindService)
        consumerThread.quitSafely()
        prefs.edit().clear().commit()
        File(context.filesDir, "wallpapers").deleteRecursively()
    }

    private fun attached(service: Class<out Service> = ColourFieldsService::class.java): IWallpaperProvider {
        val bound = LinkedBlockingQueue<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.add(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, service), conn, Context.BIND_AUTO_CREATE))
        connections += conn
        val provider = IWallpaperProvider.Stub.asInterface(assertNotNullAnd(bound.poll(10, SECONDS)))
        provider.attach(WallpaperEnvironment(960, 540, 320, "en-CA", WallpaperContract.API_VERSION), host)
        return provider
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** The first push that is not "nothing yet", within 30 s - the launcher's own patience. */
    private fun answer(): WallpaperBatch? {
        val until = SystemClock.uptimeMillis() + 30_000
        while (true) {
            val left = until - SystemClock.uptimeMillis()
            if (left <= 0) return null
            val batch = pushes.poll(left, MILLISECONDS) ?: return null
            if (batch.items.isNotEmpty()) return batch
        }
    }

    @Test
    fun answersWithItsFieldsThenTheColour() {
        attached().request(WallpaperContract.REASON_START)
        val batch = assertNotNullAnd(answer())

        assertEquals(
            listOf("field-cool-1", "field-cool-2", "field-cool-3", "field-cool-4", "colour-1b2a55"),
            batch.items.map { it.id },
        )
        val fill = WallpaperContract.MODE_FILL
        assertEquals(listOf(fill, fill, WallpaperContract.MODE_FIT, fill, WallpaperContract.MODE_DEFAULT), batch.items.map { it.mode })
        assertEquals(WallpaperContract.TYPE_COLOR, batch.items.last().type)
        assertEquals("#1B2A55", batch.items.last().uri)
        assertEquals(6 * 3_600_000L, batch.nextRequestMs)

        // Its provider serves each field, at the environment's size.
        for (item in batch.items.dropLast(1)) {
            val uri = Uri.parse(item.uri)
            assertEquals("image/png", context.contentResolver.getType(uri))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            assertEquals(960, bounds.outWidth)
            assertEquals(540, bounds.outHeight)
        }
    }

    @Test
    fun theProviderServesOnlyItsPicturesAndNeverWrites() {
        attached().request(WallpaperContract.REASON_START)
        val field = Uri.parse(assertNotNullAnd(answer()).items.first().uri)
        val base = "content://${context.packageName}.wallpapers"
        for (outside in listOf("$base/../shared_prefs/colour-fields.xml", "$base/%2E%2E/shared_prefs/colour-fields.xml", "$base/", "$base/field-warm-1.png")) {
            assertThrows(outside, FileNotFoundException::class.java) {
                context.contentResolver.openInputStream(Uri.parse(outside))?.close()
            }
        }
        assertThrows(FileNotFoundException::class.java) { context.contentResolver.openOutputStream(field)?.close() }
    }

    @Test
    fun aPaletteChangeIsNewIdsAndTheOldFilesGo() {
        val provider = attached()
        provider.request(WallpaperContract.REASON_START)
        assertNotNullAnd(answer())
        prefs.edit().putString(ColourFieldsSettings.KEY_PALETTE, "night").putInt(ColourFieldsSettings.KEY_COUNT, 2).commit()
        provider.request(WallpaperContract.REASON_SETTINGS_CLOSED)

        val batch = assertNotNullAnd(answer())
        assertEquals(listOf("field-night-1", "field-night-2", "colour-1b2a55"), batch.items.map { it.id })
        assertEquals(setOf("field-night-1.png", "field-night-2.png"), File(context.filesDir, "wallpapers").list()!!.toSet())
    }

    @Test
    fun doNotAnswerIgnoresRequests() {
        prefs.edit().putBoolean(ColourFieldsSettings.KEY_SILENT, true).commit()
        attached().request(WallpaperContract.REASON_START)
        assertNull(pushes.poll(4, SECONDS))
    }

    @Test
    fun answerSlowlyWaitsFiveSeconds() {
        prefs.edit().putBoolean(ColourFieldsSettings.KEY_SLOW, true).commit()
        val asked = SystemClock.uptimeMillis()
        attached().request(WallpaperContract.REASON_START)
        assertNull("nothing, not even \"nothing yet\", in the first 4 s", pushes.poll(4, SECONDS))
        val batch = assertNotNullAnd(answer())
        assertFalse(batch.items.isEmpty())
        assertTrue(SystemClock.uptimeMillis() - asked >= 5_000)
    }

    // Colour Fields Live, the surface kind.

    /** A surface on a [SurfaceTexture], as a launcher lends one; [frames] counts down at its first frame. */
    private class TextureSurface(handler: Handler) {
        val texture = SurfaceTexture(0).apply { setDefaultBufferSize(W, H) }
        val surface = Surface(texture)
        val frames = CountDownLatch(1)

        init {
            texture.setOnFrameAvailableListener({ frames.countDown() }, handler)
        }

        fun release() {
            surface.release()
            texture.release()
        }
    }

    private fun live(): IWallpaperProvider = attached(ColourFieldsLiveService::class.java)

    @Test
    fun theLiveServiceSaysDrawnAfterItsFirstFrameAndReleasedAfterADetach() {
        val target = TextureSurface(consumer)
        try {
            val live = live()
            live.attachSurface(1, target.surface, W, H)
            assertEquals("surfaceDrawn within the contract's 3 s", 1, drawn.poll(WallpaperContract.SURFACE_FIRST_FRAME_MS, MILLISECONDS))
            assertTrue("the frame reached the consumer", target.frames.await(1, SECONDS))

            val detached = SystemClock.uptimeMillis()
            live.detachSurface(1)
            assertEquals(1, released.poll(2, SECONDS))
            val took = SystemClock.uptimeMillis() - detached
            Log.i(TAG, "released $took ms after the detach")
            assertTrue("released in $took ms, within the launcher's 100 ms wait", took <= 100)
            assertNull("nothing failed", failed.poll())
        } finally {
            target.release()
        }
    }

    @Test
    fun theLiveServiceDrawsItsGradientAndTurnsItOnlyWhileMotionIsAllowed() {
        val reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3)
        val frames = LinkedBlockingQueue<Frame>()
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { frames.add(Frame.of(it)) } }, consumer)
        try {
            val live = live()
            live.attachSurface(2, reader.surface, W, H)
            assertEquals(2, drawn.poll(3, SECONDS))
            val first = assertNotNullAnd(frames.poll(2, SECONDS))
            first.assertAGradientOfTheTwoColours()
            assertNull("motion starts off: the first frame, then nothing", frames.poll(1_500, MILLISECONDS))

            live.setMotion(true)
            val moving = drain(frames, 3_000)
            Log.i(TAG, "motion: ${moving.size} frames in 3 s; ${first.corners} to ${moving.last().corners}")
            assertTrue("about ${ColourFieldsLiveService.FPS} a second: ${moving.size} in 3 s", moving.size in 36..48)
            moving.forEach { it.assertAGradientOfTheTwoColours() }
            assertTrue("it turned: ${first.corners} to ${moving.last().corners}", first.cornersDifferFrom(moving.last()))

            live.setMotion(false)
            SystemClock.sleep(300) // a frame already on its way lands
            frames.clear()
            assertNull("still again", frames.poll(1_000, MILLISECONDS))

            live.detachSurface(2)
            assertEquals(2, released.poll(2, SECONDS))
        } finally {
            reader.close()
        }
    }

    @Test
    fun neverDrawNeverSaysDrawnAndStillLetsTheSurfaceGo() {
        prefs.edit().putBoolean(LiveSettings.KEY_NEVER_DRAW, true).commit()
        val target = TextureSurface(consumer)
        try {
            val live = live()
            live.attachSurface(3, target.surface, W, H)
            assertNull("no surfaceDrawn: the launcher's watchdog fires", drawn.poll(4, SECONDS))
            assertEquals("no frame reached the consumer", 1L, target.frames.count)
            live.detachSurface(3)
            assertEquals(3, released.poll(2, SECONDS))
        } finally {
            target.release()
        }
    }

    @Test
    fun unsupportedSaysSoAndDrawsNothing() {
        prefs.edit().putBoolean(LiveSettings.KEY_UNSUPPORTED, true).commit()
        val target = TextureSurface(consumer)
        try {
            val live = live()
            live.attachSurface(4, target.surface, W, H)
            assertEquals(4 to WallpaperContract.SURFACE_UNSUPPORTED, failed.poll(3, SECONDS))
            assertNull(drawn.poll(1, SECONDS))
            live.detachSurface(4)
            assertEquals(4, released.poll(2, SECONDS))
        } finally {
            target.release()
        }
    }

    @Test
    fun slowReleaseAnswersADetachAfter300Milliseconds() {
        prefs.edit().putBoolean(LiveSettings.KEY_SLOW_RELEASE, true).commit()
        val target = TextureSurface(consumer)
        try {
            val live = live()
            live.attachSurface(5, target.surface, W, H)
            assertEquals(5, drawn.poll(3, SECONDS))
            val detached = SystemClock.uptimeMillis()
            live.detachSurface(5)
            assertEquals(5, released.poll(3, SECONDS))
            val took = SystemClock.uptimeMillis() - detached
            Log.i(TAG, "slow release: released $took ms after the detach")
            assertTrue("released after $took ms, past the launcher's 100 ms wait", took >= ColourFieldsLiveService.SLOW_RELEASE_MS)
        } finally {
            target.release()
        }
    }

    /** Every frame that lands within [ms]. */
    private fun drain(frames: LinkedBlockingQueue<Frame>, ms: Long): List<Frame> {
        val until = SystemClock.uptimeMillis() + ms
        val got = mutableListOf<Frame>()
        while (true) {
            val left = until - SystemClock.uptimeMillis()
            if (left <= 0) return got
            frames.poll(left, MILLISECONDS)?.let(got::add)
        }
    }

    /** One frame read back: its four corners and its centre, as RGB. */
    private class Frame(val corners: List<Rgb>, val centre: Rgb) {

        /** Every sample between the two colours, channel by channel; the centre half way. */
        fun assertAGradientOfTheTwoColours() {
            for (pixel in corners + centre) {
                for (channel in 0..2) {
                    val (low, high) = listOf(A[channel], B[channel]).sorted()
                    assertTrue("$pixel within the two colours", pixel[channel] in low - SLACK..high + SLACK)
                }
            }
            for (channel in 0..2) {
                assertTrue("centre $centre half way", abs(centre[channel] - (A[channel] + B[channel]) / 2) <= SLACK)
            }
        }

        /** The gradient turned: a corner's colour moved. */
        fun cornersDifferFrom(other: Frame): Boolean =
            corners.indices.any { i -> (0..2).any { c -> abs(corners[i][c] - other.corners[i][c]) >= TURNED } }

        companion object {
            fun of(image: Image): Frame {
                val plane = image.planes[0]
                val buffer = plane.buffer
                fun at(x: Int, y: Int): Rgb {
                    val i = y * plane.rowStride + x * plane.pixelStride
                    return Rgb(buffer.get(i).toInt() and 0xFF, buffer.get(i + 1).toInt() and 0xFF, buffer.get(i + 2).toInt() and 0xFF)
                }
                val w = image.width
                val h = image.height
                return Frame(listOf(at(0, 0), at(w - 1, 0), at(0, h - 1), at(w - 1, h - 1)), at(w / 2, h / 2))
            }
        }
    }

    private data class Rgb(val r: Int, val g: Int, val b: Int) {
        operator fun get(channel: Int): Int = when (channel) {
            0 -> r
            1 -> g
            else -> b
        }
    }

    private companion object {
        const val TAG = "ColourFieldsServiceTest"
        const val W = 320
        const val H = 180

        /** ColourFieldsLiveService.COLOURS as RGB: #FF7E5F, #1B2A55. */
        val A = Rgb(0xFF, 0x7E, 0x5F)
        val B = Rgb(0x1B, 0x2A, 0x55)

        /** Rounding and dither in a channel. */
        const val SLACK = 6

        /** A corner moves well past this in 3 s of turning (18 degrees). */
        const val TURNED = 10
    }
}
