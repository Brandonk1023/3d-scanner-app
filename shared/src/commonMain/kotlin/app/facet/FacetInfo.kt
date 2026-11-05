package app.facet

import app.facet.mesh.MeshEngine

/** Build/platform facts shared by both apps. Used by the Phase 0 shells to prove the wiring. */
object FacetInfo {
    const val SHARED_VERSION = "0.1.0"

    /** One-line status: shared module version + the C++ core version reached through [engine]. */
    fun wiringCheck(engine: MeshEngine): String =
        "shared $SHARED_VERSION · core ${engine.coreVersion()} · " +
            "box 100×100×100 mm → ${formatMm(engine.boxDiagonalMm(BOX_CHECK))} mm diag"

    private val BOX_CHECK = app.facet.geometry.BoxSize(100.0, 100.0, 100.0)

    private fun formatMm(value: Double): String {
        val tenths = kotlin.math.round(value * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}
