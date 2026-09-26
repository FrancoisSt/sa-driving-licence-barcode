package io.github.francoisst.sadl

/**
 * Decodes the portrait in a licence card's barcode: section 3 of the decrypted payload ([CardLicence.photo]), a
 * Summus wavelet image ("WI"), to 200 x 250 8-bit greyscale. Pure Kotlin, written from spec/wi-codec.md, with no
 * native code and no dependencies.
 *
 * ```kotlin
 * val pixels = WiPortrait.decode(card.photo!!)    // 50,000 bytes, row by row from the top-left
 * ```
 *
 * Only the licence-portrait profile is accepted (spec/wi-codec.md section 2). Every call has its own state, so the
 * decoder may be used from several threads at once. The result is a person's portrait: show it, but do not log,
 * store or upload it without a reason.
 */
public object WiPortrait {

    /** Width of the portrait in samples. */
    public const val WIDTH: Int = 200

    /** Height of the portrait in samples. */
    public const val HEIGHT: Int = 250

    /** Samples in a portrait. */
    public const val PIXELS: Int = WIDTH * HEIGHT

    /** The smallest input accepted: the 12-byte header and one byte of coded data. */
    public const val MIN_WI_BYTES: Int = 13

    /** The largest input accepted (licence photos are 550 to 603 bytes). */
    public const val MAX_WI_BYTES: Int = 674

    /** WI bytes to the upright portrait (checkpoint C8): [PIXELS] samples, row by row from the top-left. */
    @JvmStatic
    @Throws(WiPortraitException::class)
    public fun decode(wi: ByteArray): ByteArray = decodeNativeOrder(wi).also { it.reverse() }

    /**
     * WI bytes to the codec's raster (checkpoint C7), the portrait turned 180°: its first row is the portrait's
     * bottom row, read from the right.
     */
    @JvmStatic
    @Throws(WiPortraitException::class)
    public fun decodeNativeOrder(wi: ByteArray): ByteArray = WiDecoder(wi, null).decode()

    /** For tests: decode while [observer] receives every checkpoint of spec/wi-codec.md section 14. */
    internal fun decodeObserved(wi: ByteArray, observer: WiObserver): ByteArray = WiDecoder(wi, observer).decode()
}

/**
 * A WI photo that cannot be decoded, with the [error] of spec/wi-codec.md section 12. The message holds no data.
 */
public class WiPortraitException(public val error: Error) : Exception("${error.code}: ${error.description}") {
    /** The errors E1 to E5 of spec/wi-codec.md section 12. */
    public enum class Error(public val code: String, internal val description: String) {
        /** E1: not the licence-portrait profile, or a size outside 13 to 674 bytes. */
        UNSUPPORTED("E1", "not the licence WI profile"),

        /** E2: a bit was needed after the last byte. */
        END_OF_DATA("E2", "end of data"),

        /** E3: a skip count above 5. */
        SKIP_TOO_LARGE("E3", "skip count above 5"),

        /** E4: an escape width above 10. */
        ESCAPE_TOO_WIDE("E4", "escape width above 10"),

        /** E5: the run count was not exactly used up by the significance map. */
        RUN_NOT_EXHAUSTED("E5", "run count not exhausted after the map"),
    }
}

/** Receives the checkpoints of spec/wi-codec.md section 14. [x] is the 250 x 200 working image, row-major. */
internal interface WiObserver {
    fun header(q: Int) {}
    fun llBand(llBits: Int, x: IntArray) {}
    fun skip(skip: Int) {}
    fun map(m: ByteArray) {}
    fun sharpened(x: IntArray) {}
    fun upsampled(level: Int, x: IntArray) {}
    fun escapeBits(level: Int, bits: Int) {}
    fun coefficient(level: Int, value: Int) {}
    fun reconstructed(level: Int, x: IntArray) {}
    fun finished(bytesConsumed: Int, raster: ByteArray) {}
}

private const val H = WiPortrait.HEIGHT
private const val W = WiPortrait.WIDTH
private const val LEVELS = 5
private const val HEADER_BYTES = 12

// Significance map values (section 8).
private const val UNMARKED: Byte = 1
private const val SIGNIFICANT: Byte = 2
private const val ANCESTOR: Byte = 4

// Bands (section 5): B0 rows low / columns high, B1 rows high / columns low, B2 both high.
private const val B0 = 0
private const val B1 = 1
private const val B2 = 2

/** Section 5: rows (of H) or columns (of W) of the region of [level]. */
internal fun levelSize(n: Int, level: Int): Int = (n - 2) / (1 shl level) + 2

private fun low(n: Int): Int = n / 2 + 1

private fun high(n: Int): Int = n - low(n)

private fun fail(error: WiPortraitException.Error): Nothing = throw WiPortraitException(error)

/** The bit reader of section 3, as the Huffman decoder sees it. */
internal interface BitSource {
    fun bit(): Int
    fun bits(n: Int): Int
}

/** One decode: all mutable state lives here, never in [WiPortrait]. */
private class WiDecoder(private val data: ByteArray, private val observer: WiObserver?) : BitSource {
    private val x = IntArray(WiPortrait.PIXELS)
    private val m = ByteArray(WiPortrait.PIXELS) { UNMARKED }

    // Section 3: the bit reader. `fetched` is the checkpoint scalar bytes_consumed.
    private var fetched = HEADER_BYTES
    private var current = 0
    private var bitsLeft = 0

    override fun bit(): Int {
        if (bitsLeft == 0) {
            if (fetched >= data.size) fail(WiPortraitException.Error.END_OF_DATA)
            current = data[fetched++].toInt() and 0xFF
            bitsLeft = 8
        }
        bitsLeft--
        return (current ushr bitsLeft) and 1
    }

    /** An unsigned n-bit value, first bit most significant. At most 31 bits are ever read in this profile. */
    override fun bits(n: Int): Int {
        var v = 0
        repeat(n) { v = (v shl 1) or bit() }
        return v
    }

    fun decode(): ByteArray {
        val q = checkHeader()
        observer?.header(q)
        decodeLowBand(q)
        val skip = readSkip()
        MapStage(skip).decode()
        observer?.map(m)
        val scratch = IntArray(WiPortrait.PIXELS)
        for (level in LEVELS - 1 downTo 0) {
            if (level == 0) {
                sharpen(scratch)
                observer?.sharpened(x)
            }
            upsample(level, scratch)
            observer?.upsampled(level, x)
            if (level >= skip) addDetails(level, q)
            observer?.reconstructed(level, x)
        }
        // Section 10: X is 32 times the sample value; round and clamp.
        val raster = ByteArray(WiPortrait.PIXELS)
        for (i in raster.indices) {
            val t = x[i] + 16
            raster[i] = when {
                t >= 8192 -> 255
                t < 0 -> 0
                else -> t / 32
            }.toByte()
        }
        observer?.finished(fetched, raster)
        return raster
    }

    /** Section 2: exactly the licence profile. Returns Q. */
    private fun checkHeader(): Int {
        val d = data
        fun u(i: Int) = d[i].toInt() and 0xFF
        val ok = d.size in WiPortrait.MIN_WI_BYTES..WiPortrait.MAX_WI_BYTES &&
            u(0) == 0x57 && u(1) == 0x49 && u(2) == 0x04 && u(3) == 0x00 && u(4) == 0xFA && u(5) == 0x00 && u(6) == 0xC8 &&
            (u(7) == 0x42 || u(7) == 0x43) && (u(8) and 1) == 0 && u(9) == 0x28 && u(10) == 0x40 && u(11) == 0x00
        if (!ok) fail(WiPortraitException.Error.UNSUPPORTED)
        return ((u(7) and 1) shl 7) or (u(8) ushr 1)
    }

    /** Section 6: the 9 x 8 LL band, step 64 Q, mid-point reconstruction. Int arithmetic wraps as required. */
    private fun decodeLowBand(q: Int) {
        val llBits = bits(5)
        val offset = bits(llBits) and 0xFF
        for (r in 0 until levelSize(H, LEVELS)) {
            for (c in 0 until levelSize(W, LEVELS)) {
                val v = bits(llBits) - offset
                x[r * W + c] = (if (v >= 0) 2 * v + 1 else 2 * v - 1) * (32 * q)
            }
        }
        observer?.llBand(llBits, x)
    }

    /** Section 7: ones before the first zero; checked after the zero. */
    private fun readSkip(): Int {
        var skip = 0
        while (bit() == 1) skip++
        if (skip > LEVELS) fail(WiPortraitException.Error.SKIP_TOO_LARGE)
        observer?.skip(skip)
        return skip
    }

    // -----------------------------------------------------------------------------------------------------------
    // Section 8: the significance map.

    private inner class MapStage(private val skip: Int) {
        private val model = AdaptiveCode(256, 64)
        private var remaining = 0 // R, signed; wraps like the reference
        private var level = 0
        private var band = B0

        /** Section 4.7: `hi × 256 + lo` per extension symbol (255 or 256), with the recursion written as a loop. */
        private fun readRun(): Int {
            var extensions = 0
            var s = model.decode(this@WiDecoder, 8)
            while (s >= 255) {
                extensions++
                s = model.decode(this@WiDecoder, 8)
            }
            var value = s
            repeat(extensions) { value = value * 256 + model.decode(this@WiDecoder, 8) }
            return value
        }

        fun decode() {
            remaining = readRun()
            for (l in skip until LEVELS) {
                level = l
                val n = levelSize(H, l)
                val w = levelSize(W, l)
                val (ln, lw) = low(n) to low(w)
                if ((l - skip) % 2 == 0) {
                    band = B0; visit(0, ln, lw, w, 3)
                    band = B2; visit(ln, n, lw, w, 0)
                    band = B1; visit(ln, n, 0, lw, 0)
                } else {
                    band = B1; visit(ln, n, 0, lw, 1)
                    band = B2; visit(ln, n, lw, w, 2)
                    band = B0; visit(0, ln, lw, w, 2)
                }
            }
            if (remaining != 0) fail(WiPortraitException.Error.RUN_NOT_EXHAUSTED)
        }

        private fun unmarked(r0: Int, r1: Int, c0: Int, c1: Int): Int {
            var count = 0
            for (r in r0 until r1) {
                val base = r * W
                for (c in c0 until c1) if (m[base + c] == UNMARKED) count++
            }
            return count
        }

        private fun visit(r0: Int, r1: Int, c0: Int, c1: Int, o: Int) {
            val count = unmarked(r0, r1, c0, c1)
            if (remaining >= count) {
                remaining -= count
                return
            }
            if (r1 - r0 > 2 && c1 - c0 > 2) {
                val rm = r0 + (r1 - r0) / 2
                val cm = c0 + (c1 - c0) / 2
                when (o) {
                    0 -> { visit(r0, rm, cm, c1, 3); visit(rm, r1, cm, c1, 0); visit(rm, r1, c0, cm, 0); visit(r0, rm, c0, cm, 2) }
                    1 -> { visit(rm, r1, c0, cm, 2); visit(r0, rm, c0, cm, 1); visit(r0, rm, cm, c1, 1); visit(rm, r1, cm, c1, 3) }
                    2 -> { visit(rm, r1, c0, cm, 1); visit(rm, r1, cm, c1, 2); visit(r0, rm, cm, c1, 2); visit(r0, rm, c0, cm, 0) }
                    else -> { visit(r0, rm, cm, c1, 0); visit(r0, rm, c0, cm, 3); visit(rm, r1, c0, cm, 3); visit(rm, r1, cm, c1, 1) }
                }
                return
            }
            // Serpentine: the first line in the direction of the table in section 8.1, each next line reversed.
            when (o) {
                0, 1 -> {
                    var ascending = o == 0
                    var c = if (o == 0) c1 - 1 else c0
                    val step = if (o == 0) -1 else 1
                    while (c in c0 until c1) {
                        if (ascending) for (r in r0 until r1) cell(r, c) else for (r in r1 - 1 downTo r0) cell(r, c)
                        ascending = !ascending
                        c += step
                    }
                }
                else -> {
                    var ascending = o == 2
                    var r = if (o == 2) r1 - 1 else r0
                    val step = if (o == 2) -1 else 1
                    while (r in r0 until r1) {
                        if (ascending) for (c in c0 until c1) cell(r, c) else for (c in c1 - 1 downTo c0) cell(r, c)
                        ascending = !ascending
                        r += step
                    }
                }
            }
        }

        private fun cell(r: Int, c: Int) {
            val i = r * W + c
            if (m[i] != UNMARKED) return
            if (remaining != 0) {
                remaining-- // not "> 0": a negative R keeps counting down (and never reaches 0)
                return
            }
            m[i] = SIGNIFICANT
            markAncestors(r, c)
            remaining = readRun()
        }

        /** Section 8.2. */
        private fun markAncestors(row: Int, column: Int) {
            var r = row
            var c = column
            for (p in level until LEVELS - 1) {
                r = if (band == B1 || band == B2) parent(r, levelSize(H, p)) else r / 2
                c = if (band == B0 || band == B2) parent(c, levelSize(W, p)) else c / 2
                val i = r * W + c
                if (m[i] == ANCESTOR) return
                m[i] = ANCESTOR
            }
        }

        private fun parent(v: Int, n: Int): Int = when (n % 4) {
            0, 1 -> (v + 1) / 2
            2 -> (v + 2) / 2
            else -> if (v == n - 1) (v + 1) / 2 else (v + 2) / 2
        }
    }

    // -----------------------------------------------------------------------------------------------------------
    // Section 9: reconstruction.

    /** Section 9.1: the level-(l+1) image, bilinearly upsampled with every source biased by +1. */
    private fun upsample(level: Int, y: IntArray) {
        val n = levelSize(H, level)
        val w = levelSize(W, level)
        val lastLowRow = low(n) - 1
        val lastLowColumn = low(w) - 1
        val exceptionRow = if (n % 2 == 1) n - 1 else n - 2
        for (p in 0 until n) {
            val rowCopy = (n % 2 == 0 && p == n - 1) || p % 2 == 0
            val ra = if (n % 2 == 0 && p == n - 1) lastLowRow else p / 2
            val rb = ra + 1
            for (qc in 0 until w) {
                val columnCopy = (w % 2 == 0 && qc == w - 1) || qc % 2 == 0
                val ca = if (w % 2 == 0 && qc == w - 1) lastLowColumn else qc / 2
                val cb = ca + 1
                var sum = x[ra * W + ca] + 1
                var k = 1
                if (!columnCopy) {
                    sum += x[ra * W + cb] + 1
                    k = 2
                }
                if (!rowCopy) {
                    sum += x[rb * W + ca] + 1
                    if (!columnCopy) {
                        sum += x[rb * W + cb] + 1
                        k = 3
                    } else {
                        k = 2
                    }
                }
                val truncating = (n % 2 == 0 && p == n - 1) || (w % 2 == 0 && qc == w - 1 && p == exceptionRow)
                y[p * W + qc] = if (truncating) sum / (1 shl k) else sum shr k
            }
        }
        for (p in 0 until n) System.arraycopy(y, p * W, x, p * W, w)
    }

    private val rowPos = IntArray(5)
    private val rowWeight = IntArray(5)
    private val columnPos = IntArray(5)
    private val columnWeight = IntArray(5)

    /** Section 9.3's K_L(i; n) into [pos]/[weight]; returns the number of taps. */
    private fun lowKernel(i: Int, n: Int, pos: IntArray, weight: IntArray): Int {
        fun tap(k: Int, at: Int, wt: Int) { pos[k] = at; weight[k] = wt }
        return when {
            i == 0 -> { tap(0, 0, 2); tap(1, 1, 1); 2 }
            i == (n - 1) / 2 -> { tap(0, 2 * i - 1, 1); tap(1, 2 * i, 2); 2 }
            i == low(n) - 1 -> { tap(0, n - 1, 2); 1 }
            else -> { tap(0, 2 * i - 1, 1); tap(1, 2 * i, 2); tap(2, 2 * i + 1, 1); 3 }
        }
    }

    /** Section 9.3's K_H(t; n), centred on 2t + 1. */
    private fun highKernel(t: Int, n: Int, pos: IntArray, weight: IntArray): Int {
        val p = 2 * t + 1
        if (t == 0) {
            pos[0] = 0; pos[1] = 1; pos[2] = 2; pos[3] = 3
            weight[0] = -2; weight[1] = 6; weight[2] = -2; weight[3] = -1
            return 4
        }
        pos[0] = p - 2; pos[1] = p - 1; pos[2] = p; pos[3] = p + 1; pos[4] = p + 2
        weight[0] = -1; weight[1] = -2; weight[2] = 6; weight[3] = -2; weight[4] = -1
        return if (t == high(n) - 1) 4 else 5
    }

    /** Sections 9.2 and 9.3. */
    private fun addDetails(level: Int, q: Int) {
        val escapeBits = bits(5)
        if (escapeBits > 10) fail(WiPortraitException.Error.ESCAPE_TOO_WIDE)
        observer?.escapeBits(level, escapeBits)
        val model = AdaptiveCode(2048, 3)
        val n = levelSize(H, level)
        val w = levelSize(W, level)
        val ln = low(n)
        val lw = low(w)
        for (band in B0..B2) {
            val r0 = if (band == B0) 0 else ln
            val r1 = if (band == B0) ln else n
            val c0 = if (band == B1) 0 else lw
            val c1 = if (band == B1) lw else w
            for (r in r0 until r1) {
                for (c in c0 until c1) {
                    val mark = m[r * W + c]
                    if (mark == UNMARKED) continue
                    val negative = bit() == 1
                    var k = model.decode(this, escapeBits)
                    if (mark == SIGNIFICANT) k++
                    val magnitude = k * q + q / 2
                    val v = if (negative) -magnitude else magnitude
                    observer?.coefficient(level, v)
                    val rows: Int
                    val columns: Int
                    val scale: Int
                    when (band) {
                        B0 -> {
                            rows = lowKernel(r, n, rowPos, rowWeight)
                            columns = highKernel(c - lw, w, columnPos, columnWeight)
                            scale = 2
                        }
                        B1 -> {
                            rows = highKernel(r - ln, n, rowPos, rowWeight)
                            columns = lowKernel(c, w, columnPos, columnWeight)
                            scale = 2
                        }
                        else -> {
                            rows = highKernel(r - ln, n, rowPos, rowWeight)
                            columns = highKernel(c - lw, w, columnPos, columnWeight)
                            scale = 1
                        }
                    }
                    for (a in 0 until rows) {
                        val base = rowPos[a] * W
                        val rv = scale * v * rowWeight[a]
                        for (b in 0 until columns) x[base + columnPos[b]] += rv * columnWeight[b]
                    }
                }
            }
        }
    }

    /** Section 9.4: the level-1 image (126 x 101), sharpened in its interior; the border is kept. */
    private fun sharpen(s: IntArray) {
        val n1 = levelSize(H, 1)
        val m1 = levelSize(W, 1)
        for (r in 1 until n1 - 1) {
            for (c in 1 until m1 - 1) {
                val i = r * W + c
                s[i] = (12 * x[i] - x[i - 1] - x[i + 1] - x[i - W] - x[i + W]) shr 3
            }
        }
        for (r in 1 until n1 - 1) System.arraycopy(s, r * W + 1, x, r * W + 1, m1 - 2)
    }
}

/**
 * Section 4: the adaptive Huffman code (FGK, sibling property) over [n] symbols, plus the large-value leaf [n] and
 * the escape leaf n + 1. Node 0 is the root; an internal node's children are `payload` and `payload + 1`.
 */
internal class AdaptiveCode(private val n: Int, presets: Int) {
    val parent = IntArray(2 * n + 3) { -1 }
    val leaf = BooleanArray(2 * n + 3)
    val payload = IntArray(2 * n + 3)
    val weight = IntArray(2 * n + 3)
    val nodeOf = IntArray(n + 2) { -1 }
    var next = 3
        private set

    init {
        payload[0] = 1; weight[0] = 2
        parent[1] = 0; leaf[1] = true; payload[1] = n; weight[1] = 1
        parent[2] = 0; leaf[2] = true; payload[2] = n + 1; weight[2] = 1
        nodeOf[n] = 1
        nodeOf[n + 1] = 2
        for (s in 0 until presets) {
            add(s)
            update(s)
        }
    }

    /** Section 4.3. */
    private fun add(s: Int) {
        if (nodeOf[s] != -1) return
        val d = next
        leaf[d] = leaf[d - 1]; payload[d] = payload[d - 1]; weight[d] = weight[d - 1]; parent[d] = d - 1
        nodeOf[payload[d]] = d
        leaf[d - 1] = false; payload[d - 1] = d
        leaf[d + 1] = true; payload[d + 1] = s; weight[d + 1] = 0; parent[d + 1] = d - 1
        nodeOf[s] = d + 1
        next = d + 2
    }

    /** Section 4.4. */
    private fun update(s: Int) {
        var i = nodeOf[s]
        while (i != -1) {
            weight[i]++
            var j = i
            while (j > 0 && weight[j - 1] < weight[i]) j--
            if (j != i) {
                swap(i, j)
                i = j
            }
            i = parent[i]
        }
    }

    /** Section 4.5: exchange contents; parents stay with the node numbers. */
    private fun swap(i: Int, j: Int) {
        repoint(i, j)
        repoint(j, i)
        val l = leaf[i]; leaf[i] = leaf[j]; leaf[j] = l
        val p = payload[i]; payload[i] = payload[j]; payload[j] = p
        val w = weight[i]; weight[i] = weight[j]; weight[j] = w
    }

    /** Whatever points at node [a] (its symbol's entry, or its children's parent) now points at [b]. */
    private fun repoint(a: Int, b: Int) {
        if (leaf[a]) {
            nodeOf[payload[a]] = b
        } else {
            parent[payload[a]] = b
            parent[payload[a] + 1] = b
        }
    }

    /** Section 4.6: one symbol, 0 to n (n is the large-value leaf). */
    fun decode(reader: BitSource, escapeBits: Int): Int {
        var i = 0
        while (!leaf[i]) i = payload[i] + reader.bit()
        var s = payload[i]
        if (s == n + 1) {
            s = reader.bits(escapeBits) // below n: at most 10 bits against 2048, 8 against 256
            add(s)
        }
        update(s)
        return s
    }
}
