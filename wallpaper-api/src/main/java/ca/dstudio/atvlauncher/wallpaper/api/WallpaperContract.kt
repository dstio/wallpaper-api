package ca.dstudio.atvlauncher.wallpaper.api

/**
 * Constants for the ATV Launcher wallpaper plugin protocol, version 2 (the library's 2.1 adds the
 * surface kind and events; [API_VERSION] stays 2, since the kind is the declaration).
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
 *
 * **The surface kind** (from 2.1). A plugin can draw its wallpaper itself: the launcher lends it a
 * surface ([IWallpaperProvider.attachSurface]) and shows what it draws there. The rules:
 * - A surface plugin declares `wallpaperKind="surface"`. It is never sent `request()`; its pushes are dropped.
 * - Each surface has an id; a call naming an id that is not the current one is ignored on both sides.
 * - It calls `surfaceDrawn(id)` after its first frame on a surface - within 3 s of the attach, or 10 s for the
 *   first attach after `attach()` (a cold process, a probe, shader compiles).
 * - On `detachSurface(id)` it stops drawing, releases its EGL surface and calls `surfaceReleased(id)`; a swap
 *   that fails at any time is the surface gone: stop and wait for the next attach. `detach()` ends any surface.
 * - It draws at the size given: it never changes the buffers' geometry and never calls `Surface.setFrameRate`
 *   (on a TV a frame-rate vote can switch the HDMI mode and blank the screen).
 * - `setMotion(false)`: hold the picture still; a change it was asked for (a setting) may still draw a frame.
 *
 * The two times are [SURFACE_FIRST_FRAME_MS] and [SURFACE_FIRST_FRAME_COLD_MS]. A plugin that cannot
 * draw on a surface calls `surfaceFailed(id, code)`, [SURFACE_UNSUPPORTED] or [SURFACE_ERROR]. Motion
 * starts off at `attach()`: the picture holds still until `setMotion(true)`. [WallpaperProviderService]
 * keeps the ids for a plugin: it ends a surface (`onSurfaceDetached`) before a new one replaces it,
 * before `detach()` and before an `attach()` again, so a plugin never holds two.
 *
 * **Events** (from 2.1). A plugin that declares events (`wallpaperEvents`) asks for the ones it needs
 * now with `wantEvents(flags)` ([WallpaperProviderInfo.FLAG_EVENT_FOCUSED_TILE],
 * [WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING], [WallpaperProviderInfo.FLAG_EVENT_HOME_MOVING]) and hears them as a [WallpaperEvent] through
 * `event()`. The rules:
 * - Any kind of plugin may ask. It hears none until it asks, and none after an `attach()` until it asks
 *   again - an attach is a reset - so it asks in `onAttach`, and again whenever what it needs changes
 *   (`0` asks for none). A flag it did not declare is ignored.
 * - Each ask is answered with the latest of every event asked for, as soon as the launcher has it (a
 *   tile sampled, a cover's colours worked out), and so is each `attachSurface` while they are asked
 *   for; after that an event comes when it changes. So a plugin that asks hears where things stand,
 *   without waiting for the next change.
 * - What each event carries, the colours' order included, is [WallpaperEvent]'s KDoc. Nothing playing
 *   is an event too: [WallpaperEvent.playing] false.
 * - **Home moving** ([EVENT_HOME_MOVING]): [WallpaperEvent.moving] true the moment the
 *   user moves on Home - the cursor, a scroll, the drawer - and false once Home has been still for
 *   [HOME_STILL_MS]. While it is true Home draws every frame and needs the GPU: a surface plugin draws
 *   lighter then (a steady lower rate), never in starts and stops, and its full rate once Home is still.
 * - A launcher may send a plugin none. ATV Launcher sends events only to its own plugins for now;
 *   another plugin hears none until a later version lets the user allow it.
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

    /**
     * Event name: the focused tile changed. Declared from 2.0 (`wallpaperEvents`); sent from 2.1 as a
     * [WallpaperEvent.type], to a plugin that asks for it ([WallpaperProviderInfo.FLAG_EVENT_FOCUSED_TILE]).
     */
    const val EVENT_FOCUSED_TILE = "focusedTile"

    /**
     * Event name: what is playing changed. Declared from 2.0 (`wallpaperEvents`); sent from 2.1 as a
     * [WallpaperEvent.type], to a plugin that asks for it ([WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING]).
     */
    const val EVENT_NOW_PLAYING = "nowPlaying"

    /**
     * Event name: Home started or stopped moving ([WallpaperEvent.moving]). From 2.1, declared and
     * asked for with [WallpaperProviderInfo.FLAG_EVENT_HOME_MOVING]; "Events" above says when it comes.
     */
    const val EVENT_HOME_MOVING = "homeMoving"

    /** How long Home is still before an [EVENT_HOME_MOVING] says so ([WallpaperEvent.moving] false). */
    const val HOME_STILL_MS = 1_000L

    /**
     * The colour of an [EVENT_FOCUSED_TILE] ([WallpaperEvent.colors]) for a tile with no colour of its own
     * - grey, black or white: white light, a hair cool of pure white (`#F4F6FA`), as the launcher's focus
     * ring wears it. From 2.1.
     */
    const val WHITE_LIGHT = 0xFFF4F6FA.toInt()

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

    /**
     * [IWallpaperHost.surfaceFailed] code: this device cannot draw the plugin (its GPU lacks what the
     * plugin needs). The launcher switches the plugin off and says why.
     */
    const val SURFACE_UNSUPPORTED = 1

    /** [IWallpaperHost.surfaceFailed] code: drawing on this surface failed. */
    const val SURFACE_ERROR = 2

    /** How long after an [IWallpaperProvider.attachSurface] the first frame may take: `surfaceDrawn(id)` within it. */
    const val SURFACE_FIRST_FRAME_MS = 3_000L

    /**
     * [SURFACE_FIRST_FRAME_MS] for the first surface after an [IWallpaperProvider.attach]: a cold
     * process, a probe, shader compiles.
     */
    const val SURFACE_FIRST_FRAME_COLD_MS = 10_000L
}
