package kr.zerotwo.naksang.camera

/**
 * How to widen the back camera.
 * [ULTRA_WIDE] when at least two focal lengths are known and the shortest is
 * clearly wider than the longest. Otherwise [MIN_ZOOM]: the caller asks the
 * bound camera for its minimum zoom ratio, which is 1x when it cannot zoom out.
 */
enum class BackLensChoice {
    ULTRA_WIDE,
    MIN_ZOOM,
}

fun chooseBackLens(focalLengthsMm: List<Float?>): BackLensChoice {
    val known = focalLengthsMm.mapNotNull { length -> length?.takeIf { it > 0f } }
    if (known.size < 2) return BackLensChoice.MIN_ZOOM
    val shortest = known.min()
    val longest = known.max()
    if (longest <= 0f) return BackLensChoice.MIN_ZOOM
    return if (shortest < longest * ULTRA_WIDE_FRACTION) {
        BackLensChoice.ULTRA_WIDE
    } else {
        BackLensChoice.MIN_ZOOM
    }
}

/** Shortest focal length has to be this fraction of the longest before it counts as ultra-wide. */
private const val ULTRA_WIDE_FRACTION = 0.85f
