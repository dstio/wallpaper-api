package ca.dstudio.atvlauncher.wallpaper.api

import android.os.BadParcelableException
import android.os.Parcel

/*
 * The frame every parcelable of this contract travels in: its size in bytes (the size int itself
 * included), then its fields in declaration order - the layout stable AIDL gives a structured
 * parcelable.
 *
 * It is what makes "a parcelable only grows at its end" true. A reader reads the fields it knows
 * and then jumps to the end of the frame, so fields a newer writer appended are skipped, and the
 * value after the parcelable - the host binder after attach()'s environment, the next item in a
 * batch - is read from the right place. A plain @Parcelize class has no frame: a reader that knows
 * fewer fields stops short and reads everything after it from the wrong offset.
 *
 * A field added in a later version is read only while dataPosition() < end, and takes its default
 * otherwise (the writer predates it). The fields of 2.0.0 are always there.
 */

/** Writes [fields] inside a frame. */
internal inline fun Parcel.writeFramed(fields: Parcel.() -> Unit) {
    val start = dataPosition()
    writeInt(0)
    fields()
    val end = dataPosition()
    setDataPosition(start)
    writeInt(end - start)
    setDataPosition(end)
}

/**
 * Reads one frame [writeFramed] wrote. [fields] is handed the frame's end, for fields a later
 * version adds; whatever it leaves unread is skipped.
 */
internal inline fun <T> Parcel.readFramed(fields: Parcel.(end: Int) -> T): T {
    val start = dataPosition()
    val size = readInt()
    if (size < Int.SIZE_BYTES || start > Int.MAX_VALUE - size || start + size > dataSize()) {
        throw BadParcelableException("bad frame: $size bytes at $start of ${dataSize()}")
    }
    val end = start + size
    val value = fields(end)
    if (dataPosition() > end) {
        throw BadParcelableException("a frame of $size bytes is shorter than its fields")
    }
    setDataPosition(end)
    return value
}
