package io.github.francoisst.sadl.demo

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import io.github.francoisst.sadl.LicenceBarcode
import io.github.francoisst.sadl.SaLicenceBarcode
import io.github.francoisst.sadl.WiPortrait
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pure-Kotlin portrait decoder on the device's own runtime (ART): the six public vectors' portraits and the
 * synthetic streams must give the hashes in spec/test-vectors. Needs scripts/fetch-test-vectors.sh before the build
 * for the public vectors. Never writes or prints pixels or values.
 */
@RunWith(AndroidJUnit4::class)
class PortraitOnDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun asset(name: String): String? = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: java.io.IOException) {
        null
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun publicVectorPortraitsMatchTheSpec() {
        val src = asset("UnitTest1.cs")
        assumeTrue("run scripts/fetch-test-vectors.sh, then rebuild", src != null)
        val hashes = JsonParser.parseString(asset("public-vectors.json")!!).asJsonObject["cases"].asJsonArray
        val vectors = Regex("const string (\\w+) = \"([0-9A-Fa-f]+)\"").findAll(src!!).map { hex(it.groupValues[2]) }.toList()
        assertEquals(6, vectors.size)
        vectors.forEachIndexed { i, raw ->
            val card = (SaLicenceBarcode.decode(raw) as LicenceBarcode.Card).licence
            val started = System.nanoTime()
            val pixels = WiPortrait.decode(card.photo!!)
            Log.i("SadlDemo", "vector ${i + 1}: portrait in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertEquals("vector ${i + 1}", hashes[i].asJsonObject["portrait_sha256"].asString, sha256(pixels))
        }
    }

    @Test
    fun syntheticStreamsMatchTheSpec() {
        val cases = JsonParser.parseString(asset("wi-synthetic.json")!!).asJsonObject["cases"].asJsonArray.map { it.asJsonObject }
        for (c in cases) {
            val wi = hex(c["wi_hex"].asString)
            val expectedError = c["expected_error"]?.asString
            if (expectedError == null) {
                assertEquals(c["name"].asString, c["C8_portrait"].asString, sha256(WiPortrait.decode(wi)))
            } else {
                val code = try {
                    WiPortrait.decode(wi)
                    "none"
                } catch (e: io.github.francoisst.sadl.WiPortraitException) {
                    e.error.code
                }
                assertEquals(c["name"].asString, expectedError, code)
            }
        }
    }
}
