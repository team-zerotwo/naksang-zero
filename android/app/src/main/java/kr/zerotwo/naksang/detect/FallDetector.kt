package kr.zerotwo.naksang.detect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** MediaPipe Pose landmark indices used by the demo rule. */
object PoseJoints {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
}

/**
 * One joint in normalized image coordinates.
 * x grows right, y grows downward (0 is the top of the frame). A drop in
 * body height is therefore an increase in y.
 */
data class JointPoint(
    val x: Float,
    val y: Float,
    val visibility: Float = 1f,
)

/**
 * Thresholds for the 11/3 rule. All distances are fractions of the frame.
 * Change them from the in-app settings while filming.
 */
data class FallConfig(
    /** How far back to look for a sudden drop. */
    val dropWindowMs: Long = 800,
    /** Minimum downward move of the shoulder/hip center inside [dropWindowMs]. */
    val minDrop: Float = 0.18f,
    /** Torso angle from horizontal. 0 is flat on the floor. At or below this counts as lying. */
    val maxLieAngleDeg: Float = 28f,
    /** Cancel the lying candidate if the torso rises past this angle. */
    val lieAngleReleaseDeg: Float = 45f,
    /** Pose at the start of the drop window must still be this upright. */
    val uprightAngleDeg: Float = 58f,
    /** How long the body must stay nearly still after the drop. */
    val stillDurationMs: Long = 2_000,
    /** Torso-center movement that restarts the stillness timer. */
    val stillMovement: Float = 0.035f,
    val minVisibility: Float = 0.5f,
    /** A gap longer than this means tracking was lost, so stillness starts over. */
    val trackingGapResetMs: Long = 700,
)

/**
 * Rule-based fall suspicion from a pose time series.
 *
 * No Android types. A fall is suspected only when all three are true:
 * 1. The shoulder/hip center drops by at least [FallConfig.minDrop] within
 *    [FallConfig.dropWindowMs], and the body was upright at the start of that window.
 * 2. The shoulder–hip axis is near horizontal.
 * 3. The center then stays within [FallConfig.stillMovement] for [FallConfig.stillDurationMs].
 *
 * After a suspicion the detector stays disarmed until it sees an upright pose,
 * so a person who remains on the floor is not reported on every frame.
 */
class FallDetector(config: FallConfig = FallConfig()) {
    var config: FallConfig = config
        private set

    private data class Sample(
        val t: Long,
        val cx: Float,
        val cy: Float,
        val angleDeg: Float,
    )

    private val history = ArrayDeque<Sample>()
    private var lieSince: Long? = null
    private var anchorX = 0f
    private var anchorY = 0f
    private var lastTimestamp: Long? = null
    private var armed = true

    @Synchronized
    fun updateConfig(newConfig: FallConfig) {
        config = newConfig
    }

    @Synchronized
    fun reset() {
        history.clear()
        lieSince = null
        lastTimestamp = null
        armed = true
    }

    /**
     * @param joints rows of `[x, y]` or `[x, y, visibility]`, indexed like MediaPipe Pose.
     * @return true exactly once when a fall becomes suspected.
     */
    fun onJointArray(timestampMs: Long, joints: Array<FloatArray>): Boolean {
        val landmarks = joints.map { row ->
            JointPoint(
                x = row.getOrElse(0) { 0f },
                y = row.getOrElse(1) { 0f },
                visibility = row.getOrElse(2) { 1f },
            )
        }
        return onLandmarks(timestampMs, landmarks)
    }

    @Synchronized
    fun onLandmarks(timestampMs: Long, landmarks: List<JointPoint>): Boolean {
        val cfg = config
        val sample = measure(timestampMs, landmarks, cfg) ?: return false
        val previousTime = lastTimestamp
        lastTimestamp = sample.t

        val keepAfter = sample.t - (cfg.dropWindowMs + cfg.stillDurationMs + 3_000L)
        while (history.isNotEmpty() && history.first().t < keepAfter) {
            history.removeFirst()
        }
        history.addLast(sample)

        if (!armed) {
            if (sample.angleDeg >= cfg.uprightAngleDeg) {
                armed = true
                lieSince = null
            }
            return false
        }

        val gap = previousTime?.let { sample.t - it }
        if (lieSince != null && gap != null && gap > cfg.trackingGapResetMs) {
            lieSince = sample.t
            anchorX = sample.cx
            anchorY = sample.cy
        }

        val lying = lieSince
        if (lying == null) {
            val ref = history.lastOrNull { it.t <= sample.t - cfg.dropWindowMs } ?: return false
            val drop = sample.cy - ref.cy
            val sharp = drop >= cfg.minDrop && sample.t - ref.t <= cfg.dropWindowMs + 250
            val wasUpright = ref.angleDeg >= cfg.uprightAngleDeg
            val nowFlat = sample.angleDeg <= cfg.maxLieAngleDeg
            if (sharp && wasUpright && nowFlat) {
                lieSince = sample.t
                anchorX = sample.cx
                anchorY = sample.cy
            }
            return false
        }

        if (sample.angleDeg > cfg.lieAngleReleaseDeg) {
            lieSince = null
            return false
        }

        val moved = hypot((sample.cx - anchorX).toDouble(), (sample.cy - anchorY).toDouble()).toFloat()
        if (moved > cfg.stillMovement) {
            lieSince = sample.t
            anchorX = sample.cx
            anchorY = sample.cy
            return false
        }

        if (sample.t - lying >= cfg.stillDurationMs) {
            armed = false
            lieSince = null
            return true
        }
        return false
    }

    private fun measure(t: Long, landmarks: List<JointPoint>, cfg: FallConfig): Sample? {
        val ls = landmarks.getOrNull(PoseJoints.LEFT_SHOULDER) ?: return null
        val rs = landmarks.getOrNull(PoseJoints.RIGHT_SHOULDER) ?: return null
        val lh = landmarks.getOrNull(PoseJoints.LEFT_HIP) ?: return null
        val rh = landmarks.getOrNull(PoseJoints.RIGHT_HIP) ?: return null
        if (ls.visibility < cfg.minVisibility ||
            rs.visibility < cfg.minVisibility ||
            lh.visibility < cfg.minVisibility ||
            rh.visibility < cfg.minVisibility
        ) {
            return null
        }
        val sx = (ls.x + rs.x) / 2f
        val sy = (ls.y + rs.y) / 2f
        val hx = (lh.x + rh.x) / 2f
        val hy = (lh.y + rh.y) / 2f
        val angle = Math.toDegrees(
            atan2(abs(sy - hy).toDouble(), abs(sx - hx).toDouble()),
        ).toFloat()
        return Sample(
            t = t,
            cx = (sx + hx) / 2f,
            cy = (sy + hy) / 2f,
            angleDeg = angle,
        )
    }
}

/** Average of the two shoulders and two hips, or null when any of them is missing. */
fun torsoCenter(joints: Array<FloatArray>, minVisibility: Float = 0.5f): Pair<Float, Float>? {
    val indices = intArrayOf(
        PoseJoints.LEFT_SHOULDER,
        PoseJoints.RIGHT_SHOULDER,
        PoseJoints.LEFT_HIP,
        PoseJoints.RIGHT_HIP,
    )
    if (joints.size <= PoseJoints.RIGHT_HIP) return null
    var x = 0f
    var y = 0f
    for (index in indices) {
        val row = joints[index]
        val visibility = row.getOrElse(2) { 1f }
        if (row.size < 2 || visibility < minVisibility) return null
        x += row[0]
        y += row[1]
    }
    return x / indices.size to y / indices.size
}
