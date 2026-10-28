package kr.zerotwo.naksang.detect

/**
 * Timestamps for offline video replay. [frameTimesUs] is microseconds from
 * the start of the file, about [fps] frames per second, including 0 and the
 * last step that still falls inside the duration. Capped at 30 minutes so a
 * bad duration cannot queue an unbounded list. Nothing is stored.
 */
object ReplaySampling {
    const val FPS = 5
    private const val MAX_MINUTES = 30

    fun frameTimesUs(durationMs: Long, fps: Int = FPS): LongArray {
        if (durationMs <= 0L || fps <= 0) return LongArray(0)
        val stepUs = 1_000_000L / fps
        if (stepUs <= 0L) return LongArray(0)
        val endUs = durationMs * 1_000L
        val cap = fps * 60 * MAX_MINUTES
        val out = ArrayList<Long>(minOf(cap, 256))
        var timeUs = 0L
        while (timeUs <= endUs && out.size < cap) {
            out.add(timeUs)
            timeUs += stepUs
        }
        return out.toLongArray()
    }
}

/** Measured analysis rate over a short window. */
class FrameRate(private val windowMs: Long = 1_000L) {
    private val stamps = ArrayDeque<Long>()

    fun mark(timeMs: Long): Float {
        stamps.addLast(timeMs)
        val cutoff = timeMs - windowMs
        while (stamps.isNotEmpty() && stamps.first() < cutoff) stamps.removeFirst()
        if (stamps.size < 2) return 0f
        val span = (stamps.last() - stamps.first()).coerceAtLeast(1L)
        return (stamps.size - 1) * 1000f / span.toFloat()
    }

    fun reset() {
        stamps.clear()
    }
}
