package app.facet.scan

import app.facet.geometry.BoxSize

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
    val keyframes: Keyframes = Keyframes(),
    val storage: Storage = Storage(),
    val scale: Scale = Scale(),
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

    data class Scale(
        /** Warn when an automatic scale correction exceeds ± this fraction. */
        val warnCorrectionAbove: Double = 0.10,
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

    fun highDetailVoxelMm(box: BoxSize): Double = with(reconstruction) {
        (box.diagonalMm / highDetailVoxelDivisor).coerceIn(highDetailVoxelMinMm, highDetailVoxelMaxMm)
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
