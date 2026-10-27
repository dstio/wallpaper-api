package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import android.app.Activity
import android.os.Bundle
import android.view.View

/**
 * Colour Fields Live's settings, opened by the launcher (`wallpaperSettings`). It has nothing to
 * choose: debug builds show the five test switches ([LiveSettings]), each saved as it is changed.
 * Done or BACK closes the screen.
 */
class LiveSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_settings)
        val settings = LiveSettings.read(this)
        if (BuildConfig.DEBUG) {
            findViewById<View>(R.id.testing).visibility = View.VISIBLE
            bindSwitch(R.id.never_draw, LiveSettings.KEY_NEVER_DRAW, settings.neverDraw)
            bindSwitch(R.id.crash, LiveSettings.KEY_CRASH, settings.crash)
            bindSwitch(R.id.unsupported, LiveSettings.KEY_UNSUPPORTED, settings.unsupported)
            bindSwitch(R.id.slow_release, LiveSettings.KEY_SLOW_RELEASE, settings.slowRelease)
            bindSwitch(R.id.ask_events, LiveSettings.KEY_ASK_EVENTS, settings.askEvents)
        }
        findViewById<View>(R.id.done).setOnClickListener { finish() }
        findViewById<View>(if (BuildConfig.DEBUG) R.id.never_draw else R.id.done).requestFocus()
    }
}
