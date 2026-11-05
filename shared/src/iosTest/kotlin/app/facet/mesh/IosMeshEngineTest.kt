package app.facet.mesh

import app.facet.geometry.BoxSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory

/** Runs on the iOS Simulator: calls the real C++ core through cinterop. */
class IosMeshEngineTest {
    private val engine = IosMeshEngine()

    @Test
    fun coreVersionIsReachable() {
        assertEquals("0.3.0", engine.coreVersion())
    }

    @Test
    fun boxDiagonalMatchesKotlin() {
        val box = BoxSize(2.0, 3.0, 6.0)
        assertEquals(7.0, engine.boxDiagonalMm(box), 1e-9)
        assertEquals(box.diagonalMm, engine.boxDiagonalMm(box), 1e-9)
    }

    @Test
    fun pipelineStagesRunThroughCinterop() {
        val cube = unitCube(20f)
        val repaired = engine.repair(cube, RepairOptions(plinthHeightMm = 2f))
        assertEquals(PipelineStatus.OK, repaired.status)
        val solid = repaired.mesh!!
        assertTrue(solid.triangleCount >= 12)

        assertEquals(PipelineStatus.OK, engine.decimate(solid, 1_000).status)
        val dir = NSTemporaryDirectory()
        assertEquals(PipelineStatus.OK, engine.writeStl(solid, dir + "facet_ios_test.stl"))
        assertEquals(PipelineStatus.OK, engine.write3mf(solid, dir + "facet_ios_test.3mf", "cube"))

        // A lone triangle is not a closed manifold: status reported, input returned unchanged.
        val open = Mesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))
        val rejected = engine.repair(open)
        assertEquals(PipelineStatus.NOT_MANIFOLD, rejected.status)
        assertEquals(1, rejected.mesh!!.triangleCount)
    }

    /** Axis-aligned cube of side [s] resting on Z = 0, outward (counter-clockwise) winding. */
    private fun unitCube(s: Float): Mesh {
        val h = s / 2
        val p = floatArrayOf(
            -h, -h, 0f, h, -h, 0f, h, h, 0f, -h, h, 0f,
            -h, -h, s, h, -h, s, h, h, s, -h, h, s,
        )
        val i = intArrayOf(
            0, 2, 1, 0, 3, 2, // bottom
            4, 5, 6, 4, 6, 7, // top
            0, 1, 5, 0, 5, 4, // front
            1, 2, 6, 1, 6, 5, // right
            2, 3, 7, 2, 7, 6, // back
            3, 0, 4, 3, 4, 7, // left
        )
        return Mesh(p, i)
    }
}
