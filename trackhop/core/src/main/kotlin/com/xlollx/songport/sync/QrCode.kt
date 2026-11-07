package com.xlollx.songport.sync

/**
 * A QR code for a short text (a playlist address on a poster): byte mode, error correction M,
 * versions 1 to 10 (up to 213 bytes), the eight masks tried and the one with the lowest penalty
 * kept, as ISO/IEC 18004 says. Pure Kotlin, no library: F-Droid's reproducible build stays light,
 * and the whole thing is small enough to read. Covered by tests.
 */
object QrCode {

    /** The symbol: [size] modules a side, [dark] true where a module is black. */
    class Symbol(val size: Int, val version: Int, val mask: Int, private val dark: Array<BooleanArray>) {
        operator fun get(x: Int, y: Int): Boolean = dark[y][x]
    }

    // Error correction level M: codewords per block, number of blocks and total codewords, versions 1–10.
    private val EC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private const val EC_LEVEL_BITS = 0 // M

    /** Data bytes a version holds in byte mode (data codewords minus mode, count and terminator overhead). */
    fun capacity(version: Int): Int {
        val data = totalCodewords(version) - BLOCKS[version - 1] * EC_PER_BLOCK[version - 1]
        return (data * 8 - 4 - countBits(version)) / 8
    }

    fun encode(text: String): Symbol {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = (1..10).firstOrNull { capacity(it) >= bytes.size }
            ?: throw IllegalArgumentException("text too long for a QR code of version 10 (${bytes.size} bytes)")
        val codewords = interleave(dataCodewords(bytes, version), version)
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(modules, function, version)
        drawCodewords(modules, function, codewords)
        var best = -1
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            applyMask(modules, function, mask)
            drawFormat(modules, function, mask)
            val p = penalty(modules)
            if (p < bestPenalty) { bestPenalty = p; best = mask }
            applyMask(modules, function, mask) // undo: XOR twice
        }
        applyMask(modules, function, best)
        drawFormat(modules, function, best)
        return Symbol(size, version, best, modules)
    }

    // ------------------------------------------------------------------ bit stream and codewords

    private fun countBits(version: Int) = if (version >= 10) 16 else 8

    /** Raw data modules of the symbol, the standard's formula. */
    private fun rawDataModules(version: Int): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    fun totalCodewords(version: Int): Int = rawDataModules(version) / 8

    /** Mode, count, the bytes, terminator, then the 0xEC/0x11 padding up to the version's data codewords. */
    fun dataCodewords(bytes: ByteArray, version: Int): ByteArray {
        val dataLen = totalCodewords(version) - BLOCKS[version - 1] * EC_PER_BLOCK[version - 1]
        val bits = BitBuffer()
        bits.append(0b0100, 4)
        bits.append(bytes.size, countBits(version))
        for (b in bytes) bits.append(b.toInt() and 0xFF, 8)
        val capacity = dataLen * 8
        bits.append(0, minOf(4, capacity - bits.length))
        if (bits.length % 8 != 0) bits.append(0, 8 - bits.length % 8)
        var pad = 0xEC
        while (bits.length < capacity) { bits.append(pad, 8); pad = pad xor (0xEC xor 0x11) }
        return bits.toBytes()
    }

    /** Splits the data into the version's blocks, appends each block's EC codewords, interleaves both. */
    fun interleave(data: ByteArray, version: Int): ByteArray {
        val ecLen = EC_PER_BLOCK[version - 1]
        val numBlocks = BLOCKS[version - 1]
        val total = totalCodewords(version)
        val shortLen = total / numBlocks - ecLen
        val numShort = numBlocks - total % numBlocks
        val blocks = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val len = shortLen + if (i < numShort) 0 else 1
            blocks += data.copyOfRange(k, k + len); k += len
        }
        val ecs = blocks.map { ReedSolomon.remainder(it, ecLen) }
        val out = ByteArray(total)
        var o = 0
        for (i in 0..shortLen) for (b in blocks) if (i < b.size) out[o++] = b[i]
        for (i in 0 until ecLen) for (e in ecs) out[o++] = e[i]
        return out
    }

    class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length get() = bits.size
        fun append(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value ushr i) and 1 == 1 }
        fun toBytes(): ByteArray = ByteArray((bits.size + 7) / 8).also { out ->
            bits.forEachIndexed { i, b -> if (b) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
        }
    }

    /** Reed–Solomon over GF(2^8) with the QR polynomial 0x11D. */
    object ReedSolomon {
        private val EXP = IntArray(512)
        private val LOG = IntArray(256)
        init {
            var x = 1
            for (i in 0 until 255) { EXP[i] = x; LOG[x] = i; x = x shl 1; if (x and 0x100 != 0) x = x xor 0x11D }
            for (i in 255 until 512) EXP[i] = EXP[i - 255]
        }
        fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]
        fun pow(a: Int, n: Int): Int = if (n == 0) 1 else EXP[(LOG[a] * n) % 255]

        /** Coefficients of the generator (x - α^0)(x - α^1)…(x - α^(n-1)), highest degree first (leading 1 omitted). */
        fun generator(n: Int): IntArray {
            var g = intArrayOf(1)
            for (i in 0 until n) {
                val next = IntArray(g.size + 1)
                val root = EXP[i]
                for (j in g.indices) { next[j] = next[j] xor g[j]; next[j + 1] = next[j + 1] xor mul(g[j], root) }
                g = next
            }
            return g.copyOfRange(1, g.size)
        }

        fun remainder(data: ByteArray, n: Int): ByteArray {
            val gen = generator(n)
            val rem = IntArray(n)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor rem[0]
                System.arraycopy(rem, 1, rem, 0, n - 1)
                rem[n - 1] = 0
                for (i in 0 until n) rem[i] = rem[i] xor mul(gen[i], factor)
            }
            return ByteArray(n) { rem[it].toByte() }
        }
    }

    // ------------------------------------------------------------------ the matrix

    private fun set(m: Array<BooleanArray>, f: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) {
        m[y][x] = dark; f[y][x] = true
    }

    private fun alignmentCentres(version: Int): IntArray {
        if (version == 1) return IntArray(0)
        val size = version * 4 + 17
        val num = version / 7 + 2
        val step = if (version == 32) 26 else ((version * 4 + num * 2 + 1) / (num * 2 - 2)) * 2
        val out = IntArray(num)
        out[0] = 6
        var pos = size - 7
        for (i in num - 1 downTo 1) { out[i] = pos; pos -= step }
        return out
    }

    private fun drawFunctionPatterns(m: Array<BooleanArray>, f: Array<BooleanArray>, version: Int) {
        val size = m.size
        for (i in 0 until size) { set(m, f, 6, i, i % 2 == 0); set(m, f, i, 6, i % 2 == 0) }
        drawFinder(m, f, 3, 3); drawFinder(m, f, size - 4, 3); drawFinder(m, f, 3, size - 4)
        val centres = alignmentCentres(version)
        val last = centres.size - 1
        for (i in centres.indices) for (j in centres.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)) continue
            drawAlignment(m, f, centres[i], centres[j])
        }
        drawFormat(m, f, 0) // reserves the area; rewritten with the chosen mask
        if (version >= 7) {
            val bits = versionBits(version)
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(m, f, a, b, bit); set(m, f, b, a, bit)
            }
        }
    }

    private fun drawFinder(m: Array<BooleanArray>, f: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val x = cx + dx; val y = cy + dy
            if (x < 0 || y < 0 || x >= m.size || y >= m.size) continue
            val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            set(m, f, x, y, d != 2 && d != 4)
        }
    }

    private fun drawAlignment(m: Array<BooleanArray>, f: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) for (dx in -2..2) set(m, f, cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
    }

    /** The 15 format bits: EC level and mask, BCH(15,5) protected, XOR-masked. */
    fun formatBits(mask: Int): Int {
        val data = (EC_LEVEL_BITS shl 3) or mask
        var rem = data shl 10
        for (i in 14 downTo 10) if ((rem ushr i) and 1 == 1) rem = rem xor (0x537 shl (i - 10))
        return ((data shl 10) or rem) xor 0x5412
    }

    /** The 18 version bits for versions 7 and up: the number, BCH(18,6) protected. */
    fun versionBits(version: Int): Int {
        var rem = version shl 12
        for (i in 17 downTo 12) if ((rem ushr i) and 1 == 1) rem = rem xor (0x1F25 shl (i - 12))
        return (version shl 12) or rem
    }

    private fun drawFormat(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        val bits = formatBits(mask)
        val size = m.size
        fun bit(i: Int) = (bits ushr i) and 1 == 1
        for (i in 0..5) set(m, f, 8, i, bit(i))
        set(m, f, 8, 7, bit(6)); set(m, f, 8, 8, bit(7)); set(m, f, 7, 8, bit(8))
        for (i in 9..14) set(m, f, 14 - i, 8, bit(i))
        for (i in 0..7) set(m, f, size - 1 - i, 8, bit(i))
        for (i in 8..14) set(m, f, 8, size - 15 + i, bit(i))
        set(m, f, 8, size - 8, true)
    }

    private fun drawCodewords(m: Array<BooleanArray>, f: Array<BooleanArray>, data: ByteArray) {
        val size = m.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) for (j in 0..1) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (!f[y][x] && i < data.size * 8) {
                    m[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1
                    i++
                }
            }
            right -= 2
        }
    }

    private fun applyMask(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        val size = m.size
        for (y in 0 until size) for (x in 0 until size) {
            if (f[y][x]) continue
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert) m[y][x] = !m[y][x]
        }
    }

    /** The standard's four penalty rules: runs, 2×2 blocks, finder-like patterns, dark balance. */
    private fun penalty(m: Array<BooleanArray>): Int {
        val size = m.size
        var p = 0
        fun line(get: (Int) -> Boolean) {
            var run = 1
            val sb = StringBuilder(size)
            sb.append(if (get(0)) '1' else '0')
            for (i in 1 until size) {
                sb.append(if (get(i)) '1' else '0')
                if (get(i) == get(i - 1)) { run++; if (run == 5) p += 3 else if (run > 5) p++ } else run = 1
            }
            val s = sb.toString()
            var idx = s.indexOf("10111010000"); while (idx >= 0) { p += 40; idx = s.indexOf("10111010000", idx + 1) }
            idx = s.indexOf("00001011101"); while (idx >= 0) { p += 40; idx = s.indexOf("00001011101", idx + 1) }
        }
        for (y in 0 until size) line { x -> m[y][x] }
        for (x in 0 until size) line { y -> m[y][x] }
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) p += 3
        }
        var dark = 0
        for (row in m) for (c in row) if (c) dark++
        val percent = dark * 100 / (size * size)
        val k = maxOf(kotlin.math.abs(percent - 50) / 5, 0)
        p += 10 * k
        return p
    }
}
