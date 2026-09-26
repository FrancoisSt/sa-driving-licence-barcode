package io.github.francoisst.sadl

import io.github.francoisst.sadl.SaLicenceBarcodeException.Reason
import java.math.BigInteger
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Decodes the PDF417 barcode of a South African driving licence: the back of the card, and the paper temporary
 * driving licence.
 *
 * Pass the barcode reader's raw bytes, never its text: the card-back payload is binary, and a text rendering of it
 * is lossy. See spec/card-barcode.md for the format.
 *
 * ```kotlin
 * when (val licence = SaLicenceBarcode.decode(raw)) {
 *     is LicenceBarcode.Card -> licence.licence.licenceNumber
 *     is LicenceBarcode.Temporary -> licence.licence.issueDate
 * }
 * ```
 *
 * Plain Kotlin on the JVM, with no dependencies, so it runs on Android and on a server alike.
 */
public object SaLicenceBarcode {

    /** The number of bytes in a card-back barcode. */
    public const val CARD_BARCODE_SIZE: Int = 720

    /** The number of bytes left once the six RSA blocks are decrypted and their markers removed. */
    public const val CARD_PAYLOAD_SIZE: Int = 684

    /** Says which kind of barcode [raw] is, from its first bytes only. Nothing is decrypted or checked. */
    @JvmStatic
    public fun identify(raw: ByteArray?): BarcodeKind = when {
        raw == null -> BarcodeKind.OTHER
        version(raw) != 0 -> BarcodeKind.CARD
        isTemporaryLicence(raw) -> BarcodeKind.TEMPORARY_LICENCE
        else -> BarcodeKind.OTHER
    }

    /**
     * Decodes a card back or a temporary licence. Throws [SaLicenceBarcodeException] for anything else, and for a
     * misread: a wrong length, an unknown version, a failed block marker or a structure that does not add up. Keep
     * scanning when it throws.
     */
    @JvmStatic
    @Throws(SaLicenceBarcodeException::class)
    public fun decode(raw: ByteArray): LicenceBarcode = when (identify(raw)) {
        BarcodeKind.CARD -> LicenceBarcode.Card(parseCard(decrypt(raw), version(raw)))
        BarcodeKind.TEMPORARY_LICENCE -> LicenceBarcode.Temporary(parseTemporaryLicence(raw))
        BarcodeKind.OTHER -> throw SaLicenceBarcodeException(Reason.NOT_A_LICENCE, "not a South African driving-licence barcode")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Card back: 720 bytes = 4 version bytes, 2 zero bytes, five 128-byte RSA blocks, one 74-byte RSA block.

    private val VERSION_1 = byteArrayOf(0x01, 0xE1.toByte(), 0x02, 0x45)
    private val VERSION_2 = byteArrayOf(0x01, 0x9B.toByte(), 0x09, 0x45)

    private class PublicKey(modulusHex: String, exponentHex: String) {
        val n = BigInteger(modulusHex, 16)
        val e = BigInteger(exponentHex, 16)
    }

    // The published public keys (Stack Overflow 17549231, 2016): per version, one for the five 128-byte blocks and
    // one for the last, 74-byte block. They only undo the encryption; they are not secrets.
    private val KEYS: Map<Int, Pair<PublicKey, PublicKey>> = mapOf(
        1 to Pair(
            PublicKey(
                "fed2e1c27e3363316e77317a7a52c54981395186be4974760c72518d63e0544a" +
                    "48d088b332c5b0c370c765d65d983c1f9de0a42b310ccc07ae770bd2b61d6a4d" +
                    "cceac757689bdcbf608478faf312f6087cc496c3762cf5c4651caecda3499fae" +
                    "7edb7eb40e3e18eb304170e91ed5b156aace6f432d6eca6cc35851de8c678f67",
                "bb797ffdec7f9e42c9d6f79b137059db",
            ),
            PublicKey(
                "ff3cec6b5f40e3c3661451b9fcfaef3aeb06dc2329c0e6f4dccc9279726716ce" +
                    "15bbe05eed2c5711bcf8f5b6c8f7276db5c43bfaa3040dc01ab14b9c4d16f71c" +
                    "0ce5ea953f0c754c6b17",
                "db05ba822d9acc33fab7d8f427f9ce65",
            ),
        ),
        2 to Pair(
            PublicKey(
                "ca9f18ef6c3f3fa4c5a461fea54ab19406ba5ecd746d60a27492dca3d74e3b5c" +
                    "1d315f7b10383241809b029ebbd5de4d116030cc57f7d5a6c9a16f373bb14a50" +
                    "8523f7e80a4c744d9085663a4a1472d7af2c56ae41b5065f7efa0293bd3278ad" +
                    "693546f9f16219b79ff471a3636824cffcdb63a8ed8059e6b9a4f0db895381cb",
                "187092da6454ceb1853e6915f8466a05",
            ),
            PublicKey(
                "b404a0df11d1cacff1a1a048d4d573f953a62c583d74925927561a6d7a1e2b14" +
                    "042526af70b550547390ea6ec748d30fdb81adb490e0c36a1986b404b2f5f69e" +
                    "f5da1b663e59509130e7",
                "309cfed9719fe2a5e20c9bb44765382b",
            ),
        ),
    )

    /** 1 or 2 for a card back, 0 when the first four bytes are not a known version. */
    @JvmStatic
    public fun version(raw: ByteArray): Int = when {
        raw.size < 4 -> 0
        raw.copyOf(4).contentEquals(VERSION_1) -> 1
        raw.copyOf(4).contentEquals(VERSION_2) -> 2
        else -> 0
    }

    /**
     * 720 barcode bytes to the 684-byte payload. Each block is textbook RSA with the public key (m = c^e mod n, no
     * padding), and each decrypted block must start with its 5-byte marker, which is checked and removed.
     */
    @JvmStatic
    @Throws(SaLicenceBarcodeException::class)
    public fun decrypt(raw: ByteArray): ByteArray {
        if (raw.size != CARD_BARCODE_SIZE) {
            throw SaLicenceBarcodeException(Reason.WRONG_LENGTH, "length ${raw.size}, expected $CARD_BARCODE_SIZE")
        }
        val keys = KEYS[version(raw)] ?: throw SaLicenceBarcodeException(Reason.UNKNOWN_VERSION, "unknown version bytes")
        val out = ByteArray(CARD_PAYLOAD_SIZE)
        var o = 0
        for (k in 0 until 6) {
            val size = if (k < 5) 128 else 74
            val start = 6 + 128 * k
            val key = if (k < 5) keys.first else keys.second
            val c = BigInteger(1, raw.copyOfRange(start, start + size)) // unsigned, big-endian
            if (c >= key.n) throw SaLicenceBarcodeException(Reason.BLOCK_CHECK_FAILED, "block ${k + 1} outside the modulus")
            val block = toUnsignedFixed(c.modPow(key.e, key.n), size)
            for (j in 1..5) { // the marker: (j << k) & 0x7f for j = 1..5
                if ((block[j - 1].toInt() and 0xFF) != ((j shl k) and 0x7F)) {
                    throw SaLicenceBarcodeException(Reason.BLOCK_CHECK_FAILED, "block ${k + 1} marker mismatch")
                }
            }
            block.copyInto(out, o, 5, size)
            o += size - 5
        }
        return out
    }

    /** BigInteger.toByteArray() adds a sign byte or drops leading zeros: normalise to exactly [size] bytes. */
    private fun toUnsignedFixed(m: BigInteger, size: Int): ByteArray {
        val b = m.toByteArray()
        return when {
            b.size == size -> b
            b.size == size + 1 && b[0].toInt() == 0 -> b.copyOfRange(1, b.size)
            b.size < size -> ByteArray(size).also { b.copyInto(it, size - b.size) }
            else -> throw SaLicenceBarcodeException(Reason.BLOCK_CHECK_FAILED, "decrypted block longer than $size bytes")
        }
    }

    /**
     * The 684-byte payload (from [decrypt]) to its fields. [version] is only recorded on the result. Throws when the
     * structure does not add up.
     */
    @JvmStatic
    @JvmOverloads
    @Throws(SaLicenceBarcodeException::class)
    public fun parseCard(payload: ByteArray, version: Int = 2): CardLicence {
        val p = payload
        if (p.size < 10) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "payload too short")
        val s1 = p[5].u
        val s2 = p[7].u
        val s3 = ((p[8].u shl 8) or p[9].u) and 0x0FFF
        if (10 + s1 + s2 + s3 > p.size) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "section lengths exceed the payload")

        // Section 1: 14 Latin-1 strings, then the 13-character ID number with no delimiter. 0xE0 ends a string;
        // 0xE1 ends a string and marks the next one empty (n x 0xE1 in a row = n + 1 string ends).
        val strings = ArrayList<String>(14)
        val current = StringBuilder()
        val end1 = 10 + s1
        var i = 10
        while (i < end1 && strings.size < 14) {
            when (val c = p[i].u) {
                0xE0 -> {
                    strings += current.toString(); current.clear()
                }
                0xE1 -> {
                    strings += current.toString(); current.clear()
                    if (i + 1 < end1 && p[i + 1].u != 0xE1) strings += ""
                }
                else -> current.append(c.toChar()) // Latin-1: each byte is its own code point
            }
            i++
        }
        if (strings.size != 14) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "${strings.size} strings, expected 14")
        val idNumber = String(p, i, end1 - i, Charsets.ISO_8859_1)

        // Section 2: nibbles, high nibble first. A date is 8 digits (yyyyMMdd); a single 0xA nibble means "no date".
        val n = Nibbles(IntArray(s2 * 2) { k -> p[end1 + k / 2].u.let { if (k % 2 == 0) it shr 4 else it and 0x0F } })
        val idType = n.pair()
        val firstIssue = List(4) { n.date() }
        val driverRestrictions = n.pair()
        val prdpExpiry = n.date()
        val issueNumber = n.pair()
        val birthDate = n.date() ?: throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "no birth date")
        val validFrom = n.date() ?: throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "no valid-from date")
        val validTo = n.date() ?: throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "no valid-to date")
        val gender = n.pair()
        if (!n.doneOrPad()) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "nibbles left over")

        // Section 3: the portrait, a Summus wavelet image ("WI" header). Kept as bytes for WiPortrait.
        val photoStart = end1 + s2
        val photo = if (s3 > 0) p.copyOfRange(photoStart, photoStart + s3) else null

        val codes = (0 until 4).filter { strings[it].isNotEmpty() }
            .map { VehicleCode(strings[it], strings[9 + it], firstIssue[it]) }
        return CardLicence(
            version = version,
            codes = codes,
            surname = strings[4],
            initials = strings[5],
            prdpCategories = strings[6].split(',').map { it.trim() }.filter { it.isNotEmpty() },
            prdpExpiry = prdpExpiry,
            idCountry = strings[7],
            licenceCountry = strings[8],
            licenceNumber = strings[13],
            idNumber = idNumber,
            idType = idType,
            driverRestrictions = driverRestrictions,
            issueNumber = issueNumber,
            birthDate = birthDate,
            validFrom = validFrom,
            validTo = validTo,
            genderCode = gender,
            photo = photo,
        )
    }

    private val Byte.u: Int get() = toInt() and 0xFF

    private class Nibbles(private val v: IntArray) {
        private var at = 0

        private fun next(): Int {
            if (at >= v.size) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "ran out of nibbles")
            return v[at++]
        }

        fun pair(): String = Integer.toHexString(next()) + Integer.toHexString(next())

        fun date(): LocalDate? {
            if (at < v.size && v[at] == 0xA) {
                at++
                return null
            }
            val d = IntArray(8) { next() }
            if (d.any { it > 9 }) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "date nibble above 9")
            val year = d[0] * 1000 + d[1] * 100 + d[2] * 10 + d[3]
            if (year < 1) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "not a real date")
            return try {
                LocalDate.of(year, d[4] * 10 + d[5], d[6] * 10 + d[7])
            } catch (e: java.time.DateTimeException) {
                throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "not a real date")
            }
        }

        fun doneOrPad(): Boolean = at == v.size || (at == v.size - 1 && v[at] == 0xA)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Temporary driving licence: plain text, fields separated by '%'. The layout is inferred from real licences.

    private val TDL_START = Regex("%TDL\\d\\d%")
    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)

    /** True when [raw] starts with "%TDL", two digits and "%". */
    @JvmStatic
    public fun isTemporaryLicence(raw: ByteArray): Boolean =
        TDL_START.matchesAt(String(raw, 0, minOf(raw.size, 8), Charsets.ISO_8859_1), 0)

    /** Parses a temporary licence barcode. Throws when it is not one, or when a field is malformed. */
    @JvmStatic
    @Throws(SaLicenceBarcodeException::class)
    public fun parseTemporaryLicence(raw: ByteArray): TemporaryLicence {
        val text = String(raw, Charsets.ISO_8859_1)
        if (!TDL_START.matchesAt(text, 0)) throw SaLicenceBarcodeException(Reason.NOT_A_LICENCE, "not a temporary licence barcode")
        val f = text.split('%')
        if (f.size < 16) throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "${f.size} fields, expected 16")
        val codes = (9..12).filter { f[it].isNotEmpty() }.map { k ->
            val c = f[k].split('/')
            VehicleCode(c[0], c.getOrElse(2) { "" }, date(c.getOrNull(1)))
        }
        val prdp = f[13].split('/')
        val issueDate = date(f[14]) ?: throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "no issue date")
        return TemporaryLicence(
            tag = f[1],
            field2 = f[2],
            serial = f[3],
            field4 = f[4],
            licenceNumber = f[5],
            idType = f[6],
            idNumber = f[7],
            name = f[8],
            codes = codes,
            prdpCategories = prdp[0].map { it.toString() }, // "GP" here, not "G,P" as on the card
            prdpExpiry = date(prdp.getOrNull(1)),
            issueDate = issueDate,
        )
    }

    private val ISO_DATE_TEXT = Regex("\\d{4}-\\d{2}-\\d{2}")

    /** Exactly four year digits, year 1 to 9999: java.time alone would also take "0000" and "+20250". */
    private fun date(s: String?): LocalDate? {
        if (s.isNullOrEmpty()) return null
        if (!ISO_DATE_TEXT.matches(s) || s.startsWith("0000")) {
            throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "not a yyyy-MM-dd date")
        }
        return try {
            LocalDate.parse(s, ISO_DATE)
        } catch (e: DateTimeParseException) {
            throw SaLicenceBarcodeException(Reason.MALFORMED_PAYLOAD, "not a yyyy-MM-dd date")
        }
    }
}

/** Which kind of barcode the raw bytes hold. */
public enum class BarcodeKind {
    /** The back of a licence card: 720 bytes starting with a known version. */
    CARD,

    /** The paper temporary driving licence: text starting with "%TDL". */
    TEMPORARY_LICENCE,

    /** Anything else, for example a vehicle licence disc. */
    OTHER,
}

/**
 * The barcode was read but is not a valid licence. [reason] says why, in the terms of spec/output-format.md. The
 * message never contains decoded values, so it is safe to log.
 */
public class SaLicenceBarcodeException(public val reason: Reason, message: String) : Exception(message) {
    public enum class Reason {
        /** A card back must be exactly 720 bytes. */
        WRONG_LENGTH,

        /** The first four bytes are not a known card version. */
        UNKNOWN_VERSION,

        /** A decrypted block did not start with its marker: a misread, or the wrong key. */
        BLOCK_CHECK_FAILED,

        /** The payload decrypted but its structure does not add up. */
        MALFORMED_PAYLOAD,

        /** Not a driving-licence barcode at all, for example a vehicle licence disc. */
        NOT_A_LICENCE,
    }
}
