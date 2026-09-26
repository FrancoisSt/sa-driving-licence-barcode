using System;

namespace Sadl
{
    /// <summary>
    /// Decodes the portrait in a licence card's barcode: section 3 of the decrypted payload
    /// (<see cref="CardLicence.Photo"/>), a Summus wavelet image ("WI"), to 200 x 250 8-bit greyscale. Managed code
    /// written from spec/wi-codec.md, with no native library.
    /// </summary>
    /// <remarks>
    /// Only the licence-portrait profile is accepted (spec/wi-codec.md section 2). Every call has its own state, so
    /// the decoder may be used from several threads at once. The result is a person's portrait: show it, but do not
    /// log, store or upload it without a reason.
    /// </remarks>
    public static class WiPortrait
    {
        /// <summary>Width of the portrait in samples.</summary>
        public const int Width = 200;

        /// <summary>Height of the portrait in samples.</summary>
        public const int Height = 250;

        /// <summary>Samples in a portrait.</summary>
        public const int Pixels = Width * Height;

        /// <summary>The smallest input accepted: the 12-byte header and one byte of coded data.</summary>
        public const int MinWiBytes = 13;

        /// <summary>The largest input accepted (licence photos are 550 to 603 bytes).</summary>
        public const int MaxWiBytes = 674;

        /// <summary>WI bytes to the upright portrait (checkpoint C8): 50,000 samples, row by row from the top-left.</summary>
        /// <exception cref="WiPortraitException">The input is not a decodable licence portrait.</exception>
        public static byte[] Decode(ReadOnlySpan<byte> wi)
        {
            var raster = DecodeNativeOrder(wi);
            Array.Reverse(raster); // the codec raster is the portrait turned 180 degrees
            return raster;
        }

        /// <summary>
        /// WI bytes to the codec's raster (checkpoint C7), the portrait turned 180 degrees: its first row is the
        /// portrait's bottom row, read from the right.
        /// </summary>
        /// <exception cref="WiPortraitException">The input is not a decodable licence portrait.</exception>
        public static byte[] DecodeNativeOrder(ReadOnlySpan<byte> wi) => new WiDecoder(wi, null).Decode();

        /// <summary>For tests: decodes while <paramref name="observer"/> receives every checkpoint of section 14.</summary>
        internal static byte[] DecodeObserved(ReadOnlySpan<byte> wi, IWiObserver observer) => new WiDecoder(wi, observer).Decode();
    }

    /// <summary>The errors E1 to E5 of spec/wi-codec.md section 12.</summary>
    public enum WiError
    {
        /// <summary>E1: not the licence-portrait profile, or a size outside 13 to 674 bytes.</summary>
        Unsupported = 1,

        /// <summary>E2: a bit was needed after the last byte.</summary>
        EndOfData = 2,

        /// <summary>E3: a skip count above 5.</summary>
        SkipTooLarge = 3,

        /// <summary>E4: an escape width above 10.</summary>
        EscapeTooWide = 4,

        /// <summary>E5: the run count was not exactly used up by the significance map.</summary>
        RunNotExhausted = 5,
    }

    /// <summary>A WI photo that cannot be decoded. The message holds no data.</summary>
    public sealed class WiPortraitException : Exception
    {
        /// <summary>Creates the exception for <paramref name="error"/>.</summary>
        public WiPortraitException(WiError error) : base(CodeOf(error) + ": " + Describe(error)) => Error = error;

        /// <summary>Which error of spec/wi-codec.md section 12.</summary>
        public WiError Error { get; }

        /// <summary>"E1" to "E5".</summary>
        public string Code => CodeOf(Error);

        private static string CodeOf(WiError error) => "E" + ((int)error).ToString(System.Globalization.CultureInfo.InvariantCulture);

        private static string Describe(WiError error) => error switch
        {
            WiError.Unsupported => "not the licence WI profile",
            WiError.EndOfData => "end of data",
            WiError.SkipTooLarge => "skip count above 5",
            WiError.EscapeTooWide => "escape width above 10",
            WiError.RunNotExhausted => "run count not exhausted after the map",
            _ => "unknown error",
        };
    }

    /// <summary>Receives the checkpoints of spec/wi-codec.md section 14. <c>x</c> is the 250 x 200 working image.</summary>
    internal interface IWiObserver
    {
        void Header(int q);
        void LowBand(int llBits, int[] x);
        void Skip(int skip);
        void Map(byte[] map);
        void Sharpened(int[] x);
        void Upsampled(int level, int[] x);
        void EscapeBits(int level, int bits);
        void Coefficient(int level, int value);
        void Reconstructed(int level, int[] x);
        void Finished(int bytesConsumed, byte[] raster);
    }

    /// <summary>One decode of spec/wi-codec.md. All mutable state lives in the instance.</summary>
    internal sealed class WiDecoder
    {
        private const int H = WiPortrait.Height;
        private const int W = WiPortrait.Width;
        private const int Levels = 5;
        private const int HeaderBytes = 12;

        // Significance map values (section 8).
        private const byte Unmarked = 1;
        private const byte Significant = 2;
        private const byte Ancestor = 4;

        private readonly byte[] data;
        private readonly bool headerOk;
        private readonly IWiObserver? observer;
        private readonly int[] x = new int[WiPortrait.Pixels];
        private readonly byte[] map = new byte[WiPortrait.Pixels];

        // Section 3: the bit reader. `fetched` is the scalar bytes_consumed.
        private int fetched = HeaderBytes;
        private int current;
        private int bitsLeft;

        // The map stage (section 8).
        private AdaptiveHuffman? mapModel;
        private int remaining; // R: signed, and it may wrap
        private int mapLevel;
        private Band mapBand;

        private enum Band { B0, B1, B2 }

        public WiDecoder(ReadOnlySpan<byte> wi, IWiObserver? observer)
        {
            // The size is checked before copying, so at most 674 bytes are kept.
            headerOk = wi.Length >= WiPortrait.MinWiBytes && wi.Length <= WiPortrait.MaxWiBytes;
            data = headerOk ? wi.ToArray() : Array.Empty<byte>();
            this.observer = observer;
        }

        internal static int LevelSize(int n, int level) => (n - 2) / (1 << level) + 2;

        private static int Low(int n) => n / 2 + 1;

        private static int High(int n) => n - Low(n);

        private static WiPortraitException Fail(WiError error) => new WiPortraitException(error);

        internal int Bit()
        {
            if (bitsLeft == 0)
            {
                if (fetched >= data.Length) throw Fail(WiError.EndOfData);
                current = data[fetched++];
                bitsLeft = 8;
            }
            bitsLeft--;
            return (current >> bitsLeft) & 1;
        }

        /// <summary>An unsigned value of <paramref name="n"/> bits (at most 31 here), first bit most significant.</summary>
        internal int Bits(int n)
        {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | Bit();
            return v;
        }

        public byte[] Decode()
        {
            int q = CheckHeader();
            observer?.Header(q);
            DecodeLowBand(q);

            int skip = 0;
            while (Bit() == 1) skip++;
            if (skip > Levels) throw Fail(WiError.SkipTooLarge);
            observer?.Skip(skip);

            DecodeMap(skip);
            observer?.Map(map);

            var scratch = new int[WiPortrait.Pixels];
            for (int level = Levels - 1; level >= 0; level--)
            {
                if (level == 0)
                {
                    Sharpen(scratch);
                    observer?.Sharpened(x);
                }
                Upsample(level, scratch);
                observer?.Upsampled(level, x);
                if (level >= skip) AddDetails(level, q);
                observer?.Reconstructed(level, x);
            }

            // Section 10: X is 32 times the sample value.
            var raster = new byte[WiPortrait.Pixels];
            for (int i = 0; i < raster.Length; i++)
            {
                int t = unchecked(x[i] + 16);
                raster[i] = t >= 8192 ? (byte)255 : t < 0 ? (byte)0 : (byte)(t / 32);
            }
            observer?.Finished(fetched, raster);
            return raster;
        }

        /// <summary>Section 2: exactly the licence profile. Returns Q.</summary>
        private int CheckHeader()
        {
            var d = data;
            bool ok = headerOk
                && d[0] == 0x57 && d[1] == 0x49 && d[2] == 0x04 && d[3] == 0x00 && d[4] == 0xFA && d[5] == 0x00 && d[6] == 0xC8
                && (d[7] == 0x42 || d[7] == 0x43) && (d[8] & 1) == 0
                && d[9] == 0x28 && d[10] == 0x40 && d[11] == 0x00;
            if (!ok) throw Fail(WiError.Unsupported);
            return ((d[7] & 1) << 7) | (d[8] >> 1);
        }

        /// <summary>Section 6: the 9 x 8 LL band, step 64 Q, mid-point reconstruction, wrapping.</summary>
        private void DecodeLowBand(int q)
        {
            int llBits = Bits(5);
            int offset = Bits(llBits) & 0xFF;
            for (int r = 0; r < LevelSize(H, Levels); r++)
            {
                for (int c = 0; c < LevelSize(W, Levels); c++)
                {
                    int v = Bits(llBits) - offset;
                    x[r * W + c] = unchecked((v >= 0 ? 2 * v + 1 : 2 * v - 1) * (32 * q));
                }
            }
            observer?.LowBand(llBits, x);
        }

        // -------------------------------------------------------------------------------------------------------
        // Section 8: the significance map.

        private void DecodeMap(int skip)
        {
            for (int i = 0; i < map.Length; i++) map[i] = Unmarked;
            mapModel = new AdaptiveHuffman(256, 64);
            remaining = ReadRun();
            for (int l = skip; l < Levels; l++)
            {
                mapLevel = l;
                int n = LevelSize(H, l), m = LevelSize(W, l), ln = Low(n), lm = Low(m);
                if ((l - skip) % 2 == 0)
                {
                    mapBand = Band.B0; Visit(0, ln, lm, m, 3);
                    mapBand = Band.B2; Visit(ln, n, lm, m, 0);
                    mapBand = Band.B1; Visit(ln, n, 0, lm, 0);
                }
                else
                {
                    mapBand = Band.B1; Visit(ln, n, 0, lm, 1);
                    mapBand = Band.B2; Visit(ln, n, lm, m, 2);
                    mapBand = Band.B0; Visit(0, ln, lm, m, 2);
                }
            }
            if (remaining != 0) throw Fail(WiError.RunNotExhausted);
        }

        /// <summary>Section 4.7: extensions (255 or 256) give hi × 256 + lo, wrapping; the recursion as a loop.</summary>
        private int ReadRun()
        {
            var model = mapModel!;
            int extensions = 0;
            int s = model.Decode(this, 8);
            while (s >= 255)
            {
                extensions++;
                s = model.Decode(this, 8);
            }
            int value = s;
            for (int i = 0; i < extensions; i++) value = unchecked(value * 256 + model.Decode(this, 8));
            return value;
        }

        private int CountUnmarked(int r0, int r1, int c0, int c1)
        {
            int count = 0;
            for (int r = r0; r < r1; r++)
            {
                int row = r * W;
                for (int c = c0; c < c1; c++) if (map[row + c] == Unmarked) count++;
            }
            return count;
        }

        private void Visit(int r0, int r1, int c0, int c1, int o)
        {
            int count = CountUnmarked(r0, r1, c0, c1);
            if (remaining >= count)
            {
                remaining -= count;
                return;
            }
            if (r1 - r0 > 2 && c1 - c0 > 2)
            {
                int rm = r0 + (r1 - r0) / 2, cm = c0 + (c1 - c0) / 2;
                switch (o)
                {
                    case 0: Visit(r0, rm, cm, c1, 3); Visit(rm, r1, cm, c1, 0); Visit(rm, r1, c0, cm, 0); Visit(r0, rm, c0, cm, 2); break;
                    case 1: Visit(rm, r1, c0, cm, 2); Visit(r0, rm, c0, cm, 1); Visit(r0, rm, cm, c1, 1); Visit(rm, r1, cm, c1, 3); break;
                    case 2: Visit(rm, r1, c0, cm, 1); Visit(rm, r1, cm, c1, 2); Visit(r0, rm, cm, c1, 2); Visit(r0, rm, c0, cm, 0); break;
                    default: Visit(r0, rm, cm, c1, 0); Visit(r0, rm, c0, cm, 3); Visit(rm, r1, c0, cm, 3); Visit(rm, r1, cm, c1, 1); break;
                }
                return;
            }
            // The serpentine orders of section 8.1: the first line as in the table, each next line reversed.
            if (o == 0 || o == 1)
            {
                bool rowsAscending = o == 0;
                for (int k = 0; k < c1 - c0; k++)
                {
                    int c = o == 0 ? c1 - 1 - k : c0 + k;
                    for (int j = 0; j < r1 - r0; j++) Cell(rowsAscending ? r0 + j : r1 - 1 - j, c);
                    rowsAscending = !rowsAscending;
                }
            }
            else
            {
                bool columnsAscending = o == 2;
                for (int k = 0; k < r1 - r0; k++)
                {
                    int r = o == 2 ? r1 - 1 - k : r0 + k;
                    for (int j = 0; j < c1 - c0; j++) Cell(r, columnsAscending ? c0 + j : c1 - 1 - j);
                    columnsAscending = !columnsAscending;
                }
            }
        }

        private void Cell(int r, int c)
        {
            int i = r * W + c;
            if (map[i] != Unmarked) return;
            if (remaining != 0)
            {
                remaining = unchecked(remaining - 1); // "≠ 0": a negative R keeps counting down
                return;
            }
            map[i] = Significant;
            MarkAncestors(r, c);
            remaining = ReadRun();
        }

        /// <summary>Section 8.2: the same band's ancestors, one level coarser at a time, up to level 4.</summary>
        private void MarkAncestors(int r, int c)
        {
            bool rowsHigh = mapBand != Band.B0;
            bool columnsHigh = mapBand != Band.B1;
            for (int p = mapLevel; p < Levels - 1; p++)
            {
                r = rowsHigh ? Parent(r, LevelSize(H, p)) : r / 2;
                c = columnsHigh ? Parent(c, LevelSize(W, p)) : c / 2;
                int i = r * W + c;
                if (map[i] == Ancestor) return;
                map[i] = Ancestor;
            }
        }

        private static int Parent(int v, int n) => (n % 4) switch
        {
            0 or 1 => (v + 1) / 2,
            2 => (v + 2) / 2,
            _ => v == n - 1 ? (v + 1) / 2 : (v + 2) / 2,
        };

        // -------------------------------------------------------------------------------------------------------
        // Section 9: reconstruction.

        /// <summary>Section 9.1: the coarser image upsampled to the level-l region, every source biased by +1.</summary>
        private void Upsample(int level, int[] y)
        {
            int n = LevelSize(H, level), m = LevelSize(W, level);
            int exceptionRow = n % 2 == 1 ? n - 1 : n - 2;
            for (int p = 0; p < n; p++)
            {
                bool lastEvenRow = n % 2 == 0 && p == n - 1;
                int ra = lastEvenRow ? Low(n) - 1 : p / 2;
                bool twoRows = !lastEvenRow && p % 2 == 1;
                for (int qc = 0; qc < m; qc++)
                {
                    bool lastEvenColumn = m % 2 == 0 && qc == m - 1;
                    int ca = lastEvenColumn ? Low(m) - 1 : qc / 2;
                    bool twoColumns = !lastEvenColumn && qc % 2 == 1;
                    int sum, shift;
                    unchecked
                    {
                        sum = x[ra * W + ca] + 1;
                        if (twoColumns) sum += x[ra * W + ca + 1] + 1;
                        if (twoRows)
                        {
                            sum += x[(ra + 1) * W + ca] + 1;
                            if (twoColumns) sum += x[(ra + 1) * W + ca + 1] + 1;
                        }
                    }
                    shift = twoRows && twoColumns ? 3 : twoRows || twoColumns ? 2 : 1;
                    bool truncating = lastEvenRow || (lastEvenColumn && p == exceptionRow);
                    y[p * W + qc] = truncating ? sum / (1 << shift) : sum >> shift;
                }
            }
            for (int p = 0; p < n; p++) Array.Copy(y, p * W, x, p * W, m);
        }

        /// <summary>Section 9.3's K_L(i; n) into the tap arrays; returns the tap count.</summary>
        private static int LowKernel(int i, int n, int[] pos, int[] weight)
        {
            if (i == 0) { pos[0] = 0; weight[0] = 2; pos[1] = 1; weight[1] = 1; return 2; }
            if (i == (n - 1) / 2) { pos[0] = 2 * i - 1; weight[0] = 1; pos[1] = 2 * i; weight[1] = 2; return 2; }
            if (i == Low(n) - 1) { pos[0] = n - 1; weight[0] = 2; return 1; }
            pos[0] = 2 * i - 1; weight[0] = 1;
            pos[1] = 2 * i; weight[1] = 2;
            pos[2] = 2 * i + 1; weight[2] = 1;
            return 3;
        }

        private static readonly int[] FirstHighWeights = { -2, 6, -2, -1 };
        private static readonly int[] HighWeights = { -1, -2, 6, -2, -1 };

        /// <summary>Section 9.3's K_H(t; n), centred on 2t + 1.</summary>
        private static int HighKernel(int t, int n, int[] pos, int[] weight)
        {
            if (t == 0)
            {
                for (int k = 0; k < 4; k++) { pos[k] = k; weight[k] = FirstHighWeights[k]; }
                return 4;
            }
            int p = 2 * t + 1;
            for (int k = 0; k < 5; k++) { pos[k] = p - 2 + k; weight[k] = HighWeights[k]; }
            return t == High(n) - 1 ? 4 : 5; // the last kernel omits p + 2
        }

        /// <summary>Sections 9.2 and 9.3.</summary>
        private void AddDetails(int level, int q)
        {
            int escapeBits = Bits(5);
            if (escapeBits > 10) throw Fail(WiError.EscapeTooWide);
            observer?.EscapeBits(level, escapeBits);
            var model = new AdaptiveHuffman(2048, 3);
            int n = LevelSize(H, level), m = LevelSize(W, level), ln = Low(n), lm = Low(m);
            int[] rowPos = new int[5], rowWeight = new int[5], columnPos = new int[5], columnWeight = new int[5];
            foreach (var band in new[] { Band.B0, Band.B1, Band.B2 })
            {
                int r0 = band == Band.B0 ? 0 : ln, r1 = band == Band.B0 ? ln : n;
                int c0 = band == Band.B1 ? 0 : lm, c1 = band == Band.B1 ? lm : m;
                for (int r = r0; r < r1; r++)
                {
                    for (int c = c0; c < c1; c++)
                    {
                        byte mark = map[r * W + c];
                        if (mark == Unmarked) continue;
                        bool negative = Bit() == 1;
                        int k = model.Decode(this, escapeBits);
                        if (mark == Significant) k++;
                        int magnitude = k * q + q / 2;
                        int v = negative ? -magnitude : magnitude;
                        observer?.Coefficient(level, v);
                        int rows, columns, scale;
                        switch (band)
                        {
                            case Band.B0:
                                rows = LowKernel(r, n, rowPos, rowWeight);
                                columns = HighKernel(c - lm, m, columnPos, columnWeight);
                                scale = 2;
                                break;
                            case Band.B1:
                                rows = HighKernel(r - ln, n, rowPos, rowWeight);
                                columns = LowKernel(c, m, columnPos, columnWeight);
                                scale = 2;
                                break;
                            default:
                                rows = HighKernel(r - ln, n, rowPos, rowWeight);
                                columns = HighKernel(c - lm, m, columnPos, columnWeight);
                                scale = 1;
                                break;
                        }
                        for (int a = 0; a < rows; a++)
                        {
                            int row = rowPos[a] * W;
                            int rv = scale * v * rowWeight[a];
                            for (int b = 0; b < columns; b++) x[row + columnPos[b]] = unchecked(x[row + columnPos[b]] + rv * columnWeight[b]);
                        }
                    }
                }
            }
        }

        /// <summary>Section 9.4: the level-1 image (126 x 101) sharpened in its interior; the border is kept.</summary>
        private void Sharpen(int[] s)
        {
            int n1 = LevelSize(H, 1), m1 = LevelSize(W, 1);
            for (int r = 1; r < n1 - 1; r++)
            {
                for (int c = 1; c < m1 - 1; c++)
                {
                    int i = r * W + c;
                    s[i] = unchecked(12 * x[i] - x[i - 1] - x[i + 1] - x[i - W] - x[i + W]) >> 3;
                }
            }
            for (int r = 1; r < n1 - 1; r++) Array.Copy(s, r * W + 1, x, r * W + 1, m1 - 2);
        }
    }

    /// <summary>
    /// Section 4: the adaptive Huffman code (FGK, sibling property) over N symbols plus the large-value leaf N and
    /// the escape leaf N + 1. Node 0 is the root; an internal node's children are payload and payload + 1.
    /// </summary>
    internal sealed class AdaptiveHuffman
    {
        private readonly int n;
        internal readonly int[] Parent;
        internal readonly bool[] Leaf;
        internal readonly int[] Payload;
        internal readonly int[] Weight;
        internal readonly int[] NodeOf;

        internal int Next { get; private set; } = 3;

        public AdaptiveHuffman(int n, int presets)
        {
            this.n = n;
            int size = 2 * n + 3;
            Parent = new int[size];
            Leaf = new bool[size];
            Payload = new int[size];
            Weight = new int[size];
            NodeOf = new int[n + 2];
            for (int i = 0; i < size; i++) Parent[i] = -1;
            for (int s = 0; s < NodeOf.Length; s++) NodeOf[s] = -1;
            Payload[0] = 1; Weight[0] = 2;
            Parent[1] = 0; Leaf[1] = true; Payload[1] = n; Weight[1] = 1;
            Parent[2] = 0; Leaf[2] = true; Payload[2] = n + 1; Weight[2] = 1;
            NodeOf[n] = 1;
            NodeOf[n + 1] = 2;
            for (int s = 0; s < presets; s++)
            {
                Add(s);
                Update(s);
            }
        }

        /// <summary>Section 4.3.</summary>
        private void Add(int s)
        {
            if (NodeOf[s] != -1) return;
            int d = Next;
            Leaf[d] = Leaf[d - 1]; Payload[d] = Payload[d - 1]; Weight[d] = Weight[d - 1]; Parent[d] = d - 1;
            NodeOf[Payload[d]] = d;
            Leaf[d - 1] = false; Payload[d - 1] = d;
            Leaf[d + 1] = true; Payload[d + 1] = s; Weight[d + 1] = 0; Parent[d + 1] = d - 1;
            NodeOf[s] = d + 1;
            Next = d + 2;
        }

        /// <summary>Section 4.4.</summary>
        private void Update(int s)
        {
            int i = NodeOf[s];
            while (i != -1)
            {
                Weight[i]++;
                int j = i;
                while (j > 0 && Weight[j - 1] < Weight[i]) j--;
                if (j != i)
                {
                    Swap(i, j);
                    i = j;
                }
                i = Parent[i];
            }
        }

        /// <summary>Section 4.5: exchange contents; parents stay with the node numbers.</summary>
        private void Swap(int i, int j)
        {
            Repoint(i, j);
            Repoint(j, i);
            (Leaf[i], Leaf[j]) = (Leaf[j], Leaf[i]);
            (Payload[i], Payload[j]) = (Payload[j], Payload[i]);
            (Weight[i], Weight[j]) = (Weight[j], Weight[i]);
        }

        private void Repoint(int from, int to)
        {
            if (Leaf[from])
            {
                NodeOf[Payload[from]] = to;
            }
            else
            {
                Parent[Payload[from]] = to;
                Parent[Payload[from] + 1] = to;
            }
        }

        /// <summary>Section 4.6: one symbol, 0 to N (N is the large-value leaf).</summary>
        internal int Decode(WiDecoder reader, int escapeBits)
        {
            int i = 0;
            while (!Leaf[i]) i = Payload[i] + reader.Bit();
            int s = Payload[i];
            if (s == n + 1)
            {
                s = reader.Bits(escapeBits); // below N: at most 10 bits against 2048, 8 against 256
                Add(s);
            }
            Update(s);
            return s;
        }
    }
}
