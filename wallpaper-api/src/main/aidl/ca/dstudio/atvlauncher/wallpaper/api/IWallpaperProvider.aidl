package ca.dstudio.atvlauncher.wallpaper.api;

import android.view.Surface;
import ca.dstudio.atvlauncher.wallpaper.api.IWallpaperHost;
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperEnvironment;
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperEvent;

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

    // 2.1: the surface kind and events. Appended after detach(); see WallpaperContract's rules.

    /**
     * Surface kind: a surface to draw into, width x height pixels, named by surfaceId - a new id
     * for every attach. Answer IWallpaperHost.surfaceDrawn(surfaceId) after the first frame, within
     * SURFACE_FIRST_FRAME_MS (SURFACE_FIRST_FRAME_COLD_MS for the first attach after attach()), or
     * IWallpaperHost.surfaceFailed(surfaceId, code). Draw at the size given: never change the
     * buffers' geometry, never call Surface.setFrameRate.
     */
    void attachSurface(int surfaceId, in Surface surface, int width, int height);

    /**
     * Surface kind: stop drawing into surface surfaceId, release it (the EGL surface with it) and
     * answer IWallpaperHost.surfaceReleased(surfaceId). An id that is not the current one is ignored.
     */
    void detachSurface(int surfaceId);

    /**
     * Whether the picture may move. false: hold the picture still; a change it was asked for (a
     * setting) may still draw a frame. Motion starts off at attach(): the picture holds still until
     * setMotion(true).
     */
    void setMotion(boolean allowed);

    /**
     * An event the plugin asked for with IWallpaperHost.wantEvents() (and declared in
     * wallpaperEvents): the focused tile, or what is playing - nothing playing included. The latest of
     * each comes after every wantEvents() and every attachSurface() while asked for, then each change.
     * What it carries is WallpaperEvent's KDoc.
     */
    void event(in WallpaperEvent event);
}
