package app.facet.mesh

import app.facet.geometry.BoxSize
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs on the iOS Simulator: calls the real C++ core through cinterop. */
class IosMeshEngineTest {
    private val engine = IosMeshEngine()

    @Test
    fun coreVersionIsReachable() {
        assertEquals("0.2.0", engine.coreVersion())
    }

    @Test
    fun boxDiagonalMatchesKotlin() {
        val box = BoxSize(2.0, 3.0, 6.0)
        assertEquals(7.0, engine.boxDiagonalMm(box), 1e-9)
        assertEquals(box.diagonalMm, engine.boxDiagonalMm(box), 1e-9)
    }
}
