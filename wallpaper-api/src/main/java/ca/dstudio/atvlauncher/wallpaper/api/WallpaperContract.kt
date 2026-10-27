package ca.dstudio.atvlauncher.wallpaper.api

/**
 * Constants for the ATV Launcher wallpaper plugin protocol, version 2.
 *
 * A plugin APK declares one [android.app.Service] per wallpaper source. The launcher discovers it
 * with `queryIntentServices`, binds it, and drives it through [IWallpaperProvider]; the plugin
 * answers through the [IWallpaperHost] it is handed in `attach`. Extend [WallpaperProviderService]
 * rather than implementing the AIDL interface, and serve the pictures with a
 * [WallpaperFileProvider].
 *
 * ```xml
 * <service android:name=".MyWallpaperService" android:exported="true"
 *          android:permission="ca.dstudio.atvlauncher.permission.BIND_WALLPAPER">
 *     <intent-filter>
 *         <action android:name="ca.dstudio.atvlauncher.wallpaper.SERVICE"/>
 *     </intent-filter>
 *     <meta-data
 *         android:name="ca.dstudio.atvlauncher.wallpaper.INFO"
 *         android:resource="@xml/wallpaper_info"/>
 * </service>
 * ```
 *
 * **The rules of the conversation.**
 * - The launcher sets the pace: it asks ([IWallpaperProvider.request]) and the plugin answers with a
 *   [WallpaperBatch] through [IWallpaperHost.push]. **Every request is answered with a push.**
 * - **An empty batch means "nothing yet".** The launcher keeps what it has, is not told the plugin
 *   failed, and asks again after [WallpaperBatch.nextRequestMs] (60 s when unset). No push at all
 *   within 30 seconds of a request counts as a failure; after three in a row the launcher shows the
 *   plugin as not responding.
 * - A non-empty batch **replaces** the plugin's list, and its order is the order shown.
 * - **At most [MAX_ITEMS] items count.** The launcher keeps the first [MAX_ITEMS] (60) of a batch,
 *   in order, and ignores the rest; [WallpaperProviderService.push] sends only those.
 * - **An item's id names one picture's content.** The launcher fetches an id once and keeps the
 *   file until the id leaves the list, so new content behind the same URI needs a new id.
 * - [WallpaperBatch.nextRequestMs]: [NEXT_REQUEST_UNCHANGED] keeps the interval, `0` means never on
 *   a timer (only on start, settings closed and invalidate), and `N` asks again after N ms - never
 *   sooner than [MIN_REQUEST_INTERVAL_MS]. The first interval is `refreshIntervalMs` from
 *   `wallpaper_info.xml`.
 * - **Rate limits.** An [IWallpaperHost.invalidate] causes at most one request per
 *   [MIN_REQUEST_INTERVAL_MS]; a push nobody asked for is processed at most once a minute; nothing
 *   is fetched while no launcher window is started.
 */
object WallpaperContract {

    /** Intent action a wallpaper plugin's service declares in its manifest to be discoverable. */
    const val ACTION_WALLPAPER_SERVICE = "ca.dstudio.atvlauncher.wallpaper.SERVICE"

    /**
     * meta-data name on the plugin's service, pointing at a `wallpaper_info.xml` resource that uses
     * the `WallpaperProvider` styleable this library declares.
     */
    const val META_WALLPAPER_INFO = "ca.dstudio.atvlauncher.wallpaper.INFO"

    /**
     * Signature-level permission declared by the launcher. A plugin's service requires it
     * (`android:permission=`), so only the launcher can bind it, and its [WallpaperFileProvider]
     * requires it to read (`android:readPermission=`), so only the launcher can read its pictures.
     *
     * A plugin does **not** declare this permission and does not need a `<uses-permission>` for it;
     * it only names it on its `<service>` and `<provider>`.
     */
    const val PERMISSION_BIND_WALLPAPER = "ca.dstudio.atvlauncher.permission.BIND_WALLPAPER"

    /** Protocol version this library implements. Declared by a plugin as `apiVersion` in wallpaper_info.xml. */
    const val API_VERSION = 2

    /** [IWallpaperProvider.request] reason: the launcher has just attached (switched on, or bound again). */
    const val REASON_START = 0

    /** [IWallpaperProvider.request] reason: the interval (`refreshIntervalMs`, then [WallpaperBatch.nextRequestMs]) ran out. */
    const val REASON_TIMER = 1

    /** [IWallpaperProvider.request] reason: the plugin's settings activity closed. */
    const val REASON_SETTINGS_CLOSED = 2

    /** [IWallpaperProvider.request] reason: the user asked the launcher to ask again. */
    const val REASON_MANUAL = 3

    /** [IWallpaperProvider.request] reason: the plugin called [IWallpaperHost.invalidate]. */
    const val REASON_INVALIDATED = 4

    /** [WallpaperItem.type]: a picture. [WallpaperItem.uri] is a `content://` or `https://` link. */
    const val TYPE_IMAGE = 0

    /** [WallpaperItem.type]: a plain colour. [WallpaperItem.uri] is `#RRGGBB` or `#AARRGGBB`. */
    const val TYPE_COLOR = 1

    /** [WallpaperItem.type]: a video. Declared from 2.0; a launcher accepts it from a later version. */
    const val TYPE_VIDEO = 2

    /**
     * [WallpaperItem.mode]: no preference. Drawn as the user's Scale Mode says, and as Fill while
     * that is left to the plugin.
     */
    const val MODE_DEFAULT = 0

    /**
     * [WallpaperItem.mode]: cover the screen, cropping what does not fit. A plugin's mode applies
     * while the user's Scale Mode is left to the plugin (the default); a Scale Mode the user picks wins.
     */
    const val MODE_FILL = 1

    /** [WallpaperItem.mode]: show all of the picture. Applies as [MODE_FILL] says. */
    const val MODE_FIT = 2

    /** [WallpaperItem.mode]: show all of the picture over a blurred copy of itself. Applies as [MODE_FILL] says. */
    const val MODE_FIT_BLUR = 3

    /** [IWallpaperProvider.failed] why: the launcher could not read or decode the item. */
    const val FAIL_UNREADABLE = 1

    /** [IWallpaperProvider.failed] why: the picture is too small to be a wallpaper. */
    const val FAIL_TOO_SMALL = 2

    /** [IWallpaperProvider.failed] why: the launcher does not take this item (its type, size or link). */
    const val FAIL_UNSUPPORTED = 3

    /** Event name: the focused tile changed. Declared from 2.0 (`wallpaperEvents`); sent from a later version. */
    const val EVENT_FOCUSED_TILE = "focusedTile"

    /** Event name: what is playing changed. Declared from 2.0 (`wallpaperEvents`); sent from a later version. */
    const val EVENT_NOW_PLAYING = "nowPlaying"

    /** [WallpaperBatch.nextRequestMs] value meaning "leave my interval as it is". */
    const val NEXT_REQUEST_UNCHANGED = -1L

    /**
     * Shortest interval the launcher asks at. Smaller non-zero values from `refreshIntervalMs` or
     * [WallpaperBatch.nextRequestMs] are raised to this, and an [IWallpaperHost.invalidate] causes at
     * most one request per interval.
     */
    const val MIN_REQUEST_INTERVAL_MS = 60_000L

    /**
     * The most items of one [WallpaperBatch] the launcher takes: it keeps the first [MAX_ITEMS], in
     * order, and ignores the rest. [WallpaperProviderService.push] sends only those, and logs when it
     * leaves some out.
     */
    const val MAX_ITEMS = 60
}
