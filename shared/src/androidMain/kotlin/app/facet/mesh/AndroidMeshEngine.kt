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

    override fun save(path: String): Boolean = FacetCoreJni.volumeSave(live(), path)

    override fun close() {
        if (handle != 0L) FacetCoreJni.volumeDestroy(handle)
        handle = 0L
    }
}
