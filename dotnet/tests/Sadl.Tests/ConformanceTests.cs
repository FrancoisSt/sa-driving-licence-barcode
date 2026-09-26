using System;
using System.Linq;
using System.Text.Json;
using Xunit;

namespace Sadl.Tests
{
    /// <summary>parse-card.json, decode.json and checks.json (spec/output-format.md).</summary>
    public class ConformanceTests
    {
        private static JsonElement Canonical(Func<string> produce)
        {
            string json;
            try
            {
                json = produce();
            }
            catch (LicenceBarcodeException e)
            {
                json = CanonicalJson.Of(e);
            }
            return JsonDocument.Parse(json).RootElement;
        }

        private static void AssertCanonical(string name, JsonElement expected, JsonElement got) =>
            Assert.True(TestData.JsonEqual(got, expected), $"{name}: {TestData.Differing(got, expected)}");

        [Fact]
        public void ParseCardVectors()
        {
            var cases = TestData.Cases("parse-card.json");
            Assert.Equal(11, cases.Count);
            foreach (var c in cases)
            {
                var payload = TestData.Hex(c.GetProperty("payload_hex").GetString()!);
                var got = Canonical(() => CanonicalJson.Of(LicenceBarcode.ParseCard(payload)));
                AssertCanonical(c.GetProperty("name").GetString()!, c.GetProperty("expected"), got);
            }
        }

        private static byte[] Raw(JsonElement c) => c.TryGetProperty("raw_hex", out var hex)
            ? TestData.Hex(hex.GetString()!)
            : c.GetProperty("raw_text").GetString()!.Select(ch => checked((byte)ch)).ToArray(); // Latin-1

        [Fact]
        public void DecodeVectors()
        {
            var cases = TestData.Cases("decode.json");
            Assert.Equal(11, cases.Count);
            foreach (var c in cases)
            {
                var raw = Raw(c);
                var got = Canonical(() => CanonicalJson.Of(LicenceBarcode.Decode(raw)));
                AssertCanonical(c.GetProperty("name").GetString()!, c.GetProperty("expected"), got);
            }
        }

        [Fact]
        public void TheBuiltInJsonWriterGivesTheSameValues()
        {
            // The netstandard2.1 build uses the built-in writer; check it against System.Text.Json on every vector.
            foreach (var c in TestData.Cases("decode.json").Concat(TestData.Cases("parse-card.json")))
            {
                DecodedBarcode decoded;
                try
                {
                    decoded = c.TryGetProperty("payload_hex", out var p)
                        ? new DecodedBarcode.Card(LicenceBarcode.ParseCard(TestData.Hex(p.GetString()!)))
                        : LicenceBarcode.Decode(Raw(c));
                }
                catch (LicenceBarcodeException)
                {
                    continue;
                }
                string builtIn = CanonicalJson.Write(w =>
                {
                    if (decoded is DecodedBarcode.Card card) CanonicalJson.WriteCard(w, card.Licence);
                    else CanonicalJson.WriteTemporary(w, ((DecodedBarcode.Temporary)decoded).Licence);
                }, forceBuiltIn: true);
                Assert.True(builtIn.All(ch => ch < 0x7F), "the built-in writer writes ASCII only");
                var name = c.GetProperty("name").GetString()!;
                AssertCanonical(name, c.GetProperty("expected"), JsonDocument.Parse(builtIn).RootElement);
                AssertCanonical(name, JsonDocument.Parse(CanonicalJson.Of(decoded)).RootElement, JsonDocument.Parse(builtIn).RootElement);
            }
        }

        [Fact]
        public void ErrorJson()
        {
            var e = new LicenceBarcodeException(LicenceBarcodeError.BlockCheckFailed, "block 1 marker mismatch");
            var got = JsonDocument.Parse(CanonicalJson.Of(e)).RootElement;
            Assert.Equal("block_check_failed", got.GetProperty("error").GetString());
            var builtIn = CanonicalJson.Write(w => { w.StartObject(); w.Property("error", e.ReasonCode); w.EndObject(); }, forceBuiltIn: true);
            Assert.Equal("{\"error\": \"block_check_failed\"}", builtIn);
        }

        private static DateTime? Date(JsonElement o, string key) =>
            o.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.String
                ? DateTime.ParseExact(v.GetString()!, "yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture)
                : null;

        private static VehicleCode[] Codes(JsonElement o) => o.GetProperty("vehicle_codes").EnumerateArray()
            .Select(v => new VehicleCode(v.GetProperty("code").GetString()!, v.GetProperty("vehicle_restriction").GetString()!, Date(v, "first_issue")))
            .ToArray();

        private static string S(JsonElement o, string key) => o.GetProperty(key).GetString()!;

        [Fact]
        public void ChecksVectors()
        {
            var cases = TestData.Cases("checks.json");
            Assert.Equal(12, cases.Count);
            foreach (var c in cases)
            {
                var o = c.GetProperty("licence");
                var findings = S(o, "kind") == "card"
                    ? LicenceChecks.Check(new CardLicence(
                        o.GetProperty("version").GetInt32(), Codes(o), S(o, "surname"), S(o, "initials"),
                        o.GetProperty("prdp_categories").EnumerateArray().Select(x => x.GetString()!).ToArray(), Date(o, "prdp_expiry"),
                        S(o, "id_country"), S(o, "licence_country"), S(o, "licence_number"), S(o, "id_number"), S(o, "id_type"),
                        S(o, "driver_restrictions"), S(o, "issue_number"), Date(o, "birth_date")!.Value, Date(o, "valid_from")!.Value,
                        Date(o, "valid_to")!.Value, S(o, "gender"), null))
                    : LicenceChecks.Check(new TemporaryLicence(
                        "", "", "", "", S(o, "licence_number"), S(o, "id_type"), S(o, "id_number"), "", Codes(o),
                        Array.Empty<string>(), null, new DateTime(2025, 1, 1)));
                var expected = c.GetProperty("expected").EnumerateArray().Select(x => x.GetString()).ToArray();
                Assert.True(expected.SequenceEqual(findings.Select(f => f.Code())), S(c, "name"));
            }
        }

        [Fact]
        public void IdentifyUsesTheFirstBytesOnly()
        {
            Assert.Equal(BarcodeKind.Card, LicenceBarcode.Identify(new byte[] { 0x01, 0x9B, 0x09, 0x45 }));
            Assert.Equal(BarcodeKind.Card, LicenceBarcode.Identify(new byte[] { 0x01, 0xE1, 0x02, 0x45, 0 }));
            Assert.Equal(BarcodeKind.TemporaryLicence, LicenceBarcode.Identify("%TDL01%x"u8.ToArray()));
            Assert.Equal(BarcodeKind.Other, LicenceBarcode.Identify("%TDL0A%"u8.ToArray()));
            Assert.Equal(BarcodeKind.Other, LicenceBarcode.Identify("%MVL1CC"u8.ToArray()));
            Assert.Equal(BarcodeKind.Other, LicenceBarcode.Identify(Array.Empty<byte>()));
            // Non-ASCII digits are not digits here.
            Assert.Equal(BarcodeKind.Other, LicenceBarcode.Identify(new byte[] { (byte)'%', (byte)'T', (byte)'D', (byte)'L', 0xB2, 0xB3, (byte)'%' }));
        }

        [Fact]
        public void ChecksHelpers()
        {
            Assert.True(LicenceChecks.IdNumberValid("0001010000089"));
            Assert.False(LicenceChecks.IdNumberValid("0001010000088"));
            Assert.False(LicenceChecks.IdNumberValid("000101000008"));
            Assert.True(LicenceChecks.LicenceNumberLooksValid("0000000000BC"));
            Assert.False(LicenceChecks.LicenceNumberLooksValid("000000A000BC"));
            Assert.False(LicenceChecks.LicenceNumberLooksValid("0000000000bc"));
            Assert.True(LicenceChecks.IdMatchesBirthDate("0001010000089", new DateTime(2000, 1, 1)));
            Assert.True(Finding.LicenceNumberFormat.IsBlocking());
            Assert.False(Finding.UnknownCode.IsBlocking());
        }

        [Fact]
        public void ToStringLeavesOutPersonalValues()
        {
            var c = TestData.Cases("parse-card.json").First(x => x.GetProperty("name").GetString() == "one-code-with-prdp");
            var card = LicenceBarcode.ParseCard(TestData.Hex(c.GetProperty("payload_hex").GetString()!));
            var expected = c.GetProperty("expected");
            string text = new DecodedBarcode.Card(card).ToString() + card + string.Join(",", card.Codes);
            foreach (var key in new[] { "surname", "initials", "id_number", "licence_number", "birth_date", "valid_to", "valid_from" })
            {
                Assert.DoesNotContain(expected.GetProperty(key).GetString()!, text);
            }
            var t = TestData.Cases("decode.json").First(x => x.GetProperty("name").GetString() == "temporary-licence");
            var temp = LicenceBarcode.Decode(Raw(t));
            string ttext = temp.ToString()!;
            foreach (var key in new[] { "name", "id_number", "licence_number", "serial", "issue_date" })
            {
                Assert.DoesNotContain(t.GetProperty("expected").GetProperty(key).GetString()!, ttext);
            }
        }

        [Fact]
        public void ExceptionMessagesHoldNoValues()
        {
            var c = TestData.Cases("parse-card.json").First(x => x.GetProperty("name").GetString() == "nibbles-left-over");
            var e = Assert.Throws<LicenceBarcodeException>(() => LicenceBarcode.ParseCard(TestData.Hex(c.GetProperty("payload_hex").GetString()!)));
            Assert.Equal("malformed_payload", e.ReasonCode);
            Assert.Equal("nibbles left over", e.Message);
        }
    }
}
