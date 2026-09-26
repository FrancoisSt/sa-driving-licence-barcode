package io.github.francoisst.sadl

import java.time.LocalDate

// The toString() methods below leave out every personal value, so an accidental log line leaks nothing.

/** A decoded driving-licence barcode. */
public sealed class LicenceBarcode {
    /** The back of a licence card. */
    public class Card(public val licence: CardLicence) : LicenceBarcode() {
        override fun toString(): String = "Card($licence)"
    }

    /** A paper temporary driving licence. */
    public class Temporary(public val licence: TemporaryLicence) : LicenceBarcode() {
        override fun toString(): String = "Temporary($licence)"
    }
}

/**
 * One vehicle code with its vehicle restriction and first-issue date.
 *
 * @property code for example "EC" or "B".
 * @property vehicleRestriction "0" none, "1" automatic transmission, "2" electrically powered, "3" physically
 *   disabled, "4" bus over 16 000 kg (GVM) permitted.
 * @property firstIssue when this code was first issued, or null when the barcode has no date for it.
 */
public class VehicleCode(
    public val code: String,
    public val vehicleRestriction: String,
    public val firstIssue: LocalDate?,
) {
    override fun equals(other: Any?): Boolean = other is VehicleCode && code == other.code &&
        vehicleRestriction == other.vehicleRestriction && firstIssue == other.firstIssue

    override fun hashCode(): Int = (code.hashCode() * 31 + vehicleRestriction.hashCode()) * 31 + firstIssue.hashCode()

    override fun toString(): String = "VehicleCode($code, restriction $vehicleRestriction)"
}

/**
 * The fields of a licence card's barcode.
 *
 * @property version the barcode version, 1 or 2 (every card seen so far is version 2).
 * @property prdpCategories the professional driving permit categories, for example [G, P]; empty when none.
 * @property idType "02" for a South African ID number.
 * @property driverRestrictions two digits, one restriction each: 0 none, 1 glasses or contact lenses, 2 artificial
 *   limb. "10" means glasses.
 * @property issueNumber the licence issue number, "01" so far.
 * @property genderCode "01" male, "02" female.
 * @property photo the portrait section, including its "WI" header: a Summus wavelet image of about 600 bytes that
 *   [WiPortrait] decodes into a 200 x 250 greyscale image. Null when the section is empty.
 */
public class CardLicence(
    public val version: Int,
    public val codes: List<VehicleCode>,
    public val surname: String,
    public val initials: String,
    public val prdpCategories: List<String>,
    public val prdpExpiry: LocalDate?,
    public val idCountry: String,
    public val licenceCountry: String,
    public val licenceNumber: String,
    public val idNumber: String,
    public val idType: String,
    public val driverRestrictions: String,
    public val issueNumber: String,
    public val birthDate: LocalDate,
    public val validFrom: LocalDate,
    public val validTo: LocalDate,
    public val genderCode: String,
    public val photo: ByteArray?,
) {
    /** True when the driver must wear glasses or contact lenses. */
    public val needsCorrectiveLenses: Boolean get() = '1' in driverRestrictions

    /** True when the driver has an artificial limb. */
    public val hasArtificialLimb: Boolean get() = '2' in driverRestrictions

    override fun toString(): String =
        "CardLicence(version $version, codes ${codes.map { it.code }}, photo ${photo?.size ?: 0} bytes)"
}

/**
 * The fields of a paper temporary driving licence's barcode.
 *
 * @property tag "TDL" and two digits; the digits' meaning is unknown.
 * @property field2 four digits, meaning unknown.
 * @property serial the temporary licence's own serial number (8 characters).
 * @property field4 "1" so far, meaning unknown.
 * @property licenceNumber the driving licence's number. This is **not** the "No." printed on the temporary licence.
 * @property name initials and surname, as one string.
 * @property prdpCategories for example [G, P]; empty when none.
 * @property issueDate the date the temporary licence was issued. It is valid for six months: see [validTo].
 */
public class TemporaryLicence(
    public val tag: String,
    public val field2: String,
    public val serial: String,
    public val field4: String,
    public val licenceNumber: String,
    public val idType: String,
    public val idNumber: String,
    public val name: String,
    public val codes: List<VehicleCode>,
    public val prdpCategories: List<String>,
    public val prdpExpiry: LocalDate?,
    public val issueDate: LocalDate,
) {
    /** The issue date plus six months. Calculated: the barcode does not carry it. */
    public val validTo: LocalDate get() = issueDate.plusMonths(6)

    override fun toString(): String = "TemporaryLicence(codes ${codes.map { it.code }})"
}
