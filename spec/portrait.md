# The portrait in the card barcode

Section 3 of the decrypted card payload is a tiny photo of the driver: a **Summus wavelet image** ("WI", a
proprietary format from the late 1990s) of 550 to 603 bytes, which decodes to a **200 × 250, 8-bit greyscale**
portrait. It is heavily compressed (about 84:1), so it is soft and shows ringing; it is still clearly the person.

The format has no published specification. Donald Jansen reconstructed a C++ decoder from the original Windows DLL
and [shared it in November 2024](https://github.com/the-mars-rover/rsa_identification/issues/2#issuecomment-2493025394).
From that reconstruction, this repository wrote a clean-room functional specification,
**[wi-codec.md](wi-codec.md)**, and four independent decoders from the specification alone
([../cleanroom/LOG.md](../cleanroom/LOG.md) records how). None of them contains any of the reconstruction's code.

| Language | Decoder | Notes |
|---|---|---|
| Rust, and C through its C API | [../native/portrait/](../native/portrait/) | safe Rust, no dependencies; `cdylib` and `staticlib` for FFI from any language |
| Kotlin / Java (JVM, Android) | `WiPortrait` in [../decoder/](../decoder/) | pure Kotlin; on a low-end phone under 100 ms for the first portrait, 10 to 20 ms after |
| Python | [../python/wi.py](../python/wi.py) | standard library; the one to read next to the specification; about 50 ms per portrait |
| C# (.NET Standard 2.1, .NET 8) | `Sadl.WiPortrait` in [../dotnet/](../dotnet/) | managed code only |

To write your own, follow [wi-codec.md](wi-codec.md) and check each stage against its checkpoint hashes in
[test-vectors/wi-checkpoints.json](test-vectors/wi-checkpoints.json) and the synthetic streams in
[test-vectors/wi-synthetic.json](test-vectors/wi-synthetic.json). It is about 500 to 850 lines in each of the four languages.

## Extracting the section

From the 684-byte payload of [card-barcode.md](card-barcode.md):

```
s1 = payload[5]; s2 = payload[7]
s3 = ((payload[8] << 8) | payload[9]) & 0x0FFF
start = 10 + s1 + s2
wi = payload[start : start + s3]            # keep the "WI" header: the codec needs it
```

## The WI header, and which inputs to accept

```
57 49 | 04 | 00 fa | 00 c8 | 42 | ?? | 28 40 00 | ...
"WI"   ver   250     200     parameters (8-bit greyscale)
```

| Bytes | Meaning | Every licence seen |
|---|---|---|
| 0 to 1 | `WI` | |
| 2 | Format version | `04` (it is a version, not a count of dimension bytes) |
| 3 to 4 | Height, big-endian | 250 |
| 5 to 6 | Width, big-endian | 200 |
| 7 | Coding parameters, and the top bit of Q | `0x42` or `0x43` (8-bit greyscale) |
| 8 | The low 7 bits of Q, then a 0 bit | even |
| 9 to 11 | Coding parameters | `28 40 00` |

**Accept only this shape** (the exact rule is section 2 of [wi-codec.md](wi-codec.md)): other values select parts
of the format that licence portraits do not use and the specification does not cover, and in the original
reconstruction they drove buffer sizes and caused out-of-bounds writes. Every decoder here rejects anything else
(error E1, `SADL_PORTRAIT_UNSUPPORTED` in the C API) before decoding. Keep that gate in yours.

Why accepting real card data is safe: a photo section only reaches the codec after the barcode passed its six
block-marker checks, and without the issuer's private key no one can make a barcode decrypt to chosen bytes. So
the codec only ever sees photos from real cards (or the zero-padded payloads of the test vectors, which the header
gate rejects).

## Orientation

The codec's raster is **upside down**. Reverse the whole 50,000-sample array (a 180° turn) to get the upright face.
Every `decode` here does this; `decode_native_order` (`sadl_portrait_decode_native_order`) does not.

## Calling it

In Kotlin, Python or C#, call the decoder in that language: `WiPortrait.decode(photo)`, `wi.decode(photo)`,
`Sadl.WiPortrait.Decode(photo)`. Each returns 50,000 bytes (200 × 250 greyscale, upright, row by row from the top-left)
or throws an error whose code is E1 to E5. From any other language, use the C API
([../native/portrait/include/sadl_portrait.h](../native/portrait/include/sadl_portrait.h)), or port the
specification:

```c
int sadl_portrait_decode(const uint8_t* wi, size_t size, uint8_t* out /* 50,000 bytes */);
// 0 = ok; 1 = unsupported input (E1); 2 = decoding failed (E2 to E5); 4 = null pointer
```

The decoders keep no global state, so they are safe to call from several threads at once, and they need no Windows
DLL, Wine or network.

| Language | How |
|---|---|
| C, C++, Objective-C, Swift | Link the Rust `staticlib` or `cdylib` (`cargo build --release`, or `--target` for iOS and Android) and include the header; Swift through a module map |
| Rust | A git or path dependency on the crate (it is not on crates.io): `sadl_portrait::decode(&photo)` |
| Go | cgo against the library |
| Dart / Flutter | `dart:ffi` against the library, or port the specification to Dart |
| Node.js, browser | Port the specification (plain integer arithmetic; mind the 32-bit wrap-around of section 11), or build the Rust crate for `wasm32-unknown-unknown` |

**Integer width matters.** The specification's arithmetic is signed 32-bit and wraps. Languages with wider or
arbitrary-precision integers (Python, JavaScript, Dart) must wrap at the places section 11 of
[wi-codec.md](wi-codec.md) lists. [test-vectors/public-vectors.json](test-vectors/public-vectors.json) has the
SHA-256 of each public vector's portrait: check your build against it on every platform you ship.

## Showing it

Treat the portrait as the personal data it is: show it, but do not store, log or upload it unless you have a
reason and the right to. See [../docs/privacy.md](../docs/privacy.md).
