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
        assertTrue(row.combinedAt != null && row.combinedAt!! <= row.ruleAt!!)
        assertTrue(row.source == "rule" || row.source == "fast")
        assertTrue(row.lyingAt == null || row.combinedAt!! < row.lyingAt!!)
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

    @Test
    fun `똑바로 누운 합성 자세는 누움이고 바로 낙상이 아니다`() {
        val supineAngle = poseMeasure(supine().toJointPoints())!!.angleDeg
        assertTrue("어깨가 엉덩이 위에 있어 각도가 커야 합니다. angle=$supineAngle", supineAngle >= 50f)
        val early = hold(standFull(), supine(), leadIn = 1_000, transitionEnd = 1_600, until = 6_000)
        val detector = LyingStillDetector(config)
        assertNull("9초 전에는 발화하지 않습니다", detector.feed(early))
        assertTrue(detector.lyingNow)
        assertTrue(detector.lyingDebug().auxiliary)
        assertTrue(detector.lyingDebug().witnessedFast)
        assertEquals(9_000L, detector.lyingDebug().thresholdMs)
        val analysis = FallAnalysis(config)
        var sawLyingPosture = false
        var firedAt: Long? = null
        for ((time, joints) in early) {
            val frame = analysis.onFrame(time, joints)
            if (frame.debug.lyingAuxiliary) {
                sawLyingPosture = true
                assertEquals(PostureLabel.LYING, frame.debug.posture)
            }
            if (frame.suspected && DetectSignal.LYING_STILL in frame.debug.wouldFire) firedAt = time
        }
        assertTrue(sawLyingPosture)
        assertNull(firedAt)
        val fired = LyingStillDetector(config).feed(
            hold(standFull(), supine(), leadIn = 1_000, transitionEnd = 1_600, until = 16_000),
        )
        assertTrue("빠른 하강 뒤 9초. fired=$fired", fired != null && fired >= 8_000 && fired <= 20_000)
    }

    @Test
    fun `앉은 합성 자세는 누움이 아니다`() {
        val frames = hold(standFull(), sittingFull(), leadIn = 1_000, transitionEnd = 2_200, until = 20_000)
        val detector = LyingStillDetector(config)
        assertNull(detector.feed(frames))
        assertTrue(!detector.lyingNow)
        assertTrue(!detector.sawLying)
        assertTrue(!detector.lyingDebug().auxiliary)
    }

    @Test
    fun `서 있는 자세는 누움이 아니다`() {
        val frames = (0L..10_000L step 200L).map { it to standFull() }
        val detector = LyingStillDetector(config)
        assertNull(detector.feed(frames))
        assertTrue(!detector.lyingNow)
        assertTrue(!detector.sawLying)
    }

    @Test
    fun `멀리 서 있거나 쪼그린 자세는 누움이 아니다`() {
        val away = hold(standFull(), farStand(), leadIn = 1_000, transitionEnd = 2_000, until = 12_000)
        val squat = hold(standFull(), squatFull(), leadIn = 1_000, transitionEnd = 1_600, until = 12_000)
        assertNull(LyingStillDetector(config).feed(away))
        assertTrue(!LyingStillDetector(config).also { it.feed(away) }.sawLying)
        assertNull(LyingStillDetector(config).feed(squat))
        assertTrue(!LyingStillDetector(config).also { it.feed(squat) }.sawLying)
    }

    @Test
    fun `처음부터 똑바로 누우면 45초 경로다`() {
        val brief = (0L..12_000L step 200L).map { it to supine() }
        val detector = LyingStillDetector(config)
        assertNull(detector.feed(brief))
        assertTrue(detector.lyingNow)
        assertTrue(detector.lyingDebug().auxiliary)
        assertTrue(!detector.lyingDebug().witnessedFast)
        assertEquals(45_000L, detector.lyingDebug().thresholdMs)
        val fired = LyingStillDetector(config).feed((0L..46_000L step 200L).map { it to supine() })
        assertTrue("처음 본 똑바로 누움. fired=$fired", fired != null && fired >= 45_000)
    }

    @Test
    fun `보조 경로를 끄면 똑바로 누움은 누움이 아니다`() {
        val frames = hold(standFull(), supine(), leadIn = 1_000, transitionEnd = 1_600, until = 16_000)
        val off = FallConfig(smoothAlpha = 1f, supineAuxEnabled = false)
        val detector = LyingStillDetector(off)
        assertNull(detector.feed(frames))
        assertTrue(!detector.sawLying)
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

private fun full(
    ls: Pair<Float, Float>,
    rs: Pair<Float, Float>,
    lh: Pair<Float, Float>,
    rh: Pair<Float, Float>,
    lk: Pair<Float, Float>,
    rk: Pair<Float, Float>,
    la: Pair<Float, Float>,
    ra: Pair<Float, Float>,
): Array<FloatArray> {
    val joints = pose(floatArrayOf(ls.first, ls.second), floatArrayOf(rs.first, rs.second), floatArrayOf(lh.first, lh.second), floatArrayOf(rh.first, rh.second))
    fun put(index: Int, point: Pair<Float, Float>) {
        joints[index] = floatArrayOf(point.first, point.second, 1f)
    }
    put(PoseJoints.LEFT_KNEE, lk)
    put(PoseJoints.RIGHT_KNEE, rk)
    put(PoseJoints.LEFT_ANKLE, la)
    put(PoseJoints.RIGHT_ANKLE, ra)
    return joints
}

/** Frontal stand. Torso is long next to the shoulder width, legs hang down. */
private fun standFull() = full(
    ls = 0.42f to 0.18f,
    rs = 0.58f to 0.18f,
    lh = 0.44f to 0.46f,
    rh = 0.56f to 0.46f,
    lk = 0.43f to 0.68f,
    rk = 0.57f to 0.68f,
    la = 0.42f to 0.90f,
    ra = 0.58f to 0.90f,
)

/** Shoulders stay wide and above the hips, but the torso is short and the legs are up by the hips. */
private fun supine() = full(
    ls = 0.40f to 0.50f,
    rs = 0.60f to 0.50f,
    lh = 0.46f to 0.56f,
    rh = 0.54f to 0.56f,
    lk = 0.48f to 0.58f,
    rk = 0.52f to 0.58f,
    la = 0.47f to 0.60f,
    ra = 0.53f to 0.60f,
)

/** Short torso toward the camera, lower legs still on the floor. */
private fun sittingFull() = full(
    ls = 0.40f to 0.36f,
    rs = 0.60f to 0.36f,
    lh = 0.47f to 0.44f,
    rh = 0.53f to 0.44f,
    lk = 0.36f to 0.70f,
    rk = 0.64f to 0.70f,
    la = 0.34f to 0.88f,
    ra = 0.66f to 0.88f,
)

/** Same proportions as standing, smaller in the frame. */
private fun farStand() = full(
    ls = 0.46f to 0.32f,
    rs = 0.54f to 0.32f,
    lh = 0.47f to 0.44f,
    rh = 0.53f to 0.44f,
    lk = 0.47f to 0.53f,
    rk = 0.53f to 0.53f,
    la = 0.47f to 0.62f,
    ra = 0.53f to 0.62f,
)

/** Knees come up, ankles stay down, torso shortens. */
private fun squatFull() = full(
    ls = 0.42f to 0.42f,
    rs = 0.58f to 0.42f,
    lh = 0.46f to 0.50f,
    rh = 0.54f to 0.50f,
    lk = 0.32f to 0.48f,
    rk = 0.68f to 0.48f,
    la = 0.40f to 0.78f,
    ra = 0.60f to 0.78f,
)

private fun shift(pose: Array<FloatArray>, dx: Float, dy: Float): Array<FloatArray> {
    return Array(pose.size) { index ->
        floatArrayOf(pose[index][0] + dx, pose[index][1] + dy, pose[index][2])
    }
}
