package ca.dstudio.atvlauncher.wallpaper.api

import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.BadParcelableException
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcel
import android.os.Parcelable
import android.system.Os
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.parcelize.parcelableCreator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS

/**
 * The wire format, on a real Parcel. A frame is written here by hand, as a newer version would
 * write it, so the test does not trust the code it tests: its size int (itself included), then
 * the fields.
 */
@RunWith(AndroidJUnit4::class)
class ParcelFrameTest {

    private val parcel: Parcel = Parcel.obtain()

    private val env = WallpaperEnvironment(1920, 1080, 320, "en-CA", 2)
    private val picture = WallpaperItem(
        id = "field-warm-3",
        uri = "content://x.wallpapers/field-warm-3.png",
        type = WallpaperContract.TYPE_IMAGE,
        mode = WallpaperContract.MODE_FIT,
        title = "Warm 3",
        author = "Colour Fields",
        sourceUrl = "https://example.com/warm",
    )
    private val colour = WallpaperItem("colour-1b2a55", "#1B2A55", type = WallpaperContract.TYPE_COLOR)
    private val nowPlaying = WallpaperEvent(
        type = WallpaperContract.EVENT_NOW_PLAYING,
        packageName = "com.example.music",
        title = "Blue in Green",
        artist = "Miles Davis",
        album = "Kind of Blue",
        playing = true,
        colors = intArrayOf(0xFF3366CC.toInt(), 0xFF112233.toInt(), 0x80FFFFFF.toInt()),
    )

    @After
    fun recycle() = parcel.recycle()

    private inline fun <reified T : Parcelable> roundTrip(value: T): T {
        value.writeToParcel(parcel, 0)
        parcel.setDataPosition(0)
        return parcelableCreator<T>().createFromParcel(parcel).also {
            assertEquals("read to the end of what was written", parcel.dataSize(), parcel.dataPosition())
            parcel.setDataSize(0)
        }
    }

    /** A frame as any version writes one. */
    private fun frame(fields: Parcel.() -> Unit) {
        val start = parcel.dataPosition()
        parcel.writeInt(0)
        parcel.fields()
        val end = parcel.dataPosition()
        parcel.setDataPosition(start)
        parcel.writeInt(end - start)
        parcel.setDataPosition(end)
    }

    @Test
    fun everyParcelableRoundTrips() {
        assertEquals(env, roundTrip(env))
        assertEquals(picture, roundTrip(picture))
        assertEquals(colour, roundTrip(colour))
        val batch = WallpaperBatch(listOf(picture, colour), 21_600_000L)
        assertEquals(batch, roundTrip(batch))
        assertEquals(WallpaperBatch(emptyList()), roundTrip(WallpaperBatch(emptyList())))
        assertEquals(nowPlaying, roundTrip(nowPlaying))
        val focused = WallpaperEvent(WallpaperContract.EVENT_FOCUSED_TILE)
        assertEquals(focused, roundTrip(focused))
        val moving = WallpaperEvent(WallpaperContract.EVENT_HOME_MOVING, moving = true)
        assertEquals(moving, roundTrip(moving))
    }

    /** An event's frame as a reader of any version sees it: its fields in order, the colours counted, then moving. */
    @Test
    fun anEventIsItsFieldsInAFrame() {
        frame {
            writeString(WallpaperContract.EVENT_NOW_PLAYING); writeString("com.example.music")
            writeString("Blue in Green"); writeString("Miles Davis"); writeString("Kind of Blue")
            writeInt(1)
            writeInt(3); writeInt(0xFF3366CC.toInt()); writeInt(0xFF112233.toInt()); writeInt(0x80FFFFFF.toInt())
            writeInt(0)
        }
        parcel.setDataPosition(0)
        assertEquals(nowPlaying, parcelableCreator<WallpaperEvent>().createFromParcel(parcel))
        assertEquals("read to the end of the frame", parcel.dataSize(), parcel.dataPosition())
    }

    /** Home moving's frame: the type, no strings, not playing, no colours, then moving. */
    @Test
    fun homeMovingIsMovingAfterTheColours() {
        frame {
            writeString(WallpaperContract.EVENT_HOME_MOVING); writeString(null)
            writeString(null); writeString(null); writeString(null)
            writeInt(0)
            writeInt(0)
            writeInt(1)
        }
        parcel.setDataPosition(0)
        assertEquals(
            WallpaperEvent(WallpaperContract.EVENT_HOME_MOVING, moving = true),
            parcelableCreator<WallpaperEvent>().createFromParcel(parcel),
        )
        assertEquals("read to the end of the frame", parcel.dataSize(), parcel.dataPosition())
    }

    /** An event whose frame ends at its colours: moving reads false, and what follows it still reads. */
    @Test
    fun anOlderEventReadsStill() {
        parcel.writeInt(1) // writeTypedObject's "not null"
        frame {
            writeString(WallpaperContract.EVENT_FOCUSED_TILE); writeString(null)
            writeString(null); writeString(null); writeString(null)
            writeInt(0)
            writeInt(1); writeInt(0xFF884422.toInt())
        }
        parcel.writeInt(0x5EED)
        parcel.setDataPosition(0)
        assertEquals(
            WallpaperEvent(WallpaperContract.EVENT_FOCUSED_TILE, colors = intArrayOf(0xFF884422.toInt())),
            parcel.readTypedObject(parcelableCreator<WallpaperEvent>()),
        )
        assertEquals(0x5EED, parcel.readInt())
    }

    /** A newer launcher's event, two fields longer than moving: they are skipped, and what follows it still reads. */
    @Test
    fun aNewerEventReadsHere() {
        parcel.writeInt(1) // writeTypedObject's "not null"
        frame {
            writeString(WallpaperContract.EVENT_FOCUSED_TILE); writeString("com.example.video")
            writeString("Films"); writeString(null); writeString(null)
            writeInt(0)
            writeInt(1); writeInt(0xFF884422.toInt())
            writeInt(0)
            writeLong(42L); writeString("a field from a later version")
        }
        parcel.writeInt(0x5EED)
        parcel.setDataPosition(0)
        assertEquals(
            WallpaperEvent(
                type = WallpaperContract.EVENT_FOCUSED_TILE,
                packageName = "com.example.video",
                title = "Films",
                colors = intArrayOf(0xFF884422.toInt()),
            ),
            parcel.readTypedObject(parcelableCreator<WallpaperEvent>()),
        )
        assertEquals(0x5EED, parcel.readInt())
    }

    /**
     * As AIDL lays out attachSurface(): an id, the surface, the size. The copy read back is a surface
     * a plugin can draw into, and the ints after it read right.
     */
    @Test
    fun aSurfaceTravelsAmongTheIntsAndCanBeDrawnInto() {
        val looper = HandlerThread("parcel-frame-test").apply { start() }
        val texture = SurfaceTexture(0)
        val original = Surface(texture)
        val frame = CountDownLatch(1)
        texture.setOnFrameAvailableListener({ frame.countDown() }, Handler(looper.looper))
        try {
            parcel.writeInt(7)
            parcel.writeTypedObject(original, 0)
            parcel.writeInt(1280)
            parcel.writeInt(720)
            parcel.setDataPosition(0)
            assertEquals(7, parcel.readInt())
            val read: Surface? = parcel.readTypedObject(Surface.CREATOR)
            assertNotNull(read)
            val copy = read!!
            assertEquals(1280, parcel.readInt())
            assertEquals(720, parcel.readInt())
            assertTrue("the original keeps its surface: written with flags 0", original.isValid)
            assertTrue(copy.isValid)

            texture.setDefaultBufferSize(64, 36)
            val canvas = copy.lockCanvas(null)
            canvas.drawColor(Color.MAGENTA)
            copy.unlockCanvasAndPost(canvas)
            copy.release()
            assertTrue("a frame drawn on the copy reaches the consumer", frame.await(5, SECONDS))
        } finally {
            original.release()
            texture.release()
            looper.quitSafely()
        }
    }

    /** As AIDL lays out attach(): the environment, then the host binder. */
    @Test
    fun theHostAfterTheEnvironmentReadsRight() {
        val host = Binder()
        parcel.writeTypedObject(env, 0)
        parcel.writeStrongBinder(host)
        parcel.setDataPosition(0)
        assertEquals(env, parcel.readTypedObject(parcelableCreator<WallpaperEnvironment>()))
        assertSame(host, parcel.readStrongBinder())
    }

    /** A newer launcher's environment, two fields longer: they are skipped, and the host still reads. */
    @Test
    fun aNewerEnvironmentReadsHere() {
        val host = Binder()
        parcel.writeInt(1) // writeTypedObject's "not null"
        frame {
            writeInt(1920); writeInt(1080); writeInt(320); writeString("en-CA"); writeInt(3)
            writeInt(42); writeString("a field from a later version")
        }
        parcel.writeStrongBinder(host)
        parcel.setDataPosition(0)
        assertEquals(
            WallpaperEnvironment(1920, 1080, 320, "en-CA", 3),
            parcel.readTypedObject(parcelableCreator<WallpaperEnvironment>()),
        )
        assertSame(host, parcel.readStrongBinder())
    }

    /** A newer plugin's batch, every item a field longer: each item reads, and so does what follows them. */
    @Test
    fun aNewerBatchReadsItemByItem() {
        frame {
            writeInt(2)
            for (n in 1..2) frame {
                writeString("id-$n"); writeString("content://x.wallpapers/$n.png")
                writeInt(WallpaperContract.TYPE_IMAGE); writeInt(WallpaperContract.MODE_FILL)
                writeString("Title $n"); writeString(null); writeString(null)
                writeLong(7L) // a field from a later version
            }
            writeLong(120_000L)
            writeString("a batch field from a later version")
        }
        parcel.writeInt(0x5EED)
        parcel.setDataPosition(0)
        val batch = parcelableCreator<WallpaperBatch>().createFromParcel(parcel)
        assertEquals(listOf("id-1", "id-2"), batch.items.map { it.id })
        assertEquals(listOf("Title 1", "Title 2"), batch.items.map { it.title })
        assertEquals(WallpaperContract.MODE_FILL, batch.items[1].mode)
        assertEquals(120_000L, batch.nextRequestMs)
        assertEquals(0x5EED, parcel.readInt())
    }

    @Test
    fun brokenFramesAreRefused() {
        val environment = parcelableCreator<WallpaperEnvironment>()
        refused(environment) { writeInt(2) } // smaller than its own size int
        refused(environment) { writeInt(4_000); writeInt(1920) } // runs past the parcel
        refused(environment) { // claims 8 bytes and holds more fields than that
            writeInt(8); writeInt(1920); writeInt(1080); writeInt(320); writeString("en-CA"); writeInt(2)
        }
        refused(parcelableCreator<WallpaperBatch>()) { frame { writeInt(1_000_000); writeLong(0L) } }
        refused(parcelableCreator<WallpaperEvent>()) { // more colours than the frame can hold
            frame {
                writeString(WallpaperContract.EVENT_NOW_PLAYING); writeString(null); writeString(null)
                writeString(null); writeString(null); writeInt(1)
                writeInt(1_000_000); writeInt(0xFF3366CC.toInt())
            }
        }
        refused(parcelableCreator<WallpaperEvent>()) { // claims 8 bytes and holds more fields than that
            writeInt(8); writeString(WallpaperContract.EVENT_NOW_PLAYING); writeString(null); writeString(null)
            writeString(null); writeString(null); writeInt(0); writeInt(0)
        }
    }

    private fun refused(creator: Parcelable.Creator<*>, write: Parcel.() -> Unit) {
        parcel.setDataSize(0)
        parcel.write()
        parcel.setDataPosition(0)
        assertThrows(BadParcelableException::class.java) { creator.createFromParcel(parcel) }
    }

    /** The JVM tests cannot make a symbolic link on every machine; a device always can. */
    @Test
    fun aSymbolicLinkOutOfTheRootIsRefused() {
        val files = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "path-guard")
        files.deleteRecursively()
        val root = File(files, "wallpapers").apply { mkdirs() }
        File(root, "field.png").writeText("picture")
        val secret = File(files, "secret.xml").apply { writeText("not a wallpaper") }
        Os.symlink(secret.path, File(root, "escape.png").path)
        Os.symlink(File(root, "field.png").path, File(root, "alias.png").path)

        assertNull(PathGuard.resolveInside(root, "/escape.png"))
        assertEquals(File(root, "field.png").canonicalFile, PathGuard.resolveInside(root, "/alias.png"))
        files.deleteRecursively()
    }
}
