using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Sadl.Tests
{
    /// <summary>
    /// The language-neutral vectors in spec/test-vectors/, and the six public card vectors fetched into third_party/
    /// by scripts/fetch-test-vectors.sh. The public vectors hold what look like real people's details: tests compare
    /// them and report field names, indexes and hashes only.
    /// </summary>
    internal static class TestData
    {
        public static readonly string Root = FindRoot();

        private static string FindRoot()
        {
            var dir = new DirectoryInfo(AppContext.BaseDirectory);
            while (dir != null && !Directory.Exists(Path.Combine(dir.FullName, "spec", "test-vectors"))) dir = dir.Parent;
            return dir?.FullName ?? throw new InvalidOperationException("spec/test-vectors not found above the test binaries");
        }

        public static JsonElement Vectors(string name) =>
            JsonDocument.Parse(File.ReadAllText(Path.Combine(Root, "spec", "test-vectors", name))).RootElement;

        public static List<JsonElement> Cases(string name) => Vectors(name).GetProperty("cases").EnumerateArray().ToList();

        public static byte[] Hex(string s) => Convert.FromHexString(s);

        public static string Sha256(byte[] data) => Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();

        public static bool VectorsRequired =>
            Environment.GetEnvironmentVariable("SADL_REQUIRE_VECTORS") is "1" or "true";

        /// <summary>One public vector: the raw barcode and the four values its source's tests expect.</summary>
        public sealed class PublicVector
        {
            public PublicVector(byte[] raw, IReadOnlyDictionary<string, string> expected)
            {
                Raw = raw;
                Expected = expected;
            }

            public byte[] Raw { get; }

            public IReadOnlyDictionary<string, string> Expected { get; }

            public override string ToString() => "PublicVector"; // never the values
        }

        /// <summary>The six public vectors in the order of the constants in UnitTest1.cs, or null when not fetched.</summary>
        public static List<PublicVector>? PublicVectors()
        {
            var path = Path.Combine(Root, "third_party", "Reply.Net.SADL", "Reply.Net.SADL", "Reply.Net.SADL.Tests", "UnitTest1.cs");
            if (!File.Exists(path))
            {
                if (VectorsRequired) throw new InvalidOperationException("public vectors missing: run scripts/fetch-test-vectors.sh");
                return null;
            }
            string src = File.ReadAllText(path);
            var constants = Regex.Matches(src, "const string (\\w+) = \"([0-9A-Fa-f]+)\"")
                .Select(m => (Name: m.Groups[1].Value, Hex: m.Groups[2].Value)).ToList();
            var expected = new Dictionary<string, Dictionary<string, string>>();
            foreach (var body in src.Split("[Test]").Skip(1))
            {
                string name = Regex.Match(body, "DecryptDriversLicence\\((\\w+)\\)").Groups[1].Value;
                var strings = Regex.Matches(body, "Assert\\.AreEqual\\(\"([^\"]*)\"").Select(m => m.Groups[1].Value).ToList();
                var date = Regex.Match(body, "new DateTime\\((\\d+),\\s*(\\d+),\\s*(\\d+)\\)");
                string validTo = $"{int.Parse(date.Groups[1].Value):D4}-{int.Parse(date.Groups[2].Value):D2}-{int.Parse(date.Groups[3].Value):D2}";
                expected[name] = new Dictionary<string, string>
                {
                    ["surname"] = strings[0],
                    ["initials"] = strings[1],
                    ["licence_number"] = strings[2],
                    ["valid_to"] = validTo,
                };
            }
            return constants.Select(c => new PublicVector(Hex(c.Hex), expected[c.Name])).ToList();
        }

        /// <summary>Deep equality of two JSON values: key order and whitespace ignored, types kept.</summary>
        public static bool JsonEqual(JsonElement a, JsonElement b)
        {
            if (a.ValueKind != b.ValueKind) return false;
            switch (a.ValueKind)
            {
                case JsonValueKind.Object:
                    var pa = a.EnumerateObject().ToDictionary(p => p.Name, p => p.Value);
                    var pb = b.EnumerateObject().ToDictionary(p => p.Name, p => p.Value);
                    return pa.Count == pb.Count && pa.All(kv => pb.TryGetValue(kv.Key, out var v) && JsonEqual(kv.Value, v));
                case JsonValueKind.Array:
                    var ea = a.EnumerateArray().ToList();
                    var eb = b.EnumerateArray().ToList();
                    return ea.Count == eb.Count && ea.Zip(eb).All(p => JsonEqual(p.First, p.Second));
                case JsonValueKind.String:
                    return a.GetString() == b.GetString();
                case JsonValueKind.Number:
                    return a.GetDecimal() == b.GetDecimal();
                default:
                    return true; // true, false, null
            }
        }

        /// <summary>The keys whose values differ (names only, never values).</summary>
        public static string Differing(JsonElement got, JsonElement expected)
        {
            if (got.ValueKind != JsonValueKind.Object || expected.ValueKind != JsonValueKind.Object) return "shape";
            var keys = got.EnumerateObject().Select(p => p.Name).Union(expected.EnumerateObject().Select(p => p.Name));
            return string.Join(", ", keys.Where(k =>
                !(got.TryGetProperty(k, out var g) && expected.TryGetProperty(k, out var e) && JsonEqual(g, e))).OrderBy(k => k));
        }
    }
}
