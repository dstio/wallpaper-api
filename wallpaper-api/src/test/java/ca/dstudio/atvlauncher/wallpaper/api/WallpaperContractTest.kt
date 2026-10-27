package ca.dstudio.atvlauncher.wallpaper.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every constant against the README's table ("The contract"). A changed value is a protocol
 * break, never a refactor.
 */
class WallpaperContractTest {

    @Test
    fun `names match the table`() {
        assertEquals("ca.dstudio.atvlauncher.wallpaper.SERVICE", WallpaperContract.ACTION_WALLPAPER_SERVICE)
        assertEquals("ca.dstudio.atvlauncher.wallpaper.INFO", WallpaperContract.META_WALLPAPER_INFO)
        assertEquals("ca.dstudio.atvlauncher.permission.BIND_WALLPAPER", WallpaperContract.PERMISSION_BIND_WALLPAPER)
        assertEquals(2, WallpaperContract.API_VERSION)
    }

    @Test
    fun `reasons match the table`() {
        assertEquals(0, WallpaperContract.REASON_START)
        assertEquals(1, WallpaperContract.REASON_TIMER)
        assertEquals(2, WallpaperContract.REASON_SETTINGS_CLOSED)
        assertEquals(3, WallpaperContract.REASON_MANUAL)
        assertEquals(4, WallpaperContract.REASON_INVALIDATED)
    }

    @Test
    fun `types, modes and failures match the table`() {
        assertEquals(0, WallpaperContract.TYPE_IMAGE)
        assertEquals(1, WallpaperContract.TYPE_COLOR)
        assertEquals(2, WallpaperContract.TYPE_VIDEO)
        assertEquals(0, WallpaperContract.MODE_DEFAULT)
        assertEquals(1, WallpaperContract.MODE_FILL)
        assertEquals(2, WallpaperContract.MODE_FIT)
        assertEquals(3, WallpaperContract.MODE_FIT_BLUR)
        assertEquals(1, WallpaperContract.FAIL_UNREADABLE)
        assertEquals(2, WallpaperContract.FAIL_TOO_SMALL)
        assertEquals(3, WallpaperContract.FAIL_UNSUPPORTED)
    }

    @Test
    fun `events and timings match the table`() {
        assertEquals("focusedTile", WallpaperContract.EVENT_FOCUSED_TILE)
        assertEquals("nowPlaying", WallpaperContract.EVENT_NOW_PLAYING)
        assertEquals("homeMoving", WallpaperContract.EVENT_HOME_MOVING)
        assertEquals(1_000L, WallpaperContract.HOME_STILL_MS)
        assertEquals(-1L, WallpaperContract.NEXT_REQUEST_UNCHANGED)
        assertEquals(60_000L, WallpaperContract.MIN_REQUEST_INTERVAL_MS)
    }

    @Test
    fun `the item cap matches the table`() {
        assertEquals(60, WallpaperContract.MAX_ITEMS)
    }

    @Test
    fun `type flags are one bit per type, as attrs xml declares them`() {
        assertEquals(1, WallpaperProviderInfo.FLAG_IMAGE)
        assertEquals(2, WallpaperProviderInfo.FLAG_COLOR)
        assertEquals(4, WallpaperProviderInfo.FLAG_VIDEO)
        assertEquals(0, WallpaperProviderInfo.KIND_ITEMS)
        assertEquals(1, WallpaperProviderInfo.KIND_SURFACE)
    }

    /** The surface kind's and the events' values, from 2.1. */
    @Test
    fun `the surface kind's codes and times are the contract's`() {
        assertEquals(1, WallpaperContract.SURFACE_UNSUPPORTED)
        assertEquals(2, WallpaperContract.SURFACE_ERROR)
        assertEquals(3_000L, WallpaperContract.SURFACE_FIRST_FRAME_MS)
        assertEquals(10_000L, WallpaperContract.SURFACE_FIRST_FRAME_COLD_MS)
    }

    @Test
    fun `event flags are one bit per event, as attrs xml declares them`() {
        assertEquals(1, WallpaperProviderInfo.FLAG_EVENT_FOCUSED_TILE)
        assertEquals(2, WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING)
        assertEquals(4, WallpaperProviderInfo.FLAG_EVENT_HOME_MOVING)
    }

    /** The launcher's focus ring's white light (its AmbientPalette), which a colourless tile's event carries. */
    @Test
    fun `a colourless tile's colour is the ring's white light`() {
        assertEquals(0xFFF4F6FA.toInt(), WallpaperContract.WHITE_LIGHT)
    }
}
