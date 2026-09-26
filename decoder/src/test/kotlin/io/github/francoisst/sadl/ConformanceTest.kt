package io.github.francoisst.sadl

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the language-neutral vectors in spec/test-vectors/ through this implementation. */
class ConformanceTest {
    private val dir = File(System.getProperty("sadl.spec") ?: "../spec/test-vectors")

    private fun cases(name: String): List<JsonObject> =
        JsonParser.parseString(File(dir, name).readText()).asJsonObject["cases"].asJsonArray.map { it.asJsonObject }

    private fun canonical(block: () -> String): JsonElement = JsonParser.parseString(
        try {
            block()
        } catch (e: SaLicenceBarcodeException) {
            CanonicalJson.of(e)
        },
    )

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** Names the differing keys only, never the values. */
    private fun differing(got: JsonElement, expected: JsonElement): String {
        if (!got.isJsonObject || !expected.isJsonObject) return "shape"
        val keys = got.asJsonObject.keySet() + expected.asJsonObject.keySet()
        return keys.filter { got.asJsonObject[it] != expected.asJsonObject[it] }.sorted().toString()
    }

    @Test
    fun parseCardVectors() {
        val all = cases("parse-card.json")
        assertTrue(all.size >= 10)
        for (case in all) {
            val got = canonical { CanonicalJson.of(SaLicenceBarcode.parseCard(hex(case["payload_hex"].asString))) }
            val expected = case["expected"]
            assertEquals("${case["name"].asString}: ${differing(got, expected)}", expected, got)
        }
    }

    @Test
    fun decodeVectors() {
        for (case in cases("decode.json")) {
            val raw = case["raw_hex"]?.let { hex(it.asString) } ?: case["raw_text"].asString.toByteArray(Charsets.ISO_8859_1)
            val got = canonical { CanonicalJson.of(SaLicenceBarcode.decode(raw)) }
            val expected = case["expected"]
            assertEquals("${case["name"].asString}: ${differing(got, expected)}", expected, got)
        }
    }
}

/** Runs spec/test-vectors/checks.json through [LicenceChecks]. */
class ChecksVectorsTest {
    private val dir = File(System.getProperty("sadl.spec") ?: "../spec/test-vectors")

    private fun date(o: JsonObject, key: String) = o[key]?.takeIf { !it.isJsonNull }?.let { java.time.LocalDate.parse(it.asString) }

    private fun codes(o: JsonObject) = o["vehicle_codes"].asJsonArray.map { it.asJsonObject }
        .map { VehicleCode(it["code"].asString, it["vehicle_restriction"].asString, date(it, "first_issue")) }

    private fun card(o: JsonObject) = CardLicence(
        version = o["version"].asInt, codes = codes(o), surname = o["surname"].asString, initials = o["initials"].asString,
        prdpCategories = o["prdp_categories"].asJsonArray.map { it.asString }, prdpExpiry = date(o, "prdp_expiry"),
        idCountry = o["id_country"].asString, licenceCountry = o["licence_country"].asString,
        licenceNumber = o["licence_number"].asString, idNumber = o["id_number"].asString, idType = o["id_type"].asString,
        driverRestrictions = o["driver_restrictions"].asString, issueNumber = o["issue_number"].asString,
        birthDate = date(o, "birth_date")!!, validFrom = date(o, "valid_from")!!, validTo = date(o, "valid_to")!!,
        genderCode = o["gender"].asString, photo = null,
    )

    private fun temporary(o: JsonObject) = TemporaryLicence(
        tag = "", field2 = "", serial = "", field4 = "", licenceNumber = o["licence_number"].asString,
        idType = o["id_type"].asString, idNumber = o["id_number"].asString, name = "", codes = codes(o),
        prdpCategories = emptyList(), prdpExpiry = null, issueDate = java.time.LocalDate.of(2025, 1, 1),
    )

    @Test
    fun checksVectors() {
        val cases = JsonParser.parseString(File(dir, "checks.json").readText()).asJsonObject["cases"].asJsonArray
        assertTrue(cases.size() >= 12)
        for (c in cases.map { it.asJsonObject }) {
            val licence = c["licence"].asJsonObject
            val findings = if (licence["kind"].asString == "card") LicenceChecks.check(card(licence)) else LicenceChecks.check(temporary(licence))
            val expected = c["expected"].asJsonArray.map { it.asString }
            assertEquals(c["name"].asString, expected, findings.map { it.name.lowercase() })
        }
    }
}
