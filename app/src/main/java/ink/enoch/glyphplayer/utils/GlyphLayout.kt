package ink.enoch.glyphplayer.utils

import kotlin.math.ceil

/** Phone (3a)/(3a) Pro's 36 channels fit in one Long. No per-frame collections are built. */
object GlyphLayout {
    private class Strip(val first: Int, val count: Int)
    private val a = Strip(20, 11)
    private val b = Strip(31, 5)
    private val c = Strip(0, 20)
    private fun strip(char: Char) = when (char) { 'a' -> a; 'b' -> b; 'c' -> c; else -> null }
    private fun level(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    fun stripMask(value: Float, strip: Char, reverse: Boolean, dot: Boolean): Long {
        val spec = strip(strip) ?: return 0L
        val amount = level(value)
        if (amount <= 0f) return 0L
        if (dot) {
            val index = (amount * spec.count).toInt().coerceAtMost(spec.count - 1)
            return 1L shl (spec.first + if (reverse) spec.count - 1 - index else index)
        }
        return bar(spec, ceil(amount * spec.count).toInt(), reverse)
    }

    private fun bar(strip: Strip, count: Int, reverse: Boolean): Long {
        if (count <= 0) return 0L
        return ((1L shl count) - 1L) shl (strip.first + if (reverse) strip.count - count else 0)
    }

    fun flowMask(value: Float, start: Char, reverse: Boolean, dot: Boolean): Long {
        val amount = level(value)
        if (amount <= 0f) return 0L
        // Constant strings describe the traversal; each request visits at most three strips.
        val order = when (start.lowercaseChar()) {
            'a' -> if (reverse) "acb" else "abc"
            'b' -> if (reverse) "bac" else "bca"
            'c' -> if (reverse) "cba" else "cab"
            else -> return 0L
        }
        var remaining = if (dot) (amount * 36).toInt().coerceAtMost(35) else ceil(amount * 36).toInt()
        var mask = 0L
        for (i in 0..2) {
            val spec = strip(order[i])!!
            if (dot) {
                if (remaining < spec.count) return 1L shl (spec.first + if (reverse) spec.count - 1 - remaining else remaining)
                remaining -= spec.count
            } else {
                val count = minOf(remaining, spec.count)
                mask = mask or bar(spec, count, reverse)
                remaining -= count
                if (remaining == 0) break
            }
        }
        return mask
    }
}

/** Synchronous hardware output. Cache only successful writes; sessions/animations reset the cache. */
class GlyphFrameOutput(private val write: (Long) -> Unit) {
    private var last: Long? = null
    fun show(mask: Long) {
        if (mask == last) return
        write(mask)
        last = mask
    }
    fun reset() { last = null }
}
