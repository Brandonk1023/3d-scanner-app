package app.facet.scan

import app.facet.geometry.Pose

/** Platform-neutral AR tracking state (ARCore TrackingState / ARKit ARCamera.TrackingState). */
enum class TrackingState { TRACKING, LIMITED, NOT_TRACKING }

/**
 * Everything the shared scan logic (coverage model, guidance engine, ScanSession) needs from one
 * AR frame. Each platform's AR layer fills it from ARCore / ARKit; nothing platform-specific
 * leaks past this point.
 *
 * Speed and camera-to-box distance are *derived in shared code* from successive [cameraPose]s and
 * the placed box, so both platforms compute them identically.
 */
data class FrameMetrics(
    /** Monotonic frame timestamp. */
    val timestampNanos: Long,
    val trackingState: TrackingState,
    /** Camera pose in world space, millimetres. Z-up after the platform converts its axes. */
    val cameraPose: Pose,
    /** Ambient light estimate normalised to 0..1 (ARCore pixel intensity), null if unavailable. */
    val lightIntensity: Float?,
    /** Mean depth confidence (0..1) of depth samples inside the box, null if no depth this frame. */
    val meanDepthConfidence: Float?,
    /** Fraction (0..1) of the box's projected area that is on screen. */
    val boxInViewFraction: Float,
    /** Image blur score (higher = blurrier), null if not computed this frame. */
    val blurScore: Float?,
)
