using System.Collections.Generic;
using System.Linq;
using Xunit;

namespace Sadl.Tests
{
    /// <summary>
    /// The six public card vectors (fetched by scripts/fetch-test-vectors.sh). Skipped silently when they are
    /// missing, unless SADL_REQUIRE_VECTORS is set. Messages name fields and indexes, never values.
    /// </summary>
    public class PublicVectorTests
    {
        private static List<TestData.PublicVector>? Vectors() => TestData.PublicVectors();

        private static LicenceBarcodeError? Reason(System.Action action)
        {
            try
            {
                action();
                return null;
            }
            catch (LicenceBarcodeException e)
            {
                return e.Reason;
            }
        }

        [Fact]
        public void The24ExpectedValuesMatchAndTheChecksPass()
        {
            var vectors = Vectors();
            if (vectors == null) return;
            Assert.Equal(6, vectors.Count);
            int compared = 0;
            for (int i = 0; i < vectors.Count; i++)
            {
                var card = Assert.IsType<DecodedBarcode.Card>(LicenceBarcode.Decode(vectors[i].Raw)).Licence;
                var got = new Dictionary<string, string>
                {
                    ["surname"] = card.Surname,
                    ["initials"] = card.Initials,
                    ["licence_number"] = card.LicenceNumber,
                    ["valid_to"] = card.ValidTo.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture),
                };
                foreach (var field in vectors[i].Expected.Keys)
                {
                    Assert.True(got[field] == vectors[i].Expected[field], $"vector {i + 1}: {field} differs");
                    compared++;
                }
                Assert.False(LicenceChecks.Check(card).Any(f => f.IsBlocking()), $"vector {i + 1}: a blocking check failed");
            }
            Assert.Equal(24, compared);
        }

        [Fact]
        public void PayloadPhotoAndPortraitHashesMatch()
        {
            var vectors = Vectors();
            if (vectors == null) return;
            var hashes = TestData.Cases("public-vectors.json");
            for (int i = 0; i < vectors.Count; i++)
            {
                var h = hashes[i];
                Assert.Equal(h.GetProperty("raw_sha256").GetString(), TestData.Sha256(vectors[i].Raw));
                var payload = LicenceBarcode.Decrypt(vectors[i].Raw);
                Assert.True(h.GetProperty("payload_sha256").GetString() == TestData.Sha256(payload), $"vector {i + 1}: payload");
                var photo = LicenceBarcode.PhotoSection(payload);
                Assert.Equal(h.GetProperty("photo_length").GetInt32(), photo.Length);
                Assert.True(h.GetProperty("photo_sha256").GetString() == TestData.Sha256(photo), $"vector {i + 1}: photo");
                var card = ((DecodedBarcode.Card)LicenceBarcode.Decode(vectors[i].Raw)).Licence;
                Assert.True(TestData.Sha256(card.Photo!) == TestData.Sha256(photo), $"vector {i + 1}: card photo");
                Assert.True(h.GetProperty("portrait_sha256").GetString() == TestData.Sha256(WiPortrait.Decode(photo)), $"vector {i + 1}: portrait");
            }
        }

        [Fact]
        public void TamperedInputsAreRejected()
        {
            var vectors = Vectors();
            if (vectors == null) return;
            foreach (var v in vectors)
            {
                for (int k = 0; k < 6; k++)
                {
                    var bad = (byte[])v.Raw.Clone();
                    bad[6 + 128 * k + 40] ^= 0x01;
                    Assert.Equal(LicenceBarcodeError.BlockCheckFailed, Reason(() => LicenceBarcode.Decrypt(bad)));
                }
                var unknown = (byte[])v.Raw.Clone();
                unknown[3] = 0x46;
                Assert.Equal(LicenceBarcodeError.UnknownVersion, Reason(() => LicenceBarcode.Decrypt(unknown)));
                Assert.Equal(LicenceBarcodeError.NotALicence, Reason(() => LicenceBarcode.Decode(unknown)));
                var other = (byte[])v.Raw.Clone();
                new byte[] { 0x01, 0xE1, 0x02, 0x45 }.CopyTo(other, 0);
                Assert.Equal(LicenceBarcodeError.BlockCheckFailed, Reason(() => LicenceBarcode.Decrypt(other)));
                Assert.Equal(LicenceBarcodeError.WrongLength, Reason(() => LicenceBarcode.Decrypt(v.Raw.Take(719).ToArray())));
                Assert.Equal(LicenceBarcodeError.WrongLength, Reason(() => LicenceBarcode.Decode(v.Raw.Take(719).ToArray())));
            }
        }
    }
}
