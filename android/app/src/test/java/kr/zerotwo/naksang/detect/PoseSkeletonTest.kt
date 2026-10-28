package kr.zerotwo.naksang.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseSkeletonTest {
    @Test
    fun `표준 포즈 연결은 몸통 팔 다리를 포함한다`() {
        assertEquals(33, PoseSkeleton.LANDMARK_COUNT)
        assertEquals(35, PoseSkeleton.CONNECTIONS.size)
        val edges = PoseSkeleton.CONNECTIONS.map { (a, b) -> if (a < b) a to b else b to a }.toSet()
        assertEquals(PoseSkeleton.CONNECTIONS.size, edges.size)
        listOf(
            11 to 12,
            11 to 13,
            12 to 14,
            11 to 23,
            12 to 24,
            23 to 24,
            23 to 25,
            24 to 26,
            25 to 27,
            26 to 28,
            0 to 1,
            9 to 10,
        ).forEach { edge ->
            assertTrue("$edge 가 없습니다", edge in edges)
        }
        assertTrue(edges.all { (a, b) -> a in 0..32 && b in 0..32 })
    }

    @Test
    fun `같은 비율이면 정규화 좌표가 뷰에 그대로 맞는다`() {
        val point = mapPosePoint(0.25f, 0.5f, 100, 200, 100f, 200f, mirrorX = false)
        assertEquals(25f, point.x, 0.01f)
        assertEquals(100f, point.y, 0.01f)
    }

    @Test
    fun `전면 카메라는 좌우가 뒤집힌다`() {
        val point = mapPosePoint(0.2f, 0.4f, 200, 200, 200f, 200f, mirrorX = true)
        assertEquals(160f, point.x, 0.01f)
        assertEquals(80f, point.y, 0.01f)
    }

    @Test
    fun `FILL_CENTER 는 넘치는 쪽을 가운데에서 자른다`() {
        // Frame is taller than the view, so the top and bottom are cropped.
        val mid = mapPosePoint(0.5f, 0.5f, 100, 200, 100f, 100f, mirrorX = false)
        assertEquals(50f, mid.x, 0.01f)
        assertEquals(50f, mid.y, 0.01f)
        val top = mapPosePoint(0f, 0f, 100, 200, 100f, 100f, mirrorX = false)
        assertEquals(-50f, top.y, 0.01f)
        // Frame is wider than the view, so the sides are cropped.
        val side = mapPosePoint(0f, 0.5f, 200, 100, 100f, 100f, mirrorX = false)
        assertEquals(-50f, side.x, 0.01f)
        assertEquals(50f, side.y, 0.01f)
    }

    @Test
    fun `가시성이 낮은 관절과 그 연결은 그리지 않는다`() {
        val landmarks = List(33) { index ->
            JointPoint(
                x = 0.2f + (index % 5) * 0.1f,
                y = 0.2f + (index / 5) * 0.1f,
                visibility = if (index == 15) 0.1f else 0.9f,
            )
        }
        val layout = layoutSkeleton(
            landmarks = landmarks,
            minVisibility = 0.35f,
            frameWidth = 100,
            frameHeight = 100,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrorX = false,
        )
        assertEquals(32, layout.dots.size)
        assertTrue(layout.lines.none { (start, end) ->
            val wrist = mapPosePoint(landmarks[15].x, landmarks[15].y, 100, 100, 100f, 100f, false)
            start == wrist || end == wrist
        })
        assertTrue(layout.lines.any { (start, end) ->
            val shoulder = mapPosePoint(landmarks[11].x, landmarks[11].y, 100, 100, 100f, 100f, false)
            val elbow = mapPosePoint(landmarks[13].x, landmarks[13].y, 100, 100, 100f, 100f, false)
            (start == shoulder && end == elbow) || (start == elbow && end == shoulder)
        })
        assertNull(layout.dots.find { it == mapPosePoint(landmarks[15].x, landmarks[15].y, 100, 100, 100f, 100f, false) })
    }

    @Test
    fun `돌린 관절은 미리보기 좌표로 되돌아온다`() {
        val samples = listOf(0.2f to 0.35f, 0f to 0f, 1f to 1f, 0.5f to 0.1f, 0.8f to 0f)
        for (degrees in listOf(0, 90, 180, 270)) {
            for ((x, y) in samples) {
                val turned = rotateNormalizedClockwise(x, y, degrees)
                val back = unrotateNormalized(turned.first, turned.second, degrees)
                assertEquals("$degrees x", x, back.first, 0.0001f)
                assertEquals("$degrees y", y, back.second, 0.0001f)
            }
        }
        // Head at the top of the preview sits on the left after a 90° clockwise turn.
        val (x, y) = unrotateNormalized(0f, 0.5f, 90)
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(0f, y, 0.0001f)
    }

    @Test
    fun `보정이 0이면 관절 배열을 그대로 쓴다`() {
        val joints = arrayOf(floatArrayOf(0.25f, 0.75f, 0.9f))
        assertSame(joints, joints.unrotateForPreview(0))
        val turned = joints.unrotateForPreview(180)
        assertEquals(0.75f, turned[0][0], 0.0001f)
        assertEquals(0.25f, turned[0][1], 0.0001f)
        assertEquals(0.9f, turned[0][2], 0.0001f)
    }
}
