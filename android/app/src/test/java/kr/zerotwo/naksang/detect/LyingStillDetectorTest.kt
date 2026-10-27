package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lying-still is a second path into the same countdown. It is not the
 * 2 second rule hold. Smooth alpha is 1 so the clock matches the poses.
 */
class LyingStillDetectorTest {
    private val config = FallConfig(smoothAlpha = 1f)

    @Test
    fun `서 있다가 빨리 누워 가만히 있으면 알린다`() {
        val short = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_600, until = 8_000)
        assertNull(LyingStillDetector(config).feed(short))
        val fired = LyingStillDetector(config).feed(
            hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_600, until = 14_000),
        )
        assertTrue("빠른 하강 뒤 9초. fired=$fired", fired != null && fired >= 9_000)
    }

    @Test
    fun `누운 뒤 움직이면 다시 센다`() {
        val down = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_600, until = 3_800)
        val moved = (4_000L..14_000L step 200L).map { t ->
            val pose = if (t < 5_000L) shift(lying(), dx = 0.12f, dy = 0.02f) else lying()
            t to pose
        }
        val tooSoon = LyingStillDetector(config).feed(down + moved.filter { it.first <= 12_000L })
        assertNull("움직임 뒤 9초가 지나기 전. fired=$tooSoon", tooSoon)
        val fired = LyingStillDetector(config).feed(down + moved)
        assertTrue("멈춘 뒤 9초. fired=$fired", fired != null && fired >= 5_000 + 9_000 - 200)
    }

    @Test
    fun `천천히 눕는 침대는 짧은 시간에 알리지 않는다`() {
        val frames = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 8_000, until = 20_000)
        val fired = LyingStillDetector(config).feed(frames)
        assertNull("느린 하강은 45초를 기다립니다. fired=$fired", fired)
        val detector = LyingStillDetector(config)
        detector.feed(frames)
        assertTrue(detector.sawLying)
        assertTrue("느린 하강은 빠른 낙상 경로가 아닙니다", !detector.sawFastDescent)
    }

    @Test
    fun `처음부터 누워 있으면 긴 시간 뒤에만`() {
        val brief = (0L..12_000L step 200L).map { it to lying() }
        assertNull(LyingStillDetector(config).feed(brief))
        val fired = LyingStillDetector(config).feed((0L..46_000L step 200L).map { it to lying() })
        assertTrue("처음 본 자세가 누움. fired=$fired", fired != null && fired >= 45_000)
    }

    @Test
    fun `높은 곳에서 거의 안 내려오면 바닥이 아니다`() {
        val frames = hold(highStand(), highLie(), leadIn = 1_000, transitionEnd = 1_600, until = 16_000)
        assertNull(LyingStillDetector(config).feed(frames))
    }

    @Test
    fun `꺼 두면 알리지 않는다`() {
        val frames = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_600, until = 16_000)
        val off = FallConfig(smoothAlpha = 1f, lyingStillEnabled = false)
        assertNull(LyingStillDetector(off).feed(frames))
    }

    @Test
    fun `처음부터 누운 클립은 규칙 없이 긴 타이머로 잡힌다`() {
        val frames = (0L..46_000L step 200L).map { t ->
            CaucaFallReplay.ReplayFrame(t, sourceFrame = 0, poseFound = true, joints = lying())
        }
        val row = CaucaFallReplay.signals(frames, signalMeta("already_down"))
        assertNull(row.ruleAt)
        assertTrue("lying=${row.lyingAt}", row.lyingAt != null && row.lyingAt >= 45_000)
        assertEquals("lying", row.source)
    }

    @Test
    fun `빠른 낙상은 규칙이 누움 타이머보다 먼저다`() {
        val frames = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_600, until = 16_000).map { (t, pose) ->
            CaucaFallReplay.ReplayFrame(t, sourceFrame = 0, poseFound = true, joints = pose)
        }
        val row = CaucaFallReplay.signals(frames, signalMeta("fast_fall"))
        assertTrue(row.ruleAt != null)
        assertTrue(row.combinedAt == row.ruleAt)
        assertEquals("rule", row.source)
    }

    @Test
    fun `일어나면 초기화되고 다시 누우면 다시 센다`() {
        val first = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_600, until = 4_000)
        val up = (4_200L..6_000L step 200L).map { it to stand() }
        val second = hold(stand(), lying(), leadIn = 7_000, transitionEnd = 7_600, until = 18_000, start = 6_200)
        val detector = LyingStillDetector(config)
        assertNull(detector.feed(first + up))
        val fired = detector.feed(second)
        assertTrue("두 번째 누움. fired=$fired", fired != null && fired >= 7_600 + 8_000)
    }
}

private fun signalMeta(id: String) = CaucaFallReplay.VideoMeta(
    videoId = id,
    subject = "s",
    activity = id,
    kind = "fall",
    activityType = "fall",
    onsetMs = 0L,
    durationMs = 46_000L,
    csvFile = File(id),
)

private fun LyingStillDetector.feed(frames: List<Pair<Long, Array<FloatArray>>>): Long? {
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
    step: Long = 200,
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
            if (a[index][2] > 0f || b[index][2] > 0f) 1f else 0f,
        )
    }
}

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

/** Horizontal, but the center barely drops and stays in the upper half of the frame. */
private fun highStand() = pose(
    ls = floatArrayOf(0.42f, 0.16f),
    rs = floatArrayOf(0.58f, 0.16f),
    lh = floatArrayOf(0.44f, 0.40f),
    rh = floatArrayOf(0.56f, 0.40f),
)

private fun highLie() = pose(
    ls = floatArrayOf(0.28f, 0.30f),
    rs = floatArrayOf(0.36f, 0.29f),
    lh = floatArrayOf(0.62f, 0.32f),
    rh = floatArrayOf(0.72f, 0.30f),
)

private fun shift(pose: Array<FloatArray>, dx: Float, dy: Float): Array<FloatArray> {
    return Array(pose.size) { index ->
        floatArrayOf(pose[index][0] + dx, pose[index][1] + dy, pose[index][2])
    }
}
