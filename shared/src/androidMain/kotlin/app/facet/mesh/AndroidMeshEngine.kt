package app.facet.mesh

import app.facet.geometry.BoxSize
import app.facet.nativecore.FacetCoreJni

/** [MeshEngine] backed by the C++ core through JNI (native/android). */
class AndroidMeshEngine : MeshEngine {
    override fun coreVersion(): String = FacetCoreJni.coreVersion()

    override fun boxDiagonalMm(box: BoxSize): Double =
        FacetCoreJni.boxDiagonal(box.widthMm, box.depthMm, box.heightMm)

    override fun createVolume(box: BoxSize, voxelMm: Double, truncationMm: Double): TsdfVolume? =
        FacetCoreJni.volumeCreate(
            box.widthMm.toFloat(), box.depthMm.toFloat(), box.heightMm.toFloat(),
            voxelMm.toFloat(), truncationMm.toFloat(),
        ).takeIf { it != 0L }?.let(::JniTsdfVolume)

    override fun loadVolume(path: String): TsdfVolume? =
        FacetCoreJni.volumeLoad(path).takeIf { it != 0L }?.let(::JniTsdfVolume)

    override fun repair(mesh: Mesh, options: RepairOptions): MeshResult = withNativeMesh(mesh) { h ->
        FacetCoreJni.meshRepair(h, options.baseCutZMm, options.plinthHeightMm, options.plinthMarginMm)
    }

    override fun cleanup(mesh: Mesh, params: CleanupParams): MeshResult = withNativeMesh(mesh) { h ->
        FacetCoreJni.meshCleanup(
            h, params.tableSearchMm, params.tableClipMm, params.tableMinAreaMm2,
            params.keepLargest, params.smoothingIterations, params.fillHoles,
        )
    }

    override fun decimate(mesh: Mesh, targetTriangles: Int, maxError: Float): MeshResult = withNativeMesh(mesh) { h ->
        FacetCoreJni.meshDecimate(h, targetTriangles, maxError)
    }

    override fun writeStl(mesh: Mesh, path: String): PipelineStatus =
        withNativeMesh(mesh) { h -> FacetCoreJni.meshWriteStl(h, path) }.status

    override fun write3mf(mesh: Mesh, path: String, objectName: String): PipelineStatus =
        withNativeMesh(mesh) { h -> FacetCoreJni.meshWrite3mf(h, path, objectName) }.status

    override fun createPointCloud(box: BoxSize, floorMarginMm: Double): PointCloud? =
        FacetCoreJni.cloudCreate(
            box.widthMm.toFloat(), box.depthMm.toFloat(), box.heightMm.toFloat(), floorMarginMm.toFloat(),
        ).takeIf { it != 0L }?.let(::JniPointCloud)
}

/** Copies [mesh] into a native mesh, runs [op] on it and reads the result back (input on failure). */
private inline fun withNativeMesh(mesh: Mesh, op: (Long) -> Int): MeshResult {
    val h = FacetCoreJni.meshCreate(mesh.positions, mesh.indices)
    if (h == 0L) return MeshResult(PipelineStatus.INVALID, mesh)
    try {
        val status = PipelineStatus.fromCode(op(h))
        return if (status == PipelineStatus.OK) MeshResult(status, readNativeMesh(h)) else MeshResult(status, mesh)
    } finally {
        FacetCoreJni.meshDestroy(h)
    }
}

private fun readNativeMesh(h: Long): Mesh = Mesh(FacetCoreJni.meshPositions(h), FacetCoreJni.meshIndices(h))

private class JniPointCloud(private var handle: Long) : PointCloud {
    private val status = IntArray(1)

    private fun live(): Long = handle.also { check(it != 0L) { "point cloud closed" } }

    override fun add(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float): Int {
        require(cameraToBox.size == 16)
        require(frame.depthMm.size >= frame.width * frame.height)
        return FacetCoreJni.cloudAddDepth(
            live(), frame.depthMm, frame.width, frame.height, frame.confidence,
            frame.fx, frame.fy, frame.cx, frame.cy, cameraToBox, minConfidence,
        )
    }

    override val size: Int get() = FacetCoreJni.cloudSize(live())

    override fun reconstructPoisson(depth: Int, trimFraction: Float): MeshResult {
        val m = FacetCoreJni.poissonReconstruct(live(), depth, trimFraction, status)
        if (m == 0L) return MeshResult(PipelineStatus.fromCode(status[0]), null)
        try {
            return MeshResult(PipelineStatus.OK, readNativeMesh(m))
        } finally {
            FacetCoreJni.meshDestroy(m)
        }
    }

    override fun close() {
        if (handle != 0L) FacetCoreJni.cloudDestroy(handle)
        handle = 0L
    }
}

private class JniTsdfVolume(private var handle: Long) : TsdfVolume {
    private val counts = IntArray(2)

    private fun live(): Long = handle.also { check(it != 0L) { "volume closed" } }

    override fun integrate(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float): IntegrateResult {
        require(cameraToBox.size == 16)
        require(frame.depthMm.size >= frame.width * frame.height)
        val mean = FacetCoreJni.volumeIntegrate(
            live(), frame.depthMm, frame.width, frame.height, frame.confidence,
            frame.fx, frame.fy, frame.cx, frame.cy, cameraToBox, minConfidence, counts,
        )
        return IntegrateResult(counts[0], counts[1], mean)
    }

    override fun extract(step: Int, minWeight: Float): Mesh {
        val h = live()
        FacetCoreJni.volumeExtract(h, step, minWeight)
        return Mesh(FacetCoreJni.volumeMeshPositions(h), FacetCoreJni.volumeMeshIndices(h))
    }

    override fun carve(mask: ObjectMask, fx: Float, fy: Float, cx: Float, cy: Float, cameraToBox: FloatArray) {
        require(cameraToBox.size == 16)
        FacetCoreJni.volumeCarve(live(), mask.pixels, mask.width, mask.height, fx, fy, cx, cy, cameraToBox)
    }

    override val carvedViews: Int get() = FacetCoreJni.volumeCarvedViews(live())

    override fun extractHull(maxOutsideFraction: Float, minSeen: Int): Mesh {
        val h = live()
        FacetCoreJni.volumeExtractHull(h, maxOutsideFraction, minSeen)
        return Mesh(FacetCoreJni.volumeMeshPositions(h), FacetCoreJni.volumeMeshIndices(h))
    }

    override fun extractMarchingCubes(minWeight: Float): Mesh {
        val m = FacetCoreJni.volumeExtractMarchingCubes(live(), minWeight)
        if (m == 0L) return Mesh(FloatArray(0), IntArray(0))
        try {
            return readNativeMesh(m)
        } finally {
            FacetCoreJni.meshDestroy(m)
        }
    }

    override fun save(path: String): Boolean = FacetCoreJni.volumeSave(live(), path)

    override fun close() {
        if (handle != 0L) FacetCoreJni.volumeDestroy(handle)
        handle = 0L
    }
}
