package ca.dstudio.atvlauncher.wallpaper.sample.colourfields

import ca.dstudio.atvlauncher.wallpaper.api.WallpaperFileProvider

/**
 * Serves `filesDir/wallpapers/` to the launcher, at `content://<applicationId>.wallpapers/`. The
 * library does the rest: read-only, path-guarded, BIND_WALLPAPER to read.
 */
class ColourFieldsFiles : WallpaperFileProvider()
