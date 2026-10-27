package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Joint arrays are MediaPipe image coordinates: y grows downward.
 * Standing shoulders sit above the hips. A fall moves that center down
 * and lays the shoulder–hip axis flat, then holds still.
 */
class FallDetectorTest {
    @Test
    fun `서있다 넘어짐 정지 = 낙상`() {
        val fired = FallDetector().feed(hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_500, until = 5_000))
        assertTrue("낙상으로 봐야 합니다. fired=$fired", fired != null)
        assertTrue(fired!! >= 1_500 + 2_000 - 200)
    }

    @Test
    fun `앉기 = 낙상 아님`() {
        // The center drops past the threshold, but the torso stays vertical.
        val fired = FallDetector().feed(hold(stand(), sitting(), leadIn = 1_000, transitionEnd = 2_200, until = 6_000))
        assertNull("앉기는 낙상이 아닙니다. fired=$fired", fired)
    }

    @Test
    fun `웅크리기 = 낙상 아님`() {
        // Fast drop, torso still clearly above the lying angle.
        val fired = FallDetector().feed(hold(stand(), squatting(), leadIn = 1_000, transitionEnd = 1_600, until = 6_000))
        assertNull("웅크리기는 낙상이 아닙니다. fired=$fired", fired)
    }

    @Test
    fun `천천히 누우기 = 낙상 아님`() {
        val fired = FallDetector().feed(hold(stand(), lying(), leadIn = 1_000, transitionEnd = 9_000, until = 13_000))
        assertNull("천천히 눕는 것은 낙상이 아닙니다. fired=$fired", fired)
    }

    @Test
    fun `넘어진 직후 일어나면 낙상 아님`() {
        val fall = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_400, until = 2_000)
        val up = (2_100L..5_900L step 100L).map { it to stand() }
        val fired = FallDetector().feed(fall + up)
        assertNull(fired)
    }

    @Test
    fun `처음부터 누워 있으면 낙상 아님`() {
        val frames = (0L..5_000L step 100L).map { it to lying() }
        assertNull(FallDetector().feed(frames))
    }

    @Test
    fun `다시 일어선 뒤에만 다시 감지`() {
        val detector = FallDetector()
        val first = detector.feed(hold(stand(), lying(), 1_000, 1_500, 5_000))
        assertTrue(first != null)
        val stillLying = (5_100L..8_000L step 100L).map { it to lying() }
        assertNull(detector.feed(stillLying))
        val again = detector.feed(
            hold(stand(), lying(), leadIn = 10_000, transitionEnd = 10_500, until = 14_000, start = 8_100),
        )
        assertTrue("일어선 뒤의 두 번째 낙상. fired=$again", again != null)
    }

    @Test
    fun `관절이 안 보이면 예외 없이 무시`() {
        val detector = FallDetector()
        assertEquals(false, detector.onJointArray(0L, emptyArray()))
        val hidden = stand().map { it.copyOf().also { row -> row[2] = 0.1f } }.toTypedArray()
        assertEquals(false, detector.onJointArray(100L, hidden))
    }

    @Test
    fun `기준값을 높이면 같은 넘어짐도 낙상이 아님`() {
        val strict = FallConfig(minDrop = 0.5f)
        val fired = FallDetector(strict).feed(hold(stand(), lying(), 1_000, 1_500, 5_000))
        assertNull(fired)
    }

    @Test
    fun `한쪽 어깨와 엉덩이만 보여도 낙상`() {
        val frames = hold(stand(), lying(), 1_000, 1_500, 5_000).map { (t, pose) ->
            t to withVisibility(pose, PoseJoints.RIGHT_SHOULDER, PoseJoints.RIGHT_HIP, visibility = 0.1f)
        }
        val fired = FallDetector().feed(frames)
        assertTrue("한쪽만 보여도 낙상입니다. fired=$fired", fired != null)
    }

    @Test
    fun `가시성 0_35는 보고 그 아래는 버린다`() {
        val seen = hold(stand(), lying(), 1_000, 1_500, 5_000).map { (t, pose) ->
            t to setTorsoVisibility(pose, 0.35f)
        }
        assertTrue(FallDetector().feed(seen) != null)
        val missed = hold(stand(), lying(), 1_000, 1_500, 5_000).map { (t, pose) ->
            t to setTorsoVisibility(pose, 0.34f)
        }
        assertNull(FallDetector().feed(missed))
    }

    @Test
    fun `정지 중 떨림은 낙상을 끊지 않는다`() {
        // ±0.04 is above stillMovement (0.035). Smoothing keeps the center inside it.
        val stable = hold(stand(), lying(), 1_000, 1_500, 2_400)
        val jitter = (2_500L..6_000L step 100L).mapIndexed { index, t ->
            val dy = if (index % 2 == 0) 0.04f else -0.04f
            t to shift(lying(), dx = 0f, dy = dy)
        }
        val fired = FallDetector().feed(stable + jitter)
        assertTrue("떨림보다 평활이 우선입니다. fired=$fired", fired != null)
        val raw = FallDetector(FallConfig(smoothAlpha = 1f)).feed(stable + jitter)
        assertNull("평활 없이는 떨림이 정지를 리셋합니다. fired=$raw", raw)
    }

    @Test
    fun `낙상 도중 몇 프레임이 빠져도 이어서 판정`() {
        val before = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_500, until = 2_200)
        val after = (3_200L..6_000L step 100L).map { it to lying() }
        val fired = FallDetector().feed(before + after)
        assertTrue("1초 공백 뒤에도 낙상입니다. fired=$fired", fired != null)
    }

    @Test
    fun `낙상 끝에 몸통이 잠깐 가려져도 낙상`() {
        val frames = hold(stand(), lying(), 1_000, 1_500, 5_000).map { (t, pose) ->
            if (t in 1_800L..2_600L) {
                t to withVisibility(
                    pose,
                    PoseJoints.LEFT_SHOULDER,
                    PoseJoints.RIGHT_SHOULDER,
                    PoseJoints.LEFT_HIP,
                    visibility = 0.05f,
                )
            } else {
                t to pose
            }
        }
        val fired = FallDetector().feed(frames)
        assertTrue("가려진 뒤에도 낙상입니다. fired=$fired", fired != null)
    }

    @Test
    fun `사람을 오래 놓치면 정지 시간을 다시 센다`() {
        val before = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_500, until = 2_200)
        val tooSoon = (5_000L..6_400L step 100L).map { it to lying() }
        assertNull(FallDetector().feed(before + tooSoon))
        val longEnough = (5_000L..8_500L step 100L).map { it to lying() }
        val fired = FallDetector().feed(before + longEnough)
        assertTrue("긴 공백 뒤 다시 멈추면 낙상입니다. fired=$fired", fired != null)
    }

    @Test
    fun `어깨만 보이면 낙상도 중심도 없다`() {
        val frames = hold(stand(), lying(), 1_000, 1_500, 4_000).map { (t, pose) ->
            t to withVisibility(pose, PoseJoints.LEFT_HIP, PoseJoints.RIGHT_HIP, visibility = 0f)
        }
        assertNull(FallDetector().feed(frames))
        val shouldersOnly = stand()
        shouldersOnly[PoseJoints.LEFT_HIP][2] = 0f
        shouldersOnly[PoseJoints.RIGHT_HIP][2] = 0f
        assertNull(torsoCenter(shouldersOnly))
    }

    @Test
    fun `몸통 중심은 보이는 한쪽만으로도 나온다`() {
        val pose = stand()
        pose[PoseJoints.RIGHT_SHOULDER][2] = 0f
        pose[PoseJoints.RIGHT_HIP][2] = 0f
        val center = torsoCenter(pose)
        assertEquals(0.43f, center?.first ?: -1f, 0.001f)
        assertEquals(0.34f, center?.second ?: -1f, 0.001f)
    }
}

private fun FallDetector.feed(frames: List<Pair<Long, Array<FloatArray>>>): Long? {
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
    step: Long = 100,
    start: Long = 0,
): List<Pair<Long, Array<FloatArray>>> {
    val frames = mutableListOf<Pair<Long, Array<FloatArray>>>()
    var t = start
    while (t <= until) {
        val pose = when {
            t <= leadIn -> from
            t >= transitionEnd -> to
            else -> lerpPose(from, to, (t - leadIn).toFloat() / (transitionEnd - leadIn))
        }
        frames += t to pose
        t += step
    }
    return frames
}

private fun lerpPose(a: Array<FloatArray>, b: Array<FloatArray>, u: Float): Array<FloatArray> {
    return Array(a.size) { index ->
        floatArrayOf(
            a[index][0] + (b[index][0] - a[index][0]) * u,
            a[index][1] + (b[index][1] - a[index][1]) * u,
            1f,
        )
    }
}

/** [x, y, visibility] for all 33 pose joints. Unused joints stay invisible. */
private fun pose(ls: FloatArray, rs: FloatArray, lh: FloatArray, rh: FloatArray): Array<FloatArray> {
    val joints = Array(33) { floatArrayOf(0f, 0f, 0f) }
    joints[PoseJoints.LEFT_SHOULDER] = floatArrayOf(ls[0], ls[1], 1f)
    joints[PoseJoints.RIGHT_SHOULDER] = floatArrayOf(rs[0], rs[1], 1f)
    joints[PoseJoints.LEFT_HIP] = floatArrayOf(lh[0], lh[1], 1f)
    joints[PoseJoints.RIGHT_HIP] = floatArrayOf(rh[0], rh[1], 1f)
    return joints
}

private fun stand() = pose(
    ls = floatArrayOf(0.42f, 0.20f),
    rs = floatArrayOf(0.58f, 0.20f),
    lh = floatArrayOf(0.44f, 0.48f),
    rh = floatArrayOf(0.56f, 0.48f),
)

private fun lying() = pose(
    ls = floatArrayOf(0.28f, 0.55f),
    rs = floatArrayOf(0.36f, 0.52f),
    lh = floatArrayOf(0.62f, 0.58f),
    rh = floatArrayOf(0.72f, 0.54f),
)

private fun sitting() = pose(
    ls = floatArrayOf(0.42f, 0.38f),
    rs = floatArrayOf(0.58f, 0.38f),
    lh = floatArrayOf(0.44f, 0.70f),
    rh = floatArrayOf(0.56f, 0.70f),
)

private fun squatting() = pose(
    ls = floatArrayOf(0.36f, 0.50f),
    rs = floatArrayOf(0.52f, 0.48f),
    lh = floatArrayOf(0.48f, 0.68f),
    rh = floatArrayOf(0.64f, 0.70f),
)

private fun withVisibility(pose: Array<FloatArray>, vararg hidden: Int, visibility: Float): Array<FloatArray> {
    val copy = pose.map { it.copyOf() }.toTypedArray()
    for (index in hidden) copy[index][2] = visibility
    return copy
}

private fun setTorsoVisibility(pose: Array<FloatArray>, visibility: Float): Array<FloatArray> {
    return withVisibility(
        pose,
        PoseJoints.LEFT_SHOULDER,
        PoseJoints.RIGHT_SHOULDER,
        PoseJoints.LEFT_HIP,
        PoseJoints.RIGHT_HIP,
        visibility = visibility,
    )
}

private fun shift(pose: Array<FloatArray>, dx: Float, dy: Float): Array<FloatArray> {
    return Array(pose.size) { index ->
        floatArrayOf(pose[index][0] + dx, pose[index][1] + dy, pose[index][2])
    }
}
