"""The WI portrait codec of spec/wi-codec.md, in plain Python (standard library only, Python 3.9+).

This is the readable reference that sits next to the specification: each function names the section it follows.
It decodes the licence-portrait profile only (format 4, 250 x 200, 8-bit greyscale) and rejects anything else.

    import wi
    portrait = wi.decode(photo_section)        # 50,000 bytes, upright, row by row from the top-left (C8)
    raster = wi.decode_native_order(photo)     # the codec raster, upside down (C7)

Errors raise WiError, whose .code is 'E1' to 'E5' (section 12). Messages never carry decoded data.
The output is a person's portrait: do not log, store or upload it without a reason.
"""

H = 250  # rows of the codec raster
W = 200  # columns
PIXELS = H * W
MIN_SIZE = 13
MAX_SIZE = 674
LEVELS = 5

# Section 2: the only header accepted. Byte 7 may also be 0x43 (the top bit of Q); byte 8 must be even.
HEADER_PREFIX = bytes.fromhex('57490400fa00c8')
HEADER_SUFFIX = bytes.fromhex('284000')
HEADER_SIZE = 12

B0, B1, B2 = 0, 1, 2  # the detail bands of section 5


class WiError(ValueError):
    """A WI stream that cannot be decoded. `code` is 'E1' to 'E5' of spec/wi-codec.md section 12."""

    MESSAGES = {
        'E1': 'not the licence WI profile',
        'E2': 'end of data',
        'E3': 'skip count above 5',
        'E4': 'escape width above 10',
        'E5': 'run count not exhausted after the map',
    }

    def __init__(self, code):
        super().__init__(f'{code}: {self.MESSAGES[code]}')
        self.code = code


def decode(wi):
    """WI bytes to the upright 200 x 250 portrait (checkpoint C8): 50,000 bytes, row by row from the top-left."""
    return decode_native_order(wi)[::-1]


def decode_native_order(wi):
    """WI bytes to the codec raster (checkpoint C7), which is the portrait turned 180 degrees."""
    return _decode(bytes(wi), None)


# ---------------------------------------------------------------------------------------------------------------------
# Section 0 and 11: signed 32-bit wrap-around.

def wrap32(x):
    """x reduced to a signed 32-bit two's-complement value."""
    return ((x + 0x80000000) & 0xFFFFFFFF) - 0x80000000


def truncating_div_pow2(x, k):
    """x div 2^k, rounding toward zero (section 0), as opposed to x >> k, which rounds toward minus infinity."""
    return x >> k if x >= 0 else -((-x) >> k)


# ---------------------------------------------------------------------------------------------------------------------
# Section 3: the bit reader.

class BitReader:
    """Most significant bit first. `fetched` counts the bytes of which any bit has been used (bytes_consumed)."""

    def __init__(self, data, start):
        self.data = data
        self.fetched = start  # whole bytes already consumed (the header)
        self.byte = 0
        self.bits_left = 0  # unread bits of the current byte

    def bit(self):
        if self.bits_left == 0:
            if self.fetched >= len(self.data):
                raise WiError('E2')
            self.byte = self.data[self.fetched]
            self.fetched += 1
            self.bits_left = 8
        self.bits_left -= 1
        return (self.byte >> self.bits_left) & 1

    def bits(self, n):
        """An unsigned n-bit value (0 <= n <= 32), first bit most significant. Reading 0 bits reads nothing."""
        value = 0
        for _ in range(n):
            value = (value << 1) | self.bit()
        return value


# ---------------------------------------------------------------------------------------------------------------------
# Section 4: the adaptive Huffman coder (FGK, sibling property), with a large-value leaf N and an escape leaf N + 1.

class AdaptiveHuffman:
    def __init__(self, n, presets):
        """Section 4.2: the root, the two special leaves, then `presets` symbols added and updated once each."""
        self.n = n
        size = 2 * n + 3
        self.parent = [-1] * size  # -1: none (only the root)
        self.leaf = [False] * size
        self.payload = [0] * size  # a leaf's symbol, or an internal node's first child
        self.weight = [0] * size
        self.node_of = [-1] * (n + 2)  # -1: the symbol has no leaf yet

        self.leaf[0], self.payload[0], self.weight[0] = False, 1, 2
        self.parent[1], self.leaf[1], self.payload[1], self.weight[1] = 0, True, n, 1
        self.parent[2], self.leaf[2], self.payload[2], self.weight[2] = 0, True, n + 1, 1
        self.node_of[n], self.node_of[n + 1] = 1, 2
        self.next = 3
        for s in range(presets):
            self.add(s)
            self.update(s)

    def add(self, s):
        """Section 4.3: split the last node, a leaf, into itself and a new zero-weight leaf for s."""
        if self.node_of[s] != -1:
            return
        d = self.next
        self.leaf[d], self.payload[d], self.weight[d] = self.leaf[d - 1], self.payload[d - 1], self.weight[d - 1]
        self.parent[d] = d - 1
        self.node_of[self.payload[d]] = d
        self.leaf[d - 1], self.payload[d - 1] = False, d
        self.leaf[d + 1], self.payload[d + 1], self.weight[d + 1], self.parent[d + 1] = True, s, 0, d - 1
        self.node_of[s] = d + 1
        self.next = d + 2

    def update(self, s):
        """Section 4.4: increment from the leaf to the root, swapping each node to the front of its weight class."""
        i = self.node_of[s]
        while i != -1:
            self.weight[i] += 1
            j = i
            while j > 0 and self.weight[j - 1] < self.weight[i]:
                j -= 1
            if j != i:
                self.swap(i, j)
                i = j
            i = self.parent[i]

    def swap(self, i, j):
        """Section 4.5: exchange the contents of nodes i and j; parents stay with the node numbers."""
        for x, y in ((i, j), (j, i)):
            if self.leaf[x]:
                self.node_of[self.payload[x]] = y
            else:
                self.parent[self.payload[x]] = y
                self.parent[self.payload[x] + 1] = y
        self.leaf[i], self.leaf[j] = self.leaf[j], self.leaf[i]
        self.payload[i], self.payload[j] = self.payload[j], self.payload[i]
        self.weight[i], self.weight[j] = self.weight[j], self.weight[i]

    def decode(self, reader, escape_bits):
        """Section 4.6: one symbol, 0 to N (N is the large-value leaf)."""
        i = 0
        while not self.leaf[i]:
            i = self.payload[i] + reader.bit()
        s = self.payload[i]
        if s == self.n + 1:  # the escape leaf: the symbol follows literally
            s = reader.bits(escape_bits)  # below N here: at most 10 bits against 2048, 8 against 256 (section 12)
            self.add(s)
        self.update(s)
        return s


def read_run(model, reader):
    """Section 4.7: a run length. Symbols 255 and 256 each start an extension, hi * 256 + lo (wrapping).

    The recursion of the specification (hi = run(), then lo) is written as a loop: count the extension symbols, take
    the first symbol below 255 as the innermost value, then read one low byte per extension, innermost first.
    """
    extensions = 0
    s = model.decode(reader, 8)
    while s >= 255:
        extensions += 1
        s = model.decode(reader, 8)
    value = s
    for _ in range(extensions):
        lo = model.decode(reader, 8)
        value = wrap32(value * 256 + lo)
    return value


# ---------------------------------------------------------------------------------------------------------------------
# Section 5: geometry.

def size(n, level):
    """Rows (n = H) or columns (n = W) of level `level`'s region."""
    return (n - 2) // (1 << level) + 2


def low(n):
    """Low-pass samples of a dimension of length n: the size of the next coarser level."""
    return n // 2 + 1


def high(n):
    return n - low(n)


def band_rect(band, level):
    """(r0, r1, c0, c1) of a detail band of `level` in the coefficient layout."""
    n, m = size(H, level), size(W, level)
    if band == B0:
        return 0, low(n), low(m), m
    if band == B1:
        return low(n), n, 0, low(m)
    return low(n), n, low(m), m


# ---------------------------------------------------------------------------------------------------------------------
# Section 8: the significance map.

NOT_SIGNIFICANT, SIGNIFICANT, HAS_DESCENDANT = 1, 2, 4

# Section 8.1: the quadrant order and orientations of the recursive scan. T/Bt = top/bottom rows, Lf/Rt = columns.
QUADRANTS = {
    0: (('T', 'Rt', 3), ('Bt', 'Rt', 0), ('Bt', 'Lf', 0), ('T', 'Lf', 2)),
    1: (('Bt', 'Lf', 2), ('T', 'Lf', 1), ('T', 'Rt', 1), ('Bt', 'Rt', 3)),
    2: (('Bt', 'Lf', 1), ('Bt', 'Rt', 2), ('T', 'Rt', 2), ('T', 'Lf', 0)),
    3: (('T', 'Rt', 0), ('T', 'Lf', 3), ('Bt', 'Lf', 3), ('Bt', 'Rt', 1)),
}


def serpentine(r0, r1, c0, c1, orientation):
    """Section 8.1's table: the cells of a small rectangle in orientation o's back-and-forth order."""
    if orientation in (0, 1):  # outer loop over columns, inner sweep over rows
        columns = range(c1 - 1, c0 - 1, -1) if orientation == 0 else range(c0, c1)
        forward = orientation == 0  # the first sweep: rows ascending for 0, descending for 1
        for c in columns:
            rows = range(r0, r1) if forward else range(r1 - 1, r0 - 1, -1)
            for r in rows:
                yield r, c
            forward = not forward
    else:  # outer loop over rows, inner sweep over columns
        rows = range(r1 - 1, r0 - 1, -1) if orientation == 2 else range(r0, r1)
        forward = orientation == 2  # columns ascending for 2, descending for 3
        for r in rows:
            columns = range(c0, c1) if forward else range(c1 - 1, c0 - 1, -1)
            for c in columns:
                yield r, c
            forward = not forward


def parent_index(x, n):
    """Section 8.2: the coarser position of a high-pass row or column x of a dimension of length n."""
    if n % 4 in (0, 1):
        return (x + 1) // 2
    if n % 4 == 2:
        return (x + 2) // 2
    return (x + 1) // 2 if x == n - 1 else (x + 2) // 2


class MapDecoder:
    """Section 8: marks M (a flat 250 x 200 list) from run lengths along the recursive quadrant scan."""

    def __init__(self, reader, significance):
        self.reader = reader
        self.m = significance
        self.model = AdaptiveHuffman(256, 64)
        self.remaining = 0  # R: 1-cells still to pass before the next significant one
        self.level = 0
        self.band = B0

    def run(self, skip):
        self.remaining = read_run(self.model, self.reader)
        for level in range(skip, LEVELS):
            self.level = level
            if (level - skip) % 2 == 0:
                order = ((B0, 3), (B2, 0), (B1, 0))
            else:
                order = ((B1, 1), (B2, 2), (B0, 2))
            for band, orientation in order:
                self.band = band
                r0, r1, c0, c1 = band_rect(band, level)
                self.visit(r0, r1, c0, c1, orientation)
        if self.remaining != 0:
            raise WiError('E5')

    def count_unmarked(self, r0, r1, c0, c1):
        m = self.m
        return sum(m[r * W + c0:r * W + c1].count(NOT_SIGNIFICANT) for r in range(r0, r1))

    def visit(self, r0, r1, c0, c1, orientation):
        count = self.count_unmarked(r0, r1, c0, c1)
        if self.remaining >= count:  # signed comparison: a negative R never skips
            self.remaining -= count
            return
        if r1 - r0 > 2 and c1 - c0 > 2:
            rm, cm = r0 + (r1 - r0) // 2, c0 + (c1 - c0) // 2
            rows = {'T': (r0, rm), 'Bt': (rm, r1)}
            columns = {'Lf': (c0, cm), 'Rt': (cm, c1)}
            for rows_name, columns_name, o in QUADRANTS[orientation]:
                self.visit(*rows[rows_name], *columns[columns_name], o)
            return
        for r, c in serpentine(r0, r1, c0, c1, orientation):
            self.cell(r, c)

    def cell(self, r, c):
        if self.m[r * W + c] != NOT_SIGNIFICANT:
            return
        if self.remaining != 0:
            self.remaining = wrap32(self.remaining - 1)
            return
        self.m[r * W + c] = SIGNIFICANT
        self.mark_ancestors(r, c)
        self.remaining = read_run(self.model, self.reader)

    def mark_ancestors(self, r, c):
        """Section 8.2: mark the same band's ancestors, one level coarser each time, up to level 4."""
        rows_high = self.band in (B1, B2)
        columns_high = self.band in (B0, B2)
        for p in range(self.level, LEVELS - 1):
            n, m = size(H, p), size(W, p)
            r = parent_index(r, n) if rows_high else r // 2
            c = parent_index(c, m) if columns_high else c // 2
            if self.m[r * W + c] == HAS_DESCENDANT:
                return
            self.m[r * W + c] = HAS_DESCENDANT


# ---------------------------------------------------------------------------------------------------------------------
# Section 9: reconstruction.

def upsample(x, level):
    """Section 9.1: replace the level-`level` region by the bilinear upsampling of the coarser image."""
    n, m = size(H, level), size(W, level)
    a, b = low(n), low(m)

    def sources(p, length, last_low):
        if length % 2 == 0 and p == length - 1:
            return (last_low,)
        if p % 2 == 0:
            return (p // 2,)
        return ((p - 1) // 2, (p + 1) // 2)

    row_sources = [sources(p, n, a - 1) for p in range(n)]
    column_sources = [sources(q, m, b - 1) for q in range(m)]
    exception_row_b = n - 1 if n % 2 == 1 else n - 2

    y = [0] * (n * m)
    for p in range(n):
        rs = row_sources[p]
        for q in range(m):
            cs = column_sources[q]
            s = wrap32(sum(x[i * W + j] + 1 for i in rs for j in cs))
            k = {1: 1, 2: 2, 4: 3}[len(rs) * len(cs)]
            truncating = (n % 2 == 0 and p == n - 1) or (m % 2 == 0 and q == m - 1 and p == exception_row_b)
            y[p * m + q] = truncating_div_pow2(s, k) if truncating else s >> k
    for p in range(n):
        x[p * W:p * W + m] = y[p * m:(p + 1) * m]


def low_kernel(i, n):
    """Section 9.3: K_L(i; n) as ((position, weight), ...). The first applicable row of the table wins."""
    if i == 0:
        return ((0, 2), (1, 1))
    if i == (n - 1) // 2:
        return ((2 * i - 1, 1), (2 * i, 2))
    if i == low(n) - 1:
        return ((n - 1, 2),)
    return ((2 * i - 1, 1), (2 * i, 2), (2 * i + 1, 1))


def high_kernel(t, n):
    """Section 9.3: K_H(t; n), centred on position 2t + 1."""
    p = 2 * t + 1
    if t == 0:
        return ((0, -2), (1, 6), (2, -2), (3, -1))
    if t == high(n) - 1:
        return ((p - 2, -1), (p - 1, -2), (p, 6), (p + 1, -2))
    return ((p - 2, -1), (p - 1, -2), (p, 6), (p + 1, -2), (p + 2, -1))


def add_details(reader, x, significance, level, q, coefficients):
    """Sections 9.2 and 9.3: read level `level`'s detail values and add each one's basis function to x."""
    escape_bits = reader.bits(5)
    if escape_bits > 10:
        raise WiError('E4')
    model = AdaptiveHuffman(2048, 3)
    n, m = size(H, level), size(W, level)
    for band in (B0, B1, B2):
        r0, r1, c0, c1 = band_rect(band, level)
        for r in range(r0, r1):
            for c in range(c0, c1):
                mark = significance[r * W + c]
                if mark == NOT_SIGNIFICANT:
                    continue
                negative = reader.bit()
                k = model.decode(reader, escape_bits)
                if mark == SIGNIFICANT:
                    k += 1
                v = k * q + q // 2
                if negative:
                    v = -v
                coefficients.append(v)
                if band == B0:
                    rows, columns, scale = low_kernel(r, n), high_kernel(c - low(m), m), 2
                elif band == B1:
                    rows, columns, scale = high_kernel(r - low(n), n), low_kernel(c, m), 2
                else:
                    rows, columns, scale = high_kernel(r - low(n), n), high_kernel(c - low(m), m), 1
                for y, alpha in rows:
                    base = y * W
                    for xx, beta in columns:
                        x[base + xx] += scale * v * alpha * beta
    # Section 11: these sums cannot leave the signed 32-bit range, but reduce them anyway to keep the rule visible.
    for r in range(n):
        for c in range(m):
            x[r * W + c] = wrap32(x[r * W + c])
    return escape_bits


def sharpen(x):
    """Section 9.4: the 5-point sharpening of the level-1 image, 126 x 101, with the borders kept."""
    n1, m1 = size(H, 1), size(W, 1)
    s = list(x)
    for r in range(1, n1 - 1):
        for c in range(1, m1 - 1):
            i = r * W + c
            total = 12 * x[i] - x[i - 1] - x[i + 1] - x[i - W] - x[i + W]
            s[i] = wrap32(total) >> 3
    x[:] = s


# ---------------------------------------------------------------------------------------------------------------------
# Sections 2, 6, 7 and 10, and the walk-through of section 14.

def check_header(wi):
    """Section 2: exactly the licence profile, or E1. Returns Q."""
    if not MIN_SIZE <= len(wi) <= MAX_SIZE:
        raise WiError('E1')
    if wi[:7] != HEADER_PREFIX or wi[7] not in (0x42, 0x43) or wi[8] & 1 or wi[9:12] != HEADER_SUFFIX:
        raise WiError('E1')
    return ((wi[7] & 1) << 7) | (wi[8] >> 1)


def region_bytes(x, rows, columns):
    """An int32 region of x, little-endian and row-major (the checkpoint serialisation of section 14)."""
    out = bytearray()
    for r in range(rows):
        for c in range(columns):
            out += (x[r * W + c] & 0xFFFFFFFF).to_bytes(4, 'little')
    return bytes(out)


def _decode(wi, trace):
    """The whole decoder. `trace`, when a dict, receives every checkpoint of section 14 (tests only)."""
    q = check_header(wi)
    reader = BitReader(wi, HEADER_SIZE)
    x = [0] * PIXELS

    # Section 6: the LL band, 9 x 8, step 64 Q with mid-point reconstruction.
    ll_bits = reader.bits(5)
    offset = reader.bits(ll_bits) & 0xFF
    for r in range(size(H, LEVELS)):
        for c in range(size(W, LEVELS)):
            v = reader.bits(ll_bits) - offset
            x[r * W + c] = wrap32((2 * v + 1) * 32 * q if v >= 0 else (2 * v - 1) * 32 * q)

    # Section 7: the skip count, unary.
    skip = 0
    while reader.bit():
        skip += 1
    if skip > LEVELS:
        raise WiError('E3')

    # Section 8: the significance map.
    significance = [NOT_SIGNIFICANT] * PIXELS
    MapDecoder(reader, significance).run(skip)

    if trace is not None:
        trace.update(q=q, ll_bits=ll_bits, skip=skip, C1_ll=region_bytes(x, 9, 8), C2_map=bytes(significance),
                     levels=[])

    # Section 9: from level 4 down to level 0.
    for level in range(LEVELS - 1, -1, -1):
        n, m = size(H, level), size(W, level)
        if level == 0:
            sharpen(x)
            if trace is not None:
                trace['C6_sharpened'] = region_bytes(x, size(H, 1), size(W, 1))
        upsample(x, level)
        record = {'level': level, 'C3_upsampled': region_bytes(x, n, m) if trace is not None else None}
        coefficients = []
        record['escape_bits'] = add_details(reader, x, significance, level, q, coefficients) if level >= skip else None
        if trace is not None:
            record['C4_coefficients'] = b''.join((v & 0xFFFFFFFF).to_bytes(4, 'little') for v in coefficients)
            record['C4_coefficient_count'] = len(coefficients)
            record['C5_reconstructed'] = region_bytes(x, n, m)
            trace['levels'].append(record)

    # Section 10: X is 32 times the sample value; round, clamp, and keep the codec's (upside-down) order.
    raster = bytearray(PIXELS)
    for i in range(PIXELS):
        t = wrap32(x[i] + 16)
        raster[i] = 255 if t >= 8192 else 0 if t < 0 else t // 32
    if trace is not None:
        trace['bytes_consumed'] = reader.fetched
        trace['C7_raster'] = bytes(raster)
        trace['C8_portrait'] = bytes(raster[::-1])
    return bytes(raster)
