package ca.dstudio.atvlauncher.wallpaper.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PathGuardTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun files() {
        val files = temp.newFolder("files")
        root = File(files, "wallpapers").apply { mkdirs() }
        File(root, "field-warm-1.png").writeText("picture")
        File(root, "sub").mkdirs()
        File(root, "sub/inner.jpg").writeText("picture")
        File(files, "secret.xml").writeText("not a wallpaper")
        File(files, "wallpapers2").mkdirs()
        File(files, "wallpapers2/x").writeText("a sibling, not inside")
    }

    @Test
    fun `a file inside the root`() {
        assertEquals(File(root, "field-warm-1.png").canonicalFile, PathGuard.resolveInside(root, "/field-warm-1.png"))
        assertEquals(File(root, "field-warm-1.png").canonicalFile, PathGuard.resolveInside(root, "field-warm-1.png"))
        assertEquals(File(root, "sub/inner.jpg").canonicalFile, PathGuard.resolveInside(root, "/sub/inner.jpg"))
    }

    @Test
    fun `dot-dot never leaves, nor walks back in`() {
        assertNull(PathGuard.resolveInside(root, "/../secret.xml"))
        assertNull(PathGuard.resolveInside(root, "/sub/../field-warm-1.png"))
        assertNull(PathGuard.resolveInside(root, "..\\secret.xml"))
    }

    @Test
    fun `a sibling with the root as its prefix is outside`() {
        assertNull(PathGuard.resolveInside(root, "/../wallpapers2/x"))
        val base = root.canonicalFile
        val sibling = File(root.parentFile, "wallpapers2/x").canonicalFile
        assertTrue(base.path.let { sibling.path.startsWith(it) })
        assertFalse(PathGuard.isInside(base, sibling))
        assertTrue(PathGuard.isInside(base, File(base, "field-warm-1.png")))
        assertFalse(PathGuard.isInside(base, base))
    }

    @Test
    fun `a symbolic link out of the root is refused`() {
        val link = File(root, "escape.png").toPath()
        try {
            Files.createSymbolicLink(link, File(root.parentFile, "secret.xml").toPath())
        } catch (e: Exception) {
            assumeNoException("this machine cannot make symbolic links", e)
        }
        assertNull(PathGuard.resolveInside(root, "/escape.png"))
    }

    @Test
    fun `a folder, a missing file and an empty path are refused`() {
        assertNull(PathGuard.resolveInside(root, "/sub"))
        assertNull(PathGuard.resolveInside(root, "/"))
        assertNull(PathGuard.resolveInside(root, ""))
        assertNull(PathGuard.resolveInside(root, "/field-cool-1.png"))
    }

    @Test
    fun `the root itself is not inside`() {
        assertNull(PathGuard.resolveInside(root, "."))
    }

    @Test
    fun `mime types by extension`() {
        assertEquals("image/png", PathGuard.mimeTypeOf("/field-warm-1.png"))
        assertEquals("image/jpeg", PathGuard.mimeTypeOf("/a.JPG"))
        assertEquals("image/jpeg", PathGuard.mimeTypeOf("/a.jpeg"))
        assertEquals("image/webp", PathGuard.mimeTypeOf("/a.webp"))
        assertNull(PathGuard.mimeTypeOf("/a.gif"))
        assertNull(PathGuard.mimeTypeOf("/noextension"))
    }
}
