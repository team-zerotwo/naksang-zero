package kr.zerotwo.naksang.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class KoreanTimeTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun watchingCaptionAndDuration() {
        val morning = LocalDateTime.of(2026, 10, 26, 7, 0).atZone(zone).toInstant().toEpochMilli()
        val afternoon = LocalDateTime.of(2026, 10, 26, 15, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("오늘 오전 7시부터 계속", KoreanTime.watchingCaption(morning, afternoon, zone))
        assertEquals("8.5h", KoreanTime.durationLabel(morning, afternoon))
        assertEquals("오늘 오후 3:42", KoreanTime.sentCaption(
            LocalDateTime.of(2026, 10, 26, 15, 42).atZone(zone).toInstant().toEpochMilli(),
            afternoon,
            zone,
        ))
    }

    @Test
    fun heartbeatCaption() {
        val now = 1_000_000L
        assertEquals("오프라인 데모", KoreanTime.heartbeatCaption(now, true, now, demo = true))
        assertEquals("방금 확인", KoreanTime.heartbeatCaption(now - 5_000, true, now, demo = false))
        assertEquals("다시 시도 중", KoreanTime.heartbeatCaption(now, false, now, demo = false))
        assertEquals("확인 중", KoreanTime.heartbeatCaption(null, null, now, demo = false))
    }
}
