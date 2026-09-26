//! Decoder for the portrait in a South African driving-licence barcode: the Summus wavelet image ("WI") of the
//! card payload's photo section, as specified in `spec/wi-codec.md` (the licence-portrait profile only).
//!
//! ```no_run
//! # let photo_section: &[u8] = &[];
//! let portrait = sadl_portrait::decode(photo_section)?; // 200 x 250 greyscale, upright, row by row
//! # Ok::<(), sadl_portrait::WiError>(())
//! ```
//!
//! The decoder is safe Rust with no global state, so it may be called from any number of threads at once. It never
//! panics on any input: every failure is a [`WiError`]. A C API with the same behaviour is exported from the
//! `cdylib` and `staticlib` builds (see `include/sadl_portrait.h` and the [`ffi`] module).
//!
//! The output is a person's portrait: show it, but do not log, store or upload it without a reason.

#![deny(unsafe_code)]
#![warn(missing_docs)]

mod bits;
mod codec;
pub mod ffi;
mod huffman;

#[cfg(test)]
mod checkpoint_tests;

use core::fmt;

/// Width of the portrait in samples.
pub const WIDTH: usize = 200;
/// Height of the portrait in samples.
pub const HEIGHT: usize = 250;
/// Samples in a portrait: `WIDTH * HEIGHT`.
pub const PIXELS: usize = WIDTH * HEIGHT;
/// The smallest WI input accepted (the 12-byte header and at least one byte of coded data).
pub const MIN_WI_BYTES: usize = 13;
/// The largest WI input accepted, the limit of the C API. Licence photos are 550 to 603 bytes.
pub const MAX_WI_BYTES: usize = 674;

/// Why a WI stream could not be decoded: the errors E1 to E5 of `spec/wi-codec.md` section 12, plus a bad argument
/// at the C API. None of them carries any decoded data.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum WiError {
    /// E1: the header is not exactly the licence profile, or the size is outside 13 to 674 bytes.
    Unsupported,
    /// E2: a bit was needed after the last byte.
    EndOfData,
    /// E3: the skip count is above 5.
    SkipTooLarge,
    /// E4: an escape width above 10.
    EscapeTooWide,
    /// E5: the run count was not exactly used up by the significance map.
    RunNotExhausted,
    /// A null pointer passed to the C API. Never returned by the Rust API.
    BadArgument,
}

impl WiError {
    /// The error's code in `spec/wi-codec.md` section 12 ("E1" to "E5"), or "bad_argument".
    pub fn code(self) -> &'static str {
        match self {
            WiError::Unsupported => "E1",
            WiError::EndOfData => "E2",
            WiError::SkipTooLarge => "E3",
            WiError::EscapeTooWide => "E4",
            WiError::RunNotExhausted => "E5",
            WiError::BadArgument => "bad_argument",
        }
    }
}

impl fmt::Display for WiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let text = match self {
            WiError::Unsupported => "not the licence WI profile",
            WiError::EndOfData => "end of data",
            WiError::SkipTooLarge => "skip count above 5",
            WiError::EscapeTooWide => "escape width above 10",
            WiError::RunNotExhausted => "run count not exhausted after the map",
            WiError::BadArgument => "bad argument",
        };
        write!(f, "{}: {}", self.code(), text)
    }
}

impl std::error::Error for WiError {}

/// Decodes a WI photo section to the upright portrait (checkpoint C8): [`PIXELS`] bytes, 200 columns by 250 rows,
/// row by row from the top-left.
pub fn decode(wi: &[u8]) -> Result<Vec<u8>, WiError> {
    let mut raster = decode_native_order(wi)?;
    raster.reverse(); // the codec raster is the portrait turned 180 degrees
    Ok(raster)
}

/// Decodes a WI photo section to the codec's raster (checkpoint C7), which is upside down: its first row is the
/// portrait's bottom row, read from the right.
pub fn decode_native_order(wi: &[u8]) -> Result<Vec<u8>, WiError> {
    codec::decode_with(wi, &mut codec::Silent)
}
