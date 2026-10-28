package kr.zerotwo.naksang.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class BackLensTest {
    @Test
    fun `초점 거리가 둘이고 짧은 쪽이 확연히 넓으면 초광각`() {
        assertEquals(BackLensChoice.ULTRA_WIDE, chooseBackLens(listOf(4.25f, 1.68f)))
        assertEquals(BackLensChoice.ULTRA_WIDE, chooseBackLens(listOf(5.4f, null, 2.2f)))
    }

    @Test
    fun `렌즈가 하나이거나 초점 거리가 비슷하면 최소 줌`() {
        assertEquals(BackLensChoice.MIN_ZOOM, chooseBackLens(listOf(4.2f)))
        assertEquals(BackLensChoice.MIN_ZOOM, chooseBackLens(listOf(4.2f, 4.0f)))
        assertEquals(BackLensChoice.MIN_ZOOM, chooseBackLens(listOf(null, null)))
        assertEquals(BackLensChoice.MIN_ZOOM, chooseBackLens(emptyList()))
    }
}
