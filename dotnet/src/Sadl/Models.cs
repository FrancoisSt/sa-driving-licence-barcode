using System;
using System.Collections.Generic;
using System.Linq;

namespace Sadl
{
    // The ToString() overrides below leave out every personal value, so an accidental log line leaks nothing.

    /// <summary>Which kind of barcode the raw bytes hold, judged from the first bytes only.</summary>
    public enum BarcodeKind
    {
        /// <summary>The back of a licence card: starts with a known version.</summary>
        Card,

        /// <summary>The paper temporary driving licence: text starting with "%TDL", two digits and "%".</summary>
        TemporaryLicence,

        /// <summary>Anything else, for example a vehicle licence disc.</summary>
        Other,
    }

    /// <summary>A decoded driving-licence barcode: a <see cref="Card"/> or a <see cref="Temporary"/>.</summary>
    public abstract class DecodedBarcode
    {
        private DecodedBarcode()
        {
        }

        /// <summary>The back of a licence card.</summary>
        public sealed class Card : DecodedBarcode
        {
            /// <summary>Wraps a decoded card.</summary>
            public Card(CardLicence licence) => Licence = licence ?? throw new ArgumentNullException(nameof(licence));

            /// <summary>The card's fields.</summary>
            public CardLicence Licence { get; }

            /// <inheritdoc/>
            public override string ToString() => $"Card({Licence})";
        }

        /// <summary>A paper temporary driving licence.</summary>
        public sealed class Temporary : DecodedBarcode
        {
            /// <summary>Wraps a decoded temporary licence.</summary>
            public Temporary(TemporaryLicence licence) => Licence = licence ?? throw new ArgumentNullException(nameof(licence));

            /// <summary>The temporary licence's fields.</summary>
            public TemporaryLicence Licence { get; }

            /// <inheritdoc/>
            public override string ToString() => $"Temporary({Licence})";
        }
    }

    /// <summary>One vehicle code with its vehicle restriction and first-issue date.</summary>
    public sealed class VehicleCode : IEquatable<VehicleCode>
    {
        /// <summary>Creates a vehicle code entry.</summary>
        public VehicleCode(string code, string vehicleRestriction, DateTime? firstIssue)
        {
            Code = code;
            VehicleRestriction = vehicleRestriction;
            FirstIssue = firstIssue;
        }

        /// <summary>For example "EC" or "B".</summary>
        public string Code { get; }

        /// <summary>"0" none, "1" automatic transmission, "2" electrically powered, "3" physically disabled, "4" bus over 16 000 kg (GVM) permitted.</summary>
        public string VehicleRestriction { get; }

        /// <summary>When this code was first issued (a date, no time), or null when the barcode has no date for it.</summary>
        public DateTime? FirstIssue { get; }

        /// <inheritdoc/>
        public bool Equals(VehicleCode? other) =>
            other != null && Code == other.Code && VehicleRestriction == other.VehicleRestriction && FirstIssue == other.FirstIssue;

        /// <inheritdoc/>
        public override bool Equals(object? obj) => Equals(obj as VehicleCode);

        /// <inheritdoc/>
        public override int GetHashCode() => (Code.GetHashCode() * 31 + VehicleRestriction.GetHashCode()) * 31 + FirstIssue.GetHashCode();

        /// <inheritdoc/>
        public override string ToString() => $"VehicleCode({Code}, restriction {VehicleRestriction})";
    }

    /// <summary>The fields of a licence card's barcode (spec/card-barcode.md). Dates are <see cref="DateTime"/> values with no time part.</summary>
    public sealed class CardLicence
    {
        /// <summary>Creates a card licence from its fields.</summary>
        public CardLicence(
            int version,
            IReadOnlyList<VehicleCode> codes,
            string surname,
            string initials,
            IReadOnlyList<string> prdpCategories,
            DateTime? prdpExpiry,
            string idCountry,
            string licenceCountry,
            string licenceNumber,
            string idNumber,
            string idType,
            string driverRestrictions,
            string issueNumber,
            DateTime birthDate,
            DateTime validFrom,
            DateTime validTo,
            string genderCode,
            byte[]? photo)
        {
            Version = version;
            Codes = codes;
            Surname = surname;
            Initials = initials;
            PrdpCategories = prdpCategories;
            PrdpExpiry = prdpExpiry;
            IdCountry = idCountry;
            LicenceCountry = licenceCountry;
            LicenceNumber = licenceNumber;
            IdNumber = idNumber;
            IdType = idType;
            DriverRestrictions = driverRestrictions;
            IssueNumber = issueNumber;
            BirthDate = birthDate;
            ValidFrom = validFrom;
            ValidTo = validTo;
            GenderCode = genderCode;
            Photo = photo;
        }

        /// <summary>The barcode version, 1 or 2 (every card seen so far is version 2).</summary>
        public int Version { get; }

        /// <summary>The vehicle codes, in position order, empty positions left out.</summary>
        public IReadOnlyList<VehicleCode> Codes { get; }

        /// <summary>String 5.</summary>
        public string Surname { get; }

        /// <summary>String 6.</summary>
        public string Initials { get; }

        /// <summary>The professional driving permit categories, for example [G, P]; empty when none.</summary>
        public IReadOnlyList<string> PrdpCategories { get; }

        /// <summary>The PrDP expiry, or null when there is none.</summary>
        public DateTime? PrdpExpiry { get; }

        /// <summary>String 8.</summary>
        public string IdCountry { get; }

        /// <summary>String 9.</summary>
        public string LicenceCountry { get; }

        /// <summary>String 14: 12 characters.</summary>
        public string LicenceNumber { get; }

        /// <summary>The 13 characters after string 14.</summary>
        public string IdNumber { get; }

        /// <summary>"02" for a South African ID number.</summary>
        public string IdType { get; }

        /// <summary>Two digits, one restriction each: 0 none, 1 glasses or contact lenses, 2 artificial limb.</summary>
        public string DriverRestrictions { get; }

        /// <summary>The licence issue number, "01" so far.</summary>
        public string IssueNumber { get; }

        /// <summary>The birth date.</summary>
        public DateTime BirthDate { get; }

        /// <summary>The first day of validity.</summary>
        public DateTime ValidFrom { get; }

        /// <summary>The last day of validity.</summary>
        public DateTime ValidTo { get; }

        /// <summary>"01" male, "02" female.</summary>
        public string GenderCode { get; }

        /// <summary>The portrait section including its "WI" header, for <see cref="WiPortrait.Decode"/>; null when empty.</summary>
        public byte[]? Photo { get; }

        /// <summary>True when the driver must wear glasses or contact lenses.</summary>
        public bool NeedsCorrectiveLenses => DriverRestrictions.IndexOf('1') >= 0;

        /// <summary>True when the driver has an artificial limb.</summary>
        public bool HasArtificialLimb => DriverRestrictions.IndexOf('2') >= 0;

        /// <inheritdoc/>
        public override string ToString() =>
            $"CardLicence(version {Version}, codes [{string.Join(", ", Codes.Select(c => c.Code))}], photo {Photo?.Length ?? 0} bytes)";
    }

    /// <summary>The fields of a paper temporary driving licence's barcode (spec/temporary-licence.md).</summary>
    public sealed class TemporaryLicence
    {
        /// <summary>Creates a temporary licence from its fields.</summary>
        public TemporaryLicence(
            string tag,
            string field2,
            string serial,
            string field4,
            string licenceNumber,
            string idType,
            string idNumber,
            string name,
            IReadOnlyList<VehicleCode> codes,
            IReadOnlyList<string> prdpCategories,
            DateTime? prdpExpiry,
            DateTime issueDate)
        {
            Tag = tag;
            Field2 = field2;
            Serial = serial;
            Field4 = field4;
            LicenceNumber = licenceNumber;
            IdType = idType;
            IdNumber = idNumber;
            Name = name;
            Codes = codes;
            PrdpCategories = prdpCategories;
            PrdpExpiry = prdpExpiry;
            IssueDate = issueDate;
        }

        /// <summary>"TDL" and two digits; the digits' meaning is unknown.</summary>
        public string Tag { get; }

        /// <summary>Four digits, meaning unknown.</summary>
        public string Field2 { get; }

        /// <summary>The temporary licence's own serial number (8 characters).</summary>
        public string Serial { get; }

        /// <summary>"1" so far, meaning unknown.</summary>
        public string Field4 { get; }

        /// <summary>The driving licence's number (field 5). This is not the "No." printed on the form.</summary>
        public string LicenceNumber { get; }

        /// <summary>"02" for a South African ID number.</summary>
        public string IdType { get; }

        /// <summary>The ID number.</summary>
        public string IdNumber { get; }

        /// <summary>Initials and surname, as one string.</summary>
        public string Name { get; }

        /// <summary>The vehicle codes, in field order.</summary>
        public IReadOnlyList<VehicleCode> Codes { get; }

        /// <summary>For example [G, P]; empty when none.</summary>
        public IReadOnlyList<string> PrdpCategories { get; }

        /// <summary>The PrDP expiry, or null.</summary>
        public DateTime? PrdpExpiry { get; }

        /// <summary>The date the temporary licence was issued.</summary>
        public DateTime IssueDate { get; }

        /// <summary>The issue date plus six months, clamped to the month's last day. Calculated: the barcode does not carry it.</summary>
        public DateTime ValidTo => IssueDate.AddMonths(6);

        /// <inheritdoc/>
        public override string ToString() => $"TemporaryLicence(codes [{string.Join(", ", Codes.Select(c => c.Code))}])";
    }

    /// <summary>
    /// The barcode was read but is not a valid licence. <see cref="Reason"/> says why, in the terms of
    /// spec/output-format.md. The message never contains decoded values, so it is safe to log.
    /// </summary>
    public sealed class LicenceBarcodeException : Exception
    {
        /// <summary>Creates the exception. <paramref name="message"/> must not contain decoded values.</summary>
        public LicenceBarcodeException(LicenceBarcodeError reason, string message) : base(message) => Reason = reason;

        /// <summary>Why the barcode was rejected.</summary>
        public LicenceBarcodeError Reason { get; }

        /// <summary>The canonical error code, for example "block_check_failed".</summary>
        public string ReasonCode => Reason switch
        {
            LicenceBarcodeError.WrongLength => "wrong_length",
            LicenceBarcodeError.UnknownVersion => "unknown_version",
            LicenceBarcodeError.BlockCheckFailed => "block_check_failed",
            LicenceBarcodeError.MalformedPayload => "malformed_payload",
            _ => "not_a_licence",
        };
    }

    /// <summary>The canonical rejection reasons of spec/output-format.md.</summary>
    public enum LicenceBarcodeError
    {
        /// <summary>A card back must be exactly 720 bytes.</summary>
        WrongLength,

        /// <summary>The first four bytes are not a known card version.</summary>
        UnknownVersion,

        /// <summary>A block is not below its modulus, or did not start with its marker: a misread, or the wrong key.</summary>
        BlockCheckFailed,

        /// <summary>The payload or the temporary licence text does not follow the layout.</summary>
        MalformedPayload,

        /// <summary>Neither a card nor a temporary licence, for example a vehicle licence disc.</summary>
        NotALicence,
    }
}
