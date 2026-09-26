using System;
using System.Collections.Generic;
using System.Numerics;
using System.Text;

namespace Sadl
{
    /// <summary>
    /// Decodes the PDF417 barcode of a South African driving licence: the back of the card (spec/card-barcode.md)
    /// and the paper temporary driving licence (spec/temporary-licence.md).
    /// </summary>
    /// <remarks>
    /// Pass the barcode reader's raw bytes, never its text: the card-back payload is binary. The results hold
    /// personal data: do not log them. Exceptions carry a canonical reason and no data.
    /// </remarks>
    public static class LicenceBarcode
    {
        /// <summary>The number of bytes in a card-back barcode.</summary>
        public const int CardBarcodeSize = 720;

        /// <summary>The number of bytes left once the six RSA blocks are decrypted and their markers removed.</summary>
        public const int CardPayloadSize = 684;

        private static readonly byte[] Version1 = { 0x01, 0xE1, 0x02, 0x45 };
        private static readonly byte[] Version2 = { 0x01, 0x9B, 0x09, 0x45 };

        private sealed class PublicKey
        {
            public PublicKey(string modulusHex, string exponentHex)
            {
                N = Unsigned(modulusHex);
                E = Unsigned(exponentHex);
            }

            public BigInteger N { get; }

            public BigInteger E { get; }

            private static BigInteger Unsigned(string hex)
            {
                var bytes = new byte[hex.Length / 2];
                for (int i = 0; i < bytes.Length; i++) bytes[i] = Convert.ToByte(hex.Substring(2 * i, 2), 16);
                return new BigInteger(bytes, isUnsigned: true, isBigEndian: true);
            }
        }

        // The published public keys (Stack Overflow 17549231, 2016): per version, one for the five 128-byte blocks
        // and one for the last, 74-byte block. They only undo the encryption; they are not secrets.
        private static readonly PublicKey[] KeysV1 =
        {
            new PublicKey(
                "fed2e1c27e3363316e77317a7a52c54981395186be4974760c72518d63e0544a" +
                "48d088b332c5b0c370c765d65d983c1f9de0a42b310ccc07ae770bd2b61d6a4d" +
                "cceac757689bdcbf608478faf312f6087cc496c3762cf5c4651caecda3499fae" +
                "7edb7eb40e3e18eb304170e91ed5b156aace6f432d6eca6cc35851de8c678f67",
                "bb797ffdec7f9e42c9d6f79b137059db"),
            new PublicKey(
                "ff3cec6b5f40e3c3661451b9fcfaef3aeb06dc2329c0e6f4dccc9279726716ce" +
                "15bbe05eed2c5711bcf8f5b6c8f7276db5c43bfaa3040dc01ab14b9c4d16f71c" +
                "0ce5ea953f0c754c6b17",
                "db05ba822d9acc33fab7d8f427f9ce65"),
        };

        private static readonly PublicKey[] KeysV2 =
        {
            new PublicKey(
                "ca9f18ef6c3f3fa4c5a461fea54ab19406ba5ecd746d60a27492dca3d74e3b5c" +
                "1d315f7b10383241809b029ebbd5de4d116030cc57f7d5a6c9a16f373bb14a50" +
                "8523f7e80a4c744d9085663a4a1472d7af2c56ae41b5065f7efa0293bd3278ad" +
                "693546f9f16219b79ff471a3636824cffcdb63a8ed8059e6b9a4f0db895381cb",
                "187092da6454ceb1853e6915f8466a05"),
            new PublicKey(
                "b404a0df11d1cacff1a1a048d4d573f953a62c583d74925927561a6d7a1e2b14" +
                "042526af70b550547390ea6ec748d30fdb81adb490e0c36a1986b404b2f5f69e" +
                "f5da1b663e59509130e7",
                "309cfed9719fe2a5e20c9bb44765382b"),
        };

        /// <summary>1 or 2 for a card back, 0 when the first four bytes are not a known version.</summary>
        public static int Version(ReadOnlySpan<byte> raw)
        {
            if (raw.Length < 4) return 0;
            if (raw.Slice(0, 4).SequenceEqual(Version1)) return 1;
            if (raw.Slice(0, 4).SequenceEqual(Version2)) return 2;
            return 0;
        }

        /// <summary>Says which kind of barcode <paramref name="raw"/> is, from its first bytes only.</summary>
        public static BarcodeKind Identify(ReadOnlySpan<byte> raw)
        {
            if (Version(raw) != 0) return BarcodeKind.Card;
            if (IsTemporaryLicence(raw)) return BarcodeKind.TemporaryLicence;
            return BarcodeKind.Other;
        }

        /// <summary>True when <paramref name="raw"/> starts with "%TDL", two ASCII digits and "%".</summary>
        public static bool IsTemporaryLicence(ReadOnlySpan<byte> raw) =>
            raw.Length >= 7 && raw[0] == '%' && raw[1] == 'T' && raw[2] == 'D' && raw[3] == 'L' &&
            IsDigit(raw[4]) && IsDigit(raw[5]) && raw[6] == '%';

        private static bool IsDigit(byte b) => b >= '0' && b <= '9';

        /// <summary>
        /// Decodes a card back or a temporary licence. Throws <see cref="LicenceBarcodeException"/> for anything
        /// else, and for a misread; keep scanning when it throws.
        /// </summary>
        public static DecodedBarcode Decode(ReadOnlySpan<byte> raw) => Identify(raw) switch
        {
            BarcodeKind.Card => new DecodedBarcode.Card(ParseCard(Decrypt(raw), Version(raw))),
            BarcodeKind.TemporaryLicence => new DecodedBarcode.Temporary(ParseTemporaryLicence(raw)),
            _ => throw new LicenceBarcodeException(LicenceBarcodeError.NotALicence, "not a South African driving-licence barcode"),
        };

        /// <summary>
        /// 720 barcode bytes to the 684-byte payload: textbook RSA per block (m = c^e mod n on unsigned big-endian
        /// integers, no padding), then each block's 5-byte marker checked and removed.
        /// </summary>
        public static byte[] Decrypt(ReadOnlySpan<byte> raw)
        {
            if (raw.Length != CardBarcodeSize)
            {
                throw new LicenceBarcodeException(LicenceBarcodeError.WrongLength, $"length {raw.Length}, expected {CardBarcodeSize}");
            }
            var keys = Version(raw) switch
            {
                1 => KeysV1,
                2 => KeysV2,
                _ => throw new LicenceBarcodeException(LicenceBarcodeError.UnknownVersion, "unknown version bytes"),
            };
            var payload = new byte[CardPayloadSize];
            int o = 0;
            for (int k = 0; k < 6; k++)
            {
                int size = k < 5 ? 128 : 74;
                int start = 6 + 128 * k;
                var key = keys[k < 5 ? 0 : 1];
                var c = new BigInteger(raw.Slice(start, size), isUnsigned: true, isBigEndian: true);
                if (c >= key.N) throw new LicenceBarcodeException(LicenceBarcodeError.BlockCheckFailed, $"block {k + 1} outside the modulus");
                var block = FixedBigEndian(BigInteger.ModPow(c, key.E, key.N), size);
                for (int j = 1; j <= 5; j++)
                {
                    if (block[j - 1] != ((j << k) & 0x7F))
                    {
                        throw new LicenceBarcodeException(LicenceBarcodeError.BlockCheckFailed, $"block {k + 1} marker mismatch");
                    }
                }
                Array.Copy(block, 5, payload, o, size - 5);
                o += size - 5;
            }
            return payload;
        }

        /// <summary>An unsigned value as exactly <paramref name="size"/> big-endian bytes, left-padded with zeros.</summary>
        private static byte[] FixedBigEndian(BigInteger m, int size)
        {
            var bytes = m.ToByteArray(isUnsigned: true, isBigEndian: true);
            if (bytes.Length > size) throw new LicenceBarcodeException(LicenceBarcodeError.BlockCheckFailed, "decrypted block too long");
            var block = new byte[size];
            Array.Copy(bytes, 0, block, size - bytes.Length, bytes.Length);
            return block;
        }

        /// <summary>The portrait section (section 3, including its "WI" header) of a decrypted payload.</summary>
        public static byte[] PhotoSection(ReadOnlySpan<byte> payload)
        {
            if (payload.Length < 10) throw new LicenceBarcodeException(LicenceBarcodeError.MalformedPayload, "payload too short");
            int start = 10 + payload[5] + payload[7];
            int size = ((payload[8] << 8) | payload[9]) & 0x0FFF;
            if (start + size > payload.Length) throw new LicenceBarcodeException(LicenceBarcodeError.MalformedPayload, "section lengths exceed the payload");
            return payload.Slice(start, size).ToArray();
        }

        /// <summary>
        /// The 684-byte payload (from <see cref="Decrypt"/>) to its fields. <paramref name="version"/> is only
        /// recorded on the result. Throws when the structure does not add up.
        /// </summary>
        public static CardLicence ParseCard(ReadOnlySpan<byte> payload, int version = 2)
        {
            var p = payload;
            if (p.Length < 10) throw Malformed("payload too short");
            int s1 = p[5], s2 = p[7], s3 = ((p[8] << 8) | p[9]) & 0x0FFF;
            if (10 + s1 + s2 + s3 > p.Length) throw Malformed("section lengths exceed the payload");

            // Section 1: 14 Latin-1 strings, then the 13-character ID number. 0xE0 ends a string; 0xE1 ends a string
            // and announces an empty one, unless another 0xE1 follows (n x 0xE1 = n + 1 string ends).
            var strings = new List<string>(14);
            var current = new StringBuilder();
            int end1 = 10 + s1;
            int i = 10;
            for (; i < end1 && strings.Count < 14; i++)
            {
                byte b = p[i];
                if (b == 0xE0 || b == 0xE1)
                {
                    strings.Add(current.ToString());
                    current.Clear();
                    if (b == 0xE1 && i + 1 < end1 && p[i + 1] != 0xE1) strings.Add("");
                }
                else
                {
                    current.Append((char)b); // Latin-1: each byte is its own code point
                }
            }
            if (strings.Count != 14) throw Malformed($"{strings.Count} strings, expected 14");
            string idNumber = Latin1Text(p.Slice(i, end1 - i));

            // Section 2: nibbles, high nibble first.
            var n = new Nibbles(p.Slice(end1, s2));
            string idType = n.Pair();
            var firstIssue = new DateTime?[4];
            for (int k = 0; k < 4; k++) firstIssue[k] = n.Date();
            string driverRestrictions = n.Pair();
            DateTime? prdpExpiry = n.Date();
            string issueNumber = n.Pair();
            DateTime birthDate = n.Date() ?? throw Malformed("no birth date");
            DateTime validFrom = n.Date() ?? throw Malformed("no valid-from date");
            DateTime validTo = n.Date() ?? throw Malformed("no valid-to date");
            string gender = n.Pair();
            if (!n.DoneOrPad()) throw Malformed("nibbles left over");

            // Section 3: the portrait, kept as bytes for WiPortrait.
            byte[]? photo = s3 > 0 ? p.Slice(end1 + s2, s3).ToArray() : null;

            var codes = new List<VehicleCode>();
            for (int k = 0; k < 4; k++)
            {
                if (strings[k].Length > 0) codes.Add(new VehicleCode(strings[k], strings[9 + k], firstIssue[k]));
            }
            var prdp = new List<string>();
            foreach (var part in strings[6].Split(','))
            {
                var t = part.Trim();
                if (t.Length > 0) prdp.Add(t);
            }
            return new CardLicence(
                version: version,
                codes: codes,
                surname: strings[4],
                initials: strings[5],
                prdpCategories: prdp,
                prdpExpiry: prdpExpiry,
                idCountry: strings[7],
                licenceCountry: strings[8],
                licenceNumber: strings[13],
                idNumber: idNumber,
                idType: idType,
                driverRestrictions: driverRestrictions,
                issueNumber: issueNumber,
                birthDate: birthDate,
                validFrom: validFrom,
                validTo: validTo,
                genderCode: gender,
                photo: photo);
        }

        private static LicenceBarcodeException Malformed(string message) =>
            new LicenceBarcodeException(LicenceBarcodeError.MalformedPayload, message);

        private static string Latin1Text(ReadOnlySpan<byte> bytes)
        {
            var chars = new char[bytes.Length];
            for (int k = 0; k < bytes.Length; k++) chars[k] = (char)bytes[k];
            return new string(chars);
        }

        /// <summary>Section 2's nibble reader: pairs, and dates that may be a single 0xA nibble ("no date").</summary>
        private ref struct Nibbles
        {
            private readonly ReadOnlySpan<byte> bytes;
            private int at;

            public Nibbles(ReadOnlySpan<byte> bytes)
            {
                this.bytes = bytes;
                at = 0;
            }

            private int Count => bytes.Length * 2;

            private int Peek(int k) => k % 2 == 0 ? bytes[k / 2] >> 4 : bytes[k / 2] & 0x0F;

            private int Next()
            {
                if (at >= Count) throw Malformed("ran out of nibbles");
                return Peek(at++);
            }

            public string Pair()
            {
                const string hex = "0123456789abcdef";
                int a = Next(), b = Next();
                return new string(new[] { hex[a], hex[b] });
            }

            public DateTime? Date()
            {
                if (at < Count && Peek(at) == 0xA)
                {
                    at++;
                    return null;
                }
                var d = new int[8];
                for (int k = 0; k < 8; k++)
                {
                    d[k] = Next();
                    if (d[k] > 9) throw Malformed("date nibble above 9");
                }
                return RealDate(d[0] * 1000 + d[1] * 100 + d[2] * 10 + d[3], d[4] * 10 + d[5], d[6] * 10 + d[7]) ??
                    throw Malformed("not a real date");
            }

            public bool DoneOrPad() => at == Count || (at == Count - 1 && Peek(at) == 0xA);
        }

        /// <summary>The date, or null when it does not exist (years 1 to 9999).</summary>
        private static DateTime? RealDate(int year, int month, int day)
        {
            if (year < 1 || year > 9999 || month < 1 || month > 12 || day < 1) return null;
            if (day > DateTime.DaysInMonth(year, month)) return null;
            return new DateTime(year, month, day, 0, 0, 0, DateTimeKind.Unspecified);
        }

        /// <summary>Parses a temporary licence barcode. Throws when it is not one, or when a field is malformed.</summary>
        public static TemporaryLicence ParseTemporaryLicence(ReadOnlySpan<byte> raw)
        {
            if (!IsTemporaryLicence(raw)) throw new LicenceBarcodeException(LicenceBarcodeError.NotALicence, "not a temporary licence barcode");
            string[] f = Latin1Text(raw).Split('%');
            if (f.Length < 16) throw Malformed($"{f.Length} fields, expected 16");
            var codes = new List<VehicleCode>();
            for (int k = 9; k <= 12; k++)
            {
                if (f[k].Length == 0) continue;
                string[] c = f[k].Split('/');
                codes.Add(new VehicleCode(c[0], c.Length > 2 ? c[2] : "", IsoDate(c.Length > 1 ? c[1] : null)));
            }
            string[] prdp = f[13].Split('/');
            var categories = new List<string>();
            foreach (char ch in prdp[0]) categories.Add(ch.ToString()); // "GP" here, not "G,P" as on the card
            DateTime issueDate = IsoDate(f[14]) ?? throw Malformed("no issue date");
            return new TemporaryLicence(
                tag: f[1],
                field2: f[2],
                serial: f[3],
                field4: f[4],
                licenceNumber: f[5],
                idType: f[6],
                idNumber: f[7],
                name: f[8],
                codes: codes,
                prdpCategories: categories,
                prdpExpiry: IsoDate(prdp.Length > 1 ? prdp[1] : null),
                issueDate: issueDate);
        }

        /// <summary>Exactly "yyyy-MM-dd" in ASCII digits, a real date, year 1 to 9999; null for an empty field.</summary>
        private static DateTime? IsoDate(string? text)
        {
            if (text == null || text.Length == 0) return null;
            string s = text;
            bool shape = s.Length == 10 && s[4] == '-' && s[7] == '-';
            for (int k = 0; shape && k < 10; k++)
            {
                if (k != 4 && k != 7 && (s[k] < '0' || s[k] > '9')) shape = false;
            }
            if (!shape) throw Malformed("not a yyyy-MM-dd date");
            int Number(int from, int length)
            {
                int v = 0;
                for (int k = from; k < from + length; k++) v = v * 10 + (s[k] - '0');
                return v;
            }
            return RealDate(Number(0, 4), Number(5, 2), Number(8, 2)) ?? throw Malformed("not a yyyy-MM-dd date");
        }
    }
}
