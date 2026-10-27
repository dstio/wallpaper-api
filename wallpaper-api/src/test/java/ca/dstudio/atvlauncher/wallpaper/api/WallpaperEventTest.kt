package ca.dstudio.atvlauncher.wallpaper.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WallpaperEvent] as a value. Its wire format is ParcelFrameTest's (androidTest, a real Parcel). */
class WallpaperEventTest {

    @Test
    fun `an event is its type with nothing else by default`() {
        val event = WallpaperEvent(WallpaperContract.EVENT_FOCUSED_TILE)
        assertNull(event.packageName)
        assertNull(event.title)
        assertNull(event.artist)
        assertNull(event.album)
        assertFalse(event.playing)
        assertEquals(0, event.colors.size)
        assertFalse(event.moving)
    }

    @Test
    fun `Home moving and Home still are two different events`() {
        val moving = WallpaperEvent(WallpaperContract.EVENT_HOME_MOVING, moving = true)
        val still = WallpaperEvent(WallpaperContract.EVENT_HOME_MOVING)
        assertNotEquals(moving, still)
        assertEquals(moving, WallpaperEvent(WallpaperContract.EVENT_HOME_MOVING, moving = true))
        assertTrue(moving.toString(), moving.toString().endsWith("moving=true)"))
    }

    @Test
    fun `events with the same colours are equal, whatever array holds them`() {
        val a = nowPlaying(intArrayOf(0xFF3366CC.toInt(), 0xFF112233.toInt()))
        val b = nowPlaying(intArrayOf(0xFF3366CC.toInt(), 0xFF112233.toInt()))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, nowPlaying(intArrayOf(0xFF3366CC.toInt())))
        assertNotEquals(a, nowPlaying(intArrayOf(0xFF112233.toInt(), 0xFF3366CC.toInt())))
        assertNotEquals(a, a.copy(playing = false))
        assertNotEquals(a, a.copy(album = null))
    }

    @Test
    fun `its text shows the colours`() {
        val text = nowPlaying(intArrayOf(0xFF3366CC.toInt(), 0x0A0B0C0D)).toString()
        assertTrue(text, text.contains("colors=[#FF3366CC, #0A0B0C0D]"))
        assertTrue(text, text.contains("title=Blue in Green"))
    }

    private fun nowPlaying(colors: IntArray) = WallpaperEvent(
        type = WallpaperContract.EVENT_NOW_PLAYING,
        packageName = "com.example.music",
        title = "Blue in Green",
        artist = "Miles Davis",
        album = "Kind of Blue",
        playing = true,
        colors = colors,
    )
}
