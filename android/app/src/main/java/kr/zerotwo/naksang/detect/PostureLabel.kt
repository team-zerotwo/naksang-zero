package kr.zerotwo.naksang.detect

/**
 * Coarse label for the model-test screen. This does not feed the detector.
 *
 * Lying uses the same torso-angle cut as [FallConfig.maxLieAngleDeg].
 * Standing is a steep torso (at or above [FallConfig.lyingHighAngleDeg]) whose
 * thigh still points down. Sitting is either a steep torso with a folded
 * thigh, or a mid angle that is neither lying nor clearly upright.
 * A missing torso is [PostureLabel.NONE].
 */
enum class PostureLabel {
    STANDING,
    SITTING,
    LYING,
    NONE,
    ;

    fun korean(): String = when (this) {
        STANDING -> "서 있음"
        SITTING -> "앉아 있음"
        LYING -> "누워 있음"
        NONE -> "없음"
    }

    fun code(): String = when (this) {
        STANDING -> "standing"
        SITTING -> "sitting"
        LYING -> "lying"
        NONE -> "none"
    }
}

/**
 * Image-y of the knee minus the hip. At or below this, the thigh is folded
 * and the test screen says sitting even when the torso is steep.
 * Display only. The fall rule does not read it.
 */
const val SIT_THIGH_DROP = 0.10f

fun postureLabel(
    measured: Boolean,
    torsoAngleDeg: Float?,
    hipY: Float?,
    kneeY: Float?,
    lieAngleDeg: Float,
    highAngleDeg: Float,
): PostureLabel {
    if (!measured || torsoAngleDeg == null) return PostureLabel.NONE
    if (torsoAngleDeg <= lieAngleDeg) return PostureLabel.LYING
    val thighDrop = if (hipY != null && kneeY != null) kneeY - hipY else null
    if (thighDrop != null && thighDrop <= SIT_THIGH_DROP) return PostureLabel.SITTING
    if (torsoAngleDeg >= highAngleDeg) return PostureLabel.STANDING
    return PostureLabel.SITTING
}
