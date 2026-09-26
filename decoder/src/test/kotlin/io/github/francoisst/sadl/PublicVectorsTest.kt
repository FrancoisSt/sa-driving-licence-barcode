package io.github.francoisst.sadl

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The six public encrypted vectors from Reply.Net.SADL, fetched by scripts/fetch-test-vectors.sh. They hold what look
 * like real people's details: assertion messages name fields, never values.
 */
class PublicVectorsTest {
    private class Vector(val raw: ByteArray, val expected: Map<String, String>)

    private val vectors: List<Vector>? by lazy {
        val file = File(System.getProperty("sadl.vectors") ?: "", "Reply.Net.SADL/Reply.Net.SADL.Tests/UnitTest1.cs")
        if (!file.isFile) {
            if (System.getProperty("sadl.requireVectors") == "true") fail("public vectors missing: run scripts/fetch-test-vectors.sh")
            return@lazy null
        }
        val src = file.readText()
        val constants = Regex("const string (\\w+) = \"([0-9A-Fa-f]+)\"").findAll(src).associate { it.groupValues[1] to it.groupValues[2] }
        val expected = src.split("[Test]").drop(1).associate { body ->
            val name = Regex("DecryptDriversLicence\\((\\w+)\\)").find(body)!!.groupValues[1]
            val strings = Regex("Assert\\.AreEqual\\(\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
            val (y, m, d) = Regex("new DateTime\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)").find(body)!!.destructured
            name to mapOf(
                "surname" to strings[0],
                "initials" to strings[1],
                "licence_number" to strings[2],
                "valid_to" to "%04d-%02d-%02d".format(java.util.Locale.ROOT, y.toInt(), m.toInt(), d.toInt()),
            )
        }
        constants.map { (name, hex) -> Vector(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), expected.getValue(name)) }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private fun requireVectors(): List<Vector> = vectors.also { assumeTrue("run scripts/fetch-test-vectors.sh", it != null) }!!

    @Test
    fun the24ExpectedValuesMatch() {
        val all = requireVectors()
        assertEquals(6, all.size)
        all.forEachIndexed { i, v ->
            val card = (SaLicenceBarcode.decode(v.raw) as LicenceBarcode.Card).licence
            val got = mapOf(
                "surname" to card.surname,
                "initials" to card.initials,
                "licence_number" to card.licenceNumber,
                "valid_to" to card.validTo.toString(),
            )
            for ((field, value) in v.expected) assertTrue("vector ${i + 1}: $field differs", got[field] == value)
        }
    }

    @Test
    fun payloadAndPhotoHashesMatchTheSpec() {
        val all = requireVectors()
        val hashes = JsonParser.parseString(File(System.getProperty("sadl.spec"), "public-vectors.json").readText())
            .asJsonObject["cases"].asJsonArray.map { it.asJsonObject }
        all.zip(hashes).forEach { (v, h) ->
            assertEquals(h["raw_sha256"].asString, sha256(v.raw))
            assertEquals("vector ${h["index"]}", h["payload_sha256"].asString, sha256(SaLicenceBarcode.decrypt(v.raw)))
            val card = (SaLicenceBarcode.decode(v.raw) as LicenceBarcode.Card).licence
            assertEquals(h["photo_sha256"].asString, sha256(card.photo!!))
        }
    }

    @Test
    fun everyDecodedCardPassesTheChecks() {
        requireVectors().forEachIndexed { i, v ->
            val card = (SaLicenceBarcode.decode(v.raw) as LicenceBarcode.Card).licence
            val blocking = LicenceChecks.check(card).filter { it.blocking }
            assertTrue("vector ${i + 1}: $blocking", blocking.isEmpty())
        }
    }

    @Test
    fun tamperedInputsAreRejected() {
        for (v in requireVectors()) {
            for (k in 0 until 6) {
                val bad = v.raw.copyOf().also { it[6 + 128 * k + 40] = (it[6 + 128 * k + 40].toInt() xor 1).toByte() }
                assertEquals(SaLicenceBarcodeException.Reason.BLOCK_CHECK_FAILED, reason { SaLicenceBarcode.decrypt(bad) })
            }
            val v3 = v.raw.copyOf().also { it[3] = 0x46 }
            assertEquals(SaLicenceBarcodeException.Reason.UNKNOWN_VERSION, reason { SaLicenceBarcode.decrypt(v3) })
            val v1 = v.raw.copyOf().also { byteArrayOf(0x01, 0xE1.toByte(), 0x02, 0x45).copyInto(it) }
            assertEquals(SaLicenceBarcodeException.Reason.BLOCK_CHECK_FAILED, reason { SaLicenceBarcode.decrypt(v1) })
            assertEquals(SaLicenceBarcodeException.Reason.WRONG_LENGTH, reason { SaLicenceBarcode.decrypt(v.raw.copyOf(719)) })
        }
    }

    private fun reason(block: () -> Unit): SaLicenceBarcodeException.Reason? = try {
        block()
        null
    } catch (e: SaLicenceBarcodeException) {
        e.reason
    }
}
