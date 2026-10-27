package kr.zerotwo.naksang.detect

import kotlin.math.hypot

/**
 * Starts the same countdown as a fall when a person is on the floor and
 * almost still, even if the fall moment itself was missed.
 *
 * Lying means the torso is at or below [FallConfig.maxLieAngleDeg] and the
 * body is low in the frame or has dropped from a recent upright pose.
 * Upright here includes sitting and standing: the torso is still steep.
 *
 * A fast descent (upright to lying inside [FallConfig.lyingMaxDescentMs],
 * after the person was seen upright inside [FallConfig.lyingRecentUprightMs])
 * uses [FallConfig.lyingStillMs]. A slow lie-down, or a person who was
 * already lying when they were first seen, uses the longer
 * [FallConfig.lyingStillUnwitnessedMs]. The slow path is what keeps a
 * deliberate move onto a bed or sofa from alerting at the short duration.
 * Getting up, or shifting by more than [FallConfig.stillMovement], resets
 * the stillness clock.
 */
class LyingStillDetector(config: FallConfig = FallConfig()) {
    var config: FallConfig = config
        private set

    private val trackedX = FloatArray(TRACKED.size)
    private val trackedY = FloatArray(TRACKED.size)
    private val trackedAt = LongArray(TRACKED.size) { Long.MIN_VALUE }
    private var sawUpright = false
    private var uprightCy = 0f
    private var lastUprightAt: Long? = null
    private var lastHighAt: Long? = null
    private var lyingSince: Long? = null
    private var witnessedFast = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var armed = true
    private var lastGoodAt: Long? = null

    /** True while the current frame is being counted as lying on the floor. */
    var lyingNow: Boolean = false
        private set

    /** True when the current lying bout followed a fast descent. */
    var witnessedFastNow: Boolean = false
        private set

    /** Latched until [reset]. The clip entered the floor-lying pose. */
    var sawLying: Boolean = false
        private set

    /** Latched until [reset]. At least one bout was a fast descent. */
    var sawFastDescent: Boolean = false
        private set

    /** Longest continuous still-lying stretch seen, in milliseconds. */
    var maxStillLyingMs: Long = 0
        private set

    private var lyingDebugState = LyingPathDebug(thresholdMs = config.lyingStillMs)

    fun updateConfig(newConfig: FallConfig) {
        config = newConfig
    }

    /** Latest lying-still numbers. Does not change detection. */
    fun lyingDebug(): LyingPathDebug = lyingDebugState

    fun reset() {
        for (index in trackedAt.indices) trackedAt[index] = Long.MIN_VALUE
        sawUpright = false
        uprightCy = 0f
        lastUprightAt = null
        lastHighAt = null
        lyingSince = null
        witnessedFast = false
        armed = true
        lastGoodAt = null
        lyingNow = false
        witnessedFastNow = false
        sawLying = false
        sawFastDescent = false
        maxStillLyingMs = 0
        lyingDebugState = LyingPathDebug(thresholdMs = config.lyingStillMs)
    }

    fun onJointArray(timestampMs: Long, joints: Array<FloatArray>?): Boolean {
        val landmarks = joints?.toJointPoints()
        return onLandmarks(timestampMs, landmarks)
    }

    fun onLandmarks(timestampMs: Long, landmarks: List<JointPoint>?): Boolean {
        val cfg = config
        if (!cfg.lyingStillEnabled) {
            return publish(fired = false, counting = false, elapsedMs = 0, thresholdMs = cfg.lyingStillMs, fast = false)
        }
        val pose = if (landmarks == null) null else measure(timestampMs, landmarks, cfg)
        if (pose == null) {
            lyingNow = false
            val previous = lastGoodAt
            if (previous != null && timestampMs - previous > GAP_RESET_MS) {
                clearLyingClock()
                if (timestampMs - previous > cfg.lyingRecentUprightMs) forgetUpright()
            }
            return publish(fired = false, counting = false, elapsedMs = 0, thresholdMs = cfg.lyingStillMs, fast = false)
        }
        lastGoodAt = timestampMs
        val angle = pose.angleDeg
        val cy = pose.cy

        if (angle >= cfg.lyingHighAngleDeg) lastHighAt = timestampMs
        if (angle >= cfg.lyingUprightAngleDeg) {
            noteUpright(timestampMs, cy)
            clearLyingClock()
            armed = true
            lyingNow = false
            witnessedFastNow = false
            return publish(fired = false, counting = false, elapsedMs = 0, thresholdMs = cfg.lyingStillMs, fast = false)
        }
        if (!armed) {
            lyingNow = false
            return publish(fired = false, counting = false, elapsedMs = 0, thresholdMs = cfg.lyingStillMs, fast = false)
        }

        val low = cy >= cfg.lyingMinCenterY
        val dropped = sawUpright && cy - uprightCy >= cfg.lyingMinDrop
        val lying = angle <= cfg.maxLieAngleDeg && (low || dropped)
        if (!lying) {
            clearLyingClock()
            lyingNow = false
            witnessedFastNow = false
            return publish(fired = false, counting = false, elapsedMs = 0, thresholdMs = cfg.lyingStillMs, fast = false)
        }

        lyingNow = true
        if (lyingSince == null) {
            val uprightAt = lastUprightAt
            val highAt = lastHighAt
            val descentMs = if (highAt != null) timestampMs - highAt else Long.MAX_VALUE
            val recent = sawUpright && uprightAt != null && timestampMs - uprightAt <= cfg.lyingRecentUprightMs
            witnessedFast = recent && descentMs <= cfg.lyingMaxDescentMs && (low || dropped)
            witnessedFastNow = witnessedFast
            sawLying = true
            if (witnessedFast) sawFastDescent = true
            lyingSince = timestampMs
            anchorX = pose.cx
            anchorY = pose.cy
            val need = if (witnessedFast) cfg.lyingStillMs else cfg.lyingStillUnwitnessedMs
            return publish(fired = false, counting = true, elapsedMs = 0, thresholdMs = need, fast = witnessedFast)
        }
        witnessedFastNow = witnessedFast

        val moved = hypot((pose.cx - anchorX).toDouble(), (pose.cy - anchorY).toDouble()).toFloat()
        if (moved > cfg.stillMovement) {
            noteHold(timestampMs)
            lyingSince = timestampMs
            anchorX = pose.cx
            anchorY = pose.cy
            val need = if (witnessedFast) cfg.lyingStillMs else cfg.lyingStillUnwitnessedMs
            return publish(fired = false, counting = true, elapsedMs = 0, thresholdMs = need, fast = witnessedFast)
        }

        val since = lyingSince
        if (since == null) {
            return publish(fired = false, counting = false, elapsedMs = 0, thresholdMs = cfg.lyingStillMs, fast = false)
        }
        noteHold(timestampMs)
        val fast = witnessedFast
        val need = if (fast) cfg.lyingStillMs else cfg.lyingStillUnwitnessedMs
        val elapsed = timestampMs - since
        if (elapsed >= need) {
            armed = false
            clearLyingClock()
            lyingNow = false
            witnessedFastNow = false
            return publish(fired = true, counting = false, elapsedMs = elapsed, thresholdMs = need, fast = fast)
        }
        return publish(fired = false, counting = true, elapsedMs = elapsed, thresholdMs = need, fast = fast)
    }

    private fun publish(
        fired: Boolean,
        counting: Boolean,
        elapsedMs: Long,
        thresholdMs: Long,
        fast: Boolean,
    ): Boolean {
        lyingDebugState = LyingPathDebug(
            counting = counting,
            elapsedMs = elapsedMs,
            thresholdMs = thresholdMs,
            witnessedFast = fast,
            fired = fired,
        )
        return fired
    }

    private fun noteUpright(t: Long, cy: Float) {
        sawUpright = true
        uprightCy = cy
        lastUprightAt = t
    }

    private fun forgetUpright() {
        sawUpright = false
        lastUprightAt = null
        lastHighAt = null
        uprightCy = 0f
    }

    private fun noteHold(timestampMs: Long) {
        val since = lyingSince ?: return
        val held = timestampMs - since
        if (held > maxStillLyingMs) maxStillLyingMs = held
    }

    private fun clearLyingClock() {
        lyingSince = null
        witnessedFast = false
    }

    private fun measure(t: Long, landmarks: List<JointPoint>, cfg: FallConfig): PoseMeasure? {
        var anyLive = false
        val assembled = MutableList(TRACKED.max() + 1) { JointPoint(0f, 0f, 0f) }
        for (slot in TRACKED.indices) {
            val index = TRACKED[slot]
            val raw = landmarks.getOrNull(index)
            val live = raw != null && raw.visibility >= cfg.minVisibility
            if (live) anyLive = true
            val point = track(slot, raw, live, t, cfg) ?: continue
            assembled[index] = point
        }
        if (!anyLive) return null
        return poseMeasure(assembled, minVisibility = 0.5f)
    }

    private fun track(slot: Int, raw: JointPoint?, live: Boolean, t: Long, cfg: FallConfig): JointPoint? {
        if (live && raw != null) {
            val alpha = cfg.smoothAlpha.coerceIn(0.05f, 1f)
            val stale = trackedAt[slot] == Long.MIN_VALUE || t - trackedAt[slot] > cfg.personLostGraceMs
            val x = if (stale) raw.x else alpha * raw.x + (1f - alpha) * trackedX[slot]
            val y = if (stale) raw.y else alpha * raw.y + (1f - alpha) * trackedY[slot]
            trackedX[slot] = x
            trackedY[slot] = y
            trackedAt[slot] = t
            return JointPoint(x, y, 1f)
        }
        if (trackedAt[slot] != Long.MIN_VALUE && t - trackedAt[slot] <= cfg.personLostGraceMs) {
            return JointPoint(trackedX[slot], trackedY[slot], 1f)
        }
        return null
    }

    private companion object {
        const val GAP_RESET_MS = 1_200L
        val TRACKED = intArrayOf(
            PoseJoints.NOSE,
            PoseJoints.LEFT_SHOULDER,
            PoseJoints.RIGHT_SHOULDER,
            PoseJoints.LEFT_HIP,
            PoseJoints.RIGHT_HIP,
            PoseJoints.LEFT_KNEE,
            PoseJoints.RIGHT_KNEE,
            PoseJoints.LEFT_ANKLE,
            PoseJoints.RIGHT_ANKLE,
        )
    }
}
