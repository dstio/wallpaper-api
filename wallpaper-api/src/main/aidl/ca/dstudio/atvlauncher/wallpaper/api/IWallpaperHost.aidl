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
}
