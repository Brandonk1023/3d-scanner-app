package app.facet.scan // TODO: change to your package

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/*
 * Android implementation of the shared HapticsPlayer interface (see docs/07-multiplatform.md).
 * When the project is set up, move GuidanceMessage and ScanFeedback into shared/commonMain and
 * keep only GuidanceHaptics here. iOS maps the same patterns to Core Haptics.
 */

/**
 * Guidance messages in priority order (lower ordinal = higher priority).
 * The guidance engine itself should be pure Kotlin; it emits one of these, and the UI layer
 * calls [GuidanceHaptics.play]. Every message has its own pattern so the user can follow the
 * scan without looking at the screen.
 */
enum class GuidanceMessage { TRACKING_LOST, TOO_CLOSE, TOO_FAR, MOVE_SLOWER, MORE_LIGHT, HIGHER_ANGLES, LOWER_ANGLES }

enum class ScanFeedback { SEGMENT_CAPTURED, RING_COMPLETE }

/** Pattern = alternating off/on durations in ms, amplitudes 0–255 (0 = off). */
private class Pattern(val timings: LongArray, val amplitudes: IntArray)

private val PATTERNS: Map<GuidanceMessage, Pattern> = mapOf(
    // Long 400 ms buzz. The caller repeats it every 2 s while the banner is showing.
    GuidanceMessage.TRACKING_LOST to Pattern(longArrayOf(0, 400), intArrayOf(0, 255)),
    // Two short pulses.
    GuidanceMessage.TOO_CLOSE to Pattern(longArrayOf(0, 60, 80, 60), intArrayOf(0, 200, 0, 200)),
    GuidanceMessage.TOO_FAR to Pattern(longArrayOf(0, 60, 80, 60), intArrayOf(0, 200, 0, 200)),
    // Three quick ticks.
    GuidanceMessage.MOVE_SLOWER to Pattern(longArrayOf(0, 30, 60, 30, 60, 30), intArrayOf(0, 180, 0, 180, 0, 180)),
    // One soft pulse.
    GuidanceMessage.MORE_LIGHT to Pattern(longArrayOf(0, 80), intArrayOf(0, 90)),
    // Rising ramp, soft to strong.
    GuidanceMessage.HIGHER_ANGLES to Pattern(longArrayOf(0, 60, 40, 60, 40, 60), intArrayOf(0, 70, 0, 140, 0, 230)),
    GuidanceMessage.LOWER_ANGLES to Pattern(longArrayOf(0, 60, 40, 60, 40, 60), intArrayOf(0, 230, 0, 140, 0, 70)),
)

class GuidanceHaptics(context: Context, private val enabled: () -> Boolean) {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    fun play(message: GuidanceMessage) {
        if (!enabled() || !vibrator.hasVibrator()) return
        val p = PATTERNS.getValue(message)
        val effect = if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(p.timings, p.amplitudes, -1)
        } else {
            // No amplitude control: keep the rhythm, drop the intensity differences.
            VibrationEffect.createWaveform(p.timings, -1)
        }
        vibrator.vibrate(effect)
    }

    fun play(feedback: ScanFeedback) {
        if (!enabled() || !vibrator.hasVibrator()) return
        val effect = when (feedback) {
            ScanFeedback.SEGMENT_CAPTURED ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                } else {
                    VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            ScanFeedback.RING_COMPLETE ->
                VibrationEffect.createWaveform(longArrayOf(0, 50, 100, 50), intArrayOf(0, 255, 0, 255), -1)
        }
        vibrator.vibrate(effect)
    }

    fun cancel() = vibrator.cancel()
}

/** Timing rules for the guidance engine (see docs/04-scan-experience.md). All placeholders. */
object GuidanceTiming {
    const val SHOW_AFTER_MS = 500L        // condition must hold this long before showing
    const val MIN_VISIBLE_MS = 1_500L     // banner stays at least this long
    const val HAPTIC_COOLDOWN_MS = 4_000L // same message won't vibrate again within this window
    const val TRACKING_LOST_REPEAT_MS = 2_000L
}
