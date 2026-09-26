package io.github.francoisst.sadl

import java.time.LocalDate

/**
 * Writes a decoded licence in the canonical JSON form of spec/output-format.md, the form the conformance vectors in
 * spec/test-vectors/ expect. Any implementation, in any language, that produces the same JSON for the same input
 * decodes the same way.
 *
 * The output holds personal data: show it or send it, but do not log it.
 */
public object CanonicalJson {

    @JvmStatic
    public fun of(barcode: LicenceBarcode): String = when (barcode) {
        is LicenceBarcode.Card -> of(barcode.licence)
        is LicenceBarcode.Temporary -> of(barcode.licence)
    }

    @JvmStatic
    public fun of(c: CardLicence): String = obj(
        "kind" to "card",
        "version" to c.version,
        "licence_number" to c.licenceNumber,
        "licence_country" to c.licenceCountry,
        "surname" to c.surname,
        "initials" to c.initials,
        "id_number" to c.idNumber,
        "id_type" to c.idType,
        "id_country" to c.idCountry,
        "birth_date" to c.birthDate,
        "gender" to c.genderCode,
        "valid_from" to c.validFrom,
        "valid_to" to c.validTo,
        "issue_number" to c.issueNumber,
        "vehicle_codes" to c.codes,
        "driver_restrictions" to c.driverRestrictions,
        "prdp_categories" to c.prdpCategories,
        "prdp_expiry" to c.prdpExpiry,
        "photo_length" to (c.photo?.size ?: 0),
    )

    @JvmStatic
    public fun of(t: TemporaryLicence): String = obj(
        "kind" to "temporary_licence",
        "tag" to t.tag,
        "field2" to t.field2,
        "serial" to t.serial,
        "field4" to t.field4,
        "licence_number" to t.licenceNumber,
        "id_type" to t.idType,
        "id_number" to t.idNumber,
        "name" to t.name,
        "vehicle_codes" to t.codes,
        "prdp_categories" to t.prdpCategories,
        "prdp_expiry" to t.prdpExpiry,
        "issue_date" to t.issueDate,
        "valid_to" to t.validTo,
    )

    /** The canonical error form: `{"error": "block_check_failed"}`. */
    @JvmStatic
    public fun of(e: SaLicenceBarcodeException): String = obj("error" to e.reason.name.lowercase())

    private fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(", ", "{", "}") { (k, v) -> "${str(k)}: ${value(v)}" }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Int -> v.toString()
        is LocalDate -> str(v.toString()) // ISO yyyy-MM-dd
        is VehicleCode -> obj("code" to v.code, "vehicle_restriction" to v.vehicleRestriction, "first_issue" to v.firstIssue)
        is List<*> -> v.joinToString(", ", "[", "]") { value(it) }
        else -> error("unsupported ${v::class}")
    }

    private fun str(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch < ' ' || ch.code > 0x7E -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}
