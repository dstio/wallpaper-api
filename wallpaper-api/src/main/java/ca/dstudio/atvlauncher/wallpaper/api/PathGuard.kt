package ca.dstudio.atvlauncher.wallpaper.api

import java.io.File
import java.io.IOException
import java.util.Locale

/** Which file under a root a link may name. Pure, so it is JVM-tested. */
internal object PathGuard {

    /**
     * The regular file [path] names inside [root], or null. [path] is a link's path ("/a.png",
     * "sub/b.jpg"); a leading "/" is relative to [root].
     *
     * Refused: an empty path, any ".." segment, a path whose canonical form (symbolic links
     * followed) is not inside the canonical [root] - compared with a trailing separator, so a
     * sibling such as `wallpapers2/` is not inside `wallpapers/` - and anything that is not a
     * regular file (a folder, a missing file).
     */
    fun resolveInside(root: File, path: String): File? {
        val relative = path.trimStart('/', '\\')
        if (relative.isEmpty()) return null
        if (relative.split('/', '\\').any { it == ".." }) return null
        return try {
            val base = root.canonicalFile
            val file = File(base, relative).canonicalFile
            file.takeIf { isInside(base, it) && it.isFile }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * True when [file] is strictly below [base], both canonical. Compared with a trailing separator,
     * so `wallpapers2/x` is not inside `wallpapers`, and [base] itself is not inside.
     */
    fun isInside(base: File, file: File): Boolean = file.path.startsWith(base.path + File.separator)

    /** The MIME type of a picture file by its extension, or null for anything else. */
    fun mimeTypeOf(path: String): String? =
        when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> null
        }
}
