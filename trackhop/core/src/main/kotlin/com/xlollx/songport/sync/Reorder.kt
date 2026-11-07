package com.xlollx.songport.sync

/**
 * How to turn one order into another with the moves the services offer. Pure Kotlin, covered by
 * tests. Both lists hold keys (track ids, or item ids where a playlist can hold a track twice).
 */
object Reorder {
    /**
     * Moves "take the item at [from], put it at [to]" (remove, then insert at that index), to apply
     * one after another, each on the list as the previous one left it. The longest run of items
     * already in the right relative order stays put; only the others move, each to just after its
     * predecessor in [desired]. Keys missing on either side are left alone.
     */
    fun moves(current: List<String>, desired: List<String>): List<Pair<Int, Int>> {
        val pos = HashMap<String, Int>()
        current.forEachIndexed { i, k -> pos.putIfAbsent(k, i) }
        val seq = desired.filter { it in pos }.distinct()
        val fixed = longestIncreasing(seq.map { pos.getValue(it) }).map { seq[it] }.toHashSet()
        val cur = current.toMutableList()
        val out = ArrayList<Pair<Int, Int>>()
        for ((k, key) in seq.withIndex()) {
            if (key in fixed) continue
            val from = cur.indexOf(key)
            cur.removeAt(from)
            val to = if (k == 0) 0 else cur.indexOf(seq[k - 1]) + 1
            cur.add(to, key)
            if (from != to) out += from to to
        }
        return out
    }

    /** Spotify's call wants the insertion point in the list before the removal: one more when moving forward. */
    fun insertBefore(from: Int, to: Int): Int = if (from < to) to + 1 else to

    /** Indices into [values] of one longest strictly increasing subsequence. */
    private fun longestIncreasing(values: List<Int>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val tails = ArrayList<Int>()
        val prev = IntArray(values.size) { -1 }
        for (i in values.indices) {
            var lo = 0
            var hi = tails.size
            while (lo < hi) { val mid = (lo + hi) / 2; if (values[tails[mid]] < values[i]) lo = mid + 1 else hi = mid }
            if (lo > 0) prev[i] = tails[lo - 1]
            if (lo == tails.size) tails += i else tails[lo] = i
        }
        val out = ArrayList<Int>()
        var at = tails.last()
        while (at >= 0) { out += at; at = prev[at] }
        return out.asReversed()
    }

    /**
     * Steps "put [first] right before [second]" (YouTube Music's move), the fewest that make
     * [current] follow [desired]: walked from the end, so each step lands next to an item already
     * in place. Keys missing on either side are left where they are.
     */
    fun before(current: List<String>, desired: List<String>): List<Pair<String, String>> {
        val cur = current.toMutableList()
        val out = ArrayList<Pair<String, String>>()
        val wanted = desired.filter { it in cur }
        for (k in wanted.size - 2 downTo 0) {
            val x = wanted[k]
            val y = wanted[k + 1]
            val ix = cur.indexOf(x)
            val iy = cur.indexOf(y)
            if (ix < 0 || iy < 0 || ix + 1 == iy) continue
            cur.removeAt(ix)
            cur.add(cur.indexOf(y), x)
            out += x to y
        }
        return out
    }
}
