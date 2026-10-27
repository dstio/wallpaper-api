package ca.dstudio.atvlauncher.wallpaper.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WallpaperProviderService]'s decisions, pure: who may call into the plugin ([callerAllowed],
 * which `guarded` hands the caller it read on the binder thread), what `push` sends
 * ([cappedToMaxItems]) and which surface the plugin holds ([SurfaceSlot]). The service itself, on a
 * device, is WallpaperProviderServiceDeviceTest (androidTest).
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

    @Test
    fun `a surface is current from its attach until its detach`() {
        val slot = SurfaceSlot()
        assertNull(slot.current)
        assertNull("nothing to end before the first", slot.attach(1))
        assertEquals(1, slot.current)
        assertTrue(slot.detach(1))
        assertNull(slot.current)
        assertFalse("a surface ends once", slot.detach(1))
    }

    @Test
    fun `a call naming another id changes nothing`() {
        val slot = SurfaceSlot()
        slot.attach(2)
        assertFalse(slot.detach(1))
        assertFalse(slot.detach(3))
        assertEquals(2, slot.current)
        assertFalse("no surface, nothing to detach", SurfaceSlot().detach(2))
    }

    @Test
    fun `a new surface ends the one it replaces`() {
        val slot = SurfaceSlot()
        slot.attach(1)
        assertEquals(1, slot.attach(2))
        assertEquals(2, slot.current)
        assertFalse("the replaced id is stale now", slot.detach(1))
        assertTrue(slot.detach(2))
    }

    @Test
    fun `the end of the conversation ends the current surface, once`() {
        val slot = SurfaceSlot()
        assertNull("no surface, nothing to end", slot.end())
        slot.attach(7)
        assertEquals(7, slot.end())
        assertNull(slot.current)
        assertNull(slot.end())
        assertFalse(slot.detach(7))
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
