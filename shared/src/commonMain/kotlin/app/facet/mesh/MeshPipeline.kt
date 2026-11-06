package app.facet.mesh

import app.facet.export.ExportFormat

/** Result codes of the native pipeline calls (FACET_* in native/include/facet_pipeline.h). */
enum class PipelineStatus(val code: Int) {
    OK(0),
    INVALID(1),
    EMPTY(2),

    /** Manifold could not make a closed 2-manifold out of the input (e.g. an open depth-only scan). */
    NOT_MANIFOLD(3),
    IO(4),
    UNSUPPORTED(5),
    INTERNAL(6);

    companion object {
        fun fromCode(code: Int): PipelineStatus = entries.firstOrNull { it.code == code } ?: INTERNAL
    }
}

/** A pipeline stage's outcome. [mesh] is the stage's output, or its input unchanged when it failed. */
class MeshResult(val status: PipelineStatus, val mesh: Mesh?) {
    val ok: Boolean get() = status == PipelineStatus.OK
}

/**
 * Manifold booleans applied during repair (box frame, millimetres).
 * [baseCutZMm] > 0 cuts everything below that height and drops the model to Z = 0 (flat base).
 * [plinthHeightMm] > 0 adds a box plinth under the model, [plinthMarginMm] wider on each side.
 */
data class RepairOptions(
    val baseCutZMm: Float = 0f,
    val plinthHeightMm: Float = 0f,
    val plinthMarginMm: Float = 2f,
)

/**
 * Photo mode's oriented point cloud in the box frame, built from ARCore-posed depth stills. Not
 * thread-safe; [close] frees native memory.
 */
interface PointCloud {
    /**
     * Back-projects one depth still ([cameraToBox] as for [TsdfVolume.integrate]) and returns the
     * number of points added. Samples below [minConfidence] (0..1) are ignored.
     */
    fun add(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float): Int

    val size: Int

    /**
     * Screened Poisson (PoissonRecon) at octree [depth], then drops the lowest-density
     * [trimFraction] of vertices (0 = keep everything; trimming opens the surface).
     */
    fun reconstructPoisson(depth: Int, trimFraction: Float): MeshResult

    fun close()
}

/**
 * The two reconstruction paths, wired stage by stage (docs/04). Interfaces only for now: the
 * parameters in [Options] are placeholders until Phase 4 tuning.
 *
 * - Quick: extracted mesh → cleanup → Manifold repair → meshoptimizer ([finalMesh])
 * - Live:  TSDF fusion → marching cubes → Manifold repair → meshoptimizer → export
 * - Photo: posed depth stills → oriented points → Screened Poisson + trim → Manifold → meshoptimizer → export
 */
class ScanPipeline(private val engine: MeshEngine) {

    data class Options(
        /** Voxels with less fused weight are treated as unseen. */
        val minWeight: Float = 1f,
        val repair: RepairOptions = RepairOptions(),
        /** Exported scans are decimated to at most this many triangles. */
        val targetTriangles: Int = 200_000,
        val maxDecimationError: Float = 0.01f,
        val poissonDepth: Int = 8,
        val poissonTrimFraction: Float = 0f,
    )

    /**
     * [repaired] is false when Manifold rejected the mesh; it is then decimated and returned as is
     * (still viewable, but may not slice cleanly).
     */
    class Output(val mesh: Mesh, val repaired: Boolean, val repairStatus: PipelineStatus)

    /** Live mode. Null when the volume holds no surface. */
    fun live(volume: TsdfVolume, options: Options = Options()): Output? {
        val raw = volume.extractMarchingCubes(options.minWeight)
        if (raw.triangleCount == 0) return null
        return finish(raw, options)
    }

    /** Photo mode. Null when Poisson produced nothing (too few points). */
    fun photo(cloud: PointCloud, options: Options = Options()): Output? {
        val surface = cloud.reconstructPoisson(options.poissonDepth, options.poissonTrimFraction)
        val mesh = surface.mesh?.takeIf { surface.ok && it.triangleCount > 0 } ?: return null
        return finish(mesh, options)
    }

    /**
     * The final Quick result from the raw extracted mesh: cleanup → repair → decimate, then resting
     * on Z = 0 and centred (docs/04 §4). When cleanup fails (e.g. it would leave nothing) the raw
     * mesh goes on unchanged.
     */
    fun finalMesh(raw: Mesh, cleanup: CleanupParams, options: Options = Options()): Output {
        val cleaned = engine.cleanup(raw, cleanup)
        val mesh = cleaned.mesh?.takeIf { cleaned.ok && it.triangleCount > 0 } ?: raw
        val out = finish(mesh, options)
        return Output(out.mesh.restingCentred(), out.repaired, out.repairStatus)
    }

    fun export(mesh: Mesh, format: ExportFormat, path: String, name: String): PipelineStatus =
        when (format) {
            ExportFormat.STL -> engine.writeStl(mesh, path)
            ExportFormat.THREE_MF -> engine.write3mf(mesh, path, name)
        }

    private fun finish(mesh: Mesh, options: Options): Output {
        val repaired = engine.repair(mesh, options.repair)
        val solid = if (repaired.ok) repaired.mesh ?: mesh else mesh
        val decimated = engine.decimate(solid, options.targetTriangles, options.maxDecimationError)
        return Output(decimated.mesh?.takeIf { decimated.ok } ?: solid, repaired.ok, repaired.status)
    }
}
