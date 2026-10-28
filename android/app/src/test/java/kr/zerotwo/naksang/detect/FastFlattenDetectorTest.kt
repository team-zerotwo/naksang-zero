package kr.zerotwo.naksang.detect

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fast collapse into a flat torso holds for 2 seconds. A slow lie-down
 * and a torso that stays steep do not. The 9 second floor timer is not involved.
 */
class FastFlattenDetectorTest {
    @Test
    fun `빨리 평평해지고 그대로 있으면 발화`() {
        val fired = FastFlattenDetector().feed(hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_400, until = 5_000))
        assertTrue(fired != null && fired >= 1_400 + FastFlattenDetector.CONFIRM_MS - 250)
    }

    @Test
    fun `천천히 누우면 발화하지 않음`() {
        val fired = FastFlattenDetector().feed(hold(stand(), lying(), leadIn = 1_000, transitionEnd = 8_000, until = 12_000))
        assertNull(fired)
    }

    @Test
    fun `앉기는 몸통이 가파라서 발화하지 않음`() {
        val fired = FastFlattenDetector().feed(hold(stand(), sitting(), leadIn = 1_000, transitionEnd = 1_500, until = 6_000))
        assertNull(fired)
    }
}

private fun FastFlattenDetector.feed(frames: List<Pair<Long, Array<FloatArray>>>): Long? {
    var fired: Long? = null
    for ((t, joints) in frames) {
        if (onJointArray(t, joints) && fired == null) fired = t
    }
    return fired
}

private fun hold(
    from: Array<FloatArray>,
    to: Array<FloatArray>,
    leadIn: Long,
    transitionEnd: Long,
    until: Long,
): List<Pair<Long, Array<FloatArray>>> {
    val frames = ArrayList<Pair<Long, Array<FloatArray>>>()
    var t = 0L
    while (t < leadIn) {
        frames += t to from
        t += 200
    }
    val span = (transitionEnd - leadIn).coerceAtLeast(1)
    while (t < transitionEnd) {
        val u = (t - leadIn).toFloat() / span
        frames += t to lerp(from, to, u)
        t += 200
    }
    while (t <= until) {
        frames += t to to
        t += 200
    }
    return frames
}

private fun lerp(a: Array<FloatArray>, b: Array<FloatArray>, u: Float): Array<FloatArray> {
    return Array(a.size) { index ->
        val left = a[index]
        val right = b.getOrElse(index) { left }
        floatArrayOf(
            left[0] + (right[0] - left[0]) * u,
            left[1] + (right[1] - left[1]) * u,
            left.getOrElse(2) { 1f },
        )
    }
}

private fun pose(ls: FloatArray, rs: FloatArray, lh: FloatArray, rh: FloatArray): Array<FloatArray> {
    val joints = Array(33) { floatArrayOf(0.5f, 0.5f, 0f) }
    joints[PoseJoints.LEFT_SHOULDER] = ls
    joints[PoseJoints.RIGHT_SHOULDER] = rs
    joints[PoseJoints.LEFT_HIP] = lh
    joints[PoseJoints.RIGHT_HIP] = rh
    return joints
}

private fun stand() = pose(
    floatArrayOf(0.42f, 0.20f, 1f),
    floatArrayOf(0.58f, 0.20f, 1f),
    floatArrayOf(0.44f, 0.48f, 1f),
    floatArrayOf(0.56f, 0.48f, 1f),
)

private fun lying() = pose(
    floatArrayOf(0.28f, 0.55f, 1f),
    floatArrayOf(0.36f, 0.52f, 1f),
    floatArrayOf(0.62f, 0.58f, 1f),
    floatArrayOf(0.72f, 0.54f, 1f),
)

private fun sitting() = pose(
    floatArrayOf(0.42f, 0.40f, 1f),
    floatArrayOf(0.58f, 0.40f, 1f),
    floatArrayOf(0.44f, 0.62f, 1f),
    floatArrayOf(0.56f, 0.62f, 1f),
)
