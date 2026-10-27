package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test screen and production share [FallAnalysis]. These checks keep the
 * would-fire flags on the same frame as the boolean the countdown uses.
 */
class FallAnalysisTest {
    @Test
    fun `모델과 누움을 끄면 규칙 시각이 FallDetector 와 같다`() {
        val config = FallConfig(modelEnabled = false, lyingStillEnabled = false)
        val frames = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_500, until = 5_000)
        assertEquals(firstRule(frames, config), firstSuspicion(frames, config))
        val signals = signalsAtFire(frames, config)
        assertEquals(setOf(DetectSignal.RULE), signals)
    }

    @Test
    fun `시선 방향 낙상은 시선 방향만 발화한다`() {
        val config = FallConfig(modelEnabled = false, lyingStillEnabled = false)
        val frames = hold(stand(), foreshortened(), leadIn = 1_000, transitionEnd = 1_500, until = 5_000)
        assertEquals(firstRule(frames, config), firstSuspicion(frames, config))
        assertEquals(setOf(DetectSignal.VIEW_DIRECTION), signalsAtFire(frames, config))
    }

    @Test
    fun `빠르게 누워 있으면 바닥에 누움도 발화한다`() {
        val config = FallConfig(modelEnabled = false, smoothAlpha = 1f)
        val frames = hold(stand(), lying(), leadIn = 1_000, transitionEnd = 1_600, until = 14_000)
        val seen = mutableSetOf<DetectSignal>()
        val analysis = FallAnalysis(config)
        for ((time, joints) in frames) {
            val frame = analysis.onFrame(time, joints)
            assertEquals(frame.suspected, frame.debug.wouldFire.isNotEmpty())
            seen += frame.debug.wouldFire
        }
        assertTrue(DetectSignal.LYING_STILL in seen)
        assertTrue(DetectSignal.RULE in seen)
    }

    @Test
    fun `사람이 없으면 자세는 없음이고 신뢰도는 없다`() {
        val frame = FallAnalysis().onFrame(0L, null)
        assertEquals(PostureLabel.NONE, frame.debug.posture)
        assertEquals(false, frame.debug.personDetected)
        assertNull(frame.debug.poseConfidence)
        assertNull(frame.debug.torsoAngleDeg)
        assertTrue(frame.debug.wouldFire.isEmpty())
    }

    @Test
    fun `서 있는 한 프레임의 자세 이름은 서 있음`() {
        val frame = FallAnalysis(FallConfig(smoothAlpha = 1f)).onFrame(0L, stand())
        assertEquals(PostureLabel.STANDING, frame.debug.posture)
        assertTrue(frame.debug.personDetected)
        assertTrue(frame.debug.torsoAngleDeg!! > 70f)
        assertEquals(1f, frame.debug.poseConfidence!!, 0.001f)
    }

    private fun firstSuspicion(frames: List<Pair<Long, Array<FloatArray>>>, config: FallConfig): Long? {
        val analysis = FallAnalysis(config)
        for ((time, joints) in frames) {
            val frame = analysis.onFrame(time, joints)
            assertEquals(frame.suspected, frame.debug.wouldFire.isNotEmpty())
            if (frame.suspected) return time
        }
        return null
    }

    private fun firstRule(frames: List<Pair<Long, Array<FloatArray>>>, config: FallConfig): Long? {
        val detector = FallDetector(config)
        for ((time, joints) in frames) {
            if (detector.onJointArray(time, joints)) return time
        }
        return null
    }

    private fun signalsAtFire(frames: List<Pair<Long, Array<FloatArray>>>, config: FallConfig): Set<DetectSignal> {
        val analysis = FallAnalysis(config)
        for ((time, joints) in frames) {
            val frame = analysis.onFrame(time, joints)
            if (frame.suspected) return frame.debug.wouldFire
        }
        return emptySet()
    }
}

private fun hold(
    from: Array<FloatArray>,
    to: Array<FloatArray>,
    leadIn: Long,
    transitionEnd: Long,
    until: Long,
    step: Long = 100,
): List<Pair<Long, Array<FloatArray>>> {
    val frames = mutableListOf<Pair<Long, Array<FloatArray>>>()
    var time = 0L
    while (time <= until) {
        val pose = when {
            time <= leadIn -> from
            time >= transitionEnd -> to
            else -> lerpPose(from, to, (time - leadIn).toFloat() / (transitionEnd - leadIn))
        }
        frames += time to pose
        time += step
    }
    return frames
}

private fun lerpPose(a: Array<FloatArray>, b: Array<FloatArray>, fraction: Float): Array<FloatArray> {
    return Array(a.size) { index ->
        floatArrayOf(
            a[index][0] + (b[index][0] - a[index][0]) * fraction,
            a[index][1] + (b[index][1] - a[index][1]) * fraction,
            1f,
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

private fun foreshortened() = pose(
    ls = floatArrayOf(0.48f, 0.58f),
    rs = floatArrayOf(0.52f, 0.59f),
    lh = floatArrayOf(0.49f, 0.66f),
    rh = floatArrayOf(0.53f, 0.67f),
)
