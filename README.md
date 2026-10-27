# ATV Launcher Wallpaper API

A small Android library for writing **wallpaper plugins** for ATV Launcher: apps that hand the
launcher pictures, which it shows on the home screen with its own timing, transitions and scale
modes. A plugin decides *what* to show; the launcher decides *when* and *how*.

The protocol is a bound service with one-way AIDL calls in both directions. This library holds the
contract, a base service that does the binding and caller checks for you, and a read-only content
provider for serving your pictures.

- **Library:** `wallpaper-api/`
- **Sample plugin:** `sample-colour-fields/` - Colour Fields, soft gradients drawn on the device
- **Protocol version:** 2 (`WallpaperContract.API_VERSION`)
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
    implementation 'com.github.dstio:wallpaper-api:2.0.1'
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
| `wallpaperKind` | `items` (you hand pictures, the launcher draws them) | `items` |
| `wallpaperTypes` | `image`, `color`, `video`, combined with `\|` | `image` |
| `wallpaperEvents` | `focusedTile`, `nowPlaying` - declared now, delivered by a later version | none |
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

**Threading.** Every callback runs on the main thread. Do slow work elsewhere; `push` and
`invalidate` are safe from any thread.

**Compatibility.** The AIDL interfaces only ever gain methods at their end, and every parcelable only
gains fields at its end, travelling with its size, so a plugin built against this version keeps
working with newer launchers and the reverse.

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
