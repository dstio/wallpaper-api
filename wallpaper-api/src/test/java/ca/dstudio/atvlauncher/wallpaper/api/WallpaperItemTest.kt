package ca.dstudio.atvlauncher.wallpaper.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WallpaperItemTest {

    @Test
    fun `an item is a picture with no preference and no credits by default`() {
        val item = WallpaperItem(id = "field-warm-1", uri = "content://x.wallpapers/field-warm-1.png")
        assertEquals(WallpaperContract.TYPE_IMAGE, item.type)
        assertEquals(WallpaperContract.MODE_DEFAULT, item.mode)
        assertNull(item.title)
        assertNull(item.author)
        assertNull(item.sourceUrl)
    }

    @Test
    fun `a batch keeps the interval by default`() {
        assertEquals(WallpaperContract.NEXT_REQUEST_UNCHANGED, WallpaperBatch(emptyList()).nextRequestMs)
    }

    @Test
    fun `the info's defaults`() {
        val info = WallpaperProviderInfo("p", "p.S", "Label")
        assertEquals(WallpaperProviderInfo.KIND_ITEMS, info.kind)
        assertEquals(WallpaperProviderInfo.FLAG_IMAGE, info.types)
        assertEquals(emptySet<String>(), info.events)
        assertEquals(0L, info.refreshIntervalMs)
        assertEquals(WallpaperContract.API_VERSION, info.apiVersion)
        assertNull(info.settingsActivityClassName)
    }
}
