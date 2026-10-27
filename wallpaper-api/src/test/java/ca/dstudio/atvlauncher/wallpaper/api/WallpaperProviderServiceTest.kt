package ca.dstudio.atvlauncher.wallpaper.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WallpaperProviderService]'s two decisions, pure: who may call into the plugin ([callerAllowed],
 * which `guarded` hands the caller it read on the binder thread) and what `push` sends
 * ([cappedToMaxItems]).
 */
class WallpaperProviderServiceTest {

    @Test
    fun `the plugin's own uid passes without the permission, and is never asked for it`() {
        assertTrue(callerAllowed(PLUGIN_UID, PLUGIN_UID) { false })
        assertTrue(callerAllowed(PLUGIN_UID, PLUGIN_UID) { throw AssertionError("asked for the permission") })
    }

    @Test
    fun `another uid with the permission passes`() {
        assertTrue(callerAllowed(LAUNCHER_UID, PLUGIN_UID) { true })
    }

    @Test
    fun `another uid without the permission is refused`() {
        assertFalse(callerAllowed(LAUNCHER_UID, PLUGIN_UID) { false })
        assertFalse(callerAllowed(SHELL_UID, PLUGIN_UID) { false })
    }

    @Test
    fun `a batch within the cap is sent as it is`() {
        val full = batchOf(WallpaperContract.MAX_ITEMS)
        assertSame(full, cappedToMaxItems(full))
        val empty = WallpaperBatch(emptyList())
        assertSame(empty, cappedToMaxItems(empty))
    }

    @Test
    fun `a longer batch sends its first items, in order, and keeps its interval`() {
        val sent = cappedToMaxItems(batchOf(100))
        assertEquals((1..WallpaperContract.MAX_ITEMS).map { "item-$it" }, sent.items.map { it.id })
        assertEquals(NEXT_REQUEST_MS, sent.nextRequestMs)
        assertEquals(WallpaperContract.MAX_ITEMS, cappedToMaxItems(batchOf(WallpaperContract.MAX_ITEMS + 1)).items.size)
    }

    private fun batchOf(count: Int) = WallpaperBatch(
        items = (1..count).map { WallpaperItem(id = "item-$it", uri = "content://p.wallpapers/item-$it.png") },
        nextRequestMs = NEXT_REQUEST_MS,
    )

    private companion object {
        const val PLUGIN_UID = 10_123
        const val LAUNCHER_UID = 10_045

        /** adb shell, which holds no BIND_WALLPAPER. */
        const val SHELL_UID = 2_000

        const val NEXT_REQUEST_MS = 3_600_000L
    }
}
