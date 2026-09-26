using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;

namespace Sadl
{
    /// <summary>
    /// Sanity checks to run after decoding (spec/checks.md). The block markers already make a misread very
    /// unlikely; these catch a barcode that decodes but does not make sense.
    /// </summary>
    public static class LicenceChecks
    {
        private static readonly HashSet<string> Known =
            new HashSet<string>(StringComparer.Ordinal) { "A1", "A", "B", "EB", "C1", "C", "EC1", "EC" };

        /// <summary>The South African vehicle codes.</summary>
        public static IReadOnlyCollection<string> KnownCodes => Known;

        /// <summary>True for one of <see cref="KnownCodes"/>.</summary>
        public static bool IsKnownCode(string code) => Known.Contains(code);

        /// <summary>12 characters, the first 7 of them ASCII digits, the rest digits or capital letters. There is no check character.</summary>
        public static bool LicenceNumberLooksValid(string? s)
        {
            if (s == null || s.Length != 12) return false;
            for (int i = 0; i < 12; i++)
            {
                char c = s[i];
                bool digit = c >= '0' && c <= '9';
                if (i < 7 ? !digit : !(digit || (c >= 'A' && c <= 'Z'))) return false;
            }
            return true;
        }

        /// <summary>A South African ID number: 13 ASCII digits with a valid Luhn check digit.</summary>
        public static bool IdNumberValid(string? id)
        {
            if (id == null || id.Length != 13 || id.Any(c => c < '0' || c > '9')) return false;
            int sum = 0;
            for (int k = 0; k < 13; k++)
            {
                int d = id[12 - k] - '0';
                if (k % 2 == 1)
                {
                    d *= 2;
                    if (d > 9) d -= 9;
                }
                sum += d;
            }
            return sum % 10 == 0;
        }

        /// <summary>The ID number's first six digits (yyMMdd) are the birth date.</summary>
        public static bool IdMatchesBirthDate(string? id, DateTime birthDate)
        {
            if (id == null || id.Length < 6) return false;
            string yymmdd = (birthDate.Year % 100).ToString("00", CultureInfo.InvariantCulture) +
                birthDate.Month.ToString("00", CultureInfo.InvariantCulture) +
                birthDate.Day.ToString("00", CultureInfo.InvariantCulture);
            return string.CompareOrdinal(id, 0, yymmdd, 0, 6) == 0;
        }

        /// <summary>Every check on a card, in the order of spec/checks.md. An empty list means the card passed.</summary>
        public static IReadOnlyList<Finding> Check(CardLicence card)
        {
            var found = new List<Finding>();
            if (!LicenceNumberLooksValid(card.LicenceNumber)) found.Add(Finding.LicenceNumberFormat);
            if (!IdNumberValid(card.IdNumber)) found.Add(Finding.IdNumberCheckDigit);
            if (!IdMatchesBirthDate(card.IdNumber, card.BirthDate)) found.Add(Finding.IdNumberBirthDate);
            if (card.ValidFrom >= card.ValidTo) found.Add(Finding.ValidityOrder);
            else if (card.ValidTo != card.ValidFrom.AddYears(5).AddDays(-1)) found.Add(Finding.ValidityNotFiveYears);
            if (card.Codes.Any(c => !IsKnownCode(c.Code))) found.Add(Finding.UnknownCode);
            return found;
        }

        /// <summary>The checks that apply to a temporary licence.</summary>
        public static IReadOnlyList<Finding> Check(TemporaryLicence licence)
        {
            var found = new List<Finding>();
            if (!LicenceNumberLooksValid(licence.LicenceNumber)) found.Add(Finding.LicenceNumberFormat);
            if (licence.IdType == "02" && !IdNumberValid(licence.IdNumber)) found.Add(Finding.IdNumberCheckDigit);
            if (licence.Codes.Any(c => !IsKnownCode(c.Code))) found.Add(Finding.UnknownCode);
            return found;
        }

        /// <summary>The checks for either kind of decoded barcode.</summary>
        public static IReadOnlyList<Finding> Check(DecodedBarcode barcode) => barcode switch
        {
            DecodedBarcode.Card c => Check(c.Licence),
            DecodedBarcode.Temporary t => Check(t.Licence),
            _ => throw new ArgumentOutOfRangeException(nameof(barcode)),
        };

        /// <summary>True for the findings that mean the decoded data cannot be right: reject the read.</summary>
        public static bool IsBlocking(this Finding finding) => finding switch
        {
            Finding.ValidityNotFiveYears or Finding.UnknownCode => false,
            _ => true,
        };

        /// <summary>The finding's canonical code, for example "licence_number_format".</summary>
        public static string Code(this Finding finding) => finding switch
        {
            Finding.LicenceNumberFormat => "licence_number_format",
            Finding.IdNumberCheckDigit => "id_number_check_digit",
            Finding.IdNumberBirthDate => "id_number_birth_date",
            Finding.ValidityOrder => "validity_order",
            Finding.ValidityNotFiveYears => "validity_not_five_years",
            _ => "unknown_code",
        };
    }

    /// <summary>Something a check found (spec/checks.md), in the order the checks run.</summary>
    public enum Finding
    {
        /// <summary>Blocking: the licence number is not 12 characters starting with 7 digits.</summary>
        LicenceNumberFormat,

        /// <summary>Blocking: the ID number is not 13 digits with a valid Luhn check digit.</summary>
        IdNumberCheckDigit,

        /// <summary>Blocking: the ID number's first six digits are not the birth date.</summary>
        IdNumberBirthDate,

        /// <summary>Blocking: valid from is not before valid to.</summary>
        ValidityOrder,

        /// <summary>Not blocking: cards normally run five years.</summary>
        ValidityNotFiveYears,

        /// <summary>Not blocking: a vehicle code outside <see cref="LicenceChecks.KnownCodes"/>.</summary>
        UnknownCode,
    }
}
