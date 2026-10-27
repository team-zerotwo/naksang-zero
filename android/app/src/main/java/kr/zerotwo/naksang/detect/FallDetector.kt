package kr.zerotwo.naksang.detect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** MediaPipe Pose landmark indices used by the demo rule. */
object PoseJoints {
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
}

/** Landmark visibility below this is ignored. One side of the torso is enough. */
const val DEFAULT_MIN_VISIBILITY = 0.35f

/** Exponential smoothing on torso joints. 1 follows the raw pose. */
const val DEFAULT_SMOOTH_ALPHA = 0.55f

/**
 * Missed or partly hidden frames inside this window do not restart an
 * in-progress fall. About six analysis frames at 5 fps.
 */
const val DEFAULT_PERSON_LOST_GRACE_MS = 1_200L

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
 * Change them from the in-app settings while filming, or edit the defaults here.
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
    val minVisibility: Float = DEFAULT_MIN_VISIBILITY,
    /** 1 follows the raw joint. Lower values damp jitter that would restart stillness. */
    val smoothAlpha: Float = DEFAULT_SMOOTH_ALPHA,
    /**
     * A partly hidden torso keeps its last joint positions for this long, and a
     * hole shorter than this does not restart stillness mid-fall.
     */
    val personLostGraceMs: Long = DEFAULT_PERSON_LOST_GRACE_MS,
    /** A gap longer than this (and longer than [personLostGraceMs]) restarts stillness. */
    val trackingGapResetMs: Long = DEFAULT_PERSON_LOST_GRACE_MS,
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
 * Each side of the torso is optional: one visible shoulder and one visible hip
 * are enough. A joint that just dropped out is held for [FallConfig.personLostGraceMs]
 * so the end of a fall, when the body is partly out of frame, does not wipe the
 * sequence. Joint positions are smoothed before the three checks.
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

    private data class TrackedJoint(val x: Float, val y: Float, val seenAt: Long)

    private val history = ArrayDeque<Sample>()
    private val tracked = arrayOfNulls<TrackedJoint>(TORSO_SLOTS.size)
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
        for (i in tracked.indices) tracked[i] = null
    }

    /**
     * @param joints rows of `[x, y]` or `[x, y, visibility]`, indexed like MediaPipe Pose.
     * @return true exactly once when a fall becomes suspected.
     */
    fun onJointArray(timestampMs: Long, joints: Array<FloatArray>): Boolean {
        return onLandmarks(timestampMs, joints.toJointPoints())
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
        val gapLimit = max(cfg.personLostGraceMs, cfg.trackingGapResetMs)
        if (lieSince != null && gap != null && gap > gapLimit) {
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

    /**
     * Smooth the torso joints that are currently visible. A joint that disappeared
     * inside the grace window keeps its last position so a one-side occlusion does
     * not jump the center. Returns null when this frame has no live torso joint,
     * or when a shoulder and a hip cannot be formed even with held points.
     */
    private fun measure(t: Long, landmarks: List<JointPoint>, cfg: FallConfig): Sample? {
        var anyLive = false
        val live = BooleanArray(TORSO_SLOTS.size)
        val raw = arrayOfNulls<JointPoint>(TORSO_SLOTS.size)
        for (i in TORSO_SLOTS.indices) {
            val joint = landmarks.getOrNull(TORSO_SLOTS[i])
            raw[i] = joint
            val visible = joint != null && joint.visibility >= cfg.minVisibility
            live[i] = visible
            if (visible) anyLive = true
        }
        if (!anyLive) return null

        // Placeholders stay at visibility 0 so they cannot form a fake axis.
        val assembled = MutableList(PoseJoints.RIGHT_HIP + 1) { JointPoint(0f, 0f, 0f) }
        for (i in TORSO_SLOTS.indices) {
            val point = trackJoint(i, raw[i], live[i], t, cfg) ?: continue
            assembled[TORSO_SLOTS[i]] = point
        }
        val axis = torsoAxis(assembled, minVisibility = 0.5f) ?: return null
        return Sample(t = t, cx = axis.cx, cy = axis.cy, angleDeg = axis.angleDeg)
    }

    private fun trackJoint(
        slot: Int,
        raw: JointPoint?,
        live: Boolean,
        t: Long,
        cfg: FallConfig,
    ): JointPoint? {
        val prev = tracked[slot]
        if (live && raw != null) {
            val alpha = cfg.smoothAlpha.coerceIn(0.05f, 1f)
            val stale = prev == null || t - prev.seenAt > cfg.personLostGraceMs
            val x = if (stale) raw.x else alpha * raw.x + (1f - alpha) * prev.x
            val y = if (stale) raw.y else alpha * raw.y + (1f - alpha) * prev.y
            tracked[slot] = TrackedJoint(x, y, t)
            return JointPoint(x, y, 1f)
        }
        if (prev != null && t - prev.seenAt <= cfg.personLostGraceMs) {
            return JointPoint(prev.x, prev.y, 1f)
        }
        return null
    }

    private companion object {
        val TORSO_SLOTS = intArrayOf(
            PoseJoints.LEFT_SHOULDER,
            PoseJoints.RIGHT_SHOULDER,
            PoseJoints.LEFT_HIP,
            PoseJoints.RIGHT_HIP,
        )
    }
}

data class TorsoAxis(
    val cx: Float,
    val cy: Float,
    val angleDeg: Float,
)

/**
 * Shoulder/hip axis from whichever of the four torso joints clear [minVisibility].
 * Needs at least one shoulder and one hip. The center is the midpoint of the
 * visible shoulder group and the visible hip group.
 */
fun torsoAxis(landmarks: List<JointPoint>, minVisibility: Float = DEFAULT_MIN_VISIBILITY): TorsoAxis? {
    val shoulders = ArrayList<JointPoint>(2)
    val hips = ArrayList<JointPoint>(2)
    landmarks.getOrNull(PoseJoints.LEFT_SHOULDER)?.takeIf { it.visibility >= minVisibility }?.let { shoulders += it }
    landmarks.getOrNull(PoseJoints.RIGHT_SHOULDER)?.takeIf { it.visibility >= minVisibility }?.let { shoulders += it }
    landmarks.getOrNull(PoseJoints.LEFT_HIP)?.takeIf { it.visibility >= minVisibility }?.let { hips += it }
    landmarks.getOrNull(PoseJoints.RIGHT_HIP)?.takeIf { it.visibility >= minVisibility }?.let { hips += it }
    if (shoulders.isEmpty() || hips.isEmpty()) return null
    val sx = shoulders.sumOf { it.x.toDouble() }.toFloat() / shoulders.size
    val sy = shoulders.sumOf { it.y.toDouble() }.toFloat() / shoulders.size
    val hx = hips.sumOf { it.x.toDouble() }.toFloat() / hips.size
    val hy = hips.sumOf { it.y.toDouble() }.toFloat() / hips.size
    val angle = Math.toDegrees(
        atan2(abs(sy - hy).toDouble(), abs(sx - hx).toDouble()),
    ).toFloat()
    return TorsoAxis(
        cx = (sx + hx) / 2f,
        cy = (sy + hy) / 2f,
        angleDeg = angle,
    )
}

/** Average of the visible shoulder group and the visible hip group. */
fun torsoCenter(joints: Array<FloatArray>, minVisibility: Float = DEFAULT_MIN_VISIBILITY): Pair<Float, Float>? {
    val axis = torsoAxis(joints.toJointPoints(), minVisibility) ?: return null
    return axis.cx to axis.cy
}

fun Array<FloatArray>.toJointPoints(): List<JointPoint> = map { row ->
    JointPoint(
        x = row.getOrElse(0) { 0f },
        y = row.getOrElse(1) { 0f },
        visibility = row.getOrElse(2) { 1f },
    )
}
