package kr.zerotwo.naksang.detect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/** MediaPipe Pose landmark indices used by the demo rule. */
object PoseJoints {
    const val NOSE = 0
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
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
 * Thresholds for the on-device rule. All distances are fractions of the frame.
 * Change the five filming knobs from in-app settings, or edit the defaults here.
 *
 * A fall is a sharp drop from an upright pose into a down pose, then stillness.
 * The down pose is any of: a near-horizontal torso, a torso that shortens because
 * it points along the camera axis, or a full-body image height that collapses.
 * Those two are off unless the ratios are set above 0: on CAUCAFall and GMDCSA
 * they marked kneeling, stretching, and lying down as falls.
 *
 * A separate path catches a fall along the camera's line of sight. The torso stays
 * steep, the center still drops quickly, and the person then holds still. Walking
 * drops the center too, but it does not hold still for [stillDurationMs].
 */
data class FallConfig(
    /** How far back to look for a sudden drop into a flat or shortened pose. */
    val dropWindowMs: Long = 1_600,
    /** Minimum downward move of the shoulder/hip center inside [dropWindowMs]. */
    val minDrop: Float = 0.10f,
    /** Torso angle from horizontal. 0 is flat on the floor. At or below this counts as lying. */
    val maxLieAngleDeg: Float = 45f,
    /** Cancel a flat-torso candidate if the torso rises past this angle. */
    val lieAngleReleaseDeg: Float = 62f,
    /** Pose at the start of the drop window must still be this upright. */
    val uprightAngleDeg: Float = 58f,
    /** How long the body must stay nearly still after the drop. */
    val stillDurationMs: Long = 2_000,
    /** Torso-center movement that restarts the stillness timer. */
    val stillMovement: Float = 0.050f,
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
    /**
     * Torso length now / torso length at the upright frame. At or below this, the
     * body counts as down even when the shoulder–hip line still looks steep.
     * 0 disables this path.
     */
    val maxTorsoLenRatio: Float = 0f,
    /**
     * Full-body image height now / height while upright, using only joints seen
     * in both frames. Needs a head, knee, or ankle in addition to the torso.
     * 0 disables this path.
     */
    val maxBodyHeightRatio: Float = 0f,
    /**
     * Downward center move, divided by the upright torso length, that also counts
     * as a sharp drop. Catches a far person whose fall is a small fraction of the
     * frame. 0 disables this alternative.
     */
    val minDropInTorso: Float = 0f,
    /**
     * Head and hip must each move down by this much inside the drop window.
     * Used only when the nose is visible. 0 disables this alternative to [minDrop].
     */
    val minLimbDrop: Float = 0f,
    /**
     * Window for a fall whose torso stays steep because the body points along the
     * camera axis. 0 disables the path.
     */
    val steepWindowMs: Long = 800,
    /** Center drop inside [steepWindowMs] that starts the steep path. */
    val steepMinDrop: Float = 0.18f,
    /** The torso must stay at least this far from horizontal on that path. */
    val steepMinAngleDeg: Float = 65f,
    /** Movement limit while confirming a steep-torso fall. Tighter than [stillMovement]. */
    val steepStillMovement: Float = 0.035f,
    /**
     * Score the pose window with the shipped weights. A suspicion is the rule
     * above, or this score held for a few frames, or [lyingStillEnabled].
     */
    val modelEnabled: Boolean = true,
    /**
     * Person lying low and almost still. Short timer after a fast descent from
     * a recent upright pose; long timer if they were already lying or lay down slowly.
     */
    val lyingStillEnabled: Boolean = true,
    /** Stillness after a witnessed fast descent onto the floor. */
    val lyingStillMs: Long = 9_000,
    /** Stillness when the descent was slow or the person was already lying. */
    val lyingStillUnwitnessedMs: Long = 45_000,
    /** An upright or sitting pose inside this window counts as "seen before lying". */
    val lyingRecentUprightMs: Long = 20_000,
    /**
     * Time from a steep torso (about 72°) to lying. A fall crosses that band
     * quickly. Lying down on a bed crosses it over a longer part of the motion.
     */
    val lyingMaxDescentMs: Long = 1_200,
    /** Torso at or above this counts as clearly upright for the descent clock. */
    val lyingHighAngleDeg: Float = 72f,
    /** Torso center at or below this image y (0 is the top) counts as low in the frame. */
    val lyingMinCenterY: Float = 0.45f,
    /** Drop from the last upright center that also counts as low, even if the center is not at the bottom. */
    val lyingMinDrop: Float = 0.08f,
    /** At or above this torso angle the person is upright, sitting, or kneeling, not lying. */
    val lyingUprightAngleDeg: Float = 50f,
    /**
     * Supine pose pointing along the camera axis. The torso angle stays steep,
     * so [lyingUprightAngleDeg] would otherwise clear the lying clock every frame.
     * The pose still counts as lying when the torso is at most [supineTorsoLenRatio]
     * of the recent upright torso length and the shoulder-normalized length is at
     * most [supineShapeRatio] of the upright ratio (walking away shrinks both the
     * same way and does not pass), and the knees and ankles sit near the hips.
     * With no upright sample yet, torso/shoulder at or below [supineAbsShapeRatio]
     * is enough, together with the same leg check. The 9s/45s clock is unchanged.
     * This does not fire a fall on the spot. [maxTorsoLenRatio] stays 0.
     */
    val supineAuxEnabled: Boolean = true,
    /** Torso length now / torso length while recently upright. */
    val supineTorsoLenRatio: Float = 0.50f,
    /** (torso / shoulder width) now, divided by the same ratio while upright. */
    val supineShapeRatio: Float = 0.50f,
    /** Torso / shoulder width when this person has not been seen upright yet. */
    val supineAbsShapeRatio: Float = 0.55f,
    /** Knees and ankles must stay within this fraction of the frame of the hip. */
    val supineLegBand: Float = 0.10f,
    /** Leg band is at least this many shoulder widths, so a small person is not judged on a fixed fraction. */
    val supineLegShoulderScale: Float = 0.80f,
)

/**
 * Rule-based fall suspicion from a pose time series.
 *
 * No Android types. A fall is suspected only when all three are true:
 * 1. The body drops sharply from a pose that was upright. For a flat landing the
 *    window is [FallConfig.dropWindowMs]. For a landing that stays steep, because
 *    the person lies along the camera axis, the window is [FallConfig.steepWindowMs]
 *    and the drop has to be larger.
 * 2. The body is then down: the shoulder–hip axis is near horizontal, or (when
 *    those ratios are enabled) the torso or the body height has collapsed, or the
 *    torso is still steep after the faster drop.
 * 3. The center then stays still for [FallConfig.stillDurationMs]. A steep landing
 *    uses the tighter [FallConfig.steepStillMovement].
 *
 * Each side of the torso is optional: one visible shoulder and one visible hip
 * are enough. A joint that just dropped out is held for [FallConfig.personLostGraceMs]
 * so the end of a fall, when the body is partly out of frame, does not wipe the
 * sequence. Joint positions are smoothed before the checks.
 *
 * After a suspicion the detector stays disarmed until the person is upright and
 * long again. A steep-torso landing also has to rise in the image, because that
 * pose still looks like standing.
 */
class FallDetector(config: FallConfig = FallConfig()) {
    var config: FallConfig = config
        private set

    private data class Sample(
        val t: Long,
        val cx: Float,
        val cy: Float,
        val angleDeg: Float,
        val torsoLen: Float,
        val shoulderY: Float,
        val hipY: Float,
        val noseY: Float?,
        val kneeY: Float?,
        val ankleY: Float?,
    )

    private data class TrackedJoint(val x: Float, val y: Float, val seenAt: Long)

    private val history = ArrayDeque<Sample>()
    private val tracked = arrayOfNulls<TrackedJoint>(TRACKED_SLOTS.size)
    private var lieSince: Long? = null
    private var lieRef: Sample? = null
    private var lieFlat = false
    private var lieSteep = false
    private var lieDrop = 0f
    /** Image y of a steep-torso landing. The person must rise past this before another fall. */
    private var floorCy = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var standingLen = 0f
    private var lastTimestamp: Long? = null
    private var armed = true
    private var ruleDebugState = RulePathDebug(stillNeedMs = config.stillDurationMs)

    @Synchronized
    fun updateConfig(newConfig: FallConfig) {
        config = newConfig
    }

    /** Latest rule-path numbers. Does not change detection. */
    @Synchronized
    fun ruleDebug(): RulePathDebug = ruleDebugState

    /**
     * A frame with no pose. Matches production, which does not call
     * [onJointArray] in that case, and clears the one-frame fire flags
     * without touching the rule state.
     */
    @Synchronized
    fun noteAbsent() {
        ruleDebugState = ruleDebugState.copy(
            measured = false,
            angleDeg = null,
            hipY = null,
            kneeY = null,
            ruleFired = false,
            viewFired = false,
        )
    }

    @Synchronized
    fun reset() {
        history.clear()
        lieSince = null
        lieRef = null
        lieFlat = false
        lieSteep = false
        lieDrop = 0f
        floorCy = 0f
        standingLen = 0f
        lastTimestamp = null
        armed = true
        ruleDebugState = RulePathDebug(stillNeedMs = config.stillDurationMs)
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
        val sample = measure(timestampMs, landmarks, cfg)
        if (sample == null) return publish(fired = false, sample = null, firedSteep = false, elapsedOverride = null)
        val previousTime = lastTimestamp
        lastTimestamp = sample.t

        val keepAfter = sample.t - (cfg.dropWindowMs + cfg.stillDurationMs + 3_000L)
        while (history.isNotEmpty() && history.first().t < keepAfter) {
            history.removeFirst()
        }
        history.addLast(sample)
        rememberStanding(sample, cfg)

        if (!armed) {
            if (standingAgain(sample, cfg)) {
                armed = true
                clearLie()
            }
            return publish(fired = false, sample = sample, firedSteep = false, elapsedOverride = null)
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
            val flatLatch = flatLatch(sample, cfg)
            val steepLatch = steepLatch(sample, cfg)
            if (flatLatch == null && steepLatch == null) {
                return publish(fired = false, sample = sample, firedSteep = false, elapsedOverride = null)
            }
            // A flat landing uses the lie-angle hold. The steep path is only for a
            // torso that never looks horizontal.
            val useSteep = flatLatch == null && steepLatch != null
            val chosen = if (useSteep) steepLatch else flatLatch
            if (chosen == null) return publish(fired = false, sample = sample, firedSteep = false, elapsedOverride = null)
            lieSince = sample.t
            lieRef = chosen.ref
            lieFlat = !useSteep && chosen.flat
            lieSteep = useSteep
            lieDrop = chosen.drop
            anchorX = sample.cx
            anchorY = sample.cy
            return publish(fired = false, sample = sample, firedSteep = false, elapsedOverride = null)
        }

        if (lieSteep) {
            val flat = flatLatch(sample, cfg)
            if (flat != null) {
                // The body went from an along-view drop into a flat landing.
                // Keep the stillness clock; the flat path allows a little more movement.
                lieSteep = false
                lieFlat = true
                lieRef = flat.ref
                lieDrop = flat.drop
            }
        }

        if (!stillDown(sample, cfg)) {
            clearLie()
            return publish(fired = false, sample = sample, firedSteep = false, elapsedOverride = null)
        }

        val moved = hypot((sample.cx - anchorX).toDouble(), (sample.cy - anchorY).toDouble()).toFloat()
        val moveLimit = if (lieSteep) cfg.steepStillMovement else cfg.stillMovement
        if (moved > moveLimit) {
            lieSince = sample.t
            anchorX = sample.cx
            anchorY = sample.cy
            return publish(fired = false, sample = sample, firedSteep = false, elapsedOverride = null)
        }

        if (sample.t - lying >= cfg.stillDurationMs) {
            val steep = lieSteep
            val elapsed = sample.t - lying
            if (lieSteep) floorCy = sample.cy
            armed = false
            clearLie()
            return publish(fired = true, sample = sample, firedSteep = steep, elapsedOverride = elapsed)
        }
        return publish(fired = false, sample = sample, firedSteep = false, elapsedOverride = null)
    }

    /** Records the read-only snapshot. The boolean is the detection result. */
    private fun publish(fired: Boolean, sample: Sample?, firedSteep: Boolean, elapsedOverride: Long?): Boolean {
        val since = lieSince
        val elapsed = when {
            elapsedOverride != null -> elapsedOverride
            sample != null && since != null -> (sample.t - since).coerceAtLeast(0L)
            else -> 0L
        }
        ruleDebugState = RulePathDebug(
            measured = sample != null,
            angleDeg = sample?.angleDeg,
            hipY = sample?.hipY,
            kneeY = sample?.kneeY,
            ruleLatched = since != null && !lieSteep,
            viewLatched = since != null && lieSteep,
            stillElapsedMs = elapsed,
            stillNeedMs = config.stillDurationMs,
            ruleFired = fired && !firedSteep,
            viewFired = fired && firedSteep,
        )
        return fired
    }

    private data class Latch(val ref: Sample, val drop: Float, val flat: Boolean)

    private fun flatLatch(sample: Sample, cfg: FallConfig): Latch? {
        val ref = history.lastOrNull { it.t <= sample.t - cfg.dropWindowMs } ?: return null
        if (sample.t - ref.t > cfg.dropWindowMs + 250) return null
        if (ref.angleDeg < cfg.uprightAngleDeg) return null
        val drop = sample.cy - ref.cy
        val inTorso = cfg.minDropInTorso > 0f && ref.torsoLen >= MIN_TORSO_LEN &&
            drop / ref.torsoLen >= cfg.minDropInTorso
        val headDrop = if (sample.noseY != null && ref.noseY != null) sample.noseY - ref.noseY else null
        val hipDrop = sample.hipY - ref.hipY
        val limbsDown = cfg.minLimbDrop > 0f &&
            headDrop != null &&
            headDrop >= cfg.minLimbDrop &&
            hipDrop >= cfg.minLimbDrop
        val sharp = drop >= cfg.minDrop || inTorso || limbsDown
        if (!sharp) return null
        val flat = sample.angleDeg <= cfg.maxLieAngleDeg
        val lenRatio = if (ref.torsoLen >= MIN_TORSO_LEN) sample.torsoLen / ref.torsoLen else 1f
        val collapsed = cfg.maxTorsoLenRatio > 0f &&
            refIsFullHeight(ref) &&
            ref.torsoLen >= MIN_TORSO_LEN &&
            lenRatio <= cfg.maxTorsoLenRatio
        val heightRatio = bodyHeightRatio(ref.toMeasure(), sample.toMeasure())
        val heightCollapsed = cfg.maxBodyHeightRatio > 0f &&
            refIsFullHeight(ref) &&
            heightRatio != null &&
            heightRatio <= cfg.maxBodyHeightRatio
        if (!flat && !collapsed && !heightCollapsed) return null
        return Latch(ref, drop, flat)
    }

    /**
     * Fall along the view: the torso never lies across the image, but the center
     * drops inside [FallConfig.steepWindowMs] and the torso stays steep.
     */
    private fun steepLatch(sample: Sample, cfg: FallConfig): Latch? {
        if (cfg.steepMinDrop <= 0f || cfg.steepWindowMs <= 0L) return null
        val ref = history.lastOrNull { it.t <= sample.t - cfg.steepWindowMs } ?: return null
        if (sample.t - ref.t > cfg.steepWindowMs + 250) return null
        if (ref.angleDeg < cfg.uprightAngleDeg) return null
        if (sample.angleDeg < cfg.steepMinAngleDeg) return null
        val drop = sample.cy - ref.cy
        if (drop < cfg.steepMinDrop) return null
        return Latch(ref, drop, flat = false)
    }

    /**
     * Smooth the torso joints that are currently visible. A joint that disappeared
     * inside the grace window keeps its last position so a one-side occlusion does
     * not jump the center. Returns null when this frame has no live torso joint,
     * or when a shoulder and a hip cannot be formed even with held points.
     */
    private fun measure(t: Long, landmarks: List<JointPoint>, cfg: FallConfig): Sample? {
        var anyTorso = false
        val live = BooleanArray(TRACKED_SLOTS.size)
        val raw = arrayOfNulls<JointPoint>(TRACKED_SLOTS.size)
        for (i in TRACKED_SLOTS.indices) {
            val joint = landmarks.getOrNull(TRACKED_SLOTS[i])
            raw[i] = joint
            val visible = joint != null && joint.visibility >= cfg.minVisibility
            live[i] = visible
            if (visible && TRACKED_SLOTS[i] in TORSO_INDEXES) anyTorso = true
        }
        if (!anyTorso) return null

        // Placeholders stay at visibility 0 so they cannot form a fake axis.
        val assembled = MutableList(TRACKED_SLOTS.max() + 1) { JointPoint(0f, 0f, 0f) }
        for (i in TRACKED_SLOTS.indices) {
            val point = trackJoint(i, raw[i], live[i], t, cfg) ?: continue
            assembled[TRACKED_SLOTS[i]] = point
        }
        val pose = poseMeasure(assembled, minVisibility = 0.5f) ?: return null
        return Sample(
            t = t,
            cx = pose.cx,
            cy = pose.cy,
            angleDeg = pose.angleDeg,
            torsoLen = pose.torsoLen,
            shoulderY = pose.shoulderY,
            hipY = pose.hipY,
            noseY = pose.noseY,
            kneeY = pose.kneeY,
            ankleY = pose.ankleY,
        )
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

    private fun Sample.toMeasure(): PoseMeasure = PoseMeasure(
        cx = cx,
        cy = cy,
        angleDeg = angleDeg,
        torsoLen = torsoLen,
        shoulderY = shoulderY,
        hipY = hipY,
        noseY = noseY,
        kneeY = kneeY,
        ankleY = ankleY,
    )

    private fun rememberStanding(sample: Sample, cfg: FallConfig) {
        if (sample.angleDeg < cfg.uprightAngleDeg || sample.torsoLen < MIN_TORSO_LEN) return
        if (standingLen <= 0f) {
            standingLen = sample.torsoLen
        } else if (sample.torsoLen >= standingLen * STANDING_LEN_KEEP) {
            standingLen = 0.9f * standingLen + 0.1f * sample.torsoLen
        }
    }

    private fun clearLie() {
        lieSince = null
        lieRef = null
        lieFlat = false
        lieSteep = false
        lieDrop = 0f
    }

    /** Upright angle and a torso about as long as when this person was standing. */
    private fun standingAgain(sample: Sample, cfg: FallConfig): Boolean {
        if (floorCy > 0f && sample.cy > floorCy - STEEP_REARM_RISE) return false
        if (sample.angleDeg < cfg.uprightAngleDeg) return false
        if (standingLen > 0f && sample.torsoLen < standingLen * STANDING_LEN_KEEP) return false
        floorCy = 0f
        return true
    }

    private fun refIsFullHeight(ref: Sample): Boolean {
        if (standingLen <= 0f) return ref.torsoLen >= MIN_TORSO_LEN
        return ref.torsoLen >= standingLen * 0.80f
    }

    private fun stillDown(sample: Sample, cfg: FallConfig): Boolean {
        val ref = lieRef
        if (lieSteep) {
            if (sample.angleDeg < cfg.steepMinAngleDeg - STEEP_ANGLE_HYSTERESIS) return false
            val dropped = ref != null && sample.cy >= ref.cy + lieDrop * STEEP_DROP_KEEP
            return dropped
        }
        val flatStay = lieFlat && sample.angleDeg <= cfg.lieAngleReleaseDeg
        val lenStay = if (ref == null || cfg.maxTorsoLenRatio <= 0f || ref.torsoLen < MIN_TORSO_LEN) {
            false
        } else {
            val limit = (cfg.maxTorsoLenRatio + TORSO_RATIO_HYSTERESIS).coerceAtMost(TORSO_RATIO_STAY_CAP)
            sample.torsoLen / ref.torsoLen <= limit
        }
        val heightStay = if (ref == null || cfg.maxBodyHeightRatio <= 0f) {
            false
        } else {
            val ratio = bodyHeightRatio(ref.toMeasure(), sample.toMeasure())
            val limit = (cfg.maxBodyHeightRatio + HEIGHT_RATIO_HYSTERESIS).coerceAtMost(HEIGHT_RATIO_STAY_CAP)
            ratio != null && ratio <= limit
        }
        return flatStay || lenStay || heightStay
    }

    private companion object {
        const val MIN_TORSO_LEN = 0.04f
        const val STANDING_LEN_KEEP = 0.85f
        const val TORSO_RATIO_HYSTERESIS = 0.12f
        const val TORSO_RATIO_STAY_CAP = 0.78f
        const val HEIGHT_RATIO_HYSTERESIS = 0.08f
        const val HEIGHT_RATIO_STAY_CAP = 0.58f
        const val STEEP_ANGLE_HYSTERESIS = 5f
        const val STEEP_DROP_KEEP = 0.6f
        const val STEEP_REARM_RISE = 0.10f

        /** Torso joints. One live shoulder or hip is required before a frame counts. */
        val TORSO_INDEXES = intArrayOf(
            PoseJoints.LEFT_SHOULDER,
            PoseJoints.RIGHT_SHOULDER,
            PoseJoints.LEFT_HIP,
            PoseJoints.RIGHT_HIP,
        )

        val TRACKED_SLOTS = intArrayOf(
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

data class TorsoAxis(
    val cx: Float,
    val cy: Float,
    val angleDeg: Float,
)

/**
 * Torso plus the optional head, knee, and ankle heights used by the view-robust checks.
 * [noseY], [kneeY], and [ankleY] are null when that joint is missing. Knee and ankle
 * are the mean of whichever side is visible.
 */
data class PoseMeasure(
    val cx: Float,
    val cy: Float,
    val angleDeg: Float,
    val torsoLen: Float,
    val shoulderY: Float,
    val hipY: Float,
    val noseY: Float?,
    val kneeY: Float?,
    val ankleY: Float?,
)

/**
 * Shoulder/hip axis from whichever of the four torso joints clear [minVisibility].
 * Needs at least one shoulder and one hip. The center is the midpoint of the
 * visible shoulder group and the visible hip group. Torso length is that same
 * shoulder-group to hip-group distance, so a body pointing at the camera gets short
 * even when the axis stays steep.
 */
fun poseMeasure(landmarks: List<JointPoint>, minVisibility: Float = DEFAULT_MIN_VISIBILITY): PoseMeasure? {
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
    return PoseMeasure(
        cx = (sx + hx) / 2f,
        cy = (sy + hy) / 2f,
        angleDeg = angle,
        torsoLen = hypot((sx - hx).toDouble(), (sy - hy).toDouble()).toFloat(),
        shoulderY = sy,
        hipY = hy,
        noseY = landmarks.getOrNull(PoseJoints.NOSE)?.takeIf { it.visibility >= minVisibility }?.y,
        kneeY = meanY(landmarks, minVisibility, PoseJoints.LEFT_KNEE, PoseJoints.RIGHT_KNEE),
        ankleY = meanY(landmarks, minVisibility, PoseJoints.LEFT_ANKLE, PoseJoints.RIGHT_ANKLE),
    )
}

fun torsoAxis(landmarks: List<JointPoint>, minVisibility: Float = DEFAULT_MIN_VISIBILITY): TorsoAxis? {
    val pose = poseMeasure(landmarks, minVisibility) ?: return null
    return TorsoAxis(cx = pose.cx, cy = pose.cy, angleDeg = pose.angleDeg)
}

/**
 * Current body height divided by the reference height. Only joints present in both
 * poses are used. Returns null unless a head, knee, or ankle is in that set, so a
 * torso-only pose cannot pretend the whole body got shorter.
 */
fun bodyHeightRatio(ref: PoseMeasure, cur: PoseMeasure): Float? {
    val refYs = ArrayList<Float>(5)
    val curYs = ArrayList<Float>(5)
    fun add(a: Float?, b: Float?) {
        if (a != null && b != null) {
            refYs += a
            curYs += b
        }
    }
    add(ref.noseY, cur.noseY)
    add(ref.shoulderY, cur.shoulderY)
    add(ref.hipY, cur.hipY)
    add(ref.kneeY, cur.kneeY)
    add(ref.ankleY, cur.ankleY)
    val extra = (ref.noseY != null && cur.noseY != null) ||
        (ref.kneeY != null && cur.kneeY != null) ||
        (ref.ankleY != null && cur.ankleY != null)
    if (!extra || refYs.size < 2) return null
    val refSpan = refYs.max() - refYs.min()
    val curSpan = curYs.max() - curYs.min()
    if (refSpan < 0.02f) return null
    return curSpan / refSpan
}

private fun meanY(landmarks: List<JointPoint>, minVisibility: Float, vararg indexes: Int): Float? {
    var sum = 0.0
    var count = 0
    for (index in indexes) {
        val joint = landmarks.getOrNull(index) ?: continue
        if (joint.visibility < minVisibility) continue
        sum += joint.y.toDouble()
        count += 1
    }
    if (count == 0) return null
    return (sum / count).toFloat()
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
