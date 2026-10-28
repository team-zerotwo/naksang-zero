package kr.zerotwo.naksang.detect

/**
 * Which suspicion would start the countdown on this frame.
 * [RULE] is the flat or collapsed landing. [VIEW_DIRECTION] is the steep
 * torso path (the body points along the camera axis).
 */
enum class DetectSignal {
    RULE,
    VIEW_DIRECTION,
    MODEL,
    LYING_STILL,
    FAST_FLATTEN,
    /** Model-test log only. Not a detection and never starts a real alert. */
    ALERT_STAGE,
    ;

    fun korean(): String = when (this) {
        RULE -> "규칙"
        VIEW_DIRECTION -> "시선 방향"
        MODEL -> "포즈 모델"
        LYING_STILL -> "바닥에 누움"
        FAST_FLATTEN -> "빠른 눕힘"
        ALERT_STAGE -> ALERT_STAGE_NOTE
    }

    fun code(): String = when (this) {
        RULE -> "rule"
        VIEW_DIRECTION -> "view_direction"
        MODEL -> "model"
        LYING_STILL -> "lying_still"
        FAST_FLATTEN -> "fast_flatten"
        ALERT_STAGE -> "alert_stage"
    }
}

/** Shown on the model-test screen only. Production never sends this. */
const val ALERT_STAGE_NOTE = "알림 단계에 도달(테스트 화면이라 실제 알림은 보내지 않음)"

/** Read-only view of [FallDetector] after the latest frame. Detection does not read this. */
data class RulePathDebug(
    val measured: Boolean = false,
    val angleDeg: Float? = null,
    val hipY: Float? = null,
    val kneeY: Float? = null,
    val ruleLatched: Boolean = false,
    val viewLatched: Boolean = false,
    val stillElapsedMs: Long = 0,
    val stillNeedMs: Long = 2_000,
    val ruleFired: Boolean = false,
    val viewFired: Boolean = false,
)

/** Read-only view of [LyingStillDetector] after the latest frame. */
data class LyingPathDebug(
    val counting: Boolean = false,
    val elapsedMs: Long = 0,
    val thresholdMs: Long = 9_000,
    val witnessedFast: Boolean = false,
    val fired: Boolean = false,
    /** True when the short-torso supine check, not the flat angle, is what counts as lying. */
    val auxiliary: Boolean = false,
)

/** Read-only view of [PoseFallScorer] after the latest frame. */
data class ModelPathDebug(
    val enabled: Boolean = true,
    val score: Double = 0.0,
    val gbdtScore: Double = 0.0,
    val threshold: Double = 0.99,
    val streak: Int = 0,
    val holdFrames: Int = 2,
    val fired: Boolean = false,
)

/**
 * One analysis frame, for the test screen. [wouldFire] is the signals whose
 * production boolean is true on this frame. Nothing here starts a countdown.
 */
data class FallDebugSnapshot(
    val personDetected: Boolean,
    val posture: PostureLabel,
    val torsoAngleDeg: Float?,
    val poseConfidence: Float?,
    val lyingElapsedMs: Long,
    val lyingThresholdMs: Long,
    val lyingUnwitnessedMs: Long,
    val lyingCounting: Boolean,
    val lyingWitnessedFast: Boolean,
    val lyingEnabled: Boolean,
    val modelScore: Double,
    val modelGbdtScore: Double = 0.0,
    val modelGbdtThreshold: Double = 0.0,
    val modelThreshold: Double,
    val modelStreak: Int,
    val modelHoldFrames: Int,
    val modelEnabled: Boolean,
    val ruleLatched: Boolean,
    val viewLatched: Boolean,
    val ruleStillElapsedMs: Long,
    val ruleStillNeedMs: Long,
    val wouldFire: Set<DetectSignal>,
    /** Short-torso supine path is the reason this frame counts as lying. */
    val lyingAuxiliary: Boolean = false,
    /** Configured fast-descent stillness. Stays 9s when the active clock is the slow path. */
    val lyingFastMs: Long = 9_000,
) {
    companion object {
        fun initial(config: FallConfig = FallConfig()): FallDebugSnapshot = FallDebugSnapshot(
            personDetected = false,
            posture = PostureLabel.NONE,
            torsoAngleDeg = null,
            poseConfidence = null,
            lyingElapsedMs = 0,
            lyingThresholdMs = config.lyingStillMs,
            lyingUnwitnessedMs = config.lyingStillUnwitnessedMs,
            lyingCounting = false,
            lyingWitnessedFast = false,
            lyingEnabled = config.lyingStillEnabled,
            modelScore = 0.0,
            modelGbdtScore = 0.0,
            modelGbdtThreshold = PoseFallWeights.GBDT_THRESHOLD,
            modelThreshold = PoseFallWeights.THRESHOLD,
            modelStreak = 0,
            modelHoldFrames = PoseFallWeights.HOLD_FRAMES,
            modelEnabled = config.modelEnabled,
            ruleLatched = false,
            viewLatched = false,
            ruleStillElapsedMs = 0,
            ruleStillNeedMs = config.stillDurationMs,
            wouldFire = emptySet(),
            lyingAuxiliary = false,
            lyingFastMs = config.lyingStillMs,
        )
    }
}

data class FallFrame(
    /** True when production would call the countdown. Same OR as the three detectors. */
    val suspected: Boolean,
    val debug: FallDebugSnapshot,
)

/** Mean landmark visibility in 0..1. Null when this frame has no pose. */
fun meanLandmarkVisibility(joints: Array<FloatArray>?): Float? {
    if (joints == null || joints.isEmpty()) return null
    var sum = 0.0
    var count = 0
    for (row in joints) {
        val visibility = row.getOrNull(2) ?: continue
        if (visibility <= 0f) continue
        sum += visibility.toDouble()
        count += 1
    }
    if (count == 0) return null
    return (sum / count).toFloat()
}

fun rulePathStatus(fired: Boolean, latched: Boolean): String = when {
    fired -> "지금 발화"
    latched -> "진행 중"
    else -> "대기"
}

fun modelPathStatus(enabled: Boolean, fired: Boolean, streak: Int): String = when {
    !enabled -> "꺼짐"
    fired -> "지금 발화"
    streak > 0 -> "진행 중"
    else -> "대기"
}

fun lyingPathStatus(enabled: Boolean, fired: Boolean, counting: Boolean): String = when {
    !enabled -> "꺼짐"
    fired -> "지금 발화"
    counting -> "진행 중"
    else -> "대기"
}

fun liveTriggerLine(wouldFire: Set<DetectSignal>): String {
    if (wouldFire.isEmpty()) return "이번 프레임에는 발화할 조건이 없어요"
    return "이번 프레임에 발화할 조건 · " + wouldFire.joinToString(" · ") { it.korean() }
}

/** The lying clock is running, or this frame is the one that finished it. */
fun lyingClockActive(snapshot: FallDebugSnapshot): Boolean {
    return snapshot.lyingEnabled &&
        (snapshot.lyingCounting || DetectSignal.LYING_STILL in snapshot.wouldFire)
}

/**
 * Which stillness limit applies right now.
 * 「해당 없음」 when the person is not in the lying clock.
 */
fun lyingRouteLabel(snapshot: FallDebugSnapshot): String {
    if (!lyingClockActive(snapshot)) return "해당 없음"
    val seconds = wholeSeconds(snapshot.lyingThresholdMs)
    return if (snapshot.lyingWitnessedFast) {
        "빠르게 눕는 걸 봄: ${seconds}초"
    } else {
        "못 봄: ${seconds}초"
    }
}

/** Milliseconds left on the active lying clock. Null when that clock is not running. */
fun lyingRemainMs(snapshot: FallDebugSnapshot): Long? {
    if (!lyingClockActive(snapshot)) return null
    return (snapshot.lyingThresholdMs - snapshot.lyingElapsedMs).coerceAtLeast(0L)
}

/** Whole seconds when the value lands on a second, otherwise one decimal. */
fun wholeSeconds(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    if (clamped % 1000L == 0L) return (clamped / 1000L).toString()
    return formatSeconds(clamped)
}
