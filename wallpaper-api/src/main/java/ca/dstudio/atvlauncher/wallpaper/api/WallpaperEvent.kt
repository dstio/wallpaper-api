package ca.dstudio.atvlauncher.wallpaper.api

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize

/**
 * Something the launcher tells a plugin, through [IWallpaperProvider.event]: the focused tile changed
 * ([WallpaperContract.EVENT_FOCUSED_TILE]), what is playing did ([WallpaperContract.EVENT_NOW_PLAYING]),
 * or Home started or stopped moving ([WallpaperContract.EVENT_HOME_MOVING]).
 * A plugin hears only the events it declared (`wallpaperEvents`) and asked for
 * ([WallpaperProviderService.wantEvents]); when they come is [WallpaperContract]'s "Events".
 *
 * - **Home moving** ([WallpaperContract.EVENT_HOME_MOVING]): [moving] true as Home starts moving, false
 *   once it has been still for [WallpaperContract.HOME_STILL_MS]; nothing else set.
 * - **The focused tile** ([WallpaperContract.EVENT_FOCUSED_TILE]), once the cursor has rested on a tile:
 *   [colors] holds one colour, the tile's accent as the launcher's focus ring wears it - the hue the tile
 *   shows most, lifted toward a light (in HSV, saturation `min(1, 1.15 s + 0.08)` and value `0.85 + 0.15 v`),
 *   or [WallpaperContract.WHITE_LIGHT] for a tile with no colour of its own (grey, black or white).
 *   [packageName] and [title] may be null - the 2.1 launcher sends neither - and [playing] is false.
 * - **Now playing** ([WallpaperContract.EVENT_NOW_PLAYING]): while something plays, [playing] is true,
 *   [packageName] is the app playing, [title], [artist] and [album] are what it says, and [colors] holds seven
 *   colours from its cover, or from the app's icon where it sends none, in this order: `[0]` the cover's most
 *   vivid patch lifted to a light; `[1]` the most vivid patch of another hue, lifted; `[2]` a pale tint of the
 *   first; `[3]` a mid tone of it, deep enough under white text; `[4]`, `[5]`, `[6]` dark tones of the first
 *   hue, the second and the cover's average. A cover with no colour in it gives greys. When nothing plays -
 *   nothing at all, a pause, a stop, or no access to the device's media sessions - one event with [playing]
 *   false and nothing else set.
 *
 * Every colour is opaque ARGB. **A parcelable only grows at its end.** A later version adds fields after
 * [colors] and never removes, reorders or retypes one. Each event travels framed with its size
 * (`ParcelFrame.kt`), so an event from a newer launcher reads in an older plugin, and the reverse.
 */
@Parcelize
data class WallpaperEvent(
    /** [WallpaperContract.EVENT_FOCUSED_TILE], [WallpaperContract.EVENT_NOW_PLAYING] or [WallpaperContract.EVENT_HOME_MOVING]. */
    val type: String,
    /** The app that is playing; the focused tile's app, which the 2.1 launcher does not send. Null when unknown. */
    val packageName: String? = null,
    /** The title of what is playing; the focused tile's title, which the 2.1 launcher does not send. Null when unknown. */
    val title: String? = null,
    /** Who performs what is playing. */
    val artist: String? = null,
    /** The album of what is playing. */
    val album: String? = null,
    /**
     * True while something plays; false when nothing does - paused, stopped, or no access - and for a focused
     * tile.
     */
    val playing: Boolean = false,
    /**
     * Opaque ARGB colours: the focused tile's accent (one), or seven from the playing cover, in the order the
     * class's KDoc gives. Empty when there is none: nothing playing.
     */
    val colors: IntArray = IntArray(0),
    /**
     * An [WallpaperContract.EVENT_HOME_MOVING]'s: true while Home moves, false once it is still. False in every
     * other event. It comes after [colors]: an event whose frame ends at its colours reads false.
     */
    val moving: Boolean = false,
) : Parcelable {

    // A data class compares an array by identity; an event is equal to another with the same colours.

    override fun equals(other: Any?): Boolean =
        this === other || other is WallpaperEvent &&
            type == other.type &&
            packageName == other.packageName &&
            title == other.title &&
            artist == other.artist &&
            album == other.album &&
            playing == other.playing &&
            colors.contentEquals(other.colors) &&
            moving == other.moving

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (packageName?.hashCode() ?: 0)
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + playing.hashCode()
        result = 31 * result + colors.contentHashCode()
        result = 31 * result + moving.hashCode()
        return result
    }

    override fun toString(): String =
        "WallpaperEvent(type=$type, packageName=$packageName, title=$title, artist=$artist, album=$album, " +
            "playing=$playing, colors=${colors.joinToString(prefix = "[", postfix = "]") { hex(it) }}, moving=$moving)"

    private companion object : Parceler<WallpaperEvent> {

        /** `#AARRGGBB`, the same in every locale. */
        private fun hex(color: Int): String = "#" + Integer.toHexString(color).uppercase().padStart(8, '0')

        override fun WallpaperEvent.write(parcel: Parcel, flags: Int) = parcel.writeFramed {
            writeString(type)
            writeString(packageName)
            writeString(title)
            writeString(artist)
            writeString(album)
            // Parcel.writeBoolean is API 29; it writes this int.
            writeInt(if (playing) 1 else 0)
            writeInt(colors.size)
            for (color in colors) writeInt(color)
            writeInt(if (moving) 1 else 0)
        }

        override fun create(parcel: Parcel): WallpaperEvent = parcel.readFramed { end ->
            val type = readString().orEmpty()
            val packageName = readString()
            val title = readString()
            val artist = readString()
            val album = readString()
            val playing = readInt() != 0
            val count = readInt()
            // A count that cannot fit the frame is a broken parcel, not a reason to allocate.
            if (count < 0 || count > (end - dataPosition()) / Int.SIZE_BYTES) {
                throw BadParcelableException("$count colours cannot fit an event's frame")
            }
            val colors = IntArray(count) { readInt() }
            // A frame may end at the colours: a sender that knows no [moving].
            val moving = dataPosition() < end && readInt() != 0
            WallpaperEvent(type, packageName, title, artist, album, playing, colors, moving)
        }
    }
}
