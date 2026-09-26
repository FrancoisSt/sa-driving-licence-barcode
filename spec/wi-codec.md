# The WI portrait codec: functional specification

Revision 3. Revision 2 added [test-vectors/wi-synthetic.json](test-vectors/wi-synthetic.json) (sections 14 and 15)
and the wrap-around reachability facts of section 11. Revision 3 makes two rules explicit, without changing any
output: when an over-long skip count ends in end of data (section 7), and which error is reported when several could
be (section 12). It also adds four failing vectors. The decoding rules are unchanged since revision 1.

This page specifies the decoder for the Summus wavelet image ("WI") in the card barcode's photo section (see
[portrait.md](portrait.md)), precisely enough to write a bit-exact decoder in any language without any other
source. It covers only the **licence-portrait profile**: format 4, 250 rows by 200 columns, 8-bit greyscale, and the
coding parameters that every licence photo seen so far uses. Anything else is out of scope: reject it (section 12).

It was written clean-room ([../cleanroom/LOG.md](../cleanroom/LOG.md)). The behaviour it specifies is that of the
C++ decoder Donald Jansen reconstructed from the original Windows DLL and
[shared in November 2024](https://github.com/the-mars-rover/rsa_identification/issues/2#issuecomment-2493025394),
called with `Fast = 0`, `Smoothing = 0`, `Sharpening = 1`; this page calls it "the reference". The repository no
longer contains that code. An independent implementation written from these notes
reproduces every checkpoint in [test-vectors/wi-checkpoints.json](test-vectors/wi-checkpoints.json) and the portrait
hash of all six public vectors. The analyst's own prototype also matched the reference's output on 1,200 synthetic streams
and 1,800 corrupted copies of the public vectors, which reach paths the six vectors do not (section 15).

## 0. Conventions

- **Integers.** Unless a step says otherwise, every value is a signed 32-bit two's-complement integer, and `+`, `−`
  and `×` wrap modulo 2^32. Section 11 lists every place where width, signedness or rounding matters.
- `x >> k` is an **arithmetic** shift right: it rounds toward minus infinity (−3 >> 1 = −2).
- `x div k` is division that **truncates toward zero** (−3 div 2 = −1). It is used only where it is named.
- `floor(x / k)` appears only for non-negative `x`, where both kinds of division agree.
- Arrays are indexed `[row][column]` and stored row-major. Row 0 is the first row of the **codec raster**, which is
  the portrait turned 180° (section 10).
- `H = 250` (rows) and `W = 200` (columns) throughout.
- `[a, b)` means a ≤ x < b; `for i in [a, b)` counts up from a to b − 1.

## 1. Overview

```
bytes ─► header (12 bytes) ─► LL band (9 × 8) ─► skip count ─► significance map (250 × 200, levels skip..4)
                                                                     │
      ┌──────────────────────────────────────────────────────────────┘
      ▼
for level l = 4, 3, 2, 1, 0:
    (l = 0 only) sharpen the level-1 image
    upsample the level-(l+1) image to level l                    ─► X now covers the level-l region
    if l ≥ skip: read level l's detail coefficients; add each one's basis function to X
X ─► 8-bit samples ─► codec raster (upside down) ─► reverse the 50,000 bytes ─► upright portrait
```

In standard terms this is a 5-level separable 2-D biorthogonal wavelet, the **LeGall 5/3 (CDF 5/3)** filters, in an
unusual form. The decoder never keeps the wavelet coefficients in a coefficient array. It works in the image domain:
at each level it upsamples the coarser image by bilinear interpolation (the 5/3 low-pass synthesis), then adds, for
each non-zero detail coefficient, that coefficient times its 2-D synthesis basis function, built from the 5/3
synthesis filters [1 2 1] and [−1 −2 6 −2 −1]. The borders use truncated kernels, not symmetric extension, and the
split puts both end samples in the low band (section 5). Which detail coefficients are non-zero is sent first, as a
**significance map**: run lengths along a Hilbert-curve-like recursive quadrant scan, with zerotree-style marking of
ancestors. The entropy coder is **adaptive Huffman coding** (FGK, sibling-property tree) with an escape for new
symbols. Quantisation is uniform with mid-point reconstruction. There is no floating point anywhere.

Working state:

| Name | Shape | Contents |
|---|---|---|
| `X` | 250 × 200 int32 | The image being reconstructed. Initially all 0. At any time only the current level's region (section 5) is meaningful. |
| `M` | 250 × 200 bytes | The significance map, in the coefficient layout of section 5. Initially all 1. |
| `Q` | integer 0..255 | The quantiser step, from the header |
| `skip` | integer 0..5 | The number of finest levels that carry no detail coefficients |

## 2. The container and header

A WI image is one self-delimiting bitstream. There is no outer container, no length field and no checksum. The
number of bytes available (`size`) is the length of the photo section (550 to 603 bytes on licences; the C API in
[../native/portrait/](../native/portrait/) accepts 13 to 674). Bytes 0 to 6 are whole bytes; from byte 7 the header is a sequence of bit fields read with
the bit reader of section 3. The reader then skips to the next byte boundary, which is byte 12 in this profile.

| Bytes | Field | Licence profile | Meaning |
|---|---|---|---|
| 0–1 | magic | `57 49` ("WI") | The reference also accepts `53 49` ("SI"): reject it |
| 2 | format version | `04` | Versions 2 and 3 have a different header: reject |
| 3–4 | rows, big-endian | `00 FA` (250) | Height of the codec raster |
| 5–6 | columns, big-endian | `00 C8` (200) | Width |

The bit fields that follow, in order (MSB first from byte 7):

| # | Width | Field | Licence profile | Meaning, and what other values would do |
|---|---|---|---|---|
| F1 | 5 | bits per sample | 8 | Other depths use other output paths |
| F2 | 1 | colour | 0 | 1 = three channels |
| F3 | 1 | step width | 1 | 1: F4 is 8 bits; 0: F4 is 16 bits |
| F4 | 8 | quantiser step `Q` | any (seen: 74, 106, 124, 142, 162, 169) | The detail step size; the LL band uses 64·Q (section 6) |
| F5 | 2 | entropy-coder selector | 0 | 1 and 2 select other decoders; 3 is invalid |
| F6 | 2 | map-coder selector | 1 | The significance-map coder of section 8; 0 and 2 select other coders, 3 none |
| F7 | 2 | coefficient-coder selector | 1 | The coefficient coder of section 9.2; 0, 2 and 3 are others |
| F8 | unary | coefficient variant A | 0 | Count of 1 bits before a 0 bit. Non-zero selects another coefficient coder |
| F9 | unary | tone-curve parameter | 0 | Non-zero replaces the identity output table (section 10) |
| F10 | unary | coefficient variant B | 0 | As F8 |
| F11 | 1 | decoder variant | 0 | 1 selects a different decoder, not specified here |
| F12 | 1 | no background | 1 | 0: background sample bytes follow |
| F13 | 8 | reserved | 0 | Read and ignored (format 4 only) |
| F14 | 1 | comment present | 0 | 1: a 16-bit length and that many bytes follow |
| F15 | 1 | extension present | 0 | As F14, for application data |
| | 4 | padding | 0 | Discarded by the byte alignment |

So in this profile the header bytes are:

```
57 49 04 00 FA 00 C8 | b7 | b8 | 28 40 00
b7 = 0x42 | (Q >> 7)            the top bit of Q
b8 = (Q & 0x7F) << 1 | F5 >> 1  the low 7 bits of Q, then the high bit of F5, which must be 0
```

**Accept exactly this shape:** bytes 0 to 6 as shown, b7 ∈ {0x42, 0x43}, **b8 even**, bytes 9 to 11 = `28 40 00`.
Q may be any value 0 to 255 (Q = 0 decodes to a flat image). The reference's C wrapper checked everything except
the lowest bit of b8; an odd b8 would select entropy coder 2, which licence portraits do not use and this page does
not specify. Reject it: this repository's decoders do (E1).

The coded data begins at byte 12. The rest of the stream (sections 6 to 9) is one continuous bit sequence with no
further alignment.

## 3. The bit reader

- **Bit order:** most significant bit first within each byte; bytes in increasing address order.
- **Reading n bits** (0 ≤ n ≤ 32) returns an unsigned integer whose first-read bit is its most significant.
  Reading 0 bits returns 0 and consumes nothing.
- **Refill:** a byte is fetched only when the first of its bits is needed. The reference reads the whole stream,
  bytes 0 to 6 included, through this reader. The checkpoint value `bytes_consumed` is the number of bytes fetched
  when decoding ends, counted from byte 0: the offset just past the last byte of which any bit was used.
- **Byte alignment** (used once, after the header) discards the unread bits of a partly read byte. If the reader is
  already on a boundary, nothing is skipped.
- **End of data:** needing a bit when all `size` bytes have been fetched is a decoding **failure**, error E2. A
  decoder may stop at that point (section 12). (The reference instead sets an error flag, returns 0 for that read
  and every later one, runs to the end, and reports failure; its output must not be used.) A stream may end exactly
  on its last needed bit: public vectors 5 and 6 use every byte.
- **Trailing data:** bytes after the last consumed bit are ignored. On the six public vectors, 0 to 13 of them
  remain, all zero.

## 4. The adaptive Huffman coder

Both entropy-coded stages use one algorithm, with separate model instances: an adaptive binary code tree over an
alphabet of `N` symbols (0 to N−1), plus two special leaves. It is the FGK algorithm (sibling property). The
node numbering, the tie-breaking and the treatment of the special leaves all change the bits, so follow the rules
below exactly.

### 4.1 State

A table of nodes numbered 0, 1, 2, …, with room for 2N + 3. Each node has:

- `parent`: a node number, or none for the root (node 0);
- `leaf`: true or false;
- `payload`: for a leaf, its symbol (0 to N+1); for an internal node, the number of its first child. The second
  child is always `payload + 1`;
- `weight`: a non-negative integer.

Also `node_of[s]` for s in 0 to N+1 (the leaf holding symbol s, or absent), and `next`, the first unused node number.

Two symbols are special: **N**, the *large-value leaf*, and **N+1**, the *escape leaf*.

### 4.2 Initialisation, with a preset count P

```
node 0: parent none, internal, payload 1, weight 2          (the root)
node 1: parent 0,    leaf,     payload N,   weight 1         (large-value leaf)
node 2: parent 0,    leaf,     payload N+1, weight 1         (escape leaf)
node_of[N] = 1; node_of[N+1] = 2; every other node_of[s] absent; next = 3
for s in [0, P):  add(s); update(s)
```

| Model | Alphabet N | Presets P | Escape width | Used for |
|---|---|---|---|---|
| map model | 256 | 64 (symbols 0 to 63) | 8 bits | run lengths (section 8) |
| coefficient model | 2048 | 3 (symbols 0, 1, 2) | `E`, read per level (section 9.2) | magnitudes |

A new model instance is made for the map stage, and a new one for each coefficient level.

### 4.3 add(s): a new leaf for symbol s

Only if `node_of[s]` is absent (otherwise nothing happens). Let `d = next`. Node d−1 is always a leaf at this point.

```
node d   := copy of node d−1 (leaf flag, payload, weight), with parent d−1
node_of[payload of node d] := d
node d−1 := internal, payload d        (its parent and weight are unchanged)
node d+1 := leaf, payload s, weight 0, parent d−1
node_of[s] := d+1
next := d + 2
```

### 4.4 update(s): after coding symbol s

```
i = node_of[s]
while i is not none:
    weight[i] += 1
    j = i
    while j > 0 and weight[j−1] < weight[i]: j −= 1      # j = first node of i's old weight class
    if j ≠ i: swap(i, j); i = j
    i = parent[i]
```

The reference would rescale the weights once the root weight reaches 2^31. The root weight starts at 66 or 5 and
grows by exactly 1 per coded symbol, so this can never happen for inputs of this size; implementations need not
implement it.

### 4.5 swap(i, j)

```
for (x, y) in ((i, j), (j, i)):
    if leaf[x]: node_of[payload[x]] := y
    else:       parent[payload[x]] := y; parent[payload[x] + 1] := y
exchange leaf, payload and weight between nodes i and j     # parents stay with the node numbers
```

(A node is never swapped with its own ancestor.)

### 4.6 decode(model, E): one symbol

```
i = 0
while not leaf[i]: i = payload[i] + read 1 bit             # 0 = first child, 1 = second child
s = payload[i]
if s == N+1:                                               # escape
    s = read E bits                                        # always < N in this profile
    add(s)                                                 # no effect if s already has a leaf
update(s)
return s                                                   # 0 ≤ s ≤ N; s = N is the large-value leaf
```

### 4.7 run(): a run length (map model only)

```
run():
    s = decode(map model, 8)
    if s ≥ 255:                          # 255 or 256 (the large-value leaf): an extension; s itself is discarded
        hi = run()                       # recursive, read first
        lo = decode(map model, 8)
        return hi × 256 + lo             # wrapping 32-bit; lo is taken literally, even if it is 255 or 256
    return s
```

### 4.8 Self-test: the initial codes

Written as the bits read from the root to reach each leaf.

Coefficient model (N = 2048, P = 3), nine nodes:

| Symbol | 0 | 1 | 2 | 2048 (large) | 2049 (escape) |
|---|---|---|---|---|---|
| Code | `10` | `000` | `001` | `01` | `11` |

| Node | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|---|
| parent, leaf?, payload, weight | –, I, 1, 5 | 0, I, 3, 3 | 0, I, 5, 2 | 1, I, 7, 2 | 1, L, 2048, 1 | 2, L, 0, 1 | 2, L, 2049, 1 | 3, L, 1, 1 | 3, L, 2, 1 |

Map model (N = 256, P = 64): 131 nodes, root weight 66, and these codes:

```
 0 010001    1 110001    2 001001    3 011001    4 101001    5 111001    6 000101    7 001101
 8 010101    9 011101   10 100101   11 101101   12 110101   13 111101   14 000011   15 000111
16 001011   17 001111   18 010011   19 010111   20 011011   21 011111   22 100011   23 100111
24 101011   25 101111   26 110011   27 110111   28 111011   29 111111   30 000010   31 000100
32 000110   33 001000   34 001010   35 001100   36 001110   37 010000   38 010010   39 010100
40 010110   41 011000   42 011010   43 011100   44 011110   45 100000   46 100010   47 100100
48 100110   49 101000   50 101010   51 101100   52 101110   53 110000   54 110010   55 110100
56 110110   57 111000   58 111010   59 111100   60 111110   61 0000000  62 0000010  63 0000011
256 (large) 0000001     257 (escape) 100001
```

## 5. Geometry: levels, regions and bands

**Levels.** L = max(0, floor(log2(min(H, W) − 2)) − 2). For 250 × 200, L = floor(log2 198) − 2 = **5**.

**Level regions.** Level l (0 = finest, 5 = the LL band) covers rows [0, size(H, l)) and columns [0, size(W, l)):

```
size(N, l) = floor((N + 2^(l+1) − 2) / 2^l)                  (= floor((N − 2) / 2^l) + 2)
low(n)  = floor(n / 2) + 1                                    (= size of the next coarser level)
high(n) = n − low(n)
```

**Split.** A dimension of length n has low(n) low-pass samples and high(n) high-pass samples. In the image
domain the low samples sit at the even positions 0, 2, 4, …, and **when n is even the last position n − 1 is also a
low sample**. The high samples sit at the odd positions 1, 3, …, 2·high(n) − 1. Low index i ↔ position 2i, except
that for even n the last low index low(n) − 1 ↔ position n − 1. High index t ↔ position 2t + 1.

**Bands.** In the coefficient layout, the only layout the map M uses, level l's region (n = size(H, l) rows,
m = size(W, l) columns) holds the coarser level's region in its top-left and three detail bands:

| Band | Rows | Columns | Filters (vertical, horizontal) |
|---|---|---|---|
| B0 | [0, low(n)) | [low(m), m) | low, high |
| B1 | [low(n), n) | [0, low(m)) | high, low |
| B2 | [low(n), n) | [low(m), m) | high, high |

A cell of B0 at (r, c) is the coefficient with vertical low index r and horizontal high index c − low(m); B1 has
vertical high index r − low(n) and horizontal low index c; B2 has high indices r − low(n) and c − low(m).

For the licence profile:

| Level | Rows n | Columns m | low(n) / high(n) | low(m) / high(m) | n mod 4 | m mod 4 |
|---|---|---|---|---|---|---|
| 0 | 250 | 200 | 126 / 124 | 101 / 99 | 2 | 0 |
| 1 | 126 | 101 | 64 / 62 | 51 / 50 | 2 | 1 |
| 2 | 64 | 51 | 33 / 31 | 26 / 25 | 0 | 3 |
| 3 | 33 | 26 | 17 / 16 | 14 / 12 | 1 | 2 |
| 4 | 17 | 14 | 9 / 8 | 8 / 6 | 1 | 2 |
| 5 (LL) | 9 | 8 | | | | |

## 6. Stage 1: the LL band

```
B   = read 5 bits
off = (read B bits) mod 256          # only the low 8 bits are kept; B = 0 reads nothing and gives 0
for r in [0, 9):                     # rows of the level-5 region
    for c in [0, 8):
        u = read B bits              # 0 if B = 0
        v = u − off
        X[r][c] = (2v + 1) × 32Q  if v ≥ 0
                  (2v − 1) × 32Q  if v < 0          # wrapping 32-bit arithmetic
```

That is a uniform quantiser with step 64·Q and mid-point reconstruction. Note that v = 0 reconstructs as +32Q, not 0.
The six public vectors have B = 5 or 6. (The reference also computes a rounded mean of this band. Nothing uses it.)

**Checkpoint C1:** the 9 × 8 values X[0..8][0..7].

## 7. Stage 2: the skip count

```
skip = number of 1 bits read before the first 0 bit     # read up to and including the 0 bit
require skip ≤ 5                     # checked only after the 0 bit has been read; else E3
```

Read 1 bits until a 0 bit arrives, however many there are, and only then compare the count with 5. Do not stop at
the sixth 1 bit. So:

- If the data ends before the 0 bit, the error is **E2**, even when six or more 1 bits have already been read.
- If six or more 1 bits are followed by a 0 bit, the error is **E3**, whether or not any data follows. Nothing is
  read after that 0 bit, so a stream that ends right after it is still E3.

The reference behaves this way: in the first case it meets end of data first, and then also finds the count too
large. The vectors `e2-skip-prefix-cut`, `e2-skip-5-cut`, `e3-skip-6`, `e3-skip-9` and `e3-skip-6-at-end` test it.

Levels 0 to skip − 1 carry no detail coefficients at all: their bands are neither mapped nor read. The six public
vectors have skip 0 or 1.

## 8. Stage 3: the significance map

M starts as 250 × 200 cells, all **1**. The stage marks cells of the detail bands of levels skip to 4:

| Value | Meaning |
|---|---|
| 1 | not significant, and no significant descendant: its coefficient is zero and is not sent |
| 2 | significant: its magnitude is at least 1 |
| 4 | has a significant descendant, found at a finer level: its coefficient is sent, and its magnitude may be 0 |

Cells of the LL band and of levels below skip stay 1. When skip = 5 no band is scanned, but the first run is still
read and must be 0.

### 8.1 Procedure

```
model = new map model (section 4.2); R = run()               # R: 1-cells still to pass before the next significant one
for l in [skip, 5):                                          # finest decoded level first
    j = l − skip
    if j is even: visit(B0 of l, orientation 3); visit(B2 of l, 0); visit(B1 of l, 0)
    else:         visit(B1 of l, 1);             visit(B2 of l, 2); visit(B0 of l, 2)
require R == 0                                               # else failure
```

`visit(rows [r0, r1), columns [c0, c1), orientation o)` at level l, band b:

```
count = number of cells in the rectangle with M = 1
if R ≥ count: R −= count; return                             # nothing significant here
if (r1 − r0) > 2 and (c1 − c0) > 2:
    rm = r0 + floor((r1 − r0) / 2); cm = c0 + floor((c1 − c0) / 2)
    T = rows [r0, rm), Bt = rows [rm, r1), Lf = columns [c0, cm), Rt = columns [cm, c1)
    visit the four quadrants in this order, each with the orientation shown:
        o = 0: (T, Rt, 3)  (Bt, Rt, 0)  (Bt, Lf, 0)  (T, Lf, 2)
        o = 1: (Bt, Lf, 2) (T, Lf, 1)   (T, Rt, 1)   (Bt, Rt, 3)
        o = 2: (Bt, Lf, 1) (Bt, Rt, 2)  (T, Rt, 2)   (T, Lf, 0)
        o = 3: (T, Rt, 0)  (T, Lf, 3)   (Bt, Lf, 3)  (Bt, Rt, 1)
    return
scan the rectangle in orientation o's serpentine order, calling cell(r, c) for each cell
```

The serpentine orders (the first line is swept in the direction shown, and each following line reverses):

| o | Outer loop | First inner sweep |
|---|---|---|
| 0 | columns from c1 − 1 down to c0 | rows ascending |
| 1 | columns from c0 up to c1 − 1 | rows descending |
| 2 | rows from r1 − 1 down to r0 | columns ascending |
| 3 | rows from r0 up to r1 − 1 | columns descending |

```
cell(r, c):
    if M[r][c] ≠ 1: return
    if R ≠ 0: R −= 1; return                                 # R ≠ 0, not R > 0: a negative R keeps counting down
    M[r][c] = 2                                              # R == 0: this cell is significant
    mark_ancestors(r, c, l, b)
    R = run()
```

Skipping a whole rectangle when R ≥ count is exactly equivalent to visiting its 1-cells one by one, so the scan
order is the full recursive order. R can go negative through 32-bit wrap-around of a run (4.7), so compare signed
in `visit`. A negative R fails `R ≥ count` everywhere and is decremented at every 1-cell, so it never reaches 0 and
ends in failure (E5).

### 8.2 Ancestor marking

```
mark_ancestors(r, c, l, b):
    for p in [l, 4):                             # from the cell's level up to the coarsest detail level, 4
        n = size(H, p); m = size(W, p)
        r = parent(r, n) if b ∈ {B1, B2} (rows are high-pass) else floor(r / 2)
        c = parent(c, m) if b ∈ {B0, B2} (columns are high-pass) else floor(c / 2)
        if M[r][c] == 4: stop                    # this ancestor and all above it are marked already
        M[r][c] = 4

parent(x, n):                                    # x: an absolute row or column of M in the high part [low(n), n)
    n mod 4 = 0 or 1:  floor((x + 1) / 2)
    n mod 4 = 2:       floor((x + 2) / 2)
    n mod 4 = 3:       floor((x + 1) / 2) if x = n − 1, else floor((x + 2) / 2)
```

Both coordinates are absolute positions in M (not band-relative indices), before and after. The ancestor is in the
same band one level coarser. Marking never reaches the LL band. It never touches the level
now being scanned either, because the coarser levels' cells are all in the top-left quadrant of the current level.

**Checkpoint C2:** all 250 × 200 cells of M after `require R == 0`.

## 9. Stage 4: reconstruction, level by level

```
for l = 4, 3, 2, 1, 0:
    if l == 0: sharpen (section 9.4)
    upsample to level l (section 9.1)            # checkpoint C3.l
    if l ≥ skip: decode and add level l's details (sections 9.2 and 9.3)    # C4.l, then C5.l
    (if l < skip, C4.l is empty and C5.l = C3.l)
```

### 9.1 Upsampling

Before the step, X holds the level-(l+1) image in rows [0, a) and columns [0, b), where n = size(H, l),
m = size(W, l), a = low(n) and b = low(m). The step replaces the whole level-l region, n × m, by Y, computed only
from the values before the step. (The reference works in place, from the bottom-right corner, so that no value is
overwritten before it is used.)

For one dimension of length n, the source set S(p) of output position p:

```
S(p) = { a − 1 }                     if n is even and p = n − 1
       { p / 2 }                     if p is even (and not the case above)
       { (p − 1)/2, (p + 1)/2 }      if p is odd  (and not the case above)
```

Then, with every source value biased by +1:

```
s = Σ over i ∈ S_rows(p), j ∈ S_cols(q) of (X[i][j] + 1)           # 1, 2 or 4 terms; wrapping sum
k = 1, 2 or 3 for 1, 2 or 4 terms
Y[p][q] = s >> k                     normally
Y[p][q] = s div 2^k                  (truncating) in these two exceptions:
    (a) n is even and p = n − 1                                      (the whole last row)
    (b) m is even, q = m − 1, and p = (n − 1 if n is odd, else n − 2)
```

So a copied sample is (x + 1) >> 1, a sample between two is (x + y + 2) >> 2, and a sample between four is
(x + y + z + w + 4) >> 3. Each level halves the scale of the image, and the detail kernels (9.3) are scaled to match.
For 250 × 200 the exceptions fall on: level 0, the whole of row 249 and the cell (248, 199); level 1, row 125;
level 2, row 63; level 3, the cell (32, 25); level 4, the cell (16, 13). They change a value only where the sum is
negative and not divisible. That never happens on the six public vectors, but it does on other inputs, and the
reference does it.

### 9.2 Detail coefficients of level l

```
E = read 5 bits;  require E ≤ 10                 # escape width; else failure
model = new coefficient model (section 4.2)
for band in B0, B1, B2:
    for r in band's rows (ascending):
        for c in band's columns (ascending):
            if M[r][c] == 1: continue            # zero, not sent
            sign = read 1 bit
            k = decode(model, E)                 # 0 ≤ k ≤ 2048; k = 2048 is the large-value leaf, taken literally
            if M[r][c] == 2: k += 1              # significant cells are known to be non-zero
            v = k × Q + floor(Q / 2)
            if sign == 1: v = −v
            add_basis(band, r, c, v)             # section 9.3
```

That is mid-point reconstruction with step Q. A cell marked 4 whose magnitude symbol is 0 still contributes
±floor(Q/2). |v| ≤ 2049 × 255 + 127 = 522,622, so no product in 9.3 overflows. The six public vectors use E from 2
to 5.

**Checkpoint C4.l:** the values v in this order, one int32 each.

### 9.3 Basis functions

Every detail coefficient adds a separable kernel to the level-l image in X (n = size(H, l), m = size(W, l)).

Low kernel K_L(i; n), for low index i. Use the first row that applies:

| Case | Positions : weights |
|---|---|
| i = 0 | 0 : 2,  1 : 1 |
| i = floor((n − 1) / 2) | 2i − 1 : 1,  2i : 2 |
| i = low(n) − 1 (arises only for even n) | n − 1 : 2 |
| otherwise | 2i − 1 : 1,  2i : 2,  2i + 1 : 1 |

High kernel K_H(t; n), for high index t, with centre p = 2t + 1:

| Case | Positions : weights |
|---|---|
| t = 0 | 0 : −2,  1 : 6,  2 : −2,  3 : −1 |
| t = high(n) − 1 | p − 2 : −1,  p − 1 : −2,  p : 6,  p + 1 : −2 |
| otherwise | p − 2 : −1,  p − 1 : −2,  p : 6,  p + 1 : −2,  p + 2 : −1 |

The interior kernels are the 5/3 synthesis filters [1 2 1] and [−1 −2 6 −2 −1], shortened at the borders. The two
tables are normative: use exactly the positions they list. (For example, for even n the last high kernel omits
position p + 2 = n − 1 although it is inside the region.)

```
add_basis(B0, r, c, v): for each (y, α) in K_L(r; n), (x, β) in K_H(c − low(m); m):  X[y][x] += 2 × v × α × β
add_basis(B1, r, c, v): for each (y, α) in K_H(r − low(n); n), (x, β) in K_L(c; m):  X[y][x] += 2 × v × α × β
add_basis(B2, r, c, v): for each (y, α) in K_H(r − low(n); n), (x, β) in K_H(c − low(m); m):  X[y][x] += v × α × β
```

Every product is exact. Only the sums wrap. The order of the additions does not matter. Every position written is
inside the level-l region; for 250 × 200 this was checked for all 49,928 coefficient positions of levels 0 to 4.

For example, an interior B2 coefficient v adds the 5 × 5 pattern v·[−1 −2 6 −2 −1]ᵀ[−1 −2 6 −2 −1], centred at
(2t_r + 1, 2t_c + 1), with 36v at the centre. An interior B0 coefficient adds 2v·[1 2 1]ᵀ[−1 −2 6 −2 −1], centred
at (2r, 2t + 1).

**Checkpoint C5.l:** the n × m level-l region of X after all of level l's additions.

### 9.4 Sharpening (Sharpening = 1), before the finest level

Just before upsampling to level 0, the level-1 image (n1 = 126 rows by m1 = 101 columns, which is
floor(H/2) + 1 by floor(W/2) + 1) is replaced by S:

```
S[0][c] = X[0][c] and S[n1 − 1][c] = X[n1 − 1][c]         for every column c
S[r][0] = X[r][0] and S[r][m1 − 1] = X[r][m1 − 1]         for every row r
S[r][c] = (12·X[r][c] − X[r][c−1] − X[r][c+1] − X[r−1][c] − X[r+1][c]) >> 3      for 0 < r < n1−1, 0 < c < m1−1
```

All of S is computed from the unsharpened X. The sums wrap, and the shift is arithmetic. This happens whatever the
skip count is, and it is the only post-processing. It is a 5-point sharpening kernel with unit gain ((12 − 4) / 8 = 1).

**Checkpoint C6:** the 126 × 101 values S, the input of the level-0 upsampling.

## 10. Output samples and orientation

```
for every r in [0, 250), c in [0, 200):
    t = X[r][c] + 16                      # wrapping
    raster[r·200 + c] = 255          if t ≥ 8192
                        0            if t < 0
                        floor(t / 32) otherwise
```

That is round(X / 32), clamped to 0..255: after the last level, X is 32 times the sample value. (With F9 = 0 the
output table is the identity; other F9 values are out of scope.)

**Checkpoint C7:** the 50,000-byte raster in codec order (row 0 first). It is **upside down**: the codec's row 0 is
the bottom row of the upright portrait, and its column 0 is the portrait's rightmost column.

**Checkpoint C8:** the upright portrait, `portrait[k] = raster[49,999 − k]` (the whole array reversed, a 180°
turn). The C API's `sadl_portrait_decode` returns this, and `sadl_portrait_decode_native_order` returns C7.
C8 is `portrait_sha256` in [test-vectors/public-vectors.json](test-vectors/public-vectors.json).

## 11. Integer semantics: every place it matters

The reference is decompiled 32-bit x86 code, compiled with `-fwrapv -fno-strict-aliasing`. Signed arithmetic wraps
modulo 2^32, and `int` is 32 bits. An implementation in a language with 64-bit or arbitrary-precision integers must
reduce to signed 32-bit at each of the places marked "wraps".

| Where | Rule |
|---|---|
| Bit reads | Unsigned, up to 32 bits. In this profile a read is at most 31 bits (5-bit widths), so it fits a non-negative int32. The reference's reader returns the full 32-bit value; its accumulator and bit mask are 32-bit unsigned (one of the LP64 fixes). |
| LL offset (6) | Only the low 8 bits of the offset are kept. This matters only for B > 8, never seen (B is 5 or 6); see 15. |
| LL values (6) | v = u − off never overflows. (2v ± 1) × 32Q **wraps** (possible for B ≥ 17). |
| Run lengths (4.7) | hi × 256 + lo **wraps**. R is compared as a signed value. |
| Coefficient values (9.2) | Exact: \|v\| ≤ 522,622; floor(Q/2) with Q ≥ 0. |
| Basis additions (9.3) | Products exact (at most 36 × 522,622). The sums follow the wrap rule but cannot actually wrap: every upsampled sample has \|X\| ≤ 2^30 (a shift or division of an int32 by at least 2), and one level's details add at most 128 × 522,622 = 66,895,616 to any sample (128 is the largest sum of \|weights\| at any position, checked at every level). |
| Upsampling (9.1) | The biased sums **wrap**; then an arithmetic shift, or truncating division in the two exceptions. Reached at levels 4 and 3 (LL values near ±2^31); not reached at levels 2 to 0 in any search, but not proven impossible. The reference computes the level sizes here in unsigned 32-bit (an LP64 fix); they are small and positive, so this changes nothing. |
| Sharpening (9.4) | 12x − four neighbours **wraps**; then an arithmetic shift by 3. Not demonstrated: the largest value constructed was about 1.94 × 10^9, below 2^31. |
| Output (10) | X + 16 follows the wrap rule, but cannot wrap: \|X\| ≤ 2^30 + 66,895,616 (see the basis row). The comparisons are signed. |
| Huffman weights (4) | Non-negative and small (below 6,000 for any input up to 674 bytes). The reference's rescale test compares the root weight with 2^31 as a 32-bit value (an LP64 fix); it is never reached. |
| Geometry (5, 8) | All operands are non-negative, so every halving is a floor. |

On the six public vectors nothing wraps and no truncating division differs from a shift. The rules still matter
for other inputs. [test-vectors/wi-synthetic.json](test-vectors/wi-synthetic.json) reaches every place that can
wrap in practice: the LL values, the runs, and the upsampling at levels 4 and 3.

## 12. Errors and inputs to reject

| # | Condition | Where |
|---|---|---|
| E1 | The header is not exactly the profile of section 2 (including an odd byte 8), or `size` < 13, or `size` > 674 (the C API's limit; licences use 550 to 603) | 2 |
| E2 | End of data: a bit is needed after all `size` bytes have been fetched | 3 |
| E3 | skip > 5 | 7 |
| E4 | escape width E > 10 at any level | 9.2 |
| E5 | R ≠ 0 after the map stage | 8.1 |

**Which error.** The error to report is the **first** condition of E2 to E5 met in reading order. E1 is decided
before any decoding. A decoder **may stop at the first error**, and it must not report a later one instead.
Stopping early and running to the end cannot change which error comes first, because both follow the same steps up
to it.

The reference does not stop. After end of data it reads zeros, and it can then also meet a second condition:

- E3 after E2, when the data ends inside an over-long skip count (section 7);
- E5 after E2, when the data ends inside a run, which leaves the run count non-zero.

In both cases E2 is the canonical error. After E3, E4 or E5 the reference reads nothing more and checks nothing
more. Every failing case in [test-vectors/wi-synthetic.json](test-vectors/wi-synthetic.json) gives the canonical
error as `expected_error`. Where the reference meets more than one condition, `reference_error_sequence` lists them
in order; see `e2-skip-prefix-cut` and `e2-then-e5-run-cut`. The reference itself does not tell E2 to E5 apart: the
codes are this specification's names, and they were identified by instrumenting the reference. A sweep of 9,733
truncated synthetic streams found no other pattern.

The C API reports `SADL_PORTRAIT_UNSUPPORTED` for E1 and `SADL_PORTRAIT_DECODE_FAILED` for E2 to E5. The
reference's C wrapper did the same, except that it did not check byte 8 (section 2).

Two more reference checks cannot trigger in this profile: an escaped value ≥ N (E ≤ 10 bits against N = 2048;
8 bits against N = 256), and the weight rescale (4.4). An implementation may treat them as failures.

## 13. Decoder options, and the paths not used

The reference was called with three options of the original library fixed:

- **Sharpening = 1** turns on section 9.4. Any value other than exactly 1 would turn it off.
- **Smoothing = 0.** If it were on, magnitude-1 coefficients (after the +1 of section 9.2) of levels 0 and 1 would
  reconstruct as exactly ±Q instead of ±(Q + floor(Q/2)). Keep the formula of 9.2.
- **Fast = 0.** The library's default is Fast = 1, which decodes the finest level straight to bytes by a different,
  lower-cost route. It is not specified here.

Not used, and out of scope (reject them through E1): the "SI" magic, format versions 2 and 3, three-channel colour,
other sample depths, a 16-bit Q, entropy coders 1 to 3, map coders 0 and 2, coefficient coders 0, 2 and 3 and their
variants F8 and F10, output tone curves (F9), the other decoder selected by F11, background samples, comments and
extensions, reduced-resolution decoding ("magnification"), sub-image selection, and callback-driven input and output.

## 14. Decoding walk-through and checkpoints

Every checkpoint is the SHA-256 of a byte string. int32 arrays are serialised **little-endian, row-major, over
exactly the named region** (for level l, size(250, l) rows by size(200, l) columns, starting at row 0 and column 0).
[test-vectors/wi-checkpoints.json](test-vectors/wi-checkpoints.json) has them for the six public vectors, with the
scalar values below.

1. Check the header (section 2) and align to byte 12. *Scalars:* `wi_length` (= `size`), `wi_sha256` (of the
   input), `q`.
2. Decode the LL band (6). **C1** = 9 × 8 int32 (288 bytes). *Scalar:* `ll_bits` (B).
3. Read the skip count (7). *Scalar:* `skip`.
4. Decode the significance map (8). **C2** = 250 × 200 bytes of M (50,000 bytes, values 1, 2 or 4).
5. For l = 4, 3, 2, 1, then 0:
   - (l = 0 only) Sharpen (9.4). **C6** = 126 × 101 int32.
   - Upsample (9.1). **C3.l** = the level-l region, int32.
   - If l ≥ skip: read E (*scalar* `escape_bits`), then decode and add the details (9.2, 9.3). **C4.l** = the
     dequantised values v in decoding order, each as a little-endian int32, concatenated (*scalar*
     `C4_coefficient_count`). **C5.l** = the level-l region after the additions, int32. If l < skip: `escape_bits`
     is null, C4.l is the empty string (count 0), and C5.l = C3.l.
6. Convert to 8 bits (10). **C7** = the 50,000-byte codec raster. *Scalar:* `bytes_consumed` (section 3).
7. Reverse (10). **C8** = the upright portrait = `portrait_sha256` of public-vectors.json.

The region sizes, for hashing: level 4, 17 × 14; level 3, 33 × 26; level 2, 64 × 51; level 1, 126 × 101; level 0,
250 × 200.

**Synthetic vectors.** [test-vectors/wi-synthetic.json](test-vectors/wi-synthetic.json) has 41 more valid streams
with the same checkpoints and scalars, and 26 streams that must fail, each with the canonical (first) error of
section 12. All are generated from made-up content, and each is at most 674 bytes except the one testing that
limit. They reach what the six public vectors do not: skip 0 to 5; LL widths 0, 1, 3 to 9, 12, 16 to 18 and 31;
escape widths 0 to 10; both large-value leaves; nested run extensions; Q = 0 and 255; wrap-around; and the
truncating divisions changing values. Run them after the public vectors.

A suggested order of work: C1 and C2 test the bit reader, the Huffman coder and the map scan. C4 tests the
coefficient coder without the transform. C3 of level 4 tests the upsampling alone. C5 tests the basis functions.
C6 tests the sharpening. C7 tests the output conversion.

## 15. Verification, and what is uncertain

**How it was checked.** A decoder written by a separate agent from a draft of this specification alone, not from
the reference, reproduced C1 to C8 of all six public vectors on its first run. On the synthetic vectors it differs
only in the two cases that test the two draft defects it reported (the `R ≠ 0` test of 8.1 and the upper size limit
of E1), both corrected since. The analyst's own prototype was compared with the reference's output on:

- 1,200 synthetic, valid streams in the licence header shape, generated to cover skip counts 0 to 5, LL widths B
  from 0 to 16, escape widths 0 to 10, both large-value leaves (256 and 2048), multi-level run extensions, maps from
  empty to dense, Q from 0 to 255, 32-bit wrap-around, and tens of thousands of upsampled values where the
  truncating exceptions of 9.1 differ from a shift: all identical;
- 1,800 corrupted copies of the public vectors: the same 549 decoded, bit-identical, and the same 1,251 failed;
- every one of the 49,928 basis functions of 9.3 at levels 0 to 4, and the upsampling of 9.1 on random signed data
  at several sizes.

**Exercised by the six public vectors:** all of sections 2 to 10 as written, with skip 0 and 1, B = 5 and 6,
E = 2 to 5, escapes in both models, run extensions, all four scan orientations and all five levels.

**Not exercised by the six public vectors** (so a real card could first reach them; each was checked against the
reference with synthetic data): skip 2 to 5; B other than 5 and 6; E of 0, 1 and 6 to 10; decoding the large-value
leaf (a run extension through symbol 256; a magnitude of 2048); the truncating exceptions of 9.1 changing a value;
32-bit wrap-around.

**Behaviour that may differ from the original Summus DLL.** This specification follows the decompiled
reconstruction, which the repository has verified on the six public portraits and on real cards
([README.md](README.md#confidence)). Two details look like they could be decompilation artefacts, and the public
vectors cannot confirm them:

- the LL offset held in 8 bits (6). It is moot for B ≤ 8, and every portrait seen has B = 5 or 6;
- the two truncating divisions of 9.1, where the rest of the upsampling uses shifts. They change a value only where
  a sum over the last row or column is negative. That is plausible for a very dark border, but it did not happen on
  any public vector.

If a genuine portrait ever decodes differently from another decoder, check these first.

**Not specified:** every path listed in section 13.
