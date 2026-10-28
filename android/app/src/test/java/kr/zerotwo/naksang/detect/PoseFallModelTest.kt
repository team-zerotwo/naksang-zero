package kr.zerotwo.naksang.detect

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference file is written by ml/train/train_pose_model.py from the same
 * weights Kotlin ships. This checks the feature vector and the probability,
 * not a second copy of the math inside the test.
 */
class PoseFallModelTest {
    @Test
    fun `코틀린 추론이 파이썬 기준 샘플과 같다`() {
        val stream = javaClass.classLoader?.getResourceAsStream("pose_fall_reference.json")
        checkNotNull(stream) { "pose_fall_reference.json 이 테스트 리소스에 있어야 합니다" }
        val root = JsonParser.parseString(stream.bufferedReader().readText()).asJsonObject
        val samples = root.getAsJsonArray("samples")
        assertTrue(samples.size() >= 2)
        for (element in samples) {
            val sample = element.asJsonObject
            val name = sample.get("name").asString
            val times = sample.getAsJsonArray("timestamps_ms").map { it.asLong }.toLongArray()
            val frames = sample.getAsJsonArray("landmarks").map { frameEl ->
                if (frameEl.isJsonNull) {
                    null
                } else {
                    val rows = frameEl.asJsonArray
                    Array(rows.size()) { index ->
                        val point = rows[index].asJsonArray
                        floatArrayOf(point[0].asFloat, point[1].asFloat, point[2].asFloat)
                    }
                }
            }
            val got = checkNotNull(PoseFeatures.features(frames, times)) { "$name 특징" }
            val expected = sample.getAsJsonArray("features")
            assertEquals(expected.size(), got.size)
            for (index in got.indices) {
                assertEquals("$name feature $index", expected[index].asDouble, got[index], 1e-4)
            }
            val score = PoseFallModel.probability(got)
            assertEquals("$name score", sample.get("score").asDouble, score, 1e-4)
            if (sample.has("gbdt_score")) {
                val trees = PoseFallModel.gbdtProbability(got)
                assertEquals("$name gbdt", sample.get("gbdt_score").asDouble, trees, 1e-4)
            }
        }
    }
}
