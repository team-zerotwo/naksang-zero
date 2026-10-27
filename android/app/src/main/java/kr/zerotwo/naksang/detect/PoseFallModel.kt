package kr.zerotwo.naksang.detect

import kotlin.math.exp

/**
 * Pure-Kotlin score for one pose window. Weights live in [PoseFallWeights],
 * which `ml/train/train_pose_model.py` regenerates. No extra runtime.
 */
object PoseFallModel {
    fun probability(features: DoubleArray): Double {
        if (features.size != PoseFallWeights.MEAN.size || features.isEmpty()) return 0.0
        val z = DoubleArray(features.size)
        for (index in features.indices) {
            val scale = if (PoseFallWeights.STD[index] < 1e-8) 1.0 else PoseFallWeights.STD[index]
            var value = (features[index] - PoseFallWeights.MEAN[index]) / scale
            if (value > 8.0) value = 8.0
            if (value < -8.0) value = -8.0
            z[index] = value
        }
        val logit = if (PoseFallWeights.KIND == "mlp") mlpLogit(z) else logregLogit(z)
        return stableSigmoid(logit)
    }

    private fun logregLogit(z: DoubleArray): Double {
        var logit = PoseFallWeights.INTERCEPT
        val coef = PoseFallWeights.COEF
        val n = minOf(z.size, coef.size)
        for (index in 0 until n) logit += z[index] * coef[index]
        return logit
    }

    private fun mlpLogit(z: DoubleArray): Double {
        val b1 = PoseFallWeights.B1
        val hidden = DoubleArray(b1.size)
        for (col in b1.indices) {
            var acc = b1[col]
            for (row in z.indices) {
                val weights = PoseFallWeights.W1.getOrNull(row) ?: continue
                if (col < weights.size) acc += z[row] * weights[col]
            }
            hidden[col] = if (acc > 0.0) acc else 0.0
        }
        var logit = PoseFallWeights.B2
        val w2 = PoseFallWeights.W2
        val n = minOf(hidden.size, w2.size)
        for (index in 0 until n) logit += hidden[index] * w2[index]
        return logit
    }

    private fun stableSigmoid(z: Double): Double {
        return if (z >= 0.0) {
            val ez = exp(-z)
            1.0 / (1.0 + ez)
        } else {
            val ez = exp(z)
            ez / (1.0 + ez)
        }
    }
}

/**
 * Keeps the last [PoseFeatures.WINDOW] frames and fires once when the score
 * stays at or above the threshold for the hold length.
 */
class PoseFallScorer(
    private var enabled: Boolean = true,
    private var threshold: Double = PoseFallWeights.THRESHOLD,
    private var holdFrames: Int = PoseFallWeights.HOLD_FRAMES,
) {
    private val frames = ArrayDeque<Array<FloatArray>?>()
    private val times = ArrayDeque<Long>()
    private var streak = 0
    private var lastScore = 0.0
    private var lastFired = false

    fun update(enabled: Boolean, threshold: Double = this.threshold, holdFrames: Int = this.holdFrames) {
        this.enabled = enabled
        this.threshold = threshold
        this.holdFrames = holdFrames
    }

    fun reset() {
        frames.clear()
        times.clear()
        streak = 0
        lastScore = 0.0
        lastFired = false
    }

    /** Latest score. Does not change detection. */
    fun modelDebug(): ModelPathDebug = ModelPathDebug(
        enabled = enabled,
        score = lastScore,
        threshold = threshold,
        streak = streak,
        holdFrames = holdFrames,
        fired = lastFired,
    )

    /** @param joints null when this analysis frame has no pose. */
    fun onFrame(timestampMs: Long, joints: Array<FloatArray>?): Boolean {
        frames.addLast(joints)
        times.addLast(timestampMs)
        while (frames.size > PoseFeatures.WINDOW) {
            frames.removeFirst()
            times.removeFirst()
        }
        val timesMs = LongArray(times.size) { times[it] }
        val vector = PoseFeatures.features(frames.toList(), timesMs)
        val probability = if (vector == null) null else PoseFallModel.probability(vector)
        lastScore = probability ?: 0.0
        if (!enabled || probability == null) {
            streak = 0
            lastFired = false
            return false
        }
        if (probability >= threshold) {
            streak += 1
            val fired = streak == holdFrames
            lastFired = fired
            return fired
        }
        streak = 0
        lastFired = false
        return false
    }
}
