package ca.dstudio.atvlauncher.wallpaper.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Metadata describing one wallpaper plugin (a [WallpaperProviderService] in a plugin APK).
 *
 * Discovered with `PackageManager.queryIntentServices(Intent(ACTION_WALLPAPER_SERVICE),
 * GET_META_DATA)` and read from the `wallpaper_info.xml` resource named by the
 * [WallpaperContract.META_WALLPAPER_INFO] meta-data entry, through [Parser]. Parcelable for the
 * launcher's own use; it never crosses the plugin binder.
 */
@Parcelize
data class WallpaperProviderInfo(
    /** Package name of the plugin APK. */
    val packageName: String,
    /** Class name of the service that extends [WallpaperProviderService]. */
    val serviceClassName: String,
    /** Human-readable plugin name. */
    val label: String,
    /** Optional short description shown in the launcher's list. */
    val description: String = "",
    /** Fully-qualified class name of an exported settings Activity, or null if there is none. */
    val settingsActivityClassName: String? = null,
    /**
     * Resource ID of a 16:9 preview drawable inside the plugin APK (960 x 540, opaque), or 0 if
     * none. Loaded via `context.packageManager.getResourcesForApplication(packageName)`.
     */
    val previewResId: Int = 0,
    /** [KIND_ITEMS] (the plugin hands pictures, the launcher draws them) or [KIND_SURFACE]. */
    val kind: Int = KIND_ITEMS,
    /** The item types the plugin sends, as [FLAG_IMAGE] | [FLAG_COLOR] | [FLAG_VIDEO]. */
    val types: Int = DEFAULT_TYPES,
    /** The events the plugin asks to hear: [WallpaperContract.EVENT_FOCUSED_TILE], [WallpaperContract.EVENT_NOW_PLAYING]. */
    val events: Set<String> = emptySet(),
    /**
     * The **first** interval at which the launcher asks again, in milliseconds; the plugin changes it
     * with [WallpaperBatch.nextRequestMs]. 0 means never on a timer. Values from 1 to
     * [WallpaperContract.MIN_REQUEST_INTERVAL_MS] are raised to it.
     */
    val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
    /** Protocol version the plugin declares. The launcher ignores a plugin from the future. */
    val apiVersion: Int = WallpaperContract.API_VERSION,
) : Parcelable {

    /** ComponentName string in the form "packageName/serviceClassName". */
    val componentNameString: String get() = "$packageName/$serviceClassName"

    /** True when [types] includes [type], one of the `TYPE_` constants in [WallpaperContract]. */
    fun declaresType(type: Int): Boolean = type in 0 until Int.SIZE_BITS - 1 && types and (1 shl type) != 0

    companion object {
        /** [kind]: the plugin hands items, the launcher draws them. The only kind 2.0 serves. */
        const val KIND_ITEMS = 0

        /** [kind]: the plugin draws into a surface the launcher hands it. Served from a later version. */
        const val KIND_SURFACE = 1

        /** [types] flag for [WallpaperContract.TYPE_IMAGE]; `image` in wallpaper_info.xml. */
        const val FLAG_IMAGE = 1 shl WallpaperContract.TYPE_IMAGE

        /** [types] flag for [WallpaperContract.TYPE_COLOR]; `color` in wallpaper_info.xml. */
        const val FLAG_COLOR = 1 shl WallpaperContract.TYPE_COLOR

        /** [types] flag for [WallpaperContract.TYPE_VIDEO]; `video` in wallpaper_info.xml. */
        const val FLAG_VIDEO = 1 shl WallpaperContract.TYPE_VIDEO

        /** [types] when wallpaper_info.xml says nothing: pictures. */
        const val DEFAULT_TYPES = FLAG_IMAGE

        /** [refreshIntervalMs] when wallpaper_info.xml says nothing: never on a timer. */
        const val DEFAULT_REFRESH_INTERVAL_MS = 0L
    }

    /**
     * Builds a [WallpaperProviderInfo] from the attributes of a `<wallpaper-provider>` element
     * ([ELEMENT]), one at a time, as an XML pull parser hands them over: the text the plugin wrote,
     * or for a value aapt compiled, what `getAttributeValue` makes of it - a number for an enum,
     * a flag set or an integer ("1", "0x3"), "@<id>" for a reference. Pure, so the launcher's read
     * stays a loop:
     *
     * ```kotlin
     * val parser = WallpaperProviderInfo.Parser(
     *     pkg, cls, defaultLabel,
     *     resolveInteger = { id -> runCatching { res.getInteger(id) }.getOrNull() },
     * ) { id -> runCatching { res.getString(id) }.getOrNull() }
     * for (i in 0 until xml.attributeCount) parser.attribute(xml.getAttributeName(i), xml.getAttributeValue(i))
     * val info = parser.build()
     * ```
     *
     * A numeric attribute - `refreshIntervalMs`, `apiVersion`, `wallpaperKind`, `wallpaperTypes`,
     * `wallpaperEvents` - may be a reference (`refreshIntervalMs="@integer/refresh_ms"`), read
     * through [resolveInteger]. Unknown attributes, unknown flag names, flag bits this version does
     * not know and a reference that does not resolve are ignored; a missing attribute keeps the
     * field's default.
     */
    class Parser(
        private val packageName: String,
        private val serviceClassName: String,
        /** The label when the XML names none: the service's or the application's label. */
        private val defaultLabel: String,
        /**
         * Resolves an integer resource of the plugin APK; null when it cannot. Asked for a numeric
         * attribute whose value is a reference ("@<id>"). Without it, or when it answers null, the
         * reference reads as an unknown value, as it did before 2.0.1: the field keeps its default,
         * and a flag set names nothing. It comes before [resolveString] so that a trailing lambda
         * still names the string resolver; pass it by name.
         */
        private val resolveInteger: (resId: Int) -> Int? = { null },
        /** Resolves a string resource of the plugin APK; null when it cannot. */
        private val resolveString: (resId: Int) -> String? = { null },
    ) {
        private var label: String? = null
        private var description: String? = null
        private var settings: String? = null
        private var previewResId = 0
        private var kind = KIND_ITEMS
        private var types = DEFAULT_TYPES
        private var events = emptySet<String>()
        private var refreshIntervalMs = DEFAULT_REFRESH_INTERVAL_MS
        private var apiVersion = WallpaperContract.API_VERSION

        /** One attribute, [name] without its namespace prefix. */
        fun attribute(name: String, value: String?): Parser = apply {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return@apply
            when (name) {
                "wallpaperLabel" -> label = resolved(text)
                "wallpaperDescription" -> description = resolved(text)
                "wallpaperSettings" -> settings = resolved(text)
                "wallpaperPreview" -> previewResId = reference(text) ?: 0
                "wallpaperKind" -> kind = integer(text)?.toInt() ?: KINDS[text] ?: kind
                "wallpaperTypes" -> types = flags(text, TYPE_FLAGS) and ALL_TYPE_FLAGS
                "wallpaperEvents" -> events = flags(text, EVENT_FLAGS).let { bits ->
                    EVENT_FLAGS.filterValues { bits and it != 0 }.keys.toSet()
                }
                "refreshIntervalMs" -> integer(text)?.let { refreshIntervalMs = interval(it) }
                "apiVersion" -> integer(text)?.let { apiVersion = it.toInt() }
            }
        }

        fun build(): WallpaperProviderInfo = WallpaperProviderInfo(
            packageName = packageName,
            serviceClassName = serviceClassName,
            label = label?.takeIf { it.isNotBlank() } ?: defaultLabel,
            description = description.orEmpty(),
            settingsActivityClassName = settings?.takeIf { it.isNotBlank() },
            previewResId = previewResId,
            kind = kind,
            types = types,
            events = events,
            refreshIntervalMs = refreshIntervalMs,
            apiVersion = apiVersion,
        )

        /** "@<id>" resolved through the plugin's resources, anything else as written. */
        private fun resolved(text: String): String? =
            if (text.startsWith("@")) reference(text)?.let(resolveString)?.trim() else text

        private fun reference(text: String): Int? =
            if (text.startsWith("@")) number(text.substring(1))?.toInt()?.takeIf { it != 0 } else null

        /**
         * A number as written or compiled ("21600000", "0x1499700"), or the integer resource an
         * "@<id>" reference names, through [resolveInteger]; null otherwise. An unresolved
         * reference is null, as any text that is not a number.
         */
        private fun integer(text: String): Long? =
            if (text.startsWith("@")) reference(text)?.let(resolveInteger)?.toLong() else number(text)

        /**
         * A compiled flag set ("0x3", "3"), an integer resource ("@<id>"), or the names a plugin
         * wrote ("image|color").
         */
        private fun flags(text: String, names: Map<String, Int>): Int =
            integer(text)?.toInt() ?: text.split('|').fold(0) { bits, name -> bits or (names[name.trim()] ?: 0) }

        private fun interval(ms: Long): Long = when {
            ms <= 0L -> 0L
            else -> maxOf(ms, WallpaperContract.MIN_REQUEST_INTERVAL_MS)
        }

        private fun number(text: String): Long? = when {
            text.startsWith("0x") || text.startsWith("0X") -> text.substring(2).toLongOrNull(16)
            else -> text.toLongOrNull()
        }

        companion object {
            /** The root element of `wallpaper_info.xml`. */
            const val ELEMENT = "wallpaper-provider"

            private val KINDS = mapOf("items" to KIND_ITEMS, "surface" to KIND_SURFACE)
            private val TYPE_FLAGS = mapOf("image" to FLAG_IMAGE, "color" to FLAG_COLOR, "video" to FLAG_VIDEO)
            private const val ALL_TYPE_FLAGS = FLAG_IMAGE or FLAG_COLOR or FLAG_VIDEO
            private val EVENT_FLAGS = mapOf(
                WallpaperContract.EVENT_FOCUSED_TILE to 1,
                WallpaperContract.EVENT_NOW_PLAYING to 2,
            )
        }
    }
}
