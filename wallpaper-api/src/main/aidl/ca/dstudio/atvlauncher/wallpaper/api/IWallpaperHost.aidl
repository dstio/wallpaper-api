package ca.dstudio.atvlauncher.wallpaper.api;

import ca.dstudio.atvlauncher.wallpaper.api.WallpaperBatch;

/**
 * Plugin -> launcher. Handed to the service in IWallpaperProvider.attach().
 *
 * Every method is oneway: a plugin never blocks on the launcher, and calls made on one binder
 * arrive in the order they were sent.
 *
 * A method's transaction code is its position here: a new method goes at the end, and no method
 * is ever removed or reordered.
 */
oneway interface IWallpaperHost {

    /**
     * The answer to a request. A non-empty batch replaces the plugin's list; an empty one means
     * "nothing yet". A push nobody asked for is processed at most once a minute.
     */
    void push(in WallpaperBatch batch);

    /**
     * The plugin's data changed: ask me again. Causes at most one request (REASON_INVALIDATED)
     * per MIN_REQUEST_INTERVAL_MS.
     */
    void invalidate();

    // 2.1: the surface kind and events. Appended after invalidate(); see WallpaperContract's rules.

    /** Surface kind: the first frame on surface surfaceId is drawn. */
    void surfaceDrawn(int surfaceId);

    /**
     * Surface kind: the plugin let surface surfaceId go - it draws into it no more. The answer to
     * IWallpaperProvider.detachSurface().
     */
    void surfaceReleased(int surfaceId);

    /**
     * Surface kind: the plugin cannot draw on surface surfaceId. SURFACE_UNSUPPORTED (this device
     * cannot draw it) | SURFACE_ERROR.
     */
    void surfaceFailed(int surfaceId, int code);

    /**
     * The events the plugin needs now: WallpaperProviderInfo's FLAG_EVENT_ flags, a subset of the
     * wallpaperEvents it declared (a flag it did not declare is ignored); 0 asks for none. None until it
     * asks, and none after each attach() until it asks again. Each ask is answered with the latest of
     * every event asked for (IWallpaperProvider.event), as soon as the launcher has it; after that an
     * event comes when it changes. Any kind of plugin may call it; a launcher may send a plugin none
     * (WallpaperContract's "Events").
     */
    void wantEvents(int flags);
}
