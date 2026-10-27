package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperBatch
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperContract
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperItem
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderService
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Colour Fields: [ColourFieldsSettings.count] gradients in the chosen [Palette], made at the
 * launcher's screen size, then one plain colour.
 *
 * Every request is answered on a worker thread, one at a time, with the whole list. A picture is
 * drawn only when its file is missing; files the list no longer names are deleted. An id names one
 * picture's content, so a palette change is new ids. When drawing takes longer than 2 s, an empty
 * batch ("nothing yet") goes first. Debug builds have two test switches (the settings screen):
 * answer 5 s late, and do not answer at all.
 */
class ColourFieldsService : WallpaperProviderService() {

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onRequest(reason: Int) {
        val settings = ColourFieldsSettings.read(this)
        if (settings.silent) {
            Log.i(TAG, "request $reason ignored: Do not answer is on")
            return
        }
        val env = environment
        val width = env?.widthPx?.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = env?.heightPx?.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        Log.i(TAG, "request $reason: ${settings.count} ${settings.palette.key} at ${width}x$height")
        worker.execute { answer(settings, width, height) }
    }

    override fun onShown(itemId: String) {
        Log.i(TAG, "shown $itemId")
    }

    override fun onFailed(itemId: String, why: Int) {
        Log.w(TAG, "failed $itemId: $why")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Worker thread. Exactly one full answer, maybe after one "nothing yet". */
    private fun answer(settings: ColourFieldsSettings, width: Int, height: Int) {
        if (settings.slow) SystemClock.sleep(SLOW_MS)
        val answer = Answer()
        val nothingYet = Runnable { answer.nothingYet() }
        main.postDelayed(nothingYet, NOTHING_YET_AFTER_MS)
        val batch = try {
            WallpaperBatch(items(settings, width, height), NEXT_REQUEST_MS)
        } catch (e: Exception) {
            // Nothing to show now: say so, and the launcher asks again later.
            Log.w(TAG, "could not make the pictures", e)
            WallpaperBatch(emptyList())
        }
        main.removeCallbacks(nothingYet)
        answer.done(batch)
    }

    /**
     * Keeps the two pushes in order: "nothing yet" from the main thread can never land after the full
     * answer from the worker.
     */
    private inner class Answer {
        private var answered = false

        @Synchronized
        fun nothingYet() {
            if (answered) return
            Log.i(TAG, "still drawing: nothing yet")
            push(WallpaperBatch(emptyList()))
        }

        @Synchronized
        fun done(batch: WallpaperBatch) {
            answered = true
            Log.i(TAG, "answer: ${batch.items.size} items")
            push(batch)
        }
    }

    private fun items(settings: ColourFieldsSettings, width: Int, height: Int): List<WallpaperItem> {
        val dir = File(filesDir, FILES_DIR).apply { mkdirs() }
        val palette = settings.palette
        val ids = (1..settings.count).map { n -> "field-${palette.key}-$n" }
        val keep = ids.map { "$it.png" }.toSet()
        dir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }

        val label = getString(palette.label)
        val fields = ids.mapIndexed { index, id ->
            val n = index + 1
            val file = File(dir, "$id.png")
            if (!file.isFile) FieldRenderer.write(file, palette, n, width, height)
            WallpaperItem(
                id = id,
                uri = "content://$packageName.wallpapers/$id.png",
                // The third asks to be fitted, so the launcher's per-picture mode can be seen.
                mode = if (n == 3) WallpaperContract.MODE_FIT else WallpaperContract.MODE_FILL,
                title = "$label $n",
            )
        }
        return fields + WallpaperItem(
            id = COLOUR_ID,
            uri = COLOUR,
            type = WallpaperContract.TYPE_COLOR,
            title = getString(R.string.colour_title),
        )
    }

    private companion object {
        const val TAG = "ColourFields"

        /** WallpaperFileProvider's default root, which ColourFieldsFiles serves. */
        const val FILES_DIR = "wallpapers"
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val COLOUR = "#1B2A55"
        const val COLOUR_ID = "colour-1b2a55"

        /** Ask again in six hours. */
        const val NEXT_REQUEST_MS = 6 * 3_600_000L
        const val NOTHING_YET_AFTER_MS = 2_000L
        const val SLOW_MS = 5_000L
    }
}
