You are the IMPLEMENTER in a clean-room re-implementation. You will write four independent decoders for an image format from its written specification alone, plus, in C#, the rest of the licence-barcode pipeline from its specification. You have never seen, and must never look at, any existing implementation of this format.

## Your workspace

Everything you need is in this directory, and you work ONLY here:

    <workspace>

It is a copy of an open-source repository about South African driving-licence barcodes. Read these first:
- README.md
- spec/portrait.md (context)
- **spec/wi-codec.md**: the specification you implement.
- The test vectors:
  - spec/test-vectors/wi-checkpoints.json: checkpoint hashes for the six public vectors.
  - spec/test-vectors/wi-synthetic.json: synthetic streams, valid and invalid.
  - spec/test-vectors/public-vectors.json.
- To get each public vector's WI bytes: python/public_vectors.py loads the six vectors from third_party/, and python/sadl.py's `photo_section(decrypt(raw))` extracts the WI section.

Some documents in the workspace still say "do not re-implement the codec", or refer to C/C++ files under native/ that are not in the workspace. Ignore those statements: this brief replaces them.

## Clean-room rules: these are the point of the exercise

- Do NOT read, open, list or search anything outside <workspace>, except your language toolchains and their package registries (crates.io, Maven Central, NuGet). In particular, never look under <home>/Work/, anywhere in /tmp outside <workspace>, or in any other scratch directory. The original reconstructed decoder and the analyst's notes live there, and you must not see them.
- Do NOT look up existing implementations of this format on the internet or in package registries. That covers:
  - "Summus", "WI image", "SWI", "wavelet image" decoders;
  - the-mars-rover/rsa_identification and its issues;
  - Businessware/Swi;
  - any "sadldecoder";
  - any other South African licence photo decoder.
  General knowledge is fine: textbook descriptions of LeGall 5/3 wavelets, adaptive Huffman (FGK) coding, Rust, Kotlin, Python and C#.
- Write everything from spec/wi-codec.md. When the spec is unclear, ambiguous, or seems wrong (a checkpoint does not match and you cannot find your own bug), do not guess silently and do not go looking elsewhere. Write the question down precisely, with section numbers, what you tried and which checkpoint differs, and include it in your final message. If you resolved something by experiment against the hashes, still report it as a spec issue, saying what you found.

## Privacy

The six public vectors in third_party/ hold what look like real people's details and portraits.
- Never print, log, save or commit decoded pixels, images or any decoded field value. Print only hashes, sizes, counts and pass/fail.
- Tests compare SHA-256 hashes only.
- Do not write image files.
- Assertion messages may name a checkpoint and a vector index, never values.

## What to build (all inside <workspace>)

### 1. Rust: a new crate at native/portrait/

It replaces a C library with the same C API. Requirements:
- **Cargo.toml:** package `sadl-portrait`, edition 2021, crate-type `["rlib", "cdylib", "staticlib"]`, library name `sadl_portrait`. No runtime dependencies. Dev-dependencies are allowed if needed (for example `serde_json`, `sha2`).
- **Safety:** the decoder core is 100% safe Rust. Use `#![deny(unsafe_code)]` at the crate root, and `#[allow(unsafe_code)]` only in a small `ffi` module.
- **No panics on any input.** Every error is a returned value. Use explicit `wrapping_*` operations wherever spec section 11 says a value wraps. Use checked or clamped indexing.
- **Rust API:**
  - `pub fn decode(wi: &[u8]) -> Result<[u8; 50_000] or Vec<u8>, WiError>` returns the upright portrait, checkpoint C8.
  - `pub fn decode_native_order(wi: &[u8]) -> Result<..., WiError>` returns C7.
  - `pub enum WiError` has variants mapping to E1 to E5 of spec section 12, plus a bad-argument variant for the FFI.
- **C API:** exported from the cdylib, with a hand-written header at native/portrait/include/sadl_portrait.h:

  ```c
  #define SADL_PORTRAIT_WIDTH 200
  #define SADL_PORTRAIT_HEIGHT 250
  #define SADL_PORTRAIT_PIXELS 50000
  #define SADL_PORTRAIT_MAX_WI_BYTES 674
  typedef enum { SADL_PORTRAIT_OK = 0, SADL_PORTRAIT_UNSUPPORTED = 1, SADL_PORTRAIT_DECODE_FAILED = 2, SADL_PORTRAIT_UNEXPECTED_RASTER = 3 /* reserved, never returned */, SADL_PORTRAIT_BAD_ARGUMENT = 4 } sadl_portrait_status;
  int sadl_portrait_decode(const uint8_t* wi, size_t size, uint8_t* out /* 50,000 bytes */);                // upright (C8)
  int sadl_portrait_decode_native_order(const uint8_t* wi, size_t size, uint8_t* out);                    // codec order (C7)
  const char* sadl_portrait_status_name(int status);                                                       // static strings, no data
  ```

  - E1 maps to UNSUPPORTED, E2 to E5 map to DECODE_FAILED, and null pointers map to BAD_ARGUMENT.
  - `out` is written only on success.
  - The functions must be callable concurrently from several threads, so hold no global mutable state.
- **Tests** (`cargo test`):
  - every checkpoint (C1 to C8, and the scalars) for all six public vectors, when third_party/ is present;
  - every case in wi-synthetic.json: valid streams match all their checkpoints, and invalid ones return the expected error;
  - the header gate cases;
  - a robustness test: at least 100,000 random byte strings and random mutations of the synthetic streams (never of the public vectors) decode or fail without panicking;
  - a test that calls the C API through the Rust FFI functions.
  - To check checkpoints, give the decoder an internal observer or trace hook (for example, a `#[cfg(test)]` or `pub(crate)` callback receiving each checkpoint's bytes), so that production code carries no overhead.
- **Build:** `cargo build --release` must produce the cdylib. Update python/sadl.py's `_load_portrait_library` so it also finds `native/portrait/target/release/libsadl_portrait.so` (and `.dylib`, and `sadl_portrait.dll`).

### 2. Pure Kotlin, in the existing decoder/ module (JVM, no dependencies, no native code)

- Package `io.github.francoisst.sadl`. Create a public `object WiPortrait` with:
  - `decode(wi: ByteArray): ByteArray` (C8);
  - `decodeNativeOrder(wi: ByteArray): ByteArray` (C7);
  - a public `WiPortraitException(val error: Error)`, with an enum giving E1 to E5.
  - Match the style of the existing files. Note that `explicitApi()` is on.
- Signed 32-bit semantics are natural in Kotlin `Int`, but be careful with shifts (`shr` is arithmetic, `ushr` is logical) and with bit reads of up to 32 bits.
- It must be thread-safe (no shared mutable state) and fast enough for a phone: tens of milliseconds, not seconds.
- **Tests** (JUnit 4; Gson is already a test dependency): the same checkpoint, synthetic-vector and robustness coverage as Rust (the robustness test can use fewer iterations, for example 20,000), run with `./gradlew :decoder:test -Psadl.requireVectors=true`. JAVA_HOME must point to a JDK 17. This machine has one at <jdk17>; you may use that toolchain path, but nothing else outside the workspace.

### 3. Pure Python, as python/wi.py (standard library only, Python 3.9+)

- `decode(wi: bytes) -> bytes` (C8) and `decode_native_order(wi) -> bytes` (C7).
- A `WiError` exception with a `.code` attribute, 'E1' to 'E5'.
- Make python/sadl.py's `decode_portrait()` use python/wi.py by default. Keep the ctypes path available as an option, for example `decode_portrait(wi, library=path)` or an environment variable.
- Readability matters more than speed here: this is the reference that sits next to the spec. Still, one decode should take seconds at most.
- **Tests:** add python/test_wi.py with the same checkpoint and synthetic coverage as Rust; the robustness test can use fewer iterations. The existing tests in python/test_conformance.py must still pass: `python3 -m unittest discover -s python -v`.

### 4. C#: a .NET library at dotnet/ (managed code only, no P/Invoke; no runtime dependencies beyond the base class library, except System.Text.Json on netstandard2.1 if you choose it)

- **Layout:** a solution at dotnet/Sadl.sln with:
  - **dotnet/src/Sadl/Sadl.csproj**: `<TargetFrameworks>netstandard2.1;net8.0</TargetFrameworks>`, nullable enabled, root namespace `Sadl`, and NuGet-ready metadata (PackageId `Sadl`, MIT licence expression, a README).
  - **dotnet/tests/Sadl.Tests/Sadl.Tests.csproj**: xUnit, targeting net10.0 (the installed SDK is .NET 10; .NET 9 is also installed).
- **The portrait:** `Sadl.WiPortrait.Decode(ReadOnlySpan<byte> wi) → byte[]` (C8), `DecodeNativeOrder` (C7), and a `WiPortraitException` with an error code for E1 to E5. Written from spec/wi-codec.md, with the same checkpoint, synthetic-vector and robustness tests as the others. Use `unchecked` arithmetic where spec section 11 says a value wraps, and make sure the project does not turn on `CheckForOverflowUnderflow`.
- **The rest of the pipeline, in C# only.** This part is not clean-room sensitive: the other languages already have it, as python/sadl.py and decoder/. Write it from spec/card-barcode.md, spec/temporary-licence.md, spec/checks.md and spec/output-format.md:
  - `Sadl.LicenceBarcode.Identify(raw)`;
  - `Decrypt(raw)`, using `System.Numerics.BigInteger` with isUnsigned and isBigEndian, and `ModPow`;
  - `ParseCard(payload)`, `ParseTemporaryLicence(raw)` and `Decode(raw)`, returning typed records;
  - `LicenceChecks.Check(...)`;
  - a canonical JSON writer, using System.Text.Json on net8.0. For netstandard2.1, either reference the System.Text.Json package or write a small writer yourself. Your choice; say which.
  - Exceptions carry the canonical error reason and no data.
  - `ToString()` of the result types must not reveal personal values, the same rule as the Kotlin decoder (see decoder/src/main/kotlin/.../Models.kt for the convention; you may read our own Kotlin and Python decoders).
- **C# tests:** every vector file:
  - parse-card.json, decode.json and checks.json;
  - public-vectors.json (the 24 expected values: see how python/public_vectors.py reads them; plus the payload, photo and portrait hashes);
  - wi-checkpoints.json and wi-synthetic.json.
  - Run with `dotnet test dotnet/Sadl.sln`.

Write the four implementations independently, each from the spec. Do not machine-translate one into another. Similar structure is fine and expected, since they follow the same spec.

## Your final message

Include:
- every file you created or changed;
- the exact test commands and their results (pass counts, and the checkpoint match summary for each implementation);
- timing for one decode in each language (Rust, Kotlin, Python, C#);
- any spec issue or question, as described above (or "none");
- confirmation that you read nothing outside <workspace> apart from toolchains and registries, and looked up no existing implementation.
