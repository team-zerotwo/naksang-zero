package kr.zerotwo.naksang.detect

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Starts the same countdown as a fall when a person is on the floor and
 * almost still, even if the fall moment itself was missed.
 *
 * Lying means the torso is at or below [FallConfig.maxLieAngleDeg] and the
 * body is low in the frame or has dropped from a recent upright pose.
 * Upright here includes sitting and standing: the torso is still steep.
 *
 * A second way in is a person lying along the camera axis. The shoulder–hip
 * line stays steep, so the angle gate would clear the clock, but the torso is
 * much shorter than when they were standing and the knees and ankles sit near
 * the hips. That pose still waits out the 9 second or 45 second clock. It does
 * not fire on the spot. Walking away, sitting, and squatting keep either a
 * long torso relative to the shoulders or legs that hang below the hips.
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
    /** Torso length and shoulder width last seen while actually upright. */
    private var uprightTorsoLen = 0f
    private var uprightShoulder = 0f

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
        uprightTorsoLen = 0f
        uprightShoulder = 0f
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
        val angle = pose.pose.angleDeg
        val cy = pose.pose.cy
        val supine = isSupine(pose.pose, pose.shoulderWidth, cfg)

        if (!supine && angle >= cfg.lyingHighAngleDeg) lastHighAt = timestampMs
        if (!supine && angle >= cfg.lyingUprightAngleDeg) {
            noteUpright(timestampMs, cy)
            noteStandingShape(pose.pose.torsoLen, pose.shoulderWidth)
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
        val angleLying = angle <= cfg.maxLieAngleDeg && (low || dropped)
        val lying = angleLying || supine
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
            anchorX = pose.pose.cx
            anchorY = pose.pose.cy
            val need = if (witnessedFast) cfg.lyingStillMs else cfg.lyingStillUnwitnessedMs
            return publish(
                fired = false,
                counting = true,
                elapsedMs = 0,
                thresholdMs = need,
                fast = witnessedFast,
                auxiliary = supine,
            )
        }
        witnessedFastNow = witnessedFast

        val moved = hypot((pose.pose.cx - anchorX).toDouble(), (pose.pose.cy - anchorY).toDouble()).toFloat()
        if (moved > cfg.stillMovement) {
            noteHold(timestampMs)
            lyingSince = timestampMs
            anchorX = pose.pose.cx
            anchorY = pose.pose.cy
            val need = if (witnessedFast) cfg.lyingStillMs else cfg.lyingStillUnwitnessedMs
            return publish(
                fired = false,
                counting = true,
                elapsedMs = 0,
                thresholdMs = need,
                fast = witnessedFast,
                auxiliary = supine,
            )
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
            return publish(
                fired = true,
                counting = false,
                elapsedMs = elapsed,
                thresholdMs = need,
                fast = fast,
                auxiliary = supine,
            )
        }
        return publish(
            fired = false,
            counting = true,
            elapsedMs = elapsed,
            thresholdMs = need,
            fast = fast,
            auxiliary = supine,
        )
    }

    private fun publish(
        fired: Boolean,
        counting: Boolean,
        elapsedMs: Long,
        thresholdMs: Long,
        fast: Boolean,
        auxiliary: Boolean = false,
    ): Boolean {
        lyingDebugState = LyingPathDebug(
            counting = counting,
            elapsedMs = elapsedMs,
            thresholdMs = thresholdMs,
            witnessedFast = fast,
            fired = fired,
            auxiliary = auxiliary,
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
        uprightTorsoLen = 0f
        uprightShoulder = 0f
    }

    /** Keep the long torso from standing. A shorter frame must not shrink the reference. */
    private fun noteStandingShape(torsoLen: Float, shoulder: Float?) {
        if (shoulder == null || shoulder < MIN_SHOULDER || torsoLen < MIN_TORSO_LEN) return
        if (uprightTorsoLen <= 0f || uprightShoulder < MIN_SHOULDER) {
            uprightTorsoLen = torsoLen
            uprightShoulder = shoulder
            return
        }
        if (torsoLen < uprightTorsoLen * STANDING_LEN_KEEP) return
        uprightTorsoLen = 0.9f * uprightTorsoLen + 0.1f * torsoLen
        uprightShoulder = 0.9f * uprightShoulder + 0.1f * shoulder
    }

    /**
     * Steep torso that is foreshortened because the body points at the camera,
     * with the legs up near the hips rather than hanging down.
     */
    private fun isSupine(pose: PoseMeasure, shoulderWidth: Float?, cfg: FallConfig): Boolean {
        if (!cfg.supineAuxEnabled) return false
        val width = shoulderWidth ?: return false
        if (!legsNearHips(pose, width, cfg)) return false
        val shape = pose.torsoLen / width
        val uprightLen = uprightTorsoLen
        val uprightWidth = uprightShoulder
        if (uprightLen >= MIN_TORSO_LEN && uprightWidth >= MIN_SHOULDER) {
            val uprightShape = uprightLen / uprightWidth
            if (uprightShape <= 0f) return false
            val lenRatio = pose.torsoLen / uprightLen
            val shapeRatio = shape / uprightShape
            return lenRatio <= cfg.supineTorsoLenRatio && shapeRatio <= cfg.supineShapeRatio
        }
        return shape <= cfg.supineAbsShapeRatio
    }

    private fun legsNearHips(pose: PoseMeasure, shoulderWidth: Float, cfg: FallConfig): Boolean {
        val knee = pose.kneeY ?: return false
        val ankle = pose.ankleY ?: return false
        val band = max(cfg.supineLegBand, shoulderWidth * cfg.supineLegShoulderScale)
        val hip = pose.hipY
        return abs(knee - hip) <= band && abs(ankle - hip) <= band
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

    private data class MeasuredPose(val pose: PoseMeasure, val shoulderWidth: Float?)

    private fun measure(t: Long, landmarks: List<JointPoint>, cfg: FallConfig): MeasuredPose? {
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
        val pose = poseMeasure(assembled, minVisibility = 0.5f) ?: return null
        return MeasuredPose(pose, shoulderWidth(assembled))
    }

    private fun shoulderWidth(landmarks: List<JointPoint>): Float? {
        val left = landmarks.getOrNull(PoseJoints.LEFT_SHOULDER)?.takeIf { it.visibility >= 0.5f } ?: return null
        val right = landmarks.getOrNull(PoseJoints.RIGHT_SHOULDER)?.takeIf { it.visibility >= 0.5f } ?: return null
        val width = hypot((left.x - right.x).toDouble(), (left.y - right.y).toDouble()).toFloat()
        if (width < MIN_SHOULDER) return null
        return width
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
        const val MIN_TORSO_LEN = 0.04f
        const val MIN_SHOULDER = 0.04f
        const val STANDING_LEN_KEEP = 0.85f
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
