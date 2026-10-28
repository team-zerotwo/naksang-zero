package kr.zerotwo.naksang.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CountdownLayoutTest {
    @Test
    fun `링은 보통 화면에서 320이고 짧은 화면에서 220까지 줄인다`() {
        assertEquals(320.dp, countdownRingSize(800.dp))
        assertEquals(320.dp, countdownRingSize(600.dp))
        assertEquals(260.dp, countdownRingSize(540.dp))
        assertEquals(220.dp, countdownRingSize(400.dp))
    }
}
