package ca.dstudio.atvlauncher.wallpaper.api;

import ca.dstudio.atvlauncher.wallpaper.api.IWallpaperHost;
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperEnvironment;

/**
 * Launcher -> plugin. Implemented by WallpaperProviderService; a plugin APK never writes this
 * interface by hand.
 *
 * Every method is oneway, so the launcher's threads are never held by a plugin, and calls made on
 * one binder arrive in the order they were sent.
 *
 * A method's transaction code is its position here: a new method goes at the end, and no method
 * is ever removed or reordered.
 */
oneway interface IWallpaperProvider {

    /**
     * The launcher is ready to talk: env describes the screen, host is how to answer. May arrive
     * again with no detach() in between (either process restarted), so treat it as a reset.
     */
    void attach(in WallpaperEnvironment env, IWallpaperHost host);

    /**
     * Answer with IWallpaperHost.push(), always - an empty batch for "nothing yet".
     * REASON_START | REASON_TIMER | REASON_SETTINGS_CLOSED | REASON_MANUAL | REASON_INVALIDATED.
     */
    void request(int reason);

    /** The launcher put this item on screen. */
    void shown(String itemId);

    /** The launcher could not use this item. FAIL_UNREADABLE | FAIL_TOO_SMALL | FAIL_UNSUPPORTED. */
    void failed(String itemId, int why);

    /** The plugin was switched off, or the launcher let it go. The host is dead after this. */
    void detach();
}
