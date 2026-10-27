package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

/**
 * The service as the launcher drives it, from the app's own process (the shell cannot bind it: it
 * has no BIND_WALLPAPER). The binder is local here, so this checks the plugin, not the wire format;
 * wallpaper-api's ParcelFrameTest checks that.
 */
@RunWith(AndroidJUnit4::class)
class ColourFieldsServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = ColourFieldsSettings.prefs(context)
    private val pushes = LinkedBlockingQueue<WallpaperBatch>()
    private var connection: ServiceConnection? = null

    private val host = object : IWallpaperHost.Stub() {
        override fun push(batch: WallpaperBatch) {
            pushes.add(batch)
        }

        override fun invalidate() {}
    }

    @Before
    fun cool4() {
        prefs.edit().clear().putString(ColourFieldsSettings.KEY_PALETTE, "cool")
            .putInt(ColourFieldsSettings.KEY_COUNT, 4).commit()
        File(context.filesDir, "wallpapers").deleteRecursively()
    }

    @After
    fun unbind() {
        connection?.let(context::unbindService)
        prefs.edit().clear().commit()
        File(context.filesDir, "wallpapers").deleteRecursively()
    }

    private fun attached(): IWallpaperProvider {
        val bound = LinkedBlockingQueue<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bound.add(service)
            }

            override fun onServiceDisconnected(name: ComponentName) {}
        }
        assertTrue(context.bindService(Intent(context, ColourFieldsService::class.java), conn, Context.BIND_AUTO_CREATE))
        connection = conn
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
            val batch = pushes.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return null
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
}
