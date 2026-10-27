package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * Colour Fields' settings, opened by the launcher (`wallpaperSettings`). Plain framework views, all
 * reachable with a D-pad. Every change is saved as it is made; Done or BACK closes the screen, and
 * the launcher asks again (`REASON_SETTINGS_CLOSED`).
 */
class SettingsActivity : Activity() {

    private val palettes = mapOf(
        R.id.palette_warm to Palette.WARM,
        R.id.palette_cool to Palette.COOL,
        R.id.palette_night to Palette.NIGHT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val prefs = ColourFieldsSettings.prefs(this)
        val settings = ColourFieldsSettings.read(this)

        val chosen = palettes.entries.first { it.value == settings.palette }.key
        findViewById<RadioGroup>(R.id.palette).apply {
            check(chosen)
            setOnCheckedChangeListener { _, id ->
                palettes[id]?.let { prefs.edit().putString(ColourFieldsSettings.KEY_PALETTE, it.key).apply() }
            }
        }

        var count = settings.count
        val countView = findViewById<TextView>(R.id.count)
        countView.text = count.toString()
        fun step(by: Int) {
            count = (count + by).coerceIn(ColourFieldsSettings.MIN_COUNT, ColourFieldsSettings.MAX_COUNT)
            countView.text = count.toString()
            prefs.edit().putInt(ColourFieldsSettings.KEY_COUNT, count).apply()
        }
        findViewById<View>(R.id.fewer).setOnClickListener { step(-1) }
        findViewById<View>(R.id.more).setOnClickListener { step(+1) }

        if (BuildConfig.DEBUG) {
            findViewById<View>(R.id.testing).visibility = View.VISIBLE
            bindSwitch(R.id.slow, ColourFieldsSettings.KEY_SLOW, settings.slow)
            bindSwitch(R.id.silent, ColourFieldsSettings.KEY_SILENT, settings.silent)
        }

        findViewById<View>(R.id.done).setOnClickListener { finish() }
        findViewById<View>(chosen).requestFocus()
    }

    private fun bindSwitch(id: Int, key: String, on: Boolean) {
        findViewById<CompoundButton>(id).apply {
            isChecked = on
            setOnCheckedChangeListener { _, checked ->
                ColourFieldsSettings.prefs(context).edit().putBoolean(key, checked).apply()
            }
        }
    }
}
