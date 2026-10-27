package ca.dstudio.atvlauncher.wallpaper.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A method's transaction code is its position in the `.aidl`: a plugin built against one version and
 * a launcher built against another agree only while methods are appended, never removed or
 * reordered. The generated stubs keep the codes package-private, so they are read by reflection.
 */
class TransactionCodesTest {

    @Test
    fun `the provider's methods keep their places, 2_1's after detach`() {
        assertEquals(
            listOf("attach", "request", "shown", "failed", "detach", "attachSurface", "detachSurface", "setMotion", "event"),
            methodsInOrder(IWallpaperProvider.Stub::class.java),
        )
    }

    @Test
    fun `the host's methods keep their places, 2_1's after invalidate`() {
        assertEquals(
            listOf("push", "invalidate", "surfaceDrawn", "surfaceReleased", "surfaceFailed", "wantEvents"),
            methodsInOrder(IWallpaperHost.Stub::class.java),
        )
    }

    /** The stub's methods by transaction code, from the first call code (1) up, with no gap. */
    private fun methodsInOrder(stub: Class<*>): List<String> {
        val byCode = stub.declaredFields
            .filter { it.name.startsWith(PREFIX) }
            .associate { field -> field.apply { isAccessible = true }.getInt(null) to field.name.removePrefix(PREFIX) }
        assertEquals("codes from 1 with no gap", (FIRST_CALL_TRANSACTION until FIRST_CALL_TRANSACTION + byCode.size).toList(), byCode.keys.sorted())
        return byCode.toSortedMap().values.toList()
    }

    private companion object {
        const val PREFIX = "TRANSACTION_"

        /** `IBinder.FIRST_CALL_TRANSACTION`. */
        const val FIRST_CALL_TRANSACTION = 1
    }
}
