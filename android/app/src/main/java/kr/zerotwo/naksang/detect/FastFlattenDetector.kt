package kr.zerotwo.naksang.detect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * A fall that reaches a very flat torso quickly, then stays flat.
 *
 * The gate was chosen on CAUCAFall and GMDCSA-24 only: zero ADL false alarms,
 * then the most falls, then the stricter speed and lie angle. It does not
 * replace the 2 second rule or the 9 second lying-still default. Image y is
 * not used. UR Fall was not part of the choice.
 *
 * Angles here are the shoulder–hip line from horizontal, the same quantity
 * as [FallDetector]. 90 is upright, 0 is flat.
 */
class FastFlattenDetector {
    private val history = ArrayDeque<Sample>()
    private var confirmSince: Long? = null
    private var armed = true

    fun reset() {
        history.clear()
        confirmSince = null
        armed = true
    }

    /** True on the single frame the hold completes. */
    fun onJointArray(timestampMs: Long, joints: Array<FloatArray>?): Boolean {
        val angle = torsoAngle(joints)
        if (!armed) {
            if (angle != null && angle >= UPRIGHT_TORSO) {
                armed = true
                confirmSince = null
            }
            return false
        }
        if (angle == null) {
            confirmSince = null
            return false
        }
        history.addLast(Sample(timestampMs, angle))
        while (history.isNotEmpty() && history.first().t < timestampMs - 4_000L) {
            history.removeFirst()
        }
        val fired = step(timestampMs, angle)
        if (fired) {
            armed = false
            confirmSince = null
        }
        return fired
    }

    private fun step(now: Long, angle: Double): Boolean {
        if (confirmSince == null) {
            if (angle > FLAT_TORSO) return false
            var opened = false
            var peak = 0.0
            var previous: Sample? = null
            for (sample in history) {
                if (now - sample.t > WINDOW_MS) {
                    previous = sample
                    continue
                }
                val prior = previous
                if (prior != null) {
                    val dt = max((sample.t - prior.t) / 1000.0, 0.05)
                    val flattening = (prior.angle - sample.angle) / dt
                    if (flattening > peak) peak = flattening
                }
                if (sample.angle >= UPRIGHT_TORSO) opened = true
                previous = sample
            }
            if (!opened || peak < MIN_SPEED) return false
            confirmSince = now
        }
        if (angle > HOLD_TORSO) {
            confirmSince = null
            return false
        }
        val since = confirmSince ?: return false
        return now - since >= CONFIRM_MS
    }

    private data class Sample(val t: Long, val angle: Double)

    companion object {
        const val WINDOW_MS = 1_600L
        const val MIN_SPEED = 140.0
        /** Torso at least this far from horizontal counts as upright. */
        const val UPRIGHT_TORSO = 58.0
        /** Torso at or below this counts as the flat landing. */
        const val FLAT_TORSO = 20.0
        /** Stay at or below this during the hold. 10 degrees of slack. */
        const val HOLD_TORSO = 30.0
        const val CONFIRM_MS = 2_000L
    }
}

private fun torsoAngle(joints: Array<FloatArray>?): Double? {
    if (joints == null) return null
    val minVis = DEFAULT_MIN_VISIBILITY.toDouble()
    fun mean(indexes: IntArray): Pair<Double, Double>? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (index in indexes) {
            val point = joints.getOrNull(index) ?: continue
            val vis = point.getOrNull(2)?.toDouble() ?: 1.0
            if (vis < minVis) continue
            sx += point[0]
            sy += point[1]
            n += 1
        }
        if (n == 0) return null
        return sx / n to sy / n
    }
    val shoulders = mean(intArrayOf(PoseJoints.LEFT_SHOULDER, PoseJoints.RIGHT_SHOULDER)) ?: return null
    val hips = mean(intArrayOf(PoseJoints.LEFT_HIP, PoseJoints.RIGHT_HIP)) ?: return null
    return Math.toDegrees(atan2(abs(shoulders.second - hips.second), abs(shoulders.first - hips.first)))
}
