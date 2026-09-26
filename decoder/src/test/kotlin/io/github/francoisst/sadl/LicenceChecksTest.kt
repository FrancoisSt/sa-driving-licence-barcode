package io.github.francoisst.sadl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LicenceChecksTest {
    private val payload = "020000000036001a02524543e1e1e1544553544552e054e0472c50e05a41e05a41e030e1e1e1303030303030303030" +
        "304243e0303030313031303030303038390220100101aaa00202701010120000101202501012029123102a57490400fa00c8"

    private fun card(): CardLicence {
        val bytes = ByteArray(SaLicenceBarcode.CARD_PAYLOAD_SIZE)
        payload.chunked(2).forEachIndexed { i, h -> bytes[i] = h.toInt(16).toByte() }
        return SaLicenceBarcode.parseCard(bytes)
    }

    @Test
    fun luhn() {
        assertTrue(LicenceChecks.idNumberValid("0001010000089"))
        assertFalse(LicenceChecks.idNumberValid("0001010000088"))
        assertFalse(LicenceChecks.idNumberValid("000101000008"))
        assertFalse(LicenceChecks.idNumberValid("000101000008A"))
    }

    @Test
    fun idAndBirthDate() {
        assertTrue(LicenceChecks.idMatchesBirthDate("0001010000089", LocalDate.of(2000, 1, 1)))
        assertFalse(LicenceChecks.idMatchesBirthDate("0001010000089", LocalDate.of(1900, 1, 2)))
    }

    @Test
    fun licenceNumber() {
        assertTrue(LicenceChecks.licenceNumberLooksValid("0000000000BC"))
        assertFalse(LicenceChecks.licenceNumberLooksValid("000000A000BC"))
        assertFalse(LicenceChecks.licenceNumberLooksValid("0000000000bc"))
    }

    @Test
    fun theSyntheticCardPasses() {
        assertEquals(emptyList<LicenceChecks.Finding>(), LicenceChecks.check(card()))
    }

    @Test
    fun toStringLeavesOutPersonalValues() {
        val text = LicenceBarcode.Card(card()).toString()
        for (value in listOf("TESTER", "0001010000089", "0000000000BC", "2000-01-01")) assertFalse(text, value in text)
    }
}
