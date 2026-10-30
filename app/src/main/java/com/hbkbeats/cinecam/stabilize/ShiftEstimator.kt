package com.hbkbeats.cinecam.stabilize

/** Finds how far the picture content moved between two small grayscale frames. */
object ShiftEstimator {

    /**
     * Returns (dx, dy) so that content at p(x - dx, y - dy) appears at c(x, y).
     * Positive dx = content moved right, positive dy = content moved down. Sub-pixel refined.
     */
    fun estimate(p: IntArray, c: IntArray, w: Int, h: Int, radius: Int): FloatArray {
        val r = radius
        val n = 2 * r + 1
        val sad = LongArray(n * n)
        var best = Long.MAX_VALUE
        var bestIdx = 0
        for (j in 0 until n) {
            val dy = j - r
            for (i in 0 until n) {
                val dx = i - r
                var s = 0L
                for (y in r until h - r) {
                    val cRow = y * w
                    val pRow = (y - dy) * w
                    for (x in r until w - r) {
                        val d = c[cRow + x] - p[pRow + x - dx]
                        s += if (d < 0) -d else d
                    }
                }
                sad[j * n + i] = s
                if (s < best) {
                    best = s
                    bestIdx = j * n + i
                }
            }
        }
        val bj = bestIdx / n
        val bi = bestIdx % n
        val sx = if (bi in 1 until n - 1) refine(sad[bj * n + bi - 1], sad[bestIdx], sad[bj * n + bi + 1]) else 0f
        val sy = if (bj in 1 until n - 1) refine(sad[(bj - 1) * n + bi], sad[bestIdx], sad[(bj + 1) * n + bi]) else 0f
        return floatArrayOf((bi - r) + sx, (bj - r) + sy)
    }

    private fun refine(minus: Long, mid: Long, plus: Long): Float {
        val den = (minus - 2 * mid + plus).toFloat()
        return if (den <= 0f) 0f else 0.5f * (minus - plus).toFloat() / den
    }
}
