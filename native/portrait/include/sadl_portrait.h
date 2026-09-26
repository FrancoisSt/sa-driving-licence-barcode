/*
 * sadl_portrait: decodes the portrait in a South African driving-licence barcode.
 *
 * The input is section 3 of the decrypted card payload, a Summus wavelet image ("WI") including its "WI" header
 * (spec/portrait.md). The output is 200 x 250 8-bit greyscale. Only the licence-portrait profile of
 * spec/wi-codec.md is accepted; anything else returns SADL_PORTRAIT_UNSUPPORTED before any decoding.
 *
 * Implemented in safe Rust (native/portrait/src). The functions keep no state and may be called from several
 * threads at once. They never write `out` unless they return SADL_PORTRAIT_OK.
 *
 * The output is a person's portrait: show it, but do not log, store or upload it without a reason.
 */
#ifndef SADL_PORTRAIT_H
#define SADL_PORTRAIT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SADL_PORTRAIT_WIDTH 200
#define SADL_PORTRAIT_HEIGHT 250
#define SADL_PORTRAIT_PIXELS 50000
#define SADL_PORTRAIT_MAX_WI_BYTES 674

typedef enum {
    SADL_PORTRAIT_OK = 0,
    SADL_PORTRAIT_UNSUPPORTED = 1,       /* not the licence WI profile, or size outside 13 to 674 (E1) */
    SADL_PORTRAIT_DECODE_FAILED = 2,     /* a corrupt stream (E2 to E5) */
    SADL_PORTRAIT_UNEXPECTED_RASTER = 3, /* reserved, never returned */
    SADL_PORTRAIT_BAD_ARGUMENT = 4       /* a null pointer */
} sadl_portrait_status;

/* WI bytes to the upright portrait (checkpoint C8): SADL_PORTRAIT_PIXELS bytes, row by row from the top-left.
 * `out` must hold SADL_PORTRAIT_PIXELS bytes. Returns a sadl_portrait_status. */
int sadl_portrait_decode(const uint8_t* wi, size_t size, uint8_t* out);

/* The same in the codec's raster order (checkpoint C7): the portrait turned 180 degrees. */
int sadl_portrait_decode_native_order(const uint8_t* wi, size_t size, uint8_t* out);

/* The name of a status, for logs: a static string such as "SADL_PORTRAIT_OK". Never NULL; holds no data. */
const char* sadl_portrait_status_name(int status);

#ifdef __cplusplus
}
#endif

#endif /* SADL_PORTRAIT_H */
