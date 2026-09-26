using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using Xunit;

namespace Sadl.Tests
{
    /// <summary>
    /// WiPortrait against wi-checkpoints.json (the six public vectors) and wi-synthetic.json (spec/wi-codec.md
    /// section 14). Only hashes, sizes, counts and error codes are compared or reported.
    /// </summary>
    public class WiPortraitTests
    {
        /// <summary>Hashes every checkpoint, keyed as in the JSON; values are JSON text for comparison.</summary>
        private sealed class Recorder : IWiObserver
        {
            public readonly Dictionary<string, string> Top = new Dictionary<string, string>();
            public readonly Dictionary<int, Dictionary<string, string>> Levels = new Dictionary<int, Dictionary<string, string>>();
            private readonly Dictionary<int, MemoryStream> coefficients = new Dictionary<int, MemoryStream>();

            private static string Quoted(string s) => "\"" + s + "\"";

            private static string Region(int[] x, int rows, int columns)
            {
                var bytes = new byte[rows * columns * 4];
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < columns; c++)
                        BitConverter.TryWriteBytes(bytes.AsSpan((r * columns + c) * 4), x[r * WiPortrait.Width + c]);
                return Quoted(TestData.Sha256(bytes));
            }

            private static string LevelRegion(int[] x, int l) => Region(x, WiDecoder.LevelSize(250, l), WiDecoder.LevelSize(200, l));

            private Dictionary<string, string> Level(int l) =>
                Levels.TryGetValue(l, out var d) ? d : Levels[l] = new Dictionary<string, string>();

            public void Header(int q) => Top["q"] = q.ToString();
            public void LowBand(int llBits, int[] x) { Top["ll_bits"] = llBits.ToString(); Top["C1_ll"] = Region(x, 9, 8); }
            public void Skip(int skip) => Top["skip"] = skip.ToString();
            public void Map(byte[] map) => Top["C2_map"] = Quoted(TestData.Sha256(map));
            public void Sharpened(int[] x) => Top["C6_sharpened"] = Region(x, 126, 101);
            public void Upsampled(int level, int[] x) { Level(level)["C3_upsampled"] = LevelRegion(x, level); Level(level)["escape_bits"] = "null"; }
            public void EscapeBits(int level, int bits) => Level(level)["escape_bits"] = bits.ToString();
            public void Coefficient(int level, int value)
            {
                if (!coefficients.TryGetValue(level, out var s)) coefficients[level] = s = new MemoryStream();
                s.Write(BitConverter.GetBytes(value));
            }
            public void Reconstructed(int level, int[] x)
            {
                var bytes = coefficients.TryGetValue(level, out var s) ? s.ToArray() : Array.Empty<byte>();
                Level(level)["C4_coefficient_count"] = (bytes.Length / 4).ToString();
                Level(level)["C4_coefficients"] = Quoted(TestData.Sha256(bytes));
                Level(level)["C5_reconstructed"] = LevelRegion(x, level);
            }
            public void Finished(int bytesConsumed, byte[] raster)
            {
                Top["bytes_consumed"] = bytesConsumed.ToString();
                Top["C7_raster"] = Quoted(TestData.Sha256(raster));
                Top["C8_portrait"] = Quoted(TestData.Sha256(raster.Reverse().ToArray()));
            }
        }

        private static readonly string[] TopKeys = { "q", "ll_bits", "skip", "bytes_consumed", "C1_ll", "C2_map", "C6_sharpened", "C7_raster", "C8_portrait" };
        private static readonly string[] LevelKeys = { "C3_upsampled", "escape_bits", "C4_coefficient_count", "C4_coefficients", "C5_reconstructed" };

        /// <summary>The names of the checkpoints that differ from the case.</summary>
        private static List<string> Differences(byte[] wi, JsonElement c)
        {
            var recorder = new Recorder();
            try
            {
                WiPortrait.DecodeObserved(wi, recorder);
            }
            catch (WiPortraitException e)
            {
                return new List<string> { "failed with " + e.Code };
            }
            var bad = TopKeys.Where(k => !recorder.Top.TryGetValue(k, out var v) || v != c.GetProperty(k).GetRawText()).ToList();
            if (wi.Length != c.GetProperty("wi_length").GetInt32()) bad.Add("wi_length");
            if (TestData.Sha256(wi) != c.GetProperty("wi_sha256").GetString()) bad.Add("wi_sha256");
            var levels = c.GetProperty("levels").EnumerateArray().ToList();
            if (levels.Count != 5) bad.Add("levels");
            foreach (var expected in levels)
            {
                int l = expected.GetProperty("level").GetInt32();
                foreach (var k in LevelKeys)
                {
                    if (!recorder.Levels.TryGetValue(l, out var got) || !got.TryGetValue(k, out var v) || v != expected.GetProperty(k).GetRawText())
                        bad.Add($"level {l} {k}");
                }
            }
            return bad;
        }

        private static string? ErrorOf(byte[] wi)
        {
            try
            {
                WiPortrait.Decode(wi);
                return null;
            }
            catch (WiPortraitException e)
            {
                return e.Code;
            }
        }

        private static List<JsonElement> Synthetic(bool valid) =>
            TestData.Cases("wi-synthetic.json").Where(c => c.TryGetProperty("expected_error", out _) != valid).ToList();

        private static byte[] Stream(JsonElement c) => TestData.Hex(c.GetProperty("wi_hex").GetString()!);

        private static byte[] ValidStream() => Stream(Synthetic(true).First(c => c.GetProperty("name").GetString() == "random-01"));

        [Fact]
        public void PublicVectorsMatchEveryCheckpoint()
        {
            var vectors = TestData.PublicVectors();
            if (vectors == null) return;
            var checkpoints = TestData.Cases("wi-checkpoints.json");
            Assert.Equal(6, vectors.Count);
            for (int i = 0; i < vectors.Count; i++)
            {
                var photo = ((DecodedBarcode.Card)LicenceBarcode.Decode(vectors[i].Raw)).Licence.Photo!;
                Assert.True(Differences(photo, checkpoints[i]).Count == 0, $"vector {i + 1}: {string.Join(", ", Differences(photo, checkpoints[i]))}");
                Assert.True(checkpoints[i].GetProperty("C7_raster").GetString() == TestData.Sha256(WiPortrait.DecodeNativeOrder(photo)), $"vector {i + 1}");
                Assert.True(checkpoints[i].GetProperty("C8_portrait").GetString() == TestData.Sha256(WiPortrait.Decode(photo)), $"vector {i + 1}");
            }
        }

        [Fact]
        public void SyntheticValidStreamsMatchEveryCheckpoint()
        {
            var valid = Synthetic(true);
            Assert.Equal(41, valid.Count);
            foreach (var c in valid)
            {
                var diff = Differences(Stream(c), c);
                Assert.True(diff.Count == 0, $"{c.GetProperty("name").GetString()}: {string.Join(", ", diff)}");
            }
        }

        [Fact]
        public void SyntheticInvalidStreamsFailWithTheExpectedError()
        {
            var invalid = Synthetic(false);
            Assert.Equal(26, invalid.Count);
            foreach (var c in invalid)
            {
                Assert.True(c.GetProperty("expected_error").GetString() == ErrorOf(Stream(c)), c.GetProperty("name").GetString());
            }
        }

        [Fact]
        public void HeaderGateAcceptsOnlyTheLicenceProfile()
        {
            var valid = ValidStream();
            Assert.Null(ErrorOf(valid));
            foreach (int at in new[] { 0, 1, 2, 3, 4, 5, 6, 9, 10, 11 })
            {
                for (int value = 0; value < 256; value++)
                {
                    if (value == valid[at]) continue;
                    var data = (byte[])valid.Clone();
                    data[at] = (byte)value;
                    Assert.True(ErrorOf(data) == "E1", $"byte {at}");
                }
            }
            for (int value = 0; value < 256; value++)
            {
                var b7 = (byte[])valid.Clone();
                b7[7] = (byte)value;
                Assert.True((value != 0x42 && value != 0x43) == (ErrorOf(b7) == "E1"), "byte 7");
                var b8 = (byte[])valid.Clone();
                b8[8] = (byte)value;
                Assert.True((value % 2 == 1) == (ErrorOf(b8) == "E1"), "byte 8");
            }
        }

        [Fact]
        public void SizeLimits()
        {
            var valid = ValidStream();
            Assert.Equal("E1", ErrorOf(Array.Empty<byte>()));
            Assert.Equal("E1", ErrorOf(valid.Take(12).ToArray()));
            Assert.Equal("E2", ErrorOf(valid.Take(12).Append((byte)0).ToArray()));
            var padded = new byte[WiPortrait.MaxWiBytes];
            valid.CopyTo(padded, 0);
            Assert.Equal(WiPortrait.Decode(valid), WiPortrait.Decode(padded));
            Assert.Equal("E1", ErrorOf(padded.Append((byte)0).ToArray()));
        }

        [Fact]
        public void UprightIsTheNativeOrderReversed()
        {
            var valid = ValidStream();
            Assert.Equal(WiPortrait.DecodeNativeOrder(valid).Reverse().ToArray(), WiPortrait.Decode(valid));
            Assert.Equal(WiPortrait.Pixels, WiPortrait.Decode(valid.AsSpan()).Length);
        }

        [Fact]
        public void ErrorCodesAndMessagesHoldNoData()
        {
            var all = (WiError[])Enum.GetValues(typeof(WiError));
            Assert.Equal(new[] { "E1", "E2", "E3", "E4", "E5" }, all.Select(e => new WiPortraitException(e).Code).ToArray());
            foreach (var e in all) Assert.StartsWith(new WiPortraitException(e).Code + ": ", new WiPortraitException(e).Message);
        }

        /// <summary>Section 4.8: the initial codes of both models.</summary>
        [Fact]
        public void HuffmanInitialCodes()
        {
            static string Code(AdaptiveHuffman m, int s)
            {
                var bits = new List<char>();
                int i = m.NodeOf[s];
                while (m.Parent[i] != -1)
                {
                    bits.Add(i == m.Payload[m.Parent[i]] ? '0' : '1');
                    i = m.Parent[i];
                }
                bits.Reverse();
                return new string(bits.ToArray());
            }
            var c = new AdaptiveHuffman(2048, 3);
            Assert.Equal(new[] { "10", "000", "001", "01", "11" }, new[] { 0, 1, 2, 2048, 2049 }.Select(s => Code(c, s)).ToArray());
            Assert.Equal(9, c.Next);
            Assert.Equal(5, c.Weight[0]);
            var m = new AdaptiveHuffman(256, 64);
            Assert.Equal(131, m.Next);
            Assert.Equal(66, m.Weight[0]);
            var expected = new Dictionary<int, string>
            {
                [0] = "010001", [5] = "111001", [15] = "000111", [29] = "111111", [30] = "000010", [44] = "011110",
                [60] = "111110", [61] = "0000000", [62] = "0000010", [63] = "0000011", [256] = "0000001", [257] = "100001",
            };
            foreach (var kv in expected) Assert.True(kv.Value == Code(m, kv.Key), $"map symbol {kv.Key}");
        }

        [Fact]
        public void ConcurrentDecodesGiveTheSameResult()
        {
            var streams = Synthetic(true).Select(Stream).ToList();
            var expected = streams.Select(s => TestData.Sha256(WiPortrait.Decode(s))).ToList();
            int failures = 0;
            Parallel.For(0, 8, t =>
            {
                for (int round = 0; round < 3; round++)
                    for (int i = 0; i < streams.Count; i++)
                        if ((i + t + round) % 2 == 0 && TestData.Sha256(WiPortrait.Decode(streams[i])) != expected[i])
                            System.Threading.Interlocked.Increment(ref failures);
            });
            Assert.Equal(0, failures);
        }

        /// <summary>100,000 random byte strings and mutations of the synthetic streams (never the public vectors).</summary>
        [Fact]
        public void RobustnessAgainstRandomAndMutatedInput()
        {
            var seeds = Synthetic(true).Select(Stream).ToList();
            var header = seeds[0].Take(12).ToArray();
            var rng = new Random(20260926);
            var counts = new Dictionary<string, int>();
            const int iterations = 100_000;
            for (int n = 0; n < iterations; n++)
            {
                byte[] data;
                switch (n % 4)
                {
                    case 0:
                        data = new byte[rng.Next(700)];
                        rng.NextBytes(data);
                        break;
                    case 1:
                        var tail = new byte[1 + rng.Next(662)];
                        rng.NextBytes(tail);
                        data = header.Concat(tail).ToArray();
                        break;
                    default:
                        var d = seeds[rng.Next(seeds.Count)].ToList();
                        int edits = 1 + rng.Next(6);
                        for (int e = 0; e < edits; e++)
                        {
                            int at = rng.Next(10) == 0 ? rng.Next(d.Count) : 12 + rng.Next(d.Count - 12);
                            switch (rng.Next(3))
                            {
                                case 0: d[at] ^= (byte)(1 << rng.Next(8)); break;
                                case 1: d[at] = (byte)rng.Next(256); break;
                                default: d.Insert(at, (byte)rng.Next(256)); break;
                            }
                        }
                        if (rng.Next(4) == 0) d = d.Take(rng.Next(d.Count + 1)).ToList();
                        data = d.ToArray();
                        break;
                }
                string outcome;
                try
                {
                    Assert.Equal(WiPortrait.Pixels, WiPortrait.Decode(data).Length);
                    outcome = "ok";
                }
                catch (WiPortraitException e)
                {
                    outcome = e.Code;
                }
                counts[outcome] = counts.TryGetValue(outcome, out var k) ? k + 1 : 1;
            }
            Assert.Equal(iterations, counts.Values.Sum());
            Assert.True(counts.ContainsKey("ok") && counts.ContainsKey("E2") && counts.ContainsKey("E5"));
        }

        [Fact]
        public void Timing()
        {
            var vectors = TestData.PublicVectors();
            var streams = vectors != null
                ? vectors.Select(v => ((DecodedBarcode.Card)LicenceBarcode.Decode(v.Raw)).Licence.Photo!).ToList()
                : Synthetic(true).Select(Stream).ToList();
            for (int i = 0; i < 100; i++) foreach (var s in streams) WiPortrait.Decode(s); // warm up
            var watch = System.Diagnostics.Stopwatch.StartNew();
            const int rounds = 200;
            for (int i = 0; i < rounds; i++) foreach (var s in streams) WiPortrait.Decode(s);
            double ms = watch.Elapsed.TotalMilliseconds / (rounds * streams.Count);
            File.WriteAllText(Path.Combine(AppContext.BaseDirectory, "wi-timing.txt"), $"one decode {ms:F3} ms\n");
            Assert.True(ms < 1000);
        }
    }
}
