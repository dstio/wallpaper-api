package ca.dstudio.atvlauncher.wallpaper.api

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize

/**
 * One wallpaper in a [WallpaperBatch].
 *
 * **A parcelable only grows at its end.** A later version adds fields after [sourceUrl] and never
 * removes, reorders or retypes one. Each item travels framed with its size (`ParcelFrame.kt`), so a
 * batch from a newer plugin reads in an older launcher, item after item, and the reverse.
 */
@Parcelize
data class WallpaperItem(
    /**
     * Names this picture's **content**, not its place in the list. The launcher fetches an id once
     * and keeps the file until the id leaves the list, so new content behind the same [uri] must
     * come with a new id.
     */
    val id: String,
    /**
     * Where the launcher reads the item. For [WallpaperContract.TYPE_IMAGE]: a `content://` link to
     * this plugin's own provider (a [WallpaperFileProvider], read with the launcher's permission)
     * or an `https://` URL. For [WallpaperContract.TYPE_COLOR]: `#RRGGBB` or `#AARRGGBB`.
     */
    val uri: String,
    /** [WallpaperContract.TYPE_IMAGE], [WallpaperContract.TYPE_COLOR] or [WallpaperContract.TYPE_VIDEO]. */
    val type: Int = WallpaperContract.TYPE_IMAGE,
    /**
     * How the plugin would like the item drawn: one of the `MODE_` constants in [WallpaperContract].
     * [WallpaperContract.MODE_DEFAULT] leaves it to the launcher.
     */
    val mode: Int = WallpaperContract.MODE_DEFAULT,
    /** The picture's title, in the [WallpaperEnvironment.locale] where the plugin can. Optional. */
    val title: String? = null,
    /** Who made it. Optional. */
    val author: String? = null,
    /** A web page about it (`https://`). Optional. */
    val sourceUrl: String? = null,
) : Parcelable {

    private companion object : Parceler<WallpaperItem> {

        override fun WallpaperItem.write(parcel: Parcel, flags: Int) = parcel.writeFramed {
            writeString(id)
            writeString(uri)
            writeInt(type)
            writeInt(mode)
            writeString(title)
            writeString(author)
            writeString(sourceUrl)
        }

        override fun create(parcel: Parcel): WallpaperItem = parcel.readFramed {
            val id = readString().orEmpty()
            val uri = readString().orEmpty()
            val type = readInt()
            val mode = readInt()
            val title = readString()
            val author = readString()
            val sourceUrl = readString()
            WallpaperItem(id, uri, type, mode, title, author, sourceUrl)
        }
    }
}
