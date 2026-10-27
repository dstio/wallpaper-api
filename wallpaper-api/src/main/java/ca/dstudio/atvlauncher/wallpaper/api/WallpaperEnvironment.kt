package ca.dstudio.atvlauncher.wallpaper.api

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize

/**
 * What a plugin needs to know about the screen it makes wallpapers for. Delivered with
 * [IWallpaperProvider.attach].
 *
 * **A parcelable only grows at its end.** A later version adds fields after [apiVersion] and
 * never removes, reorders or retypes one. Each instance travels
 * framed with its size (`ParcelFrame.kt`), so a plugin built against this version reads an
 * environment from a newer launcher, skipping what it does not know, and the reverse.
 */
@Parcelize
data class WallpaperEnvironment(
    /** Width of the screen the wallpaper fills, in pixels; 0 when not known (make pictures for 1920 x 1080). */
    val widthPx: Int,
    /** Height of the screen the wallpaper fills, in pixels; 0 when not known (make pictures for 1920 x 1080). */
    val heightPx: Int,
    /** Density of that screen, as in `DisplayMetrics.densityDpi`. */
    val densityDpi: Int,
    /** The launcher's language as a BCP 47 tag ("en-CA", "zh-CN"), for titles a plugin sends. */
    val locale: String,
    /** [WallpaperContract.API_VERSION] of the launcher that sent this. */
    val apiVersion: Int,
) : Parcelable {

    private companion object : Parceler<WallpaperEnvironment> {

        override fun WallpaperEnvironment.write(parcel: Parcel, flags: Int) = parcel.writeFramed {
            writeInt(widthPx)
            writeInt(heightPx)
            writeInt(densityDpi)
            writeString(locale)
            writeInt(apiVersion)
        }

        override fun create(parcel: Parcel): WallpaperEnvironment = parcel.readFramed {
            val widthPx = readInt()
            val heightPx = readInt()
            val densityDpi = readInt()
            val locale = readString().orEmpty()
            val apiVersion = readInt()
            WallpaperEnvironment(widthPx, heightPx, densityDpi, locale, apiVersion)
        }
    }
}
