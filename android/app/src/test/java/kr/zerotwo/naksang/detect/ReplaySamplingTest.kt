package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplaySamplingTest {
    @Test
    fun `5fps 면 200ms 간격이고 끝 시각을 포함한다`() {
        val times = ReplaySampling.frameTimesUs(1_000L, fps = 5)
        assertEquals(longArrayOf(0, 200_000, 400_000, 600_000, 800_000, 1_000_000).toList(), times.toList())
    }

    @Test
    fun `길이나 프레임이 없으면 비어 있다`() {
        assertEquals(0, ReplaySampling.frameTimesUs(0L).size)
        assertEquals(0, ReplaySampling.frameTimesUs(5_000L, fps = 0).size)
    }

    @Test
    fun `30분을 넘기면 자른다`() {
        val times = ReplaySampling.frameTimesUs(31L * 60L * 1_000L, fps = 5)
        assertEquals(5 * 60 * 30, times.size)
    }

    @Test
    fun `1초 창의 다섯 간격은 5fps`() {
        val rate = FrameRate()
        var fps = 0f
        for (t in 0L..1_000L step 200L) fps = rate.mark(t)
        assertEquals(5f, fps, 0.01f)
        rate.reset()
        assertEquals(0f, rate.mark(2_000L), 0.01f)
    }
}
