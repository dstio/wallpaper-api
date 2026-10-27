# ATV Launcher Wallpaper API

A small Android library for writing **wallpaper plugins** for ATV Launcher: apps that hand the
launcher pictures, which it shows on the home screen with its own timing, transitions and scale
modes. A plugin decides *what* to show; the launcher decides *when* and *how*. From 2.1 a plugin can
also draw the wallpaper itself, on a surface the launcher lends it ([the surface kind](#the-surface-kind)),
and hear what happens on the home screen ([events](#events)).

The protocol is a bound service with one-way AIDL calls in both directions. This library holds the
contract, a base service that does the binding and caller checks for you, and a read-only content
provider for serving your pictures.

- **Library:** `wallpaper-api/`
- **Sample plugins:** `sample-colour-fields/` - Colour Fields, soft gradients drawn on the device and
  handed over as pictures, and Colour Fields Live, a gradient that turns slowly, drawn on a surface
- **Protocol version:** 2 (`WallpaperContract.API_VERSION`); 2.1 adds to it and a plugin still declares 2
- **minSdk:** 23

## Add it

The library is served by [JitPack](https://jitpack.io). In `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

In your app's `build.gradle`:

```groovy
dependencies {
    implementation 'com.github.dstio:wallpaper-api:2.1.0'
}
```

## Write a plugin

**1. The service.** Extend `WallpaperProviderService` and answer every request with a push:

```kotlin
class MyWallpaperService : WallpaperProviderService() {

    override fun onRequest(reason: Int) {
        // Main thread: do the work elsewhere, then push from any thread.
        worker.execute {
            val items = listOf(
                WallpaperItem(
                    id = "sunrise-2026-10-27",
                    uri = "content://${packageName}.wallpapers/sunrise.jpg",
                    mode = WallpaperContract.MODE_FILL,
                    title = "Sunrise",
                ),
            )
            push(WallpaperBatch(items, nextRequestMs = 6 * 60 * 60 * 1000L))
        }
    }
}
```

**2. The pictures.** Write them under `filesDir/wallpapers/` and serve them with an empty subclass of
`WallpaperFileProvider`:

```kotlin
class MyWallpaperFiles : WallpaperFileProvider()
```

**3. The manifest.**

```xml
<service
    android:name=".MyWallpaperService"
    android:exported="true"
    android:permission="ca.dstudio.atvlauncher.permission.BIND_WALLPAPER">
    <intent-filter>
        <action android:name="ca.dstudio.atvlauncher.wallpaper.SERVICE" />
    </intent-filter>
    <meta-data
        android:name="ca.dstudio.atvlauncher.wallpaper.INFO"
        android:resource="@xml/wallpaper_info" />
</service>

<provider
    android:name=".MyWallpaperFiles"
    android:authorities="${applicationId}.wallpapers"
    android:exported="true"
    android:readPermission="ca.dstudio.atvlauncher.permission.BIND_WALLPAPER" />
```

Do not declare the permission and do not add a `<uses-permission>` for it: the launcher declares it,
and only the launcher holds it. That is what keeps other apps from binding your service or reading
your pictures.

**4. `res/xml/wallpaper_info.xml`.**

```xml
<wallpaper-provider xmlns:app="http://schemas.android.com/apk/res-auto"
    app:wallpaperLabel="@string/app_name"
    app:wallpaperDescription="@string/wallpaper_description"
    app:wallpaperSettings="com.example.mywallpapers.SettingsActivity"
    app:wallpaperPreview="@drawable/wallpaper_preview"
    app:wallpaperTypes="image"
    app:refreshIntervalMs="21600000"
    app:apiVersion="2" />
```

| Attribute | Meaning | Default |
|---|---|---|
| `wallpaperLabel` | Name shown in the launcher's list of plugins | the service's label |
| `wallpaperDescription` | One line under the name | none |
| `wallpaperSettings` | Fully-qualified class of an **exported** settings activity | none |
| `wallpaperPreview` | A 16:9 drawable, 960 x 540, opaque, square corners | none |
| `wallpaperKind` | `items` (you hand pictures, the launcher draws them) or `surface` (you draw, from 2.1) | `items` |
| `wallpaperTypes` | `image`, `color`, `video`, combined with `\|`; an `items` plugin's only | `image` |
| `wallpaperEvents` | The [events](#events) you may ask for: `focusedTile`, `nowPlaying`, `homeMoving`, combined with `\|` | none |
| `refreshIntervalMs` | First interval the launcher asks again at; below 60000 is raised to it, 0 is never on a timer | `0` |
| `apiVersion` | The protocol version you built against | `2` |

## The contract

- **The launcher sets the pace.** It asks (`onRequest`) and you answer with a push. Every request is
  answered with exactly one push, from any thread.
- **An empty batch means "nothing yet".** The launcher keeps what it has and asks again later. No
  push within 30 seconds of a request counts as a failure; after three in a row the plugin is shown
  as not responding.
- **A non-empty batch replaces your list**, and its order is the order shown. At most 60 items count
  (`WallpaperContract.MAX_ITEMS`); `push` sends only the first 60.
- **An item's id names one picture's content.** The launcher fetches an id once and keeps the file
  until the id leaves your list. New content behind the same link needs a new id.
- **`nextRequestMs`**: `-1` keeps the interval, `0` means never on a timer, `N` asks again after N ms
  and never sooner than 60 seconds.
- **`invalidate()`** tells the launcher your data changed; it asks again at most once a minute.
- **Links**: `content://` from your own provider, or `https://`. Colours are `#RRGGBB` /
  `#AARRGGBB` with `TYPE_COLOR`.
- **`onAttach` may come again without `onDetach`** after either process restarts. Treat it as a reset.
- **Request reasons**: `REASON_START`, `REASON_TIMER`, `REASON_SETTINGS_CLOSED` (your settings
  activity closed), `REASON_MANUAL`, `REASON_INVALIDATED`.
- **Feedback**: `onShown(itemId)` when a picture goes on screen, `onFailed(itemId, why)` with
  `FAIL_UNREADABLE`, `FAIL_TOO_SMALL` or `FAIL_UNSUPPORTED`.

**Threading.** Every callback runs on the main thread. Do slow work elsewhere; `push`,
`invalidate`, `wantEvents` and the `surface...` calls are safe from any thread.

**Compatibility.** The AIDL interfaces only ever gain methods at their end, and every parcelable only
gains fields at its end, travelling with its size, so a plugin built against this version keeps
working with newer launchers and the reverse.

## The surface kind

From 2.1 a plugin can draw its wallpaper itself. It declares `app:wallpaperKind="surface"`; the
launcher lends it a surface the size of the screen and shows what it draws there.

```kotlin
class MyLiveService : WallpaperProviderService() {

    // A surface plugin is never asked for pictures.
    override fun onRequest(reason: Int) {}

    override fun onSurfaceAttached(surfaceId: Int, surface: Surface, width: Int, height: Int) {
        // Draw on a thread of your own; after the first frame, call surfaceDrawn(surfaceId).
        renderer.start(surfaceId, surface, width, height)
    }

    override fun onSurfaceDetached(surfaceId: Int) {
        // Stop, release the surface, then call surfaceReleased(surfaceId) from the thread that released it.
        renderer.stop()
    }

    override fun onMotionChanged(allowed: Boolean) = renderer.setMoving(allowed)
}
```

- **A surface plugin is never sent `onRequest`**, and its pushes are dropped. It declares no
  `wallpaperTypes` and no `refreshIntervalMs`.
- **Each surface has an id.** A call naming an id that is not the current one is ignored on both
  sides. The base service ends one surface (`onSurfaceDetached`) before it lends the next, before
  `onDetach` and before `onAttach` again, so you never hold two.
- **Say when the first frame is drawn**: `surfaceDrawn(id)` within 3 seconds of `onSurfaceAttached`,
  or 10 seconds for the first surface after `onAttach` - a cold process, shaders compiling
  (`SURFACE_FIRST_FRAME_MS`, `SURFACE_FIRST_FRAME_COLD_MS`).
- **On `onSurfaceDetached(id)`** stop drawing, release your EGL surface and the `Surface`, and call
  `surfaceReleased(id)`. A swap that fails at any time means the surface is gone: stop and wait for
  the next one.
- **Draw at the size given.** Never change the buffers' geometry and never call
  `Surface.setFrameRate`: on a TV a frame-rate vote can switch the HDMI mode and blank the screen.
- **Motion starts off.** Hold the picture still until `onMotionChanged(true)`, and whenever it says
  `false`; a change the user asked for (a setting) may still draw a frame.
- **A plugin that cannot draw** calls `surfaceFailed(id, SURFACE_UNSUPPORTED)` (this device cannot
  draw it) or `surfaceFailed(id, SURFACE_ERROR)`.

Colour Fields Live (`ColourFieldsLiveService` in the sample) is a complete one, drawn with
`Surface.lockCanvas`.

## Events

From 2.1 a plugin can hear what happens on the home screen, to colour or pace what it shows. Declare
the events in `wallpaperEvents`, ask for the ones you need now with `wantEvents(flags)` and hear them
in `onEvent`:

```kotlin
override fun onAttach(env: WallpaperEnvironment) {
    wantEvents(WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING or WallpaperProviderInfo.FLAG_EVENT_HOME_MOVING)
}

override fun onEvent(event: WallpaperEvent) {
    when (event.type) {
        WallpaperContract.EVENT_NOW_PLAYING -> palette.use(event.colors)
        WallpaperContract.EVENT_HOME_MOVING -> renderer.setLight(event.moving)
    }
}
```

| Event | Flag | What it carries |
|---|---|---|
| `focusedTile` | `FLAG_EVENT_FOCUSED_TILE` | Once the cursor rests on a tile: `colors[0]`, the tile's accent as the launcher's focus ring wears it, or `WHITE_LIGHT` for a tile with no colour of its own |
| `nowPlaying` | `FLAG_EVENT_NOW_PLAYING` | `playing`, the app, title, artist and album, and seven colours from the cover in a fixed order (`WallpaperEvent`); `playing` false when nothing plays |
| `homeMoving` | `FLAG_EVENT_HOME_MOVING` | `moving` true as the user moves on the home screen - the cursor, a scroll, the menu - and false once it has been still for a second (`HOME_STILL_MS`) |

- **Any kind of plugin may ask.** An `onAttach` is a reset: you hear nothing until you ask, so ask
  there, and again whenever what you need changes (`0` asks for none). A flag you did not declare is
  ignored.
- **Each ask is answered with the latest of every event asked for**, and so is each surface lent while
  you have asked; after that an event comes when it changes.
- **While the home screen moves it draws every frame and needs the GPU.** A surface plugin draws
  lighter then - a steady lower rate, never in starts and stops - and at its full rate once
  `moving` is false.
- **A launcher may send a plugin none.** ATV Launcher sends events only to its own plugins for now;
  another plugin hears none until a later version lets the user allow it.

## Build the project

```
./gradlew :wallpaper-api:testDebugUnitTest :wallpaper-api:assembleRelease :sample-colour-fields:assembleDebug
```

JDK 17 or newer and the Android SDK (platform 36) are needed.

## Licence

The ATV Launcher Wallpaper API is free to use, in free and paid apps alike, under the
[Apache License 2.0](LICENSE).

**Credit is required.** An app that ships this library must include the text of the [NOTICE](NOTICE)
file - "ATV Launcher Wallpaper API, Copyright 2026 DStudio" - where it lists its open-source licences
(an About or Licences screen, or a NOTICE file in its distribution), as section 4(d) of the licence
asks.

Copyright 2026 DStudio.
