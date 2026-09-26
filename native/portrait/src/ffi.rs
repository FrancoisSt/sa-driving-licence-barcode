//! The C API of `include/sadl_portrait.h`. This is the only module with `unsafe` code: it turns the caller's
//! pointers into slices and nothing else. It keeps no state, so every function may be called from several threads
//! at once.

#![allow(unsafe_code)]

use core::ffi::{c_char, c_int};
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::{WiError, MAX_WI_BYTES, PIXELS};

/// `SADL_PORTRAIT_OK`: decoded; the output buffer holds the portrait.
pub const SADL_PORTRAIT_OK: c_int = 0;
/// `SADL_PORTRAIT_UNSUPPORTED`: not the licence WI profile (error E1), including a size outside 13 to 674.
pub const SADL_PORTRAIT_UNSUPPORTED: c_int = 1;
/// `SADL_PORTRAIT_DECODE_FAILED`: a corrupt stream (errors E2 to E5).
pub const SADL_PORTRAIT_DECODE_FAILED: c_int = 2;
/// `SADL_PORTRAIT_UNEXPECTED_RASTER`: reserved; never returned.
pub const SADL_PORTRAIT_UNEXPECTED_RASTER: c_int = 3;
/// `SADL_PORTRAIT_BAD_ARGUMENT`: a null pointer.
pub const SADL_PORTRAIT_BAD_ARGUMENT: c_int = 4;

fn status_of(error: WiError) -> c_int {
    match error {
        WiError::Unsupported => SADL_PORTRAIT_UNSUPPORTED,
        WiError::EndOfData | WiError::SkipTooLarge | WiError::EscapeTooWide | WiError::RunNotExhausted => {
            SADL_PORTRAIT_DECODE_FAILED
        }
        WiError::BadArgument => SADL_PORTRAIT_BAD_ARGUMENT,
    }
}

/// Checks the arguments, runs `decode` on the input, and copies its result to `out` only on success.
///
/// # Safety
/// `wi` must point to `size` readable bytes and `out` to [`PIXELS`] writable bytes, unless null.
unsafe fn run(wi: *const u8, size: usize, out: *mut u8, decode: fn(&[u8]) -> Result<Vec<u8>, WiError>) -> c_int {
    if wi.is_null() || out.is_null() {
        return SADL_PORTRAIT_BAD_ARGUMENT;
    }
    if size > MAX_WI_BYTES {
        return SADL_PORTRAIT_UNSUPPORTED; // checked before the input is touched
    }
    // SAFETY: the caller guarantees `size` readable bytes at `wi`; `size` is at most 674.
    let input = unsafe { core::slice::from_raw_parts(wi, size) };
    // The decoder does not panic; this guard only keeps a Rust panic from unwinding into C if that ever changed.
    match catch_unwind(AssertUnwindSafe(|| decode(input))) {
        Ok(Ok(pixels)) if pixels.len() == PIXELS => {
            // SAFETY: the caller guarantees PIXELS writable bytes at `out`, which cannot overlap our own vector.
            let output = unsafe { core::slice::from_raw_parts_mut(out, PIXELS) };
            output.copy_from_slice(&pixels);
            SADL_PORTRAIT_OK
        }
        Ok(Ok(_)) => SADL_PORTRAIT_DECODE_FAILED,
        Ok(Err(error)) => status_of(error),
        Err(_) => SADL_PORTRAIT_DECODE_FAILED,
    }
}

/// Decodes a WI photo section to the upright portrait (checkpoint C8): 50,000 bytes, 200 x 250, row by row from
/// the top-left. `out` is written only when the result is `SADL_PORTRAIT_OK`.
///
/// # Safety
/// `wi` must be null or point to `size` readable bytes; `out` must be null or point to 50,000 writable bytes.
#[no_mangle]
pub unsafe extern "C" fn sadl_portrait_decode(wi: *const u8, size: usize, out: *mut u8) -> c_int {
    // SAFETY: forwarded from this function's own contract.
    unsafe { run(wi, size, out, crate::decode) }
}

/// Decodes a WI photo section to the codec's raster order (checkpoint C7), which is the portrait turned 180°.
///
/// # Safety
/// As [`sadl_portrait_decode`].
#[no_mangle]
pub unsafe extern "C" fn sadl_portrait_decode_native_order(wi: *const u8, size: usize, out: *mut u8) -> c_int {
    // SAFETY: forwarded from this function's own contract.
    unsafe { run(wi, size, out, crate::decode_native_order) }
}

/// The name of a status code, as a static NUL-terminated string (for example "SADL_PORTRAIT_OK"). Never null.
#[no_mangle]
pub extern "C" fn sadl_portrait_status_name(status: c_int) -> *const c_char {
    let name: &'static core::ffi::CStr = match status {
        SADL_PORTRAIT_OK => c"SADL_PORTRAIT_OK",
        SADL_PORTRAIT_UNSUPPORTED => c"SADL_PORTRAIT_UNSUPPORTED",
        SADL_PORTRAIT_DECODE_FAILED => c"SADL_PORTRAIT_DECODE_FAILED",
        SADL_PORTRAIT_UNEXPECTED_RASTER => c"SADL_PORTRAIT_UNEXPECTED_RASTER",
        SADL_PORTRAIT_BAD_ARGUMENT => c"SADL_PORTRAIT_BAD_ARGUMENT",
        _ => c"SADL_PORTRAIT_UNKNOWN_STATUS",
    };
    name.as_ptr()
}
