package app.facet.scan

import app.facet.geometry.Vec3
import kotlin.math.exp

/** Everything the guidance rules look at for one AR frame. Times in ms. */
data class GuidanceInput(
    val nowMs: Long,
    val tracking: TrackingState,
    /** 0..1 of the box's on-screen area that is inside the view. */
    val boxInView: Float,
    val distanceMm: Double,
    val distanceRangeMm: ClosedFloatingPointRange<Double>,
    val orbitSpeedDegPerSec: Double,
    val linearSpeedMmPerSec: Double,
    val blurScore: Float?,
    val lightIntensity: Float?,
    /** Band the camera is in now (null above the top band). */
    val band: CoverageBand?,
    val coverage: CoverageState,
)

data class GuidanceOutput(
    /** The one banner to show, or null. */
    val message: GuidanceMessage?,
    /** Haptics to play this frame. */
    val haptics: List<GuidanceMessage>,
)

/**
 * Live guidance (docs/04 §2): one message at a time by priority. A condition must hold
 * [ScanTuning.Guidance.showAfterMs] before its banner shows; a banner stays at least
 * [ScanTuning.Guidance.minVisibleMs] unless a higher priority replaces it; the same message's
 * haptic doesn't repeat within [ScanTuning.Guidance.hapticCooldownMs], except Tracking lost,
 * which repeats every [ScanTuning.Guidance.trackingLostHapticRepeatMs] while shown.
 */
class GuidanceEngine(private val tuning: ScanTuning) {
    private val g = tuning.guidance
    private val since = HashMap<GuidanceMessage, Long>()
    private val lastHaptic = HashMap<GuidanceMessage, Long>()
    private var shown: GuidanceMessage? = null
    private var shownAt = 0L
    private var boxOutSince: Long? = null
    private var darkSince: Long? = null
    private var bandSince: Long = 0
    private var lastBand: CoverageBand? = null

    fun reset() {
        since.clear()
        shown = null
        boxOutSince = null
        darkSince = null
        lastBand = null
    }

    fun update(input: GuidanceInput): GuidanceOutput {
        val now = input.nowMs
        val active = conditions(input)
        since.keys.retainAll(active)
        active.forEach { since.getOrPut(it) { now } }
        val eligible = active.filter { now - since.getValue(it) >= g.showAfterMs }.minByOrNull { it.priority }

        val current = shown
        val next = when {
            current == null -> eligible
            // A higher priority replaces the banner immediately.
            eligible != null && eligible.priority < current.priority -> eligible
            current in active -> current
            // The condition ended: keep the banner for its minimum time, then move on.
            now - shownAt < g.minVisibleMs -> current
            else -> eligible
        }
        val haptics = ArrayList<GuidanceMessage>(1)
        if (next != current) {
            shown = next
            shownAt = now
            if (next != null && now - (lastHaptic[next] ?: Long.MIN_VALUE / 2) >= g.hapticCooldownMs) {
                haptics += next
                lastHaptic[next] = now
            }
        } else if (next == GuidanceMessage.TRACKING_LOST &&
            now - (lastHaptic[next] ?: Long.MIN_VALUE / 2) >= g.trackingLostHapticRepeatMs
        ) {
            haptics += next
            lastHaptic[next] = now
        }
        return GuidanceOutput(next, haptics)
    }

    private fun conditions(input: GuidanceInput): Set<GuidanceMessage> {
        val now = input.nowMs
        val out = HashSet<GuidanceMessage>()

        // 1 · Tracking lost: AR tracking gone, or the box out of view for a while.
        val boxOut = input.boxInView < BOX_OUT_OF_VIEW
        boxOutSince = if (boxOut) boxOutSince ?: now else null
        val boxGone = boxOutSince?.let { now - it > g.boxOutOfViewMs } == true
        if (input.tracking != TrackingState.TRACKING || boxGone) {
            out += GuidanceMessage.TRACKING_LOST
            return out // nothing else is meaningful without tracking
        }

        // 2 · Distance.
        if (input.distanceMm < input.distanceRangeMm.start) out += GuidanceMessage.TOO_CLOSE
        if (input.distanceMm > input.distanceRangeMm.endInclusive) out += GuidanceMessage.TOO_FAR

        // 3 · Speed / blur.
        val blurry = input.blurScore?.let { it > g.maxBlurScore } == true
        if (input.orbitSpeedDegPerSec > g.maxOrbitSpeedDegPerSec || input.linearSpeedMmPerSec > g.maxLinearSpeedMmPerSec || blurry) {
            out += GuidanceMessage.MOVE_SLOWER
        }

        // 4 · Light (must stay dark for lowLightHoldMs before the usual show delay).
        val dark = input.lightIntensity?.let { it < g.minLightIntensity } == true
        darkSince = if (dark) darkSince ?: now else null
        if (darkSince?.let { now - it >= g.lowLightHoldMs } == true) out += GuidanceMessage.MORE_LIGHT

        // 5 · Angles: current band done, another one far behind, and the user has stayed here a while.
        if (input.band != lastBand) {
            lastBand = input.band
            bandSince = now
        }
        val band = input.band
        if (band != null && now - bandSince > g.angleHintSameElevationMs &&
            input.coverage.band(band) >= g.angleHintCurrentBandDone
        ) {
            val behind = { b: CoverageBand -> input.coverage.band(b) < g.angleHintOtherBandBelow }
            val higher = CoverageBand.entries.filter { it.ordinal > band.ordinal }
            val lower = CoverageBand.entries.filter { it.ordinal < band.ordinal }
            when {
                higher.any(behind) -> out += GuidanceMessage.HIGHER_ANGLES
                lower.any(behind) -> out += GuidanceMessage.LOWER_ANGLES
            }
        }
        return out
    }

    private companion object {
        /** Below this fraction on screen the box counts as out of view. */
        const val BOX_OUT_OF_VIEW = 0.05f
    }
}

/**
 * Orbit speed (degrees per second around the box's vertical axis) and linear speed (mm/s) of the
 * camera, smoothed over ~[SMOOTHING_MS] so single noisy poses don't trigger "Move slower".
 */
class MotionTracker {
    private var lastMs: Long? = null
    private var lastPosition: Vec3? = null
    private var lastAzimuth = 0.0
    var orbitDegPerSec = 0.0
        private set
    var linearMmPerSec = 0.0
        private set

    fun update(nowMs: Long, camera: Vec3, azimuthDeg: Double) {
        val prevMs = lastMs
        val prevPos = lastPosition
        lastMs = nowMs
        lastPosition = camera
        val prevAz = lastAzimuth
        lastAzimuth = azimuthDeg
        if (prevMs == null || prevPos == null) return
        val dt = (nowMs - prevMs).coerceAtLeast(1) / 1000.0
        if (dt > 0.5) { // a gap (pause, dropped frames): start over
            orbitDegPerSec = 0.0
            linearMmPerSec = 0.0
            return
        }
        val orbit = angleBetween(azimuthDeg, prevAz) / dt
        val linear = (camera - prevPos).length() / dt
        val a = 1 - exp(-dt * 1000 / SMOOTHING_MS)
        orbitDegPerSec += (orbit - orbitDegPerSec) * a
        linearMmPerSec += (linear - linearMmPerSec) * a
    }

    fun reset() {
        lastMs = null
        lastPosition = null
        orbitDegPerSec = 0.0
        linearMmPerSec = 0.0
    }

    private companion object {
        const val SMOOTHING_MS = 250.0
    }
}
