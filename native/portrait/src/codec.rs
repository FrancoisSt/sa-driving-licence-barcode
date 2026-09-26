//! The decoder of spec/wi-codec.md: header gate (2), LL band (6), skip count (7), significance map (8),
//! reconstruction (9) and output (10). Section numbers in comments refer to that page.
//!
//! All arithmetic that section 11 says wraps uses explicit `wrapping_*` operations on `i32`. Array positions come
//! from the fixed 250 x 200 geometry, never from the input, and are accessed through checked helpers.

use crate::bits::BitReader;
use crate::huffman::Model;
use crate::{WiError, HEIGHT, MAX_WI_BYTES, MIN_WI_BYTES, PIXELS, WIDTH};

const H: usize = HEIGHT;
const W: usize = WIDTH;
/// Wavelet levels: 0 is the finest, 5 the LL band.
const LEVELS: usize = 5;
const HEADER_BYTES: usize = 12;

/// Receives the checkpoints of section 14 while decoding. Production code uses [`Silent`], whose empty methods
/// compile away; the tests use an observer that hashes each checkpoint.
pub(crate) trait Observer {
    fn header(&mut self, _q: u32) {}
    fn ll_band(&mut self, _ll_bits: u32, _x: &[i32]) {}
    fn skip(&mut self, _skip: usize) {}
    fn map(&mut self, _m: &[u8]) {}
    fn sharpened(&mut self, _x: &[i32]) {}
    fn upsampled(&mut self, _level: usize, _x: &[i32]) {}
    fn escape_bits(&mut self, _level: usize, _bits: u32) {}
    fn coefficient(&mut self, _level: usize, _value: i32) {}
    fn reconstructed(&mut self, _level: usize, _x: &[i32]) {}
    fn finished(&mut self, _bytes_consumed: usize, _raster: &[u8]) {}
}

/// The observer that observes nothing.
pub(crate) struct Silent;

impl Observer for Silent {}

// ---------------------------------------------------------------------------------------------------------------
// Section 5: geometry.

/// Rows (of `H`) or columns (of `W`) of the region of `level`.
pub(crate) const fn size(n: usize, level: usize) -> usize {
    (n - 2) / (1 << level) + 2
}

/// Low-pass samples of a dimension of length `n`.
const fn low(n: usize) -> usize {
    n / 2 + 1
}

const fn high(n: usize) -> usize {
    n - low(n)
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Band {
    /// Rows low-pass, columns high-pass.
    B0,
    /// Rows high-pass, columns low-pass.
    B1,
    /// Both high-pass.
    B2,
}

/// A rectangle of rows `[r0, r1)` and columns `[c0, c1)`.
#[derive(Clone, Copy)]
struct Rect {
    r0: usize,
    r1: usize,
    c0: usize,
    c1: usize,
}

fn band_rect(band: Band, level: usize) -> Rect {
    let (n, m) = (size(H, level), size(W, level));
    match band {
        Band::B0 => Rect { r0: 0, r1: low(n), c0: low(m), c1: m },
        Band::B1 => Rect { r0: low(n), r1: n, c0: 0, c1: low(m) },
        Band::B2 => Rect { r0: low(n), r1: n, c0: low(m), c1: m },
    }
}

// ---------------------------------------------------------------------------------------------------------------
// The whole decoder.

pub(crate) fn decode_with<O: Observer>(wi: &[u8], observer: &mut O) -> Result<Vec<u8>, WiError> {
    let q = check_header(wi)?;
    observer.header(q as u32);
    let mut reader = BitReader::new(wi, HEADER_BYTES);
    let mut x = vec![0i32; PIXELS];

    // Section 6: the 9 x 8 LL band, uniform step 64 Q, mid-point reconstruction.
    let ll_bits = reader.bits(5)?;
    let offset = (reader.bits(ll_bits)? & 0xFF) as i32;
    let step = 32 * q;
    for r in 0..size(H, LEVELS) {
        for c in 0..size(W, LEVELS) {
            let v = reader.bits(ll_bits)? as i32 - offset; // at most 31 bits, so it fits
            let odd = if v >= 0 { v.wrapping_mul(2).wrapping_add(1) } else { v.wrapping_mul(2).wrapping_sub(1) };
            set(&mut x, r, c, odd.wrapping_mul(step));
        }
    }
    observer.ll_band(ll_bits, &x);

    // Section 7: the skip count, in unary; checked after its terminating 0 bit.
    let mut skip = 0usize;
    while reader.bit()? == 1 {
        skip += 1;
    }
    if skip > LEVELS {
        return Err(WiError::SkipTooLarge);
    }
    observer.skip(skip);

    // Section 8: the significance map.
    let mut map = vec![NOT_SIGNIFICANT; PIXELS];
    MapDecoder { reader: &mut reader, map: &mut map, model: Model::for_map(), remaining: 0, level: 0, band: Band::B0 }
        .run(skip)?;
    observer.map(&map);

    // Section 9: level 4 down to level 0.
    let mut scratch = vec![0i32; PIXELS];
    for level in (0..LEVELS).rev() {
        if level == 0 {
            sharpen(&mut x, &mut scratch);
            observer.sharpened(&x);
        }
        upsample(&mut x, &mut scratch, level);
        observer.upsampled(level, &x);
        if level >= skip {
            add_details(&mut reader, &mut x, &map, level, q, observer)?;
        }
        observer.reconstructed(level, &x);
    }

    // Section 10: X is 32 times the sample value.
    let raster: Vec<u8> = x
        .iter()
        .map(|&value| {
            let t = value.wrapping_add(16);
            if t >= 8192 {
                255
            } else if t < 0 {
                0
            } else {
                (t / 32) as u8
            }
        })
        .collect();
    observer.finished(reader.bytes_consumed(), &raster);
    Ok(raster)
}

/// Section 2: exactly the licence profile, or E1. Returns the quantiser step Q.
fn check_header(wi: &[u8]) -> Result<i32, WiError> {
    if wi.len() < MIN_WI_BYTES || wi.len() > MAX_WI_BYTES {
        return Err(WiError::Unsupported);
    }
    let fixed = wi.get(..7) == Some(&[0x57, 0x49, 0x04, 0x00, 0xFA, 0x00, 0xC8][..])
        && wi.get(9..12) == Some(&[0x28, 0x40, 0x00][..]);
    let (b7, b8) = (wi.get(7).copied().unwrap_or(0), wi.get(8).copied().unwrap_or(1));
    if !fixed || (b7 != 0x42 && b7 != 0x43) || b8 & 1 != 0 {
        return Err(WiError::Unsupported);
    }
    Ok((i32::from(b7 & 1) << 7) | i32::from(b8 >> 1))
}

#[inline]
fn get(x: &[i32], r: usize, c: usize) -> i32 {
    x.get(r * W + c).copied().unwrap_or(0)
}

#[inline]
fn set(x: &mut [i32], r: usize, c: usize, value: i32) {
    if let Some(cell) = x.get_mut(r * W + c) {
        *cell = value;
    }
}

#[inline]
fn add(x: &mut [i32], r: usize, c: usize, value: i32) {
    if let Some(cell) = x.get_mut(r * W + c) {
        *cell = cell.wrapping_add(value);
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Section 8: the significance map.

const NOT_SIGNIFICANT: u8 = 1;
const SIGNIFICANT: u8 = 2;
const HAS_DESCENDANT: u8 = 4;

struct MapDecoder<'r, 'a> {
    reader: &'r mut BitReader<'a>,
    map: &'r mut [u8],
    model: Model,
    /// R: cells still marked 1 to pass before the next significant one. Signed, and it may wrap negative.
    remaining: i32,
    level: usize,
    band: Band,
}

impl MapDecoder<'_, '_> {
    fn run(mut self, skip: usize) -> Result<(), WiError> {
        self.remaining = self.model.run(self.reader)?;
        for level in skip..LEVELS {
            self.level = level;
            let order = if (level - skip) % 2 == 0 {
                [(Band::B0, 3), (Band::B2, 0), (Band::B1, 0)]
            } else {
                [(Band::B1, 1), (Band::B2, 2), (Band::B0, 2)]
            };
            for (band, orientation) in order {
                self.band = band;
                self.visit(band_rect(band, level), orientation)?;
            }
        }
        if self.remaining != 0 {
            return Err(WiError::RunNotExhausted);
        }
        Ok(())
    }

    fn unmarked(&self, rect: Rect) -> i32 {
        let mut count = 0i32;
        for r in rect.r0..rect.r1 {
            if let Some(row) = self.map.get(r * W + rect.c0..r * W + rect.c1) {
                count += row.iter().filter(|&&m| m == NOT_SIGNIFICANT).count() as i32;
            }
        }
        count
    }

    /// Section 8.1: skip a rectangle whose unmarked cells the run covers, else split it or scan it.
    fn visit(&mut self, rect: Rect, orientation: u8) -> Result<(), WiError> {
        let count = self.unmarked(rect);
        if self.remaining >= count {
            self.remaining = self.remaining.wrapping_sub(count);
            return Ok(());
        }
        let Rect { r0, r1, c0, c1 } = rect;
        if r1 - r0 > 2 && c1 - c0 > 2 {
            let (rm, cm) = (r0 + (r1 - r0) / 2, c0 + (c1 - c0) / 2);
            let (top, bottom) = ((r0, rm), (rm, r1));
            let (left, right) = ((c0, cm), (cm, c1));
            let quadrants = match orientation {
                0 => [(top, right, 3), (bottom, right, 0), (bottom, left, 0), (top, left, 2)],
                1 => [(bottom, left, 2), (top, left, 1), (top, right, 1), (bottom, right, 3)],
                2 => [(bottom, left, 1), (bottom, right, 2), (top, right, 2), (top, left, 0)],
                _ => [(top, right, 0), (top, left, 3), (bottom, left, 3), (bottom, right, 1)],
            };
            for ((ra, rb), (ca, cb), o) in quadrants {
                self.visit(Rect { r0: ra, r1: rb, c0: ca, c1: cb }, o)?;
            }
            return Ok(());
        }
        // The serpentine scans: the first line runs in the direction shown, and each following line reverses.
        match orientation {
            0 | 1 => {
                let mut down = orientation == 0; // orientation 0: first column's rows ascending
                let columns: Vec<usize> = if orientation == 0 { (c0..c1).rev().collect() } else { (c0..c1).collect() };
                for c in columns {
                    if down {
                        for r in r0..r1 {
                            self.cell(r, c)?;
                        }
                    } else {
                        for r in (r0..r1).rev() {
                            self.cell(r, c)?;
                        }
                    }
                    down = !down;
                }
            }
            _ => {
                let mut rightward = orientation == 2; // orientation 2: first row's columns ascending
                let rows: Vec<usize> = if orientation == 2 { (r0..r1).rev().collect() } else { (r0..r1).collect() };
                for r in rows {
                    if rightward {
                        for c in c0..c1 {
                            self.cell(r, c)?;
                        }
                    } else {
                        for c in (c0..c1).rev() {
                            self.cell(r, c)?;
                        }
                    }
                    rightward = !rightward;
                }
            }
        }
        Ok(())
    }

    fn cell(&mut self, r: usize, c: usize) -> Result<(), WiError> {
        let Some(&mark) = self.map.get(r * W + c) else { return Ok(()) };
        if mark != NOT_SIGNIFICANT {
            return Ok(());
        }
        if self.remaining != 0 {
            self.remaining = self.remaining.wrapping_sub(1); // "≠ 0", not "> 0": a negative R keeps counting down
            return Ok(());
        }
        self.mark(r, c, SIGNIFICANT);
        self.mark_ancestors(r, c);
        self.remaining = self.model.run(self.reader)?;
        Ok(())
    }

    fn mark(&mut self, r: usize, c: usize, value: u8) {
        if let Some(cell) = self.map.get_mut(r * W + c) {
            *cell = value;
        }
    }

    /// Section 8.2: the same band's ancestors, one level coarser each step, up to level 4.
    fn mark_ancestors(&mut self, mut r: usize, mut c: usize) {
        let rows_high = matches!(self.band, Band::B1 | Band::B2);
        let columns_high = matches!(self.band, Band::B0 | Band::B2);
        for p in self.level..LEVELS - 1 {
            r = if rows_high { parent(r, size(H, p)) } else { r / 2 };
            c = if columns_high { parent(c, size(W, p)) } else { c / 2 };
            if self.map.get(r * W + c) == Some(&HAS_DESCENDANT) {
                return;
            }
            self.mark(r, c, HAS_DESCENDANT);
        }
    }
}

/// Section 8.2: the coarser position of the high-pass position `x` of a dimension of length `n`.
#[allow(clippy::manual_div_ceil)] // written as the specification's floor((x + 1) / 2)
fn parent(x: usize, n: usize) -> usize {
    match n % 4 {
        0 | 1 => (x + 1) / 2,
        2 => (x + 2) / 2,
        _ if x == n - 1 => (x + 1) / 2,
        _ => (x + 2) / 2,
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Section 9: reconstruction.

/// The source positions of output position `p` in a dimension of length `n`: one or two coarser positions.
#[allow(clippy::manual_div_ceil)] // the specification's (p + 1) / 2
fn sources(p: usize, n: usize) -> (usize, usize, usize) {
    if n % 2 == 0 && p == n - 1 {
        (low(n) - 1, 0, 1)
    } else if p % 2 == 0 {
        (p / 2, 0, 1)
    } else {
        ((p - 1) / 2, (p + 1) / 2, 2)
    }
}

/// Section 9.1: bilinear upsampling of the level-(l+1) image to the whole level-l region, each source biased by 1.
fn upsample(x: &mut [i32], y: &mut [i32], level: usize) {
    let (n, m) = (size(H, level), size(W, level));
    let exception_b_row = if n % 2 == 1 { n - 1 } else { n - 2 };
    for p in 0..n {
        let (ra, rb, rows) = sources(p, n);
        for q in 0..m {
            let (ca, cb, columns) = sources(q, m);
            let mut sum = get(x, ra, ca).wrapping_add(1);
            if columns == 2 {
                sum = sum.wrapping_add(get(x, ra, cb).wrapping_add(1));
            }
            if rows == 2 {
                sum = sum.wrapping_add(get(x, rb, ca).wrapping_add(1));
                if columns == 2 {
                    sum = sum.wrapping_add(get(x, rb, cb).wrapping_add(1));
                }
            }
            let k = match rows * columns {
                1 => 1,
                2 => 2,
                _ => 3,
            };
            let truncating = (n % 2 == 0 && p == n - 1) || (m % 2 == 0 && q == m - 1 && p == exception_b_row);
            let value = if truncating { sum / (1 << k) } else { sum >> k };
            set(y, p, q, value);
        }
    }
    for p in 0..n {
        let range = p * W..p * W + m;
        if let (Some(dst), Some(src)) = (x.get_mut(range.clone()), y.get(range)) {
            dst.copy_from_slice(src);
        }
    }
}

/// One dimension of a basis function: up to five (position, weight) pairs.
struct Kernel {
    taps: [(usize, i32); 5],
    len: usize,
}

impl Kernel {
    fn of(taps: &[(usize, i32)]) -> Kernel {
        let mut k = Kernel { taps: [(0, 0); 5], len: taps.len().min(5) };
        k.taps[..k.len].copy_from_slice(&taps[..k.len]);
        k
    }

    fn taps(&self) -> &[(usize, i32)] {
        &self.taps[..self.len]
    }

    /// Section 9.3's K_L(i; n): the first applicable row of the table.
    fn low(i: usize, n: usize) -> Kernel {
        if i == 0 {
            Kernel::of(&[(0, 2), (1, 1)])
        } else if i == (n - 1) / 2 {
            Kernel::of(&[(2 * i - 1, 1), (2 * i, 2)])
        } else if i == low(n) - 1 {
            Kernel::of(&[(n - 1, 2)])
        } else {
            Kernel::of(&[(2 * i - 1, 1), (2 * i, 2), (2 * i + 1, 1)])
        }
    }

    /// Section 9.3's K_H(t; n), centred on 2t + 1.
    fn high(t: usize, n: usize) -> Kernel {
        let p = 2 * t + 1;
        if t == 0 {
            Kernel::of(&[(0, -2), (1, 6), (2, -2), (3, -1)])
        } else if t + 1 == high(n) {
            Kernel::of(&[(p - 2, -1), (p - 1, -2), (p, 6), (p + 1, -2)])
        } else {
            Kernel::of(&[(p - 2, -1), (p - 1, -2), (p, 6), (p + 1, -2), (p + 2, -1)])
        }
    }
}

/// Sections 9.2 and 9.3: the detail values of one level, each added to X as its basis function.
fn add_details<O: Observer>(
    reader: &mut BitReader<'_>,
    x: &mut [i32],
    map: &[u8],
    level: usize,
    q: i32,
    observer: &mut O,
) -> Result<(), WiError> {
    let escape_bits = reader.bits(5)?;
    if escape_bits > 10 {
        return Err(WiError::EscapeTooWide);
    }
    observer.escape_bits(level, escape_bits);
    let mut model = Model::for_coefficients();
    let (n, m) = (size(H, level), size(W, level));
    for band in [Band::B0, Band::B1, Band::B2] {
        let rect = band_rect(band, level);
        for r in rect.r0..rect.r1 {
            for c in rect.c0..rect.c1 {
                let mark = map.get(r * W + c).copied().unwrap_or(NOT_SIGNIFICANT);
                if mark == NOT_SIGNIFICANT {
                    continue;
                }
                let negative = reader.bit()? == 1;
                let mut k = model.decode(reader, escape_bits)? as i32; // 0 to 2048
                if mark == SIGNIFICANT {
                    k += 1;
                }
                let magnitude = k * q + q / 2; // at most 2049 × 255 + 127
                let v = if negative { -magnitude } else { magnitude };
                observer.coefficient(level, v);
                let (rows, columns, scale) = match band {
                    Band::B0 => (Kernel::low(r, n), Kernel::high(c - low(m), m), 2),
                    Band::B1 => (Kernel::high(r - low(n), n), Kernel::low(c, m), 2),
                    Band::B2 => (Kernel::high(r - low(n), n), Kernel::high(c - low(m), m), 1),
                };
                let sv = v.wrapping_mul(scale);
                for &(yy, alpha) in rows.taps() {
                    let row_value = sv.wrapping_mul(alpha);
                    for &(xx, beta) in columns.taps() {
                        add(x, yy, xx, row_value.wrapping_mul(beta));
                    }
                }
            }
        }
    }
    Ok(())
}

/// Section 9.4: the 5-point sharpening of the level-1 image (126 x 101), borders kept, all from the unsharpened X.
fn sharpen(x: &mut [i32], s: &mut [i32]) {
    let (n1, m1) = (size(H, 1), size(W, 1));
    for r in 1..n1 - 1 {
        for c in 1..m1 - 1 {
            let centre = get(x, r, c).wrapping_mul(12);
            let total = centre
                .wrapping_sub(get(x, r, c - 1))
                .wrapping_sub(get(x, r, c + 1))
                .wrapping_sub(get(x, r - 1, c))
                .wrapping_sub(get(x, r + 1, c));
            set(s, r, c, total >> 3);
        }
    }
    for r in 1..n1 - 1 {
        let range = r * W + 1..r * W + m1 - 1;
        if let (Some(dst), Some(src)) = (x.get_mut(range.clone()), s.get(range)) {
            dst.copy_from_slice(src);
        }
    }
}

#[cfg(test)]
pub(crate) mod geometry {
    pub(crate) use super::size;
}
