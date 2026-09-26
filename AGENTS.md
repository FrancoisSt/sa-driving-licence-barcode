# AGENTS.md

Instructions for a coding agent (or a person) who has been asked to **read South African driving-licence
barcodes** in some app, in some language, on some platform. Follow this file and you should get a correct,
verified implementation the first time. Everything it relies on is in this repository.

## 0. What you are building

```text
camera frame / image file ──► PDF417 reader ──► raw bytes ──► identify ──► card: decrypt (RSA, public keys) ──► parse ──► fields
                                                                       └─► temporary licence: parse text ─────────────► fields
                                                              card payload section 3 ──► portrait codec (spec/wi-codec.md) ──► 200 x 250 greyscale
```

Two documents carry this barcode:

| Document | Barcode | Size | Protection |
| --- | --- | --- | --- |
| Driving-licence card (back) | PDF417, **mirrored** | exactly 720 bytes | RSA with published public keys, plus 30 known marker bytes |
| Paper temporary driving licence | PDF417 | 103 to 122 bytes of text | none (plain `%`-separated text) |

Decoding is small (about 300 lines in any language). Reading the barcode reliably from a camera is where the
effort goes. The portrait is optional: use one of the four decoders here (Rust with a C API, Kotlin, Python, C#), or
write one from [spec/wi-codec.md](spec/wi-codec.md), about 500 to 850 lines, with a checkpoint hash for every stage.

## 1. Read first, in this order

1. [spec/card-barcode.md](spec/card-barcode.md): the card format, the keys, the algorithm, every pitfall.
2. [spec/temporary-licence.md](spec/temporary-licence.md).
3. [spec/output-format.md](spec/output-format.md): the canonical JSON the test vectors expect.
4. [spec/checks.md](spec/checks.md), [docs/privacy.md](docs/privacy.md).
5. [docs/scanning.md](docs/scanning.md) if you touch the camera or image input.
6. [spec/portrait.md](spec/portrait.md) if you need the photo, then [spec/wi-codec.md](spec/wi-codec.md) if no
   decoder here fits your platform.

The spec is authoritative. Three reference implementations follow it and pass every vector:
[python/sadl.py](python/sadl.py) and [python/wi.py](python/wi.py) (standard library only: the easiest to read side
by side), [decoder/](decoder) (Kotlin) and [dotnet/](dotnet) (C#). Use them to compare intermediate values when
something differs; do not port their quirks without reading the spec's reason for them.

## 2. Ground rules

- **Privacy is not optional.** Never log, print, commit or put in test fixtures any decoded value, raw bytes or
  portrait from a real licence, including the public vectors' decoded values and the user's own card. Log outcomes
  only ("decoded", "block_check_failed", the four version bytes). Assertion messages name fields, not values. When
  testing on a device with a real card, read logs, not screenshots. See [docs/privacy.md](docs/privacy.md).
- **Do not commit the public vectors.** `scripts/fetch-test-vectors.sh` puts them in git-ignored `third_party/`.
- **Raw bytes, never text**, from every barcode reader.
- **The portrait:** use a decoder from this repository, call the Rust library's C API through FFI, or implement
  [spec/wi-codec.md](spec/wi-codec.md). Work only from that specification: do not copy code from other WI decoders,
  whose licences are unclear (see [cleanroom/LOG.md](cleanroom/LOG.md)). Keep the input gate (error E1).

## 3. Implementation steps, each with a gate

Do them in order; do not start the next until the gate passes.

**Step 1: parse a decrypted card payload.** Implement `parse_card(payload: bytes) -> fields` from
spec/card-barcode.md step 4 (header, 14 strings with the `0xE0`/`0xE1` rule, the ID number, the nibbles, the photo
length), and an adapter to the canonical JSON.
*Gate:* all 11 cases of [spec/test-vectors/parse-card.json](spec/test-vectors/parse-card.json) give exactly the
expected JSON, including the 7 rejections as `{"error": "malformed_payload"}`.

**Step 2: the temporary licence and identification.** `identify(raw)`, `parse_temporary_licence(raw)`, and a
`decode(raw)` that routes (card decryption can throw "not implemented" for now).
*Gate:* the 6 cases named `temporary-licence*` and the `vehicle-licence-disc` case in
[spec/test-vectors/decode.json](spec/test-vectors/decode.json) pass.

**Step 3: decrypt.** Six blocks, textbook RSA, fixed-length output, marker check (spec/card-barcode.md step 3).
*Gate:* every case in `decode.json` passes. Then fetch the public vectors and check: the 24 expected values
(surname, initials, licence number, valid-to of each of the six, from the `Assert.AreEqual` lines of their
`UnitTest1.cs`), `payload_sha256` and `photo_sha256` in
[spec/test-vectors/public-vectors.json](spec/test-vectors/public-vectors.json), and the negative cases
(one flipped bit per block, wrong version bytes, the other version's bytes, 719 bytes). If you cannot fetch them
(no network), say so; do not claim the gate passed.

**Step 4: checks.** spec/checks.md. *Gate:* all 12 cases of
[spec/test-vectors/checks.json](spec/test-vectors/checks.json) give the expected findings, and all six public
vectors pass every blocking check.

**Step 5: read barcodes.** Wire a PDF417 reader that returns raw bytes and reads the mirrored symbol (section 5).
*Gate:* a real card decodes on a real device. There is no synthetic substitute: the vectors are bytes, not images,
and emulators cannot show a card. Ask the user to hold a card up, and read the outcome from your logs.

**Step 6 (optional): the portrait.** Use a decoder here, or implement [spec/wi-codec.md](spec/wi-codec.md) stage by
stage, hashing each stage's output as section 14 says.
*Gate:* every case of [spec/test-vectors/wi-synthetic.json](spec/test-vectors/wi-synthetic.json) (41 valid streams
match every checkpoint; 26 fail with the expected error), every checkpoint of
[spec/test-vectors/wi-checkpoints.json](spec/test-vectors/wi-checkpoints.json), and `portrait_sha256` of all six
public vectors, on **every platform you ship** (the demo checks this on the phone with
`./gradlew :demo:connectedDebugAndroidTest`).

## 4. Lessons learned (each one cost someone time)

Decoding:

1. **Text instead of bytes.** Readers expose a text rendering of binary payloads. It is lossy. Symptom:
   `wrong_length` or `block_check_failed` on every real card. Use the raw byte accessor.
2. **Signed big integers.** Java/C# `BigInteger(bytes)` is signed: a block whose first bit is set goes negative.
   Use the unsigned constructor. Symptom: some cards fail, others work.
3. **Variable-length output.** Java `toByteArray()` adds a sign byte or drops leading zeros; Python `to_bytes`
   must be given the exact size. Normalise each decrypted block to exactly 128 or 74 bytes, left-padded.
4. **Library RSA "decrypt".** Fails: there is no padding. Use modular exponentiation.
5. **Forgetting the PrDP string (string 7).** A public decoder does this. Every field after it shifts. The
   synthetic `two-codes-no-prdp` vector has an empty string 7; the others do not.
6. **The `0xE1` rule.** `n` consecutive `0xE1` = `n + 1` string ends. An implementation that treats `0xE1` as
   "end, plus one empty string" per byte gets runs wrong. The first vector has a run of three.
7. **The "no date" nibble.** A single `0xA` nibble replaces a whole 8-nibble date. Read nibble by nibble, not in
   fixed 4-byte chunks.
8. **Leftover nibbles.** Only one `0xA` pad nibble may remain. Anything else means a parse bug: fail loudly.
9. **Dates.** Validate real calendar dates, years 1 to 9999 with exactly four digits (the vectors include
   2010-02-30, year 0000, `+20250` and a 29 February; `java.time` alone wrongly accepts year 0000 and `+20250`). Format with a
   locale-independent formatter: a phone in a locale with its own digits otherwise produces non-ASCII dates.
10. **Latin-1.** Strings are ISO 8859-1, not UTF-8: decoding as UTF-8 breaks names with accents (a vector has
    `MÜLLER`, byte `0xDC`).
11. **Identify before checking length.** 719 bytes that start with a card's version are a truncated card
    (`wrong_length`), not "some other barcode".
12. **The temporary licence's number** is field 5, not the "No." printed on the form. Its PrDP categories have no
    commas (`GP`). Its valid-to is calculated (+6 months, clamped to month end).

Scanning (details in [docs/scanning.md](docs/scanning.md)):

13. **The symbol is mirrored.** zxing-cpp (since 2020) and ML Kit read it. Assume nothing about other readers.
14. **Resolution.** Default camera analysis resolutions (about 1080p) are too low for worn cards. Ask for the
    highest (about 4000 × 3000), trading frame rate.
15. **Preview and analysis must match.** Same aspect ratio, letterboxed preview; otherwise the on-screen guide
    lies about what is in the frame.
16. **Crop to the guide** with a 5% margin; otherwise reads are slow (800 ms+ per frame on a cheap phone) and
    small symbols are missed.
17. **zxing-cpp alone often finds but cannot correct** a worn card's symbol. ML Kit first plus zxing-cpp fallback,
    and the warp + vertical-blur retry, fixed that on a low-end phone that "never detected" real cards. With both
    engines, frames took about 50 ms.
18. **An engine can stall.** ML Kit can take seconds per frame on an unreadable symbol. Cap the wait (about
    1.5 s), keep scanning with the other engine, collect the late answer.
19. **Stop at the first good read** (atomic flag), close every frame, catch per-frame errors.
20. **Feedback matters.** People move the card when nothing happens. Show "found, hold steady" (amber box on the
    symbol's corners) as soon as a symbol is found but not read.
21. **Image files:** crop candidates and retry with recipes (upscale, vertical blur, threshold, 180°). Whole-page
    reads mostly fail even when the symbol is found. For PDFs, extract the embedded image at full resolution.

Portrait:

22. **Upside down.** The codec's raster is rotated 180°: reverse the whole array. Every `decode` here already does;
    `decode_native_order` does not.
23. **32-bit wrap-around.** The codec's arithmetic is signed 32-bit and wraps. Python, JavaScript and Dart integers
    do not: wrap at each place spec/wi-codec.md section 11 lists. In Kotlin and Java mind `shr` versus `ushr`; in
    C# do not turn on `CheckForOverflowUnderflow`; in Rust use `wrapping_*` (debug builds panic otherwise).
24. **Shifts and divisions round differently.** The spec uses both `>>` (toward minus infinity) and `div` (toward
    zero), deliberately. Use exactly the one each step names.
25. **Hash every stage.** A wrong portrait says nothing about where the bug is. The checkpoint hashes of each level
    (spec/wi-codec.md section 14) do: fix the first checkpoint that differs. The clean-room decoders matched every
    checkpoint on the first run this way.
26. **Input gate, then first error.** Accept only the licence header (E1, including an odd byte 8). After that,
    report the first error met in reading order; stopping there is allowed (spec/wi-codec.md section 12). No real
    card's photo has failed yet: errors come from synthetic tests, and would come from a misread that passed the
    block checks (none seen).

Process:

27. **Small device log buffers.** Some phones keep only 256 KB of log; camera frame spam rolls it over in about a
    minute. Stream the log (or filter by your tag) while testing; do not rely on reading it afterwards.
28. **Version 1 is untested.** Keep its keys, but report unknown version bytes clearly so a new card design is
    noticed when it arrives.

## 5. Platform notes

| Platform | Barcode reader (raw bytes) | Big integers | Portrait |
| --- | --- | --- | --- |
| Android (Kotlin/Java) | ML Kit `Barcode.rawBytes` + zxing-cpp `io.github.zxing-cpp:android` `Result.bytes`; see [demo/](demo) | `java.math.BigInteger(1, bytes)`; or use [decoder/](decoder) directly | `WiPortrait` in [decoder/](decoder) (pure Kotlin) |
| iOS (Swift) | ML Kit iOS `rawData`, or zxing-cpp `wrappers/ios`. Apple Vision is untested on the mirrored symbol | Swift has no big integer: use a small package (for example attaswift/BigInt) or call the C side with OpenSSL `BN_mod_exp` | the Rust `staticlib` (built with `--target aarch64-apple-ios`) through a module map, or a Swift port of the spec |
| Flutter / Dart | zxing-cpp-based plugins, or ML Kit plugins that expose raw bytes | `BigInt.modPow` | `dart:ffi` to the Rust library, or a Dart port (mind lesson 23) |
| React Native / web | zxing-cpp WebAssembly build (`zxing-wasm`) returns bytes | JavaScript `BigInt` with a square-and-multiply `modPow` | a JavaScript port of the spec (mind lesson 23), or the Rust crate built for WebAssembly |
| .NET / MAUI | zxing-cpp .NET wrapper | `System.Numerics.BigInteger(bytes, isUnsigned: true, isBigEndian: true)`, `BigInteger.ModPow`; or use [dotnet/](dotnet) | `Sadl.WiPortrait` in [dotnet/](dotnet) (managed) |
| Server (any) | zxing-cpp (C++, Python `zxing-cpp`, ...) for image files | the language's big integer | the Rust library through FFI, or a port |
| Python | `zxingcpp.read_barcodes(img, formats=zxingcpp.BarcodeFormat.PDF417)[i].bytes` | built in (`pow(c, e, n)`) | [python/wi.py](python/wi.py), or the Rust library through `ctypes` |
| JVM server | zxing-cpp via JNI, or accept bytes from the client | [decoder/](decoder) as-is | `WiPortrait` in [decoder/](decoder) |

On any platform: do not add a network permission or upload path unless the app needs one; decode on the device, and
if a server must trust the result, send the raw bytes and decode again there ([docs/privacy.md](docs/privacy.md)).

## 6. This repository

```text
spec/                 the language-neutral specification (including the portrait codec), public keys, test vectors (JSON)
docs/                 scanning lessons, privacy, sources and credits
decoder/              Kotlin/JVM reference decoder and portrait decoder (no dependencies), with tests
python/               Python reference decoder and portrait decoder (standard library), with tests
dotnet/               C# library (.NET Standard 2.1 and .NET 8): the whole pipeline and the portrait, with tests
native/portrait/      the portrait decoder in safe Rust, with a C API for any language
demo/                 Android demo app (CameraX, ML Kit + zxing-cpp, animated overlay), with a device test
cleanroom/            how the portrait specification and decoders were written clean-room: the log and briefs
scripts/              fetch the public vectors, regenerate vectors and hashes
```

Commands (JDK 17 and the Android SDK for the Kotlin and Android modules; Rust 1.77+; the .NET 10 SDK):

```sh
scripts/fetch-test-vectors.sh                                  # the public vectors, into third_party/ (git-ignored)
python3 -m unittest discover -s python -v                      # Python: synthetic + public vectors + portrait
./gradlew :decoder:test -Psadl.requireVectors=true             # Kotlin; fails if the public vectors are missing
(cd native/portrait && cargo test && cargo build --release)    # Rust portrait decoder and its C API
dotnet test dotnet/Sadl.sln                                    # C#
./gradlew :demo:installDebug                                   # the demo app
./gradlew :demo:connectedDebugAndroidTest                      # portrait hashes on a connected phone
```

When changing this repository:

- Change the spec first, then every implementation (Python, Kotlin, C#, and Rust for the portrait), then the
  vectors. Regenerate vectors with
  `scripts/make-test-vectors.py` (it encodes made-up values; the expected output comes from those values, not from a
  decoder) and hashes with `scripts/make-public-vector-hashes.py`. Every suite must pass afterwards.
- New vectors must be synthetic. Never derive one from a real card.
- Keep the `toString()` of results free of values (Kotlin and C#; the Python reference returns plain dicts, so
  never print them), and exception messages free of data.

## 7. Known gaps

- Version 1 cards: untested (none seen).
- A new card design has been announced and may change the barcode or keys.
- Temporary licence fields 1, 2 and 4, and card header bytes 1 to 4 and 6: meaning unknown.
- The portrait codec follows a decompiled reconstruction, not the original DLL; two details that may be
  decompilation artefacts are listed at the end of [spec/wi-codec.md](spec/wi-codec.md) section 15.
- Other South African documents (smart ID card, vehicle licence disc, RC1) have their own, simpler barcodes; they
  are out of scope here.
