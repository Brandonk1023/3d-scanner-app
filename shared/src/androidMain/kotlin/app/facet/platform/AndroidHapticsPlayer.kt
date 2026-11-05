package app.facet.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.facet.scan.GuidanceMessage
import app.facet.scan.ScanFeedback

/**
 * Android [HapticsPlayer], from design/android/scan/Haptics.kt. Every guidance message has its own
 * pattern so the user can follow the scan without looking at the screen. Repetition and cooldown
 * rules live in the shared guidance engine (ScanTuning.Guidance), not here.
 *
 * Requires the VIBRATE permission in the app manifest.
 */
class AndroidHapticsPlayer(
    context: Context,
    private val enabled: () -> Boolean,
) : HapticsPlayer {

    /** Alternating off/on durations in ms, amplitudes 0–255 (0 = off). */
    private class Pattern(val timings: LongArray, val amplitudes: IntArray)

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    override fun play(message: GuidanceMessage) {
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

    override fun play(feedback: ScanFeedback) {
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

    override fun cancel() = vibrator.cancel()

    private companion object {
        val PATTERNS: Map<GuidanceMessage, Pattern> = mapOf(
            // Long 400 ms buzz; the guidance engine repeats it every 2 s while shown.
            GuidanceMessage.TRACKING_LOST to Pattern(longArrayOf(0, 400), intArrayOf(0, 255)),
            // Two short pulses.
            GuidanceMessage.TOO_CLOSE to Pattern(longArrayOf(0, 60, 80, 60), intArrayOf(0, 200, 0, 200)),
            GuidanceMessage.TOO_FAR to Pattern(longArrayOf(0, 60, 80, 60), intArrayOf(0, 200, 0, 200)),
            // Three quick ticks.
            GuidanceMessage.MOVE_SLOWER to Pattern(longArrayOf(0, 30, 60, 30, 60, 30), intArrayOf(0, 180, 0, 180, 0, 180)),
            // One soft pulse.
            GuidanceMessage.MORE_LIGHT to Pattern(longArrayOf(0, 80), intArrayOf(0, 90)),
            // Rising ramp, soft to strong (mirrored for lower).
            GuidanceMessage.HIGHER_ANGLES to Pattern(longArrayOf(0, 60, 40, 60, 40, 60), intArrayOf(0, 70, 0, 140, 0, 230)),
            GuidanceMessage.LOWER_ANGLES to Pattern(longArrayOf(0, 60, 40, 60, 40, 60), intArrayOf(0, 230, 0, 140, 0, 70)),
        )
    }
}
