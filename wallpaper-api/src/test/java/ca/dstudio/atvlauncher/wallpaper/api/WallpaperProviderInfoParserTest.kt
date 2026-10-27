package ca.dstudio.atvlauncher.wallpaper.api

import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderInfo.Companion.DEFAULT_TYPES
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderInfo.Companion.FLAG_COLOR
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderInfo.Companion.FLAG_IMAGE
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderInfo.Companion.FLAG_VIDEO
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderInfo.Companion.KIND_ITEMS
import ca.dstudio.atvlauncher.wallpaper.api.WallpaperProviderInfo.Companion.KIND_SURFACE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Values arrive as `XmlResourceParser.getAttributeValue` gives them: the text a plugin wrote, or for
 * a value aapt compiled, "0x3" (a flag set), "1" (an enum, an integer), "@2131755036" (a reference).
 */
class WallpaperProviderInfoParserTest {

    private val strings = mapOf(0x7f120001 to "Colour Fields", 0x7f120002 to "Soft gradients made on this device")

    /** The plugin's `@integer` resources. */
    private val integers = mapOf(
        REFRESH_SIX_HOURS to 21_600_000,
        REFRESH_ONE_SECOND to 1_000,
        REFRESH_NEVER to 0,
        API_THREE to 3,
        KIND_ONE to 1,
        TYPES_IMAGE_COLOR to 0x3,
        TYPES_WITH_UNKNOWN_BITS to 0xa,
        EVENTS_NOW_PLAYING to 0x2,
    )

    private fun parse(vararg attributes: Pair<String, String?>): WallpaperProviderInfo {
        val parser = WallpaperProviderInfo.Parser(
            "com.example.plugin",
            "com.example.plugin.Service",
            "Fallback",
            resolveInteger = { integers[it] },
        ) { strings[it] }
        for ((name, value) in attributes) parser.attribute(name, value)
        return parser.build()
    }

    /** The 2.0.0 call, which must still compile: no integer resolver, the trailing lambda the string one. */
    private fun parseWithoutIntegers(vararg attributes: Pair<String, String?>): WallpaperProviderInfo {
        val parser = WallpaperProviderInfo.Parser("com.example.plugin", "com.example.plugin.Service", "Fallback") {
            strings[it]
        }
        for ((name, value) in attributes) parser.attribute(name, value)
        return parser.build()
    }

    /** A reference as `getAttributeValue` spells it: "@" and the decimal id. */
    private fun ref(resId: Int) = "@$resId"

    @Test
    fun `flag names combine`() {
        assertEquals(3, parse("wallpaperTypes" to "image|color").types)
        assertEquals(FLAG_IMAGE or FLAG_VIDEO, parse("wallpaperTypes" to "video | image").types)
    }

    @Test
    fun `unknown flag names are ignored`() {
        assertEquals(FLAG_IMAGE, parse("wallpaperTypes" to "image|hologram").types)
        assertEquals(0, parse("wallpaperTypes" to "hologram").types)
    }

    @Test
    fun `a repeated flag name is one flag`() {
        assertEquals(FLAG_IMAGE, parse("wallpaperTypes" to "image|image").types)
    }

    @Test
    fun `compiled flag sets read as numbers, unknown bits dropped`() {
        assertEquals(3, parse("wallpaperTypes" to "0x3").types)
        assertEquals(3, parse("wallpaperTypes" to "3").types)
        assertEquals(FLAG_COLOR, parse("wallpaperTypes" to "0xa").types)
    }

    @Test
    fun `missing types are pictures`() {
        assertEquals(DEFAULT_TYPES, parse().types)
        assertEquals(FLAG_IMAGE, parse("wallpaperTypes" to "").types)
    }

    @Test
    fun `declaresType reads the flags`() {
        val info = parse("wallpaperTypes" to "image|color")
        assertTrue(info.declaresType(WallpaperContract.TYPE_IMAGE))
        assertTrue(info.declaresType(WallpaperContract.TYPE_COLOR))
        assertFalse(info.declaresType(WallpaperContract.TYPE_VIDEO))
        assertFalse(info.declaresType(-1))
        assertFalse(info.declaresType(40))
    }

    @Test
    fun `missing kind is items`() {
        assertEquals(KIND_ITEMS, parse().kind)
        assertEquals(KIND_ITEMS, parse("wallpaperKind" to "hologram").kind)
    }

    @Test
    fun `kind reads names and compiled numbers, an unknown number kept`() {
        assertEquals(KIND_SURFACE, parse("wallpaperKind" to "surface").kind)
        assertEquals(KIND_SURFACE, parse("wallpaperKind" to "1").kind)
        assertEquals(KIND_ITEMS, parse("wallpaperKind" to "items").kind)
        assertEquals(2, parse("wallpaperKind" to "2").kind)
    }

    @Test
    fun `events read names and compiled numbers`() {
        assertEquals(
            setOf(WallpaperContract.EVENT_FOCUSED_TILE, WallpaperContract.EVENT_NOW_PLAYING),
            parse("wallpaperEvents" to "focusedTile|nowPlaying").events,
        )
        assertEquals(setOf(WallpaperContract.EVENT_NOW_PLAYING), parse("wallpaperEvents" to "0x2").events)
        assertEquals(setOf(WallpaperContract.EVENT_FOCUSED_TILE), parse("wallpaperEvents" to "focusedTile|bogus").events)
        assertEquals(emptySet<String>(), parse().events)
    }

    @Test
    fun `event flags are the FLAG_EVENT_ constants`() {
        assertEquals(setOf(WallpaperContract.EVENT_FOCUSED_TILE), parse("wallpaperEvents" to "${WallpaperProviderInfo.FLAG_EVENT_FOCUSED_TILE}").events)
        assertEquals(setOf(WallpaperContract.EVENT_NOW_PLAYING), parse("wallpaperEvents" to "${WallpaperProviderInfo.FLAG_EVENT_NOW_PLAYING}").events)
        assertEquals(setOf(WallpaperContract.EVENT_HOME_MOVING), parse("wallpaperEvents" to "${WallpaperProviderInfo.FLAG_EVENT_HOME_MOVING}").events)
        assertEquals(
            setOf(WallpaperContract.EVENT_FOCUSED_TILE, WallpaperContract.EVENT_HOME_MOVING),
            parse("wallpaperEvents" to "focusedTile|homeMoving").events,
        )
    }

    /** As aapt2 compiles the sample's Colour Fields Live: `surface` to 1, the label a reference. */
    @Test
    fun `a surface plugin's info`() {
        val info = parse(
            "wallpaperLabel" to "@2131886081",
            "wallpaperKind" to "1",
            "wallpaperEvents" to "0x3",
            "apiVersion" to "2",
        )
        assertEquals(KIND_SURFACE, info.kind)
        assertEquals(setOf(WallpaperContract.EVENT_FOCUSED_TILE, WallpaperContract.EVENT_NOW_PLAYING), info.events)
        assertEquals(WallpaperContract.API_VERSION, info.apiVersion)
        assertEquals(0L, info.refreshIntervalMs)
    }

    @Test
    fun `refresh interval is raised to the minimum, zero and below mean never`() {
        assertEquals(21_600_000L, parse("refreshIntervalMs" to "21600000").refreshIntervalMs)
        assertEquals(21_600_000L, parse("refreshIntervalMs" to "0x1499700").refreshIntervalMs)
        assertEquals(WallpaperContract.MIN_REQUEST_INTERVAL_MS, parse("refreshIntervalMs" to "1000").refreshIntervalMs)
        assertEquals(0L, parse("refreshIntervalMs" to "0").refreshIntervalMs)
        assertEquals(0L, parse("refreshIntervalMs" to "-5").refreshIntervalMs)
        assertEquals(0L, parse().refreshIntervalMs)
        assertEquals(0L, parse("refreshIntervalMs" to "soon").refreshIntervalMs)
    }

    @Test
    fun `refresh interval as an integer resource, raised to the minimum as a written one is`() {
        assertEquals(21_600_000L, parse("refreshIntervalMs" to ref(REFRESH_SIX_HOURS)).refreshIntervalMs)
        assertEquals(WallpaperContract.MIN_REQUEST_INTERVAL_MS, parse("refreshIntervalMs" to ref(REFRESH_ONE_SECOND)).refreshIntervalMs)
        assertEquals(0L, parse("refreshIntervalMs" to ref(REFRESH_NEVER)).refreshIntervalMs)
    }

    @Test
    fun `api version as an integer resource`() {
        assertEquals(3, parse("apiVersion" to ref(API_THREE)).apiVersion)
    }

    @Test
    fun `kind as an integer resource`() {
        assertEquals(KIND_SURFACE, parse("wallpaperKind" to ref(KIND_ONE)).kind)
    }

    @Test
    fun `types as an integer resource, unknown bits dropped`() {
        assertEquals(FLAG_IMAGE or FLAG_COLOR, parse("wallpaperTypes" to ref(TYPES_IMAGE_COLOR)).types)
        assertEquals(FLAG_COLOR, parse("wallpaperTypes" to ref(TYPES_WITH_UNKNOWN_BITS)).types)
    }

    @Test
    fun `events as an integer resource`() {
        assertEquals(setOf(WallpaperContract.EVENT_NOW_PLAYING), parse("wallpaperEvents" to ref(EVENTS_NOW_PLAYING)).events)
    }

    @Test
    fun `an integer reference that does not resolve falls back as 2_0_0 read every reference`() {
        // The field keeps its default, and a flag set names nothing - an unknown value.
        assertEquals(0L, parse("refreshIntervalMs" to ref(MISSING)).refreshIntervalMs)
        assertEquals(WallpaperContract.API_VERSION, parse("apiVersion" to ref(MISSING)).apiVersion)
        assertEquals(KIND_ITEMS, parse("wallpaperKind" to ref(MISSING)).kind)
        assertEquals(0, parse("wallpaperTypes" to ref(MISSING)).types)
        assertEquals(emptySet<String>(), parse("wallpaperEvents" to ref(MISSING)).events)
        assertEquals(0L, parse("refreshIntervalMs" to "@0").refreshIntervalMs)
        assertEquals(0, parse("wallpaperTypes" to "@0").types)

        // Without a resolver nothing resolves, the 2.0.0 parse exactly: the same info as a missing id.
        for (attribute in listOf("refreshIntervalMs", "apiVersion", "wallpaperKind", "wallpaperTypes", "wallpaperEvents")) {
            assertEquals(attribute, parse(attribute to ref(MISSING)), parseWithoutIntegers(attribute to ref(KIND_ONE)))
        }
    }

    @Test
    fun `the integer resolver is asked only for a numeric reference, with its id`() {
        val asked = mutableListOf<Int>()
        val parser = WallpaperProviderInfo.Parser("p", "p.S", "Fallback", resolveInteger = { asked += it; null })
        parser.attribute("refreshIntervalMs", "21600000")
        parser.attribute("wallpaperTypes", "image|color")
        parser.attribute("wallpaperKind", "surface")
        parser.attribute("wallpaperLabel", "@2131886081")
        parser.attribute("wallpaperPreview", "@2131230721")
        parser.attribute("apiVersion", ref(API_THREE))
        assertEquals(listOf(API_THREE), asked)
    }

    @Test
    fun `label and description resolve references, falling back`() {
        val info = parse("wallpaperLabel" to "@2131886081", "wallpaperDescription" to "@2131886082")
        assertEquals("Colour Fields", info.label)
        assertEquals("Soft gradients made on this device", info.description)

        assertEquals("Fallback", parse("wallpaperLabel" to "@2131886099").label)
        assertEquals("", parse("wallpaperDescription" to "@2131886099").description)
        assertEquals("Fallback", parse().label)
        assertEquals("Plain", parse("wallpaperLabel" to " Plain ").label)
    }

    @Test
    fun `preview is a reference id, anything else none`() {
        assertEquals(0x7f080001, parse("wallpaperPreview" to "@2131230721").previewResId)
        assertEquals(0, parse("wallpaperPreview" to "@0").previewResId)
        assertEquals(0, parse("wallpaperPreview" to "preview.png").previewResId)
        assertEquals(0, parse().previewResId)
    }

    @Test
    fun `settings is a class name or nothing`() {
        assertEquals("com.example.plugin.Settings", parse("wallpaperSettings" to " com.example.plugin.Settings ").settingsActivityClassName)
        assertNull(parse("wallpaperSettings" to "").settingsActivityClassName)
        assertNull(parse().settingsActivityClassName)
    }

    @Test
    fun `api version and unknown attributes`() {
        assertEquals(3, parse("apiVersion" to "3").apiVersion)
        assertEquals(WallpaperContract.API_VERSION, parse().apiVersion)
        assertEquals(parse(), parse("wallpaperSomethingNew" to "1"))
    }

    @Test
    fun `the whole info`() {
        val info = parse(
            "wallpaperLabel" to "@2131886081",
            "wallpaperDescription" to "@2131886082",
            "wallpaperSettings" to "com.example.plugin.Settings",
            "wallpaperPreview" to "@2131230721",
            "wallpaperKind" to "0",
            "wallpaperTypes" to "0x3",
            "refreshIntervalMs" to "21600000",
            "apiVersion" to "2",
        )
        assertEquals(
            WallpaperProviderInfo(
                packageName = "com.example.plugin",
                serviceClassName = "com.example.plugin.Service",
                label = "Colour Fields",
                description = "Soft gradients made on this device",
                settingsActivityClassName = "com.example.plugin.Settings",
                previewResId = 0x7f080001,
                kind = KIND_ITEMS,
                types = FLAG_IMAGE or FLAG_COLOR,
                events = emptySet(),
                refreshIntervalMs = 21_600_000L,
                apiVersion = 2,
            ),
            info,
        )
        assertEquals("com.example.plugin/com.example.plugin.Service", info.componentNameString)
    }

    private companion object {
        // @integer ids of the plugin APK.
        const val REFRESH_SIX_HOURS = 0x7f0b0001
        const val REFRESH_ONE_SECOND = 0x7f0b0002
        const val REFRESH_NEVER = 0x7f0b0003
        const val API_THREE = 0x7f0b0004
        const val KIND_ONE = 0x7f0b0005
        const val TYPES_IMAGE_COLOR = 0x7f0b0006
        const val TYPES_WITH_UNKNOWN_BITS = 0x7f0b0007
        const val EVENTS_NOW_PLAYING = 0x7f0b0008

        /** An id the plugin's resources do not hold. */
        const val MISSING = 0x7f0b00ff
    }
}
