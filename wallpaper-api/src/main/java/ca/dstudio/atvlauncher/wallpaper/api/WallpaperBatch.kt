package ca.dstudio.atvlauncher.wallpaper.api

import android.os.BadParcelableException
import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.parcelableCreator

/**
 * A plugin's answer to [IWallpaperProvider.request], sent through [IWallpaperHost.push].
 *
 * **A parcelable only grows at its end.** A later version adds fields after [nextRequestMs] and
 * never removes, reorders or retypes one. The batch and each item in it travel framed with their
 * size (`ParcelFrame.kt`).
 */
@Parcelize
data class WallpaperBatch(
    /**
     * The plugin's whole list, in the order it is shown. A non-empty list **replaces** the last
     * one; an **empty list means "nothing yet"**: the launcher keeps what it has, is not told the
     * plugin failed, and asks again after [nextRequestMs] (60 s when unset).
     *
     * **At most [WallpaperContract.MAX_ITEMS] (60).** The launcher keeps the first 60, in order, and
     * ignores the rest; [WallpaperProviderService.push] sends only those.
     */
    val items: List<WallpaperItem>,
    /**
     * When to ask again, in milliseconds. [WallpaperContract.NEXT_REQUEST_UNCHANGED] (-1, the
     * default) keeps the current interval, `0` means never on a timer (only on start, settings
     * closed and invalidate), and any other value sets it - never sooner than
     * [WallpaperContract.MIN_REQUEST_INTERVAL_MS]. The interval starts at `refreshIntervalMs` from
     * `wallpaper_info.xml`.
     */
    val nextRequestMs: Long = WallpaperContract.NEXT_REQUEST_UNCHANGED,
) : Parcelable {

    private companion object : Parceler<WallpaperBatch> {

        override fun WallpaperBatch.write(parcel: Parcel, flags: Int) = parcel.writeFramed {
            writeInt(items.size)
            for (item in items) item.writeToParcel(this, flags)
            writeLong(nextRequestMs)
        }

        override fun create(parcel: Parcel): WallpaperBatch = parcel.readFramed { end ->
            val count = readInt()
            // Every item is at least its own size int: a count that cannot fit is a broken parcel,
            // not a reason to allocate.
            if (count < 0 || count > (end - dataPosition()) / Int.SIZE_BYTES) {
                throw BadParcelableException("a batch of $count items cannot fit its frame")
            }
            val creator = parcelableCreator<WallpaperItem>()
            val items = List(count) { creator.createFromParcel(this) }
            val nextRequestMs = readLong()
            WallpaperBatch(items, nextRequestMs)
        }
    }
}
