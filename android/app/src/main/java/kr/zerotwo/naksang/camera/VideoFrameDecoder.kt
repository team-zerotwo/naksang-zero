package kr.zerotwo.naksang.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kr.zerotwo.naksang.detect.ReplaySampling
import kotlin.math.max

data class DecodedFrame(
    val bitmap: Bitmap,
    val timestampMs: Long,
    val index: Int,
    val total: Int,
)

/**
 * Pulls a few frames per second out of a video the user just picked.
 * The caller recycles each [DecodedFrame.bitmap]. Frames are not written
 * to disk and are not uploaded.
 */
class VideoFrameDecoder(private val context: Context) {
    /**
     * @return a Korean error, or null when the file was read. Cancellation
     * returns null as well: the caller already left the replay.
     */
    fun decode(
        uri: Uri,
        fps: Int = ReplaySampling.FPS,
        cancelled: () -> Boolean = { false },
        onFrame: (DecodedFrame) -> Unit,
    ): String? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            if (durationMs == null || durationMs <= 0L) return "영상 길이를 읽지 못했어요"
            val times = ReplaySampling.frameTimesUs(durationMs, fps)
            if (times.isEmpty()) return "영상 길이를 읽지 못했어요"
            var emitted = 0
            for (index in times.indices) {
                if (cancelled()) return null
                val raw = retriever.getFrameAtTime(times[index], MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                val bitmap = scaleBitmapDown(ensureArgb(raw), MAX_EDGE)
                onFrame(
                    DecodedFrame(
                        bitmap = bitmap,
                        timestampMs = times[index] / 1_000L,
                        index = index,
                        total = times.size,
                    ),
                )
                emitted += 1
            }
            if (emitted == 0) return "이 영상에서 프레임을 읽지 못했어요"
            return null
        } catch (e: Exception) {
            Log.e(TAG, "video replay", e)
            return "영상을 열지 못했어요"
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TAG = "NaksangReplay"
        private const val MAX_EDGE = 640
    }
}

internal fun ensureArgb(src: Bitmap): Bitmap {
    if (src.config == Bitmap.Config.ARGB_8888) return src
    val copy = src.copy(Bitmap.Config.ARGB_8888, false) ?: return src
    if (copy != src) src.recycle()
    return copy
}

internal fun scaleBitmapDown(src: Bitmap, maxEdge: Int): Bitmap {
    val edge = max(src.width, src.height)
    if (edge <= maxEdge || edge <= 0) return src
    val scale = maxEdge.toFloat() / edge.toFloat()
    val width = (src.width * scale).toInt().coerceAtLeast(1)
    val height = (src.height * scale).toInt().coerceAtLeast(1)
    val out = Bitmap.createScaledBitmap(src, width, height, true)
    if (out != src) src.recycle()
    return out
}
