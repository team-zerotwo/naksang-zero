package kr.zerotwo.naksang.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneOrientationTest {
    @Test
    fun `경계 근처는 무시하고 1초 동안 분명할 때만 바꾼다`() {
        var state = OrientationSnap()
        state = PhoneOrientation.step(state, 40, 0L)
        assertEquals(0, state.snapped)
        assertNull(state.pending)

        state = PhoneOrientation.step(state, 70, 1_000L)
        assertEquals(0, state.snapped)
        assertEquals(90, state.pending)

        state = PhoneOrientation.step(state, 80, 1_999L)
        assertEquals(0, state.snapped)
        assertEquals(90, state.pending)

        state = PhoneOrientation.step(state, 90, 2_000L)
        assertEquals(90, state.snapped)
        assertNull(state.pending)
    }

    @Test
    fun `흔들리거나 경계로 돌아오면 대기 중인 전환을 취소한다`() {
        var state = PhoneOrientation.step(OrientationSnap(), 100, 0L)
        assertEquals(90, state.pending)
        state = PhoneOrientation.step(state, 45, 400L)
        assertEquals(0, state.snapped)
        assertNull(state.pending)

        state = PhoneOrientation.step(state, 200, 1_000L)
        assertEquals(180, state.pending)
        state = PhoneOrientation.step(state, 10, 1_200L)
        assertEquals(0, state.snapped)
        assertNull(state.pending)
    }

    @Test
    fun `평평하면 마지막 방향을 유지한다`() {
        var state = OrientationSnap(snapped = 270)
        state = PhoneOrientation.step(state, null, 5_000L)
        assertEquals(270, state.snapped)
        assertNull(state.pending)
        state = PhoneOrientation.step(state, -1, 6_000L)
        assertEquals(270, state.snapped)

        state = PhoneOrientation.step(state, 90, 7_000L)
        state = PhoneOrientation.step(state, null, 7_500L)
        assertEquals(270, state.snapped)
        assertNull(state.pending)
    }

    @Test
    fun `270도와 180도도 같은 기준으로 붙잡는다`() {
        var state = OrientationSnap()
        state = PhoneOrientation.step(state, 270, 0L)
        state = PhoneOrientation.step(state, 250, 1_000L)
        assertEquals(270, state.snapped)

        state = PhoneOrientation.step(state, 180, 2_000L)
        state = PhoneOrientation.step(state, 190, 3_000L)
        assertEquals(180, state.snapped)
    }

    @Test
    fun `0도에서는 전면과 후면 모두 보정이 없다`() {
        assertEquals(0, analysisCompensationDegrees(0, frontCamera = true))
        assertEquals(0, analysisCompensationDegrees(0, frontCamera = false))
        assertEquals(0, analysisCompensationDegrees(360, frontCamera = true))
    }

    @Test
    fun `앞뒤 카메라는 가로에서 반대로 돌린다`() {
        assertEquals(90, analysisCompensationDegrees(90, frontCamera = true))
        assertEquals(270, analysisCompensationDegrees(90, frontCamera = false))
        assertEquals(270, analysisCompensationDegrees(270, frontCamera = true))
        assertEquals(90, analysisCompensationDegrees(270, frontCamera = false))
        assertEquals(180, analysisCompensationDegrees(180, frontCamera = true))
        assertEquals(180, analysisCompensationDegrees(180, frontCamera = false))
    }

    @Test
    fun `칸의 한가운데만 분명한 방향으로 본다`() {
        assertEquals(0, PhoneOrientation.clearQuadrant(0))
        assertEquals(0, PhoneOrientation.clearQuadrant(359))
        assertEquals(0, PhoneOrientation.clearQuadrant(30))
        assertNull(PhoneOrientation.clearQuadrant(31))
        assertNull(PhoneOrientation.clearQuadrant(45))
        assertEquals(90, PhoneOrientation.clearQuadrant(90))
        assertEquals(180, PhoneOrientation.clearQuadrant(180))
        assertEquals(270, PhoneOrientation.clearQuadrant(270))
    }

    @Test
    fun `방향 이름은 확인용 한 줄에 쓴다`() {
        assertEquals("세로", PhoneOrientation.label(0))
        assertEquals("가로 왼쪽", PhoneOrientation.label(90))
        assertEquals("거꾸로", PhoneOrientation.label(180))
        assertEquals("가로 오른쪽", PhoneOrientation.label(270))
    }
}
