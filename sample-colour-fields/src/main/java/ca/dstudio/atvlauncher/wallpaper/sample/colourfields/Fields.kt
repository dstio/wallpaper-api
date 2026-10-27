package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The three looks a user picks from. [key] is stored, and is part of every picture's id; [label]
 * is a string resource.
 */
enum class Palette(val key: String, val label: Int, private val fields: List<List<String>>) {
    WARM(
        "warm", R.string.palette_warm,
        listOf(
            listOf("#FF7E5F", "#FEB47B"),
            listOf("#F12711", "#F5AF19"),
            listOf("#FF5F6D", "#FFC371"),
            listOf("#ED4264", "#FFEDBC"),
            listOf("#B24592", "#F15F79"),
            listOf("#E65C00", "#F9D423", "#FFF3B0"),
        ),
    ),
    COOL(
        "cool", R.string.palette_cool,
        listOf(
            listOf("#2193B0", "#6DD5ED"),
            listOf("#0052D4", "#4364F7", "#6FB1FC"),
            listOf("#43C6AC", "#191654"),
            listOf("#1A2980", "#26D0CE"),
            listOf("#4CA1AF", "#C4E0E5"),
            listOf("#348F50", "#56B4D3"),
        ),
    ),
    NIGHT(
        "night", R.string.palette_night,
        listOf(
            listOf("#0F2027", "#203A43", "#2C5364"),
            listOf("#141E30", "#243B55"),
            listOf("#232526", "#414345"),
            listOf("#000428", "#004E92"),
            listOf("#200122", "#6F0000"),
            listOf("#0B0C2A", "#2E1A47", "#4A2C5E"),
        ),
    );

    /** The colour stops of picture [n], counted from 1. */
    fun stops(n: Int): IntArray = fields[(n - 1) % fields.size].map(Color::parseColor).toIntArray()

    /** The angle picture [n]'s gradient runs at, in degrees; a second round of the stops turns 90. */
    fun angle(n: Int): Float = ((n - 1) * 37 % 180 + (n - 1) / fields.size * 90).toFloat()

    companion object {
        fun of(key: String?): Palette = entries.firstOrNull { it.key == key } ?: WARM
    }
}

/** What the settings screen stores, in prefs [FILE]. The service reads it at every request. */
class ColourFieldsSettings(
    val palette: Palette,
    val count: Int,
    /** Debug builds: wait 5 s before answering. */
    val slow: Boolean,
    /** Debug builds: ignore requests. */
    val silent: Boolean,
) {
    companion object {
        const val FILE = "colour-fields"
        const val KEY_PALETTE = "palette"
        const val KEY_COUNT = "count"
        const val KEY_SLOW = "answer-slowly"
        const val KEY_SILENT = "do-not-answer"
        const val MIN_COUNT = 1
        const val MAX_COUNT = 12
        const val DEFAULT_COUNT = 6

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun read(context: Context): ColourFieldsSettings = prefs(context).let {
            ColourFieldsSettings(
                palette = Palette.of(it.getString(KEY_PALETTE, null)),
                count = it.getInt(KEY_COUNT, DEFAULT_COUNT).coerceIn(MIN_COUNT, MAX_COUNT),
                slow = BuildConfig.DEBUG && it.getBoolean(KEY_SLOW, false),
                silent = BuildConfig.DEBUG && it.getBoolean(KEY_SILENT, false),
            )
        }
    }
}

/**
 * Colour Fields Live's test switches, debug builds only, in prefs [ColourFieldsSettings.FILE]: what the
 * launcher's surface host is checked against. The service reads them when they apply.
 */
class LiveSettings(
    /** Never draw, so never say drawn: the host's first-frame watchdog. Read at each attach. */
    val neverDraw: Boolean,
    /** Kill the process at the next frame: a plugin's binder dying while it holds a surface. */
    val crash: Boolean,
    /** Answer each surface with `surfaceFailed(SURFACE_UNSUPPORTED)`. Read at each attach. */
    val unsupported: Boolean,
    /** Answer a detach 300 ms late, past the host's bounded wait. Read at each detach. */
    val slowRelease: Boolean,
    /**
     * Ask for both events it declares at each attach and log every one that comes: a launcher sends a
     * plugin of another package none. Read at each attach.
     */
    val askEvents: Boolean = false,
) {
    companion object {
        const val KEY_NEVER_DRAW = "live-never-draw"
        const val KEY_CRASH = "live-crash"
        const val KEY_UNSUPPORTED = "live-unsupported"
        const val KEY_SLOW_RELEASE = "live-slow-release"
        const val KEY_ASK_EVENTS = "live-ask-events"

        fun read(context: Context): LiveSettings = ColourFieldsSettings.prefs(context).let {
            LiveSettings(
                neverDraw = BuildConfig.DEBUG && it.getBoolean(KEY_NEVER_DRAW, false),
                crash = BuildConfig.DEBUG && it.getBoolean(KEY_CRASH, false),
                unsupported = BuildConfig.DEBUG && it.getBoolean(KEY_UNSUPPORTED, false),
                slowRelease = BuildConfig.DEBUG && it.getBoolean(KEY_SLOW_RELEASE, false),
                askEvents = BuildConfig.DEBUG && it.getBoolean(KEY_ASK_EVENTS, false),
            )
        }
    }
}

/** Draws one field and writes it as a PNG; [drawGradient] is the field itself, which Colour Fields Live turns. */
object FieldRenderer {

    /**
     * A linear gradient through the centre at the palette's angle, spanning the whole picture, written
     * to a `.tmp` file and renamed into place, so a half-written picture is never served as [file].
     */
    fun write(file: File, palette: Palette, n: Int, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            drawGradient(Canvas(bitmap), width, height, palette.angle(n), palette.stops(n))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("could not write $file")
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A linear gradient through the centre at [degrees] through [stops], spanning the whole
     * [width] x [height] box: the first stop at one extreme corner, the last at the opposite one.
     */
    fun drawGradient(canvas: Canvas, width: Int, height: Int, degrees: Float, stops: IntArray, paint: Paint = Paint()) {
        val radians = Math.toRadians(degrees.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val half = (abs(width * dx) + abs(height * dy)) / 2f
        val cx = width / 2f
        val cy = height / 2f
        paint.shader = LinearGradient(
            cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            stops, null, Shader.TileMode.CLAMP,
        )
        canvas.drawPaint(paint)
    }
}
