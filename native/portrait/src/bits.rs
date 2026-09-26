//! The bit reader of spec/wi-codec.md section 3.

use crate::WiError;

/// Reads bits most significant first. A byte is fetched only when its first bit is needed.
pub(crate) struct BitReader<'a> {
    data: &'a [u8],
    /// Bytes fetched so far, counted from byte 0: the checkpoint scalar `bytes_consumed`.
    fetched: usize,
    current: u8,
    /// Unread bits left in `current`.
    left: u32,
}

impl<'a> BitReader<'a> {
    /// A reader positioned on the byte boundary `start` (the header before it counts as consumed).
    pub(crate) fn new(data: &'a [u8], start: usize) -> Self {
        BitReader { data, fetched: start, current: 0, left: 0 }
    }

    pub(crate) fn bytes_consumed(&self) -> usize {
        self.fetched
    }

    #[inline]
    pub(crate) fn bit(&mut self) -> Result<u32, WiError> {
        if self.left == 0 {
            self.current = *self.data.get(self.fetched).ok_or(WiError::EndOfData)?;
            self.fetched += 1;
            self.left = 8;
        }
        self.left -= 1;
        Ok(u32::from(self.current >> self.left) & 1)
    }

    /// An unsigned `n`-bit value, `n` at most 32; the first bit read is the most significant. Zero bits read nothing.
    pub(crate) fn bits(&mut self, n: u32) -> Result<u32, WiError> {
        let mut value: u32 = 0;
        for _ in 0..n.min(32) {
            value = (value << 1) | self.bit()?;
        }
        Ok(value)
    }
}
