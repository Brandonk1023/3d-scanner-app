package app.facet.scan

/**
 * Live guidance messages, in priority order: lower ordinal = higher priority (docs/04 §2).
 * Only one is shown at a time. Each has its own icon and haptic pattern, so colour is never
 * the only cue. TOO_CLOSE/TOO_FAR share priority 2 and HIGHER/LOWER_ANGLES share priority 5.
 */
enum class GuidanceMessage(val priority: Int) {
    TRACKING_LOST(1),
    TOO_CLOSE(2),
    TOO_FAR(2),
    MOVE_SLOWER(3),
    MORE_LIGHT(4),
    HIGHER_ANGLES(5),
    LOWER_ANGLES(5),
}

/** Non-banner feedback events during capture. */
enum class ScanFeedback { SEGMENT_CAPTURED, RING_COMPLETE }

/** Camera elevation bands of the coverage model. */
enum class CoverageBand { LOW, MID, HIGH }

/** Chosen at placement. */
enum class ScanType { FULL_OBJECT, ONE_SIDE }

/** Chosen at Finish (or from Settings › Default result). */
enum class ResultMode { QUICK, HIGH_DETAIL }
