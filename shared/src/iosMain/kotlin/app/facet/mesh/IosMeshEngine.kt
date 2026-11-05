package app.facet.mesh

import app.facet.geometry.BoxSize
import app.facet.nativecore.facet_box_diagonal
import app.facet.nativecore.facet_core_version
import cnames.structs.facet_point_cloud
import cnames.structs.facet_trimesh
import cnames.structs.facet_volume
import app.facet.nativecore.facet_point_cloud_add_depth
import app.facet.nativecore.facet_point_cloud_create
import app.facet.nativecore.facet_point_cloud_destroy
import app.facet.nativecore.facet_point_cloud_size
import app.facet.nativecore.facet_poisson_reconstruct
import app.facet.nativecore.facet_trimesh_create
import app.facet.nativecore.facet_trimesh_decimate
import app.facet.nativecore.facet_trimesh_destroy
import app.facet.nativecore.facet_trimesh_indices
import app.facet.nativecore.facet_trimesh_positions
import app.facet.nativecore.facet_trimesh_repair
import app.facet.nativecore.facet_trimesh_write_3mf
import app.facet.nativecore.facet_trimesh_write_stl
import app.facet.nativecore.facet_volume_extract_marching_cubes
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

    override fun repair(mesh: Mesh, options: RepairOptions): MeshResult = withNativeMesh(mesh) { h ->
        facet_trimesh_repair(h, options.baseCutZMm, options.plinthHeightMm, options.plinthMarginMm)
    }

    override fun decimate(mesh: Mesh, targetTriangles: Int, maxError: Float): MeshResult = withNativeMesh(mesh) { h ->
        facet_trimesh_decimate(h, targetTriangles, maxError)
    }

    override fun writeStl(mesh: Mesh, path: String): PipelineStatus =
        withNativeMesh(mesh) { h -> facet_trimesh_write_stl(h, path) }.status

    override fun write3mf(mesh: Mesh, path: String, objectName: String): PipelineStatus =
        withNativeMesh(mesh) { h -> facet_trimesh_write_3mf(h, path, objectName) }.status

    override fun createPointCloud(box: BoxSize, floorMarginMm: Double): PointCloud? =
        facet_point_cloud_create(
            box.widthMm.toFloat(), box.depthMm.toFloat(), box.heightMm.toFloat(), floorMarginMm.toFloat(),
        )?.let(::NativePointCloud)
}

/** Copies [mesh] into a native mesh, runs [op] on it and reads the result back (input on failure). */
@OptIn(ExperimentalForeignApi::class)
private inline fun withNativeMesh(mesh: Mesh, op: (CPointer<facet_trimesh>) -> Int): MeshResult {
    val h = mesh.positions.usePinned { p ->
        mesh.indices.usePinned { i ->
            facet_trimesh_create(
                if (mesh.positions.isEmpty()) null else p.addressOf(0), mesh.positions.size,
                if (mesh.indices.isEmpty()) null else i.addressOf(0), mesh.indices.size,
            )
        }
    } ?: return MeshResult(PipelineStatus.INVALID, mesh)
    try {
        val status = PipelineStatus.fromCode(op(h))
        return if (status == PipelineStatus.OK) MeshResult(status, readTrimesh(h)) else MeshResult(status, mesh)
    } finally {
        facet_trimesh_destroy(h)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readTrimesh(h: CPointer<facet_trimesh>): Mesh = memScoped {
    val floatCount = alloc<IntVar>()
    val indexCount = alloc<IntVar>()
    val p = facet_trimesh_positions(h, floatCount.ptr)
    val i = facet_trimesh_indices(h, indexCount.ptr)
    Mesh(FloatArray(floatCount.value) { p!![it] }, IntArray(indexCount.value) { i!![it] })
}

@OptIn(ExperimentalForeignApi::class)
private class NativePointCloud(private var handle: CPointer<facet_point_cloud>?) : PointCloud {
    private fun live(): CPointer<facet_point_cloud> = checkNotNull(handle) { "point cloud closed" }

    override fun add(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float): Int {
        require(cameraToBox.size == 16)
        val c = live()
        return frame.depthMm.usePinned { depth ->
            cameraToBox.usePinned { m ->
                val conf = frame.confidence
                if (conf == null) {
                    facet_point_cloud_add_depth(
                        c, depth.addressOf(0).reinterpret(), frame.width, frame.height, frame.width,
                        null, 0, frame.fx, frame.fy, frame.cx, frame.cy, m.addressOf(0), minConfidence,
                    )
                } else {
                    conf.usePinned { k ->
                        facet_point_cloud_add_depth(
                            c, depth.addressOf(0).reinterpret(), frame.width, frame.height, frame.width,
                            k.addressOf(0).reinterpret(), frame.width, frame.fx, frame.fy, frame.cx, frame.cy,
                            m.addressOf(0), minConfidence,
                        )
                    }
                }
            }
        }
    }

    override val size: Int get() = facet_point_cloud_size(live())

    override fun reconstructPoisson(depth: Int, trimFraction: Float): MeshResult = memScoped {
        val status = alloc<IntVar>()
        val m = facet_poisson_reconstruct(live(), depth, trimFraction, status.ptr)
            ?: return@memScoped MeshResult(PipelineStatus.fromCode(status.value), null)
        try {
            MeshResult(PipelineStatus.OK, readTrimesh(m))
        } finally {
            facet_trimesh_destroy(m)
        }
    }

    override fun close() {
        handle?.let(::facet_point_cloud_destroy)
        handle = null
    }
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

    override fun extractMarchingCubes(minWeight: Float): Mesh {
        val m = facet_volume_extract_marching_cubes(live(), minWeight) ?: return Mesh(FloatArray(0), IntArray(0))
        try {
            return readTrimesh(m)
        } finally {
            facet_trimesh_destroy(m)
        }
    }

    override fun save(path: String): Boolean = facet_volume_save(live(), path) == 1

    override fun close() {
        handle?.let(::facet_volume_destroy)
        handle = null
    }
}
