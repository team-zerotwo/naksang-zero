package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PostureLabelTest {
    private val lie = 45f
    private val high = 72f

    @Test
    fun `몸통이 없으면 없음`() {
        assertEquals(PostureLabel.NONE, postureLabel(false, 80f, 0.4f, 0.7f, lie, high))
        assertEquals(PostureLabel.NONE, postureLabel(true, null, null, null, lie, high))
    }

    @Test
    fun `눕는 각도 이하면 누워 있음`() {
        assertEquals(PostureLabel.LYING, postureLabel(true, 45f, 0.6f, 0.7f, lie, high))
        assertEquals(PostureLabel.LYING, postureLabel(true, 10f, null, null, lie, high))
    }

    @Test
    fun `가파르고 허벅지가 내려가면 서 있음`() {
        assertEquals(PostureLabel.STANDING, postureLabel(true, 72f, 0.40f, 0.70f, lie, high))
        assertEquals(PostureLabel.STANDING, postureLabel(true, 88f, null, null, lie, high))
    }

    @Test
    fun `허벅지가 접히거나 중간 각도면 앉아 있음`() {
        assertEquals(PostureLabel.SITTING, postureLabel(true, 80f, 0.60f, 0.66f, lie, high))
        assertEquals(PostureLabel.SITTING, postureLabel(true, 60f, 0.40f, 0.70f, lie, high))
        assertEquals(PostureLabel.SITTING, postureLabel(true, 45.1f, null, null, lie, high))
    }

    @Test
    fun `경로 상태 문구`() {
        assertEquals("지금 발화", rulePathStatus(fired = true, latched = false))
        assertEquals("진행 중", rulePathStatus(fired = false, latched = true))
        assertEquals("대기", rulePathStatus(fired = false, latched = false))
        assertEquals("꺼짐", modelPathStatus(enabled = false, fired = false, streak = 3))
        assertEquals("진행 중", modelPathStatus(enabled = true, fired = false, streak = 1))
        assertEquals("꺼짐", lyingPathStatus(enabled = false, fired = true, counting = true))
        assertEquals("지금 발화", lyingPathStatus(enabled = true, fired = true, counting = false))
        assertEquals("이번 프레임에는 발화할 조건이 없어요", liveTriggerLine(emptySet()))
        assertEquals(
            "이번 프레임에 발화할 조건 · 규칙 · 시선 방향",
            liveTriggerLine(linkedSetOf(DetectSignal.RULE, DetectSignal.VIEW_DIRECTION)),
        )
    }

    @Test
    fun `누움 시계는 9초와 45초와 해당 없음을 보여 준다`() {
        val idle = FallDebugSnapshot.initial()
        assertEquals("해당 없음", lyingRouteLabel(idle))
        assertNull(lyingRemainMs(idle))
        val fast = idle.copy(
            lyingCounting = true,
            lyingWitnessedFast = true,
            lyingElapsedMs = 1_200,
            lyingThresholdMs = 9_000,
        )
        assertEquals("빠르게 눕는 걸 봄: 9초", lyingRouteLabel(fast))
        assertEquals(7_800L, lyingRemainMs(fast))
        val slow = idle.copy(
            lyingCounting = true,
            lyingWitnessedFast = false,
            lyingElapsedMs = 5_000,
            lyingThresholdMs = 45_000,
            lyingUnwitnessedMs = 45_000,
        )
        assertEquals("못 봄: 45초", lyingRouteLabel(slow))
        assertEquals(40_000L, lyingRemainMs(slow))
    }
}
