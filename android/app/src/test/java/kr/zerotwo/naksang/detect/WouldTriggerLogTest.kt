package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WouldTriggerLogTest {
    @Test
    fun `최근 100건만 남기고 신호별로 센다`() {
        val log = WouldTriggerLog(limit = 100)
        val events = (0 until 105).map { index -> sample(index.toLong(), if (index % 2 == 0) DetectSignal.RULE else DetectSignal.MODEL) }
        log.addAll(events)
        val kept = log.snapshot()
        assertEquals(100, kept.size)
        assertEquals(5L, kept.first().atEpochMs)
        assertEquals(104L, kept.last().atEpochMs)
        assertEquals(50, log.counts().rule)
        assertEquals(50, log.counts().model)
        assertEquals(0, log.counts().viewDirection)
        assertEquals(0, log.counts().lyingStill)
        assertEquals(100, log.counts().total)
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        assertEquals(0, log.counts().total)
    }

    @Test
    fun `발화 집합을 한 줄씩 기록한다`() {
        val debug = FallDebugSnapshot.initial().copy(
            posture = PostureLabel.LYING,
            torsoAngleDeg = 12f,
            wouldFire = linkedSetOf(DetectSignal.RULE, DetectSignal.LYING_STILL),
            personDetected = true,
            modelScore = 0.2,
            lyingElapsedMs = 9_000,
        )
        val rows = wouldTriggerEvents(1_000L, 2_500L, debug)
        assertEquals(listOf(DetectSignal.RULE, DetectSignal.LYING_STILL), rows.map { it.signal })
        assertEquals(2_500L, rows[0].videoOffsetMs)
        assertEquals(1_000L, rows[0].atEpochMs)
        assertTrue(wouldTriggerEvents(0L, null, FallDebugSnapshot.initial()).isEmpty())
    }

    @Test
    fun `CSV 는 서울 시각과 빈 칸을 그대로 둔다`() {
        val event = sample(0L, DetectSignal.RULE).copy(
            posture = PostureLabel.LYING,
            torsoAngleDeg = 12.34f,
            modelScore = 0.5,
            modelThreshold = 0.99,
            lyingElapsedMs = 1_500,
            lyingThresholdMs = 9_000,
            poseConfidence = null,
            personDetected = true,
            videoOffsetMs = null,
        )
        val csv = formatWouldTriggerCsv(listOf(event), SignalCounts(rule = 1), ZoneId.of("Asia/Seoul"))
        val lines = csv.lines()
        assertEquals("# 낙상제로 모델 테스트", lines[0])
        assertEquals("# 규칙 1, 시선방향 0, 포즈모델 0, 바닥에누움 0, 빠른눕힘 0, 알림단계 0", lines[1])
        assertEquals(
            "time,signal,posture,torso_angle_deg,model_score,model_threshold,lying_elapsed_s,lying_threshold_s,pose_confidence,person,video_offset_ms,note",
            lines[2],
        )
        assertEquals("1970-01-01 09:00:00,rule,lying,12.3,0.500,0.990,1.5,9.0,,1,,", lines[3])
    }

    @Test
    fun `알림 단계 도달은 기록과 내보내기에 남는다`() {
        val event = alertStageEvent(0L)
        assertEquals(ALERT_STAGE_NOTE, event.signal.korean())
        assertEquals("alert_stage", event.signal.code())
        val csv = formatWouldTriggerCsv(listOf(event), SignalCounts(alertStage = 1), ZoneId.of("Asia/Seoul"))
        assertTrue(csv.contains("알림단계 1"))
        assertTrue(csv.contains("alert_stage"))
        assertTrue(csv.contains(ALERT_STAGE_NOTE))
    }

    @Test
    fun `영상 시각은 분 초로 보여 준다`() {
        val event = sample(0L, DetectSignal.VIEW_DIRECTION).copy(videoOffsetMs = 75_400L)
        assertEquals("영상 1:15.4", formatEventClock(event))
        assertEquals("view_direction", DetectSignal.VIEW_DIRECTION.code())
    }

    private fun sample(at: Long, signal: DetectSignal) = WouldTriggerEvent(
        atEpochMs = at,
        videoOffsetMs = null,
        signal = signal,
        posture = PostureLabel.NONE,
        torsoAngleDeg = null,
        modelScore = 0.0,
        modelThreshold = 0.99,
        lyingElapsedMs = 0,
        lyingThresholdMs = 9_000,
        poseConfidence = null,
        personDetected = false,
    )
}
