package io.github.francoisst.sadl

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random

/**
 * [WiPortrait] against spec/test-vectors/wi-checkpoints.json and wi-synthetic.json (spec/wi-codec.md section 14).
 * Only hashes, sizes, counts and error codes are compared or reported, never decoded values.
 */
class WiPortraitTest {
    private val dir = File(System.getProperty("sadl.spec") ?: "../spec/test-vectors")

    private fun cases(name: String): List<JsonObject> =
        JsonParser.parseString(File(dir, name).readText()).asJsonObject["cases"].asJsonArray.map { it.asJsonObject }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    /** Hashes every checkpoint, keyed as in the JSON. */
    private class Recorder : WiObserver {
        val top = HashMap<String, JsonElement>()
        val levels = HashMap<Int, HashMap<String, JsonElement>>()
        private val coefficients = HashMap<Int, java.io.ByteArrayOutputStream>()

        private fun digest(b: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

        private fun region(x: IntArray, rows: Int, columns: Int): String {
            val buffer = ByteBuffer.allocate(rows * columns * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (r in 0 until rows) for (c in 0 until columns) buffer.putInt(x[r * WiPortrait.WIDTH + c])
            return digest(buffer.array())
        }

        private fun level(l: Int) = levels.getOrPut(l) { HashMap() }
        private fun levelRegion(x: IntArray, l: Int) = region(x, levelSize(250, l), levelSize(200, l))

        override fun header(q: Int) { top["q"] = JsonPrimitive(q) }
        override fun llBand(llBits: Int, x: IntArray) {
            top["ll_bits"] = JsonPrimitive(llBits)
            top["C1_ll"] = JsonPrimitive(region(x, 9, 8))
        }
        override fun skip(skip: Int) { top["skip"] = JsonPrimitive(skip) }
        override fun map(m: ByteArray) { top["C2_map"] = JsonPrimitive(digest(m)) }
        override fun sharpened(x: IntArray) { top["C6_sharpened"] = JsonPrimitive(region(x, 126, 101)) }
        override fun upsampled(level: Int, x: IntArray) {
            level(level)["C3_upsampled"] = JsonPrimitive(levelRegion(x, level))
            level(level)["escape_bits"] = JsonNull.INSTANCE
        }
        override fun escapeBits(level: Int, bits: Int) { level(level)["escape_bits"] = JsonPrimitive(bits) }
        override fun coefficient(level: Int, value: Int) {
            val out = coefficients.getOrPut(level) { java.io.ByteArrayOutputStream() }
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }
        override fun reconstructed(level: Int, x: IntArray) {
            val bytes = coefficients.remove(level)?.toByteArray() ?: ByteArray(0)
            level(level)["C4_coefficient_count"] = JsonPrimitive(bytes.size / 4)
            level(level)["C4_coefficients"] = JsonPrimitive(digest(bytes))
            level(level)["C5_reconstructed"] = JsonPrimitive(levelRegion(x, level))
        }
        override fun finished(bytesConsumed: Int, raster: ByteArray) {
            top["bytes_consumed"] = JsonPrimitive(bytesConsumed)
            top["C7_raster"] = JsonPrimitive(digest(raster))
            top["C8_portrait"] = JsonPrimitive(digest(raster.reversedArray()))
        }
    }

    private val topKeys = listOf("q", "ll_bits", "skip", "bytes_consumed", "C1_ll", "C2_map", "C6_sharpened", "C7_raster", "C8_portrait")
    private val levelKeys = listOf("C3_upsampled", "escape_bits", "C4_coefficient_count", "C4_coefficients", "C5_reconstructed")

    /** The names of the checkpoints that differ from [case]. Gson numbers compare by value, so compare as strings. */
    private fun differences(wi: ByteArray, case: JsonObject): List<String> {
        val recorder = Recorder()
        try {
            WiPortrait.decodeObserved(wi, recorder)
        } catch (e: WiPortraitException) {
            return listOf("failed with ${e.error.code}")
        }
        fun same(a: JsonElement?, b: JsonElement?) = a.toString() == b.toString()
        val bad = topKeys.filter { !same(recorder.top[it], case[it]) }.toMutableList()
        if (wi.size != case["wi_length"].asInt) bad += "wi_length"
        if (sha256(wi) != case["wi_sha256"].asString) bad += "wi_sha256"
        val levels = case["levels"].asJsonArray.map { it.asJsonObject }
        if (levels.size != 5) bad += "levels"
        for (expected in levels) {
            val l = expected["level"].asInt
            for (k in levelKeys) if (!same(recorder.levels[l]?.get(k), expected[k])) bad += "level $l $k"
        }
        return bad
    }

    private fun errorOf(wi: ByteArray): String? = try {
        WiPortrait.decode(wi)
        null
    } catch (e: WiPortraitException) {
        e.error.code
    }

    private fun publicPhotos(): List<ByteArray>? {
        val file = File(System.getProperty("sadl.vectors") ?: "", "Reply.Net.SADL/Reply.Net.SADL.Tests/UnitTest1.cs")
        if (!file.isFile) {
            if (System.getProperty("sadl.requireVectors") == "true") fail("public vectors missing: run scripts/fetch-test-vectors.sh")
            return null
        }
        return Regex("const string (\\w+) = \"([0-9A-Fa-f]+)\"").findAll(file.readText()).map { match ->
            (SaLicenceBarcode.decode(hex(match.groupValues[2])) as LicenceBarcode.Card).licence.photo!!
        }.toList()
    }

    @Test
    fun publicVectorsMatchEveryCheckpoint() {
        val photos = publicPhotos()
        assumeTrue("run scripts/fetch-test-vectors.sh", photos != null)
        val checkpoints = cases("wi-checkpoints.json")
        val portraits = cases("public-vectors.json")
        assertEquals(6, photos!!.size)
        for (i in photos.indices) {
            assertEquals("vector ${i + 1}", emptyList<String>(), differences(photos[i], checkpoints[i]))
            assertEquals("vector ${i + 1}", portraits[i]["portrait_sha256"].asString, sha256(WiPortrait.decode(photos[i])))
            assertEquals("vector ${i + 1}", checkpoints[i]["C7_raster"].asString, sha256(WiPortrait.decodeNativeOrder(photos[i])))
        }
    }

    @Test
    fun syntheticValidStreamsMatchEveryCheckpoint() {
        val valid = cases("wi-synthetic.json").filter { !it.has("expected_error") }
        assertEquals(41, valid.size)
        for (case in valid) {
            val wi = hex(case["wi_hex"].asString)
            assertEquals(case["name"].asString, emptyList<String>(), differences(wi, case))
            assertEquals(case["name"].asString, case["C8_portrait"].asString, sha256(WiPortrait.decode(wi)))
        }
    }

    @Test
    fun syntheticInvalidStreamsFailWithTheExpectedError() {
        val invalid = cases("wi-synthetic.json").filter { it.has("expected_error") }
        assertEquals(26, invalid.size)
        for (case in invalid) {
            assertEquals(case["name"].asString, case["expected_error"].asString, errorOf(hex(case["wi_hex"].asString)))
        }
    }

    private fun validStream() = hex(cases("wi-synthetic.json").first { it["name"].asString == "random-01" }["wi_hex"].asString)

    @Test
    fun headerGateAcceptsOnlyTheLicenceProfile() {
        val valid = validStream()
        assertEquals(null, errorOf(valid))
        for (at in listOf(0, 1, 2, 3, 4, 5, 6, 9, 10, 11)) {
            for (value in 0..255) {
                if (value.toByte() == valid[at]) continue
                val data = valid.copyOf().also { it[at] = value.toByte() }
                assertEquals("byte $at", "E1", errorOf(data))
            }
        }
        for (value in 0..255) {
            val b7 = valid.copyOf().also { it[7] = value.toByte() }
            assertEquals("byte 7", value != 0x42 && value != 0x43, errorOf(b7) == "E1")
            val b8 = valid.copyOf().also { it[8] = value.toByte() }
            assertEquals("byte 8", value % 2 == 1, errorOf(b8) == "E1")
        }
    }

    @Test
    fun sizeLimits() {
        val valid = validStream()
        assertEquals("E1", errorOf(ByteArray(0)))
        assertEquals("E1", errorOf(valid.copyOf(12)))
        assertEquals("E2", errorOf(valid.copyOf(12) + byteArrayOf(0)))
        assertArrayEquals(WiPortrait.decode(valid), WiPortrait.decode(valid.copyOf(WiPortrait.MAX_WI_BYTES)))
        assertEquals("E1", errorOf(valid.copyOf(WiPortrait.MAX_WI_BYTES + 1)))
    }

    @Test
    fun uprightIsTheNativeOrderReversed() {
        val valid = validStream()
        assertArrayEquals(WiPortrait.decodeNativeOrder(valid).reversedArray(), WiPortrait.decode(valid))
    }

    /** Section 4.8: the initial codes, read from the root. */
    @Test
    fun huffmanInitialCodes() {
        fun code(m: AdaptiveCode, s: Int): String {
            val bits = StringBuilder()
            var i = m.nodeOf[s]
            while (m.parent[i] != -1) {
                bits.append(i - m.payload[m.parent[i]])
                i = m.parent[i]
            }
            return bits.reverse().toString()
        }
        val c = AdaptiveCode(2048, 3)
        assertEquals(listOf("10", "000", "001", "01", "11"), listOf(0, 1, 2, 2048, 2049).map { code(c, it) })
        assertEquals(9, c.next)
        assertEquals(5, c.weight[0])
        val m = AdaptiveCode(256, 64)
        assertEquals(131, m.next)
        assertEquals(66, m.weight[0])
        val expected = mapOf(0 to "010001", 1 to "110001", 14 to "000011", 29 to "111111", 30 to "000010", 45 to "100000",
            60 to "111110", 61 to "0000000", 62 to "0000010", 63 to "0000011", 256 to "0000001", 257 to "100001")
        for ((s, bits) in expected) assertEquals("map symbol $s", bits, code(m, s))
    }

    @Test
    fun exceptionMessagesHoldNoData() {
        val codes = WiPortraitException.Error.entries.map { it.code }
        assertEquals(listOf("E1", "E2", "E3", "E4", "E5"), codes)
        for (e in WiPortraitException.Error.entries) assertTrue(WiPortraitException(e).message!!.startsWith(e.code))
    }

    @Test
    fun concurrentDecodesGiveTheSameResult() {
        val streams = cases("wi-synthetic.json").filter { !it.has("expected_error") }.map { hex(it["wi_hex"].asString) }
        val expected = streams.map { sha256(WiPortrait.decode(it)) }
        val failures = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 8).map { t ->
            Thread {
                for (round in 0 until 3) {
                    for (i in streams.indices) {
                        if ((i + t + round) % 2 == 0 && sha256(WiPortrait.decode(streams[i])) != expected[i]) failures.incrementAndGet()
                    }
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        assertEquals(0, failures.get())
    }

    /** Random bytes and mutated synthetic streams (never the public vectors): decode or fail with E1 to E5. */
    @Test
    fun robustnessAgainstRandomAndMutatedInput() {
        val seeds = cases("wi-synthetic.json").filter { !it.has("expected_error") }.map { hex(it["wi_hex"].asString) }
        val header = seeds[0].copyOf(12)
        val rng = Random(20260926L)
        val counts = HashMap<String, Int>()
        val iterations = 20_000
        repeat(iterations) { n ->
            val data = when (n % 4) {
                0 -> ByteArray(rng.nextInt(700)).also { rng.nextBytes(it) }
                1 -> header + ByteArray(1 + rng.nextInt(662)).also { rng.nextBytes(it) }
                else -> {
                    var d = seeds[rng.nextInt(seeds.size)].copyOf()
                    repeat(1 + rng.nextInt(6)) {
                        val at = if (rng.nextInt(10) == 0) rng.nextInt(d.size) else 12 + rng.nextInt(d.size - 12)
                        when (rng.nextInt(3)) {
                            0 -> d[at] = (d[at].toInt() xor (1 shl rng.nextInt(8))).toByte()
                            1 -> d[at] = rng.nextInt(256).toByte()
                            else -> d = d.copyOfRange(0, at) + byteArrayOf(rng.nextInt(256).toByte()) + d.copyOfRange(at, d.size)
                        }
                    }
                    if (rng.nextInt(4) == 0) d.copyOf(rng.nextInt(d.size + 1)) else d
                }
            }
            val outcome = try {
                assertEquals(WiPortrait.PIXELS, WiPortrait.decode(data).size)
                "ok"
            } catch (e: WiPortraitException) {
                e.error.code
            }
            counts[outcome] = (counts[outcome] ?: 0) + 1
        }
        assertEquals(iterations, counts.values.sum())
        assertNotEquals(null, counts["ok"])
    }

    @Test
    fun timing() {
        val streams = publicPhotos() ?: cases("wi-synthetic.json").filter { !it.has("expected_error") }.map { hex(it["wi_hex"].asString) }
        repeat(200) { for (s in streams) WiPortrait.decode(s) } // warm up the JIT
        val rounds = 200
        val start = System.nanoTime()
        repeat(rounds) { for (s in streams) WiPortrait.decode(s) }
        val each = (System.nanoTime() - start) / 1e6 / (rounds * streams.size)
        println("WiPortrait: one decode %.3f ms (mean of %d, after warm-up)".format(java.util.Locale.ROOT, each, rounds * streams.size))
    }
}
