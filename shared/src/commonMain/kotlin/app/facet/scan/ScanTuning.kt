package app.facet.scan

import app.facet.geometry.BoxSize
import app.facet.mesh.CleanupParams
import app.facet.mesh.Smoothing

/**
 * Every value marked _placeholder_ in design/docs/04-scan-experience.md and 06-open-questions.md,
 * in one place, shared by Android and iOS. Tune these on the device; don't hard-code thresholds
 * anywhere else. Lengths in millimetres, times in milliseconds, angles in degrees,
 * fractions in 0..1.
 */
data class ScanTuning(
    val placement: Placement = Placement(),
    val coverage: Coverage = Coverage(),
    val guidance: Guidance = Guidance(),
    val finish: Finish = Finish(),
    val reconstruction: Reconstruction = Reconstruction(),
    val cleanup: Cleanup = Cleanup(),
    val keyframes: Keyframes = Keyframes(),
    val storage: Storage = Storage(),
    val scale: Scale = Scale(),
    val highDetail: HighDetail = HighDetail(),
) {
    data class Placement(
        /** Depth points within this radius of the tap seed the auto-sized box. */
        val autoSizeClusterRadiusMm: Double = 300.0,
        val boxMinSideMm: Double = 20.0,
        val boxMaxSideMm: Double = 2_500.0,
        /** A cell whose median height above the fitted table is below this is table, not object. */
        val autoSizeMinPointHeightMm: Double = 10.0,
        /** How far the real tabletop may sit above/below the AR plane estimate. */
        val autoSizeTableSearchMm: Double = 80.0,
        /** Horizontal grid cell used to judge object vs table and separate nearby clutter. */
        val autoSizeCellMm: Double = 15.0,
        /** A cell needs this many depth samples before its height is trusted. */
        val autoSizeMinPointsPerCell: Int = 6,
        /** Object cells up to this many cells apart still join (depth holes, mug handles). */
        val autoSizeCellGap: Int = 2,
        /** Distinct depth frames gathered for one fit (ARCore depth barely changes while the phone is still). */
        val autoSizeDepthFrames: Int = 3,
        /** The tap must land within this of the nearest object point, or nothing is fitted. */
        val autoSizeMaxSeedGapMm: Double = 100.0,
        /** Fewer object points than this → no auto-fit; fall back to [defaultBoxSideMm]. */
        val autoSizeMinPoints: Int = 25,
        /** Fraction of outlier points trimmed from each end of every axis. */
        val autoSizeTrimFraction: Double = 0.03,
        /** Clearance added around the fitted object on every side and above it. */
        val autoSizeMarginMm: Double = 10.0,
        /** Box placed when auto-fit finds nothing (cube). */
        val defaultBoxSideMm: Double = 150.0,
        /** Depth samples farther than this from the camera are ignored. */
        val maxDepthMm: Double = 4_000.0,
        /** One drag event can change a box side by at most this (guards against degenerate views). */
        val maxDragStepMm: Double = 200.0,
    )

    data class Coverage(
        /** Elevation band edges above the table plane: LOW [0,30), MID [30,55), HIGH [55,80]. */
        val lowBandMaxDeg: Double = 30.0,
        val midBandMaxDeg: Double = 55.0,
        val highBandMaxDeg: Double = 80.0,
        val azimuthSectors: Int = 24,
        val acceptedFramesPerSector: Int = 3,
        val minBoxInViewFraction: Float = 0.6f,
        val minDepthConfidence: Float = 0.5f,
        /** One side mode: only sectors within ± this of the start heading count. */
        val oneSideHalfSpanDeg: Double = 60.0,
    ) {
        val sectorSpanDeg: Double get() = 360.0 / azimuthSectors
    }

    data class Guidance(
        // Too close / too far: [max(minFloor, minFactor·D), max(maxFloor, maxFactor·D)], D = box diagonal.
        val distanceMinFloorMm: Double = 200.0,
        val distanceMinFactor: Double = 0.8,
        val distanceMaxFloorMm: Double = 600.0,
        val distanceMaxFactor: Double = 2.5,
        // Move slower
        val maxOrbitSpeedDegPerSec: Double = 30.0,
        val maxLinearSpeedMmPerSec: Double = 250.0,
        val maxBlurScore: Float = 0.5f, // TODO(Phase 3): calibrate against the real blur metric
        // More light
        val minLightIntensity: Float = 0.15f,
        val lowLightHoldMs: Long = 1_000,
        // Capture higher / lower angles
        val angleHintCurrentBandDone: Float = 0.9f,
        val angleHintOtherBandBelow: Float = 0.5f,
        val angleHintSameElevationMs: Long = 5_000,
        // Tracking lost
        val boxOutOfViewMs: Long = 1_000,
        val trackingLostMeshAlpha: Float = 0.4f,
        // Banner timing rules
        val showAfterMs: Long = 500,
        val minVisibleMs: Long = 1_500,
        val hapticCooldownMs: Long = 4_000,
        val trackingLostHapticRepeatMs: Long = 2_000,
        val ringCompleteToastMs: Long = 1_500,
    )

    data class Finish(
        val finishEnabledAtCoverage: Float = 0.15f,
        /** Full-object scans below this overall coverage (or any band below the next) get a warning. */
        val incompleteOverallBelow: Float = 0.70f,
        val incompleteAnyBandBelow: Float = 0.50f,
        /** "Best for small parts" badge on High detail when box diagonal is under this. */
        val highDetailBadgeMaxDiagonalMm: Double = 200.0,
    )

    data class Reconstruction(
        val livePreviewMinHz: Double = 5.0,
        /** Live volume voxel count cap; the voxel grows past the Quick size if the box needs more. */
        val liveMaxVoxels: Int = 2_500_000,
        /** Phone depth is coarse (S24 Ultra: ~3.7 mm per pixel at 40 cm); finer voxels only fuse noise. */
        val liveVoxelMinMm: Double = 2.0,
        /** TSDF truncation: max(this many voxels, [truncationMinMm]); must exceed phone depth noise. */
        val truncationVoxels: Double = 4.0,
        val truncationMinMm: Double = 8.0,
        /** Depth samples below this confidence (0..1) aren't fused at all. */
        val fuseMinConfidence: Float = 0.6f,
        /** Surface needs this much accumulated confidence weight to appear in a mesh. */
        val meshMinWeight: Float = 6f,
        /** Live preview samples every Nth voxel. */
        val livePreviewStep: Int = 2,
        // Silhouette carving (Quick result). Tuned on an S24 Ultra mug scan, 236 photos.
        /** A voxel is carved away when outside the object in more than this fraction of the photos that saw it. */
        val carveMaxOutsideFraction: Float = 0.1f,
        /** Voxels seen in fewer photos than this are treated as empty. */
        val carveMinSeen: Int = 10,
        /** Below this many carved photos, Quick falls back to the depth-only mesh. */
        val carveMinViews: Int = 20,
        /** Masks outside this area range (fraction of the photo) are rejected. */
        val carveMinMaskArea: Float = 0.002f,
        val carveMaxMaskArea: Float = 0.6f,
        /** Height of the seed point inside the box (fraction of box height). */
        val carveSeedHeight: Float = 0.4f,
        /** The volume extends past the placed box so the object's top and sides aren't clipped. */
        val volumeMarginSideMm: Double = 15.0,
        val volumeMarginTopMm: Double = 25.0,
        // Depth calibration against the AR table (DepthCalibration).
        /** Table pixels this close to the box footprint are skipped (the object, its shadow). */
        val calibrationFootprintMarginMm: Double = 40.0,
        val calibrationMinConfidence: Float = 0.5f,
        val calibrationMaxDistanceMm: Float = 1_200f,
        /** Ratios outside this range aren't the table (floor past the edge, other objects). */
        val calibrationMinRatio: Double = 0.75,
        val calibrationMaxRatio: Double = 1.25,
        val calibrationMinSamples: Int = 150,
        /** Table pixels seen at a shallower angle than this are too unreliable (grazing views). */
        val calibrationMinIncidenceDeg: Double = 25.0,
        /** The scale locks to the median of this many per-frame estimates. */
        val calibrationLockFrames: Int = 30,
        val calibrationPixelStep: Int = 2,

        val quickVoxelDivisor: Double = 250.0,
        val quickVoxelMinMm: Double = 1.0,
        val quickVoxelMaxMm: Double = 10.0,
        val highDetailVoxelDivisor: Double = 600.0,
        val highDetailVoxelMinMm: Double = 0.4,
        val highDetailVoxelMaxMm: Double = 4.0,
        /** Shown as "Ready in ~N s" / "About N min" until real timings are measured. */
        val quickEstimateSec: Int = 5,
        val highDetailEstimateMin: Int = 4,
    )

    /** Final-mesh cleanup (docs/04 §4), see [CleanupParams]. */
    data class Cleanup(
        /** Phone depth noise (~8 mm) blurs the table; objects lower than this can't be told apart from it. */
        val tableSearchMm: Float = 12f,
        /** Object and table merge in the depth this close to the table; the base is rebuilt straight down. */
        val tableClipMm: Float = 6f,
        /** 20 × 20 mm of flat, upward-facing surface. */
        val tableMinAreaMm2: Float = 400f,
        /** Taubin passes per Settings › Smoothing level. */
        val smoothingLow: Int = 2,
        val smoothingMedium: Int = 5,
        val smoothingHigh: Int = 10,
    ) {
        fun smoothingIterations(level: Smoothing): Int = when (level) {
            Smoothing.OFF -> 0
            Smoothing.LOW -> smoothingLow
            Smoothing.MEDIUM -> smoothingMedium
            Smoothing.HIGH -> smoothingHigh
        }

        /** [hasTable] is false for a carved hull: carving already removed the table. */
        fun params(watertight: Boolean, smoothing: Smoothing, hasTable: Boolean) = CleanupParams(
            tableSearchMm = if (hasTable) tableSearchMm else 0f,
            tableClipMm = tableClipMm,
            tableMinAreaMm2 = tableMinAreaMm2,
            keepLargest = true,
            smoothingIterations = smoothingIterations(smoothing),
            fillHoles = watertight,
        )
    }

    data class Keyframes(
        val minRotationDeg: Double = 10.0,
        val minTranslationMm: Double = 50.0,
        val maxCount: Int = 300,
        val minSharpness: Double = 100.0, // TODO(Phase 3): variance-of-Laplacian threshold, calibrate on device
    )

    data class Storage(
        val minFreeToStartScanBytes: Long = 500L * MB,
        val highDetailEstimateBytes: Long = 2_048L * MB,
    )

    /**
     * High detail background pass (docs/04 §4). The fine-mesh method is still a research spike: for
     * now the saved depth is re-fused at [highDetailVoxelMm] and carved with the saved masks.
     */
    data class HighDetail(
        /** Fine volume voxel cap (12 bytes each in the native TSDF ≈ 145 MB); the voxel grows past D/600 to stay under it. */
        val maxVoxels: Int = 12_000_000,
        /**
         * Share of the overall progress per stage: Aligning photos, Depth maps, Building fine mesh,
         * Cleanup & watertight. Guesses until timed on the phone.
         */
        val stageWeights: List<Double> = listOf(0.05, 0.15, 0.65, 0.15),
        /** "~N min left" appears once this much is done and this long has passed. */
        val etaMinProgress: Float = 0.05f,
        val etaMinElapsedMs: Long = 5_000,
        /** The gallery card's "Processing NN%" is written to the database in steps of this many percent. */
        val galleryPercentStep: Int = 2,
        /** The result is decimated to at most this many triangles (Quick: 200k). */
        val targetTriangles: Int = 500_000,
    ) {
        init {
            require(stageWeights.size == 4 && stageWeights.all { it > 0 }) { "one positive weight per stage" }
        }
    }

    data class Scale(
        /** Warn when a scale correction exceeds ± this fraction. */
        val warnCorrectionAbove: Double = 0.10,
        /** Corrections beyond ± this fraction are refused (a typo or the wrong edge, not a scan error). */
        val maxCorrection: Double = 0.5,
        /** Manual measurements shorter than this are too imprecise to scale from. */
        val minMeasuredMm: Double = 5.0,
    )

    /** Allowed camera-to-box-centre distance for a box of this size. */
    fun distanceRangeMm(box: BoxSize): ClosedFloatingPointRange<Double> {
        val d = box.diagonalMm
        val g = guidance
        return maxOf(g.distanceMinFloorMm, g.distanceMinFactor * d)..maxOf(g.distanceMaxFloorMm, g.distanceMaxFactor * d)
    }

    fun quickVoxelMm(box: BoxSize): Double = with(reconstruction) {
        (box.diagonalMm / quickVoxelDivisor).coerceIn(quickVoxelMinMm, quickVoxelMaxMm)
    }

    /** Live fusion voxel: the Quick voxel, grown if needed to stay under [Reconstruction.liveMaxVoxels]. */
    fun liveVoxelMm(box: BoxSize): Double {
        val cap = kotlin.math.cbrt(box.widthMm * box.depthMm * box.heightMm / reconstruction.liveMaxVoxels)
        return maxOf(quickVoxelMm(box), cap, reconstruction.liveVoxelMinMm)
    }

    fun truncationMm(voxelMm: Double): Double =
        maxOf(reconstruction.truncationVoxels * voxelMm, reconstruction.truncationMinMm)

    fun highDetailVoxelMm(box: BoxSize): Double = with(reconstruction) {
        (box.diagonalMm / highDetailVoxelDivisor).coerceIn(highDetailVoxelMinMm, highDetailVoxelMaxMm)
    }

    /** High detail's fine voxel for a volume this size, grown if needed to stay under [HighDetail.maxVoxels]. */
    fun highDetailVolumeVoxelMm(volume: BoxSize): Double {
        val cap = kotlin.math.cbrt(volume.widthMm * volume.depthMm * volume.heightMm / highDetail.maxVoxels)
        return maxOf(highDetailVoxelMm(volume), cap)
    }

    /** The fusion volume around a placed box: margins on the sides and on top so carving doesn't clip the object. */
    fun volumeSize(box: BoxSize): BoxSize = with(reconstruction) {
        BoxSize(box.widthMm + 2 * volumeMarginSideMm, box.depthMm + 2 * volumeMarginSideMm, box.heightMm + volumeMarginTopMm)
    }

    /** Band for a camera elevation above the table, or null if outside every band (above 80°). */
    fun bandForElevation(elevationDeg: Double): CoverageBand? = with(coverage) {
        when {
            elevationDeg < 0.0 -> null
            elevationDeg < lowBandMaxDeg -> CoverageBand.LOW
            elevationDeg < midBandMaxDeg -> CoverageBand.MID
            elevationDeg <= highBandMaxDeg -> CoverageBand.HIGH
            else -> null
        }
    }

    companion object {
        private const val MB = 1024L * 1024L
        val Default = ScanTuning()
    }
}
