package app.facet.mesh

import app.facet.geometry.BoxSize
import app.facet.nativecore.facet_box_diagonal
import app.facet.nativecore.facet_core_version
import cnames.structs.facet_volume
import app.facet.nativecore.facet_volume_carve
import app.facet.nativecore.facet_volume_carved_views
import app.facet.nativecore.facet_volume_create
import app.facet.nativecore.facet_volume_destroy
import app.facet.nativecore.facet_volume_extract
import app.facet.nativecore.facet_volume_extract_hull
import app.facet.nativecore.facet_volume_integrate
import app.facet.nativecore.facet_volume_load
import app.facet.nativecore.facet_volume_mesh_indices
import app.facet.nativecore.facet_volume_mesh_positions
import app.facet.nativecore.facet_volume_save
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/** [MeshEngine] backed by the C++ core through Kotlin/Native cinterop. */
@OptIn(ExperimentalForeignApi::class)
class IosMeshEngine : MeshEngine {
    override fun coreVersion(): String = facet_core_version()?.toKString() ?: "unknown"

    override fun boxDiagonalMm(box: BoxSize): Double =
        facet_box_diagonal(box.widthMm, box.depthMm, box.heightMm)

    override fun createVolume(box: BoxSize, voxelMm: Double, truncationMm: Double): TsdfVolume? =
        facet_volume_create(
            box.widthMm.toFloat(), box.depthMm.toFloat(), box.heightMm.toFloat(),
            voxelMm.toFloat(), truncationMm.toFloat(),
        )?.let(::NativeTsdfVolume)

    override fun loadVolume(path: String): TsdfVolume? = facet_volume_load(path)?.let(::NativeTsdfVolume)
}

@OptIn(ExperimentalForeignApi::class)
private class NativeTsdfVolume(private var handle: CPointer<facet_volume>?) : TsdfVolume {
    private fun live(): CPointer<facet_volume> = checkNotNull(handle) { "volume closed" }

    override fun integrate(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float): IntegrateResult {
        require(cameraToBox.size == 16)
        val v = live()
        return frame.depthMm.usePinned { depth ->
            cameraToBox.usePinned { m ->
                val conf = frame.confidence
                if (conf == null) {
                    facet_volume_integrate(
                        v, depth.addressOf(0).reinterpret(), frame.width, frame.height, frame.width,
                        null, 0, frame.fx, frame.fy, frame.cx, frame.cy, m.addressOf(0), minConfidence,
                    ).useContents { IntegrateResult(updated, surface_samples, mean_confidence) }
                } else {
                    conf.usePinned { c ->
                        facet_volume_integrate(
                            v, depth.addressOf(0).reinterpret(), frame.width, frame.height, frame.width,
                            c.addressOf(0).reinterpret(), frame.width, frame.fx, frame.fy, frame.cx, frame.cy,
                            m.addressOf(0), minConfidence,
                        ).useContents { IntegrateResult(updated, surface_samples, mean_confidence) }
                    }
                }
            }
        }
    }

    override fun carve(mask: ObjectMask, fx: Float, fy: Float, cx: Float, cy: Float, cameraToBox: FloatArray) {
        require(cameraToBox.size == 16)
        val v = live()
        mask.pixels.usePinned { px ->
            cameraToBox.usePinned { m ->
                facet_volume_carve(v, px.addressOf(0).reinterpret(), mask.width, mask.height, fx, fy, cx, cy, m.addressOf(0))
            }
        }
    }

    override val carvedViews: Int get() = facet_volume_carved_views(live())

    override fun extractHull(maxOutsideFraction: Float, minSeen: Int): Mesh {
        facet_volume_extract_hull(live(), maxOutsideFraction, minSeen)
        return readMesh()
    }

    override fun extract(step: Int, minWeight: Float): Mesh {
        facet_volume_extract(live(), step, minWeight)
        return readMesh()
    }

    private fun readMesh(): Mesh = memScoped {
        val v = live()
        val floatCount = alloc<IntVar>()
        val indexCount = alloc<IntVar>()
        val p = facet_volume_mesh_positions(v, floatCount.ptr)
        val i = facet_volume_mesh_indices(v, indexCount.ptr)
        val positions = FloatArray(floatCount.value) { p!![it] }
        val indices = IntArray(indexCount.value) { i!![it] }
        Mesh(positions, indices)
    }

    override fun save(path: String): Boolean = facet_volume_save(live(), path) == 1

    override fun close() {
        handle?.let(::facet_volume_destroy)
        handle = null
    }
}
