package io.github.francoisst.sadl

import java.time.LocalDate

/**
 * Sanity checks to run after decoding. The block markers already make a misread very unlikely; these catch a
 * barcode that decodes but does not make sense, and an ID number that belongs to someone else.
 */
public object LicenceChecks {

    /** The South African vehicle codes. */
    @JvmField
    public val KNOWN_CODES: Set<String> = setOf("A1", "A", "B", "EB", "C1", "C", "EC1", "EC")

    private val LICENCE_NUMBER = Regex("\\d{7}[0-9A-Z]{5}")

    /** 12 characters, the first 7 of them digits. It has no check character, so this is all that can be checked. */
    @JvmStatic
    public fun licenceNumberLooksValid(s: String?): Boolean = s != null && LICENCE_NUMBER.matches(s)

    /** A South African ID number: 13 digits with a valid Luhn check digit. */
    @JvmStatic
    public fun idNumberValid(id: String?): Boolean {
        if (id == null || id.length != 13 || !id.all { it in '0'..'9' }) return false
        var sum = 0
        for (k in 0 until 13) {
            var d = id[12 - k] - '0'
            if (k % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    /** The ID number's first six digits (yyMMdd) are the birth date. */
    @JvmStatic
    public fun idMatchesBirthDate(id: String?, birthDate: LocalDate?): Boolean {
        if (id == null || birthDate == null || id.length < 6) return false
        val yymmdd = "%02d%02d%02d".format(java.util.Locale.ROOT, birthDate.year % 100, birthDate.monthValue, birthDate.dayOfMonth)
        return id.substring(0, 6) == yymmdd
    }

    /** Runs every check on a card. An empty list means the card passed. */
    @JvmStatic
    public fun check(card: CardLicence): List<Finding> = buildList {
        if (!licenceNumberLooksValid(card.licenceNumber)) add(Finding.LICENCE_NUMBER_FORMAT)
        if (!idNumberValid(card.idNumber)) add(Finding.ID_NUMBER_CHECK_DIGIT)
        if (!idMatchesBirthDate(card.idNumber, card.birthDate)) add(Finding.ID_NUMBER_BIRTH_DATE)
        if (!card.validFrom.isBefore(card.validTo)) {
            add(Finding.VALIDITY_ORDER)
        } else if (card.validTo != card.validFrom.plusYears(5).minusDays(1)) {
            add(Finding.VALIDITY_NOT_FIVE_YEARS)
        }
        if (card.codes.any { it.code !in KNOWN_CODES }) add(Finding.UNKNOWN_CODE)
    }

    /** Runs the checks that apply to a temporary licence. */
    @JvmStatic
    public fun check(tdl: TemporaryLicence): List<Finding> = buildList {
        if (!licenceNumberLooksValid(tdl.licenceNumber)) add(Finding.LICENCE_NUMBER_FORMAT)
        if (tdl.idType == "02" && !idNumberValid(tdl.idNumber)) add(Finding.ID_NUMBER_CHECK_DIGIT)
        if (tdl.codes.any { it.code !in KNOWN_CODES }) add(Finding.UNKNOWN_CODE)
    }

    /**
     * Something a check found. [blocking] findings mean the decoded data cannot be right: reject the read. The
     * others are worth flagging but are not proof of a fault.
     */
    public enum class Finding(public val blocking: Boolean) {
        /** The licence number is not 12 characters starting with 7 digits. */
        LICENCE_NUMBER_FORMAT(true),

        /** The ID number is not 13 digits with a valid Luhn check digit. */
        ID_NUMBER_CHECK_DIGIT(true),

        /** The ID number's first six digits are not the birth date. */
        ID_NUMBER_BIRTH_DATE(true),

        /** Valid from is not before valid to. */
        VALIDITY_ORDER(true),

        /** Cards normally run five years. */
        VALIDITY_NOT_FIVE_YEARS(false),

        /** A vehicle code outside [KNOWN_CODES]. */
        UNKNOWN_CODE(false),
    }
}
