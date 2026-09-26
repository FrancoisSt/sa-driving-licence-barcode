# Clean-room log: the WI portrait decoder

This log records how the portrait decoder in this repository was re-implemented without copying the reconstructed
codec it replaces. It is kept as the work happens, in time order, and is not edited afterwards except to add
entries. Times are SAST (UTC+2); the two entries before the log was started are approximate, reconstructed from
the session.

## Why

The only decoder for the Summus wavelet image ("WI") in South African licence barcodes is Donald Jansen's C++
reconstruction, decompiled from the original Windows DLL and shared in November 2024 without a licence
([the-mars-rover/rsa_identification issue #2](https://github.com/the-mars-rover/rsa_identification/issues/2#issuecomment-2493025394)).
To publish a decoder that everyone can use under a clear licence, in any language, the format is specified by one
party from the reconstruction, and implemented by another party from the specification alone.

## Roles and access

| Role | Who | May read the reconstructed codec | Produces |
|---|---|---|---|
| Owner | Francois Stander | yes | decisions |
| Orchestrator | the main Claude Code session (Claude Opus 5.5) | only what is listed under "Orchestrator's prior exposure"; nothing further | briefs, this log, integration of the delivered code, relaying questions and answers |
| Analyst | a separate Claude Opus 5.5 subagent | yes, in full | `spec/wi-codec.md` (the functional specification) and `spec/test-vectors/wi-checkpoints.json` (hashes) |
| Implementer | a separate, fresh Claude Opus 5.5 subagent that starts with no context | **no**: it works in a copy of the repository without `native/portrait/swi/`, and is told not to look for the codec or its original attachment | the new decoders, written from the specification |

Communication between analyst and implementer goes only through the orchestrator, only as questions about the
specification and answers that amend the specification text. No code passes between them. Every exchange is
recorded below.

Records: the briefs are in [briefs/](briefs). Local paths are redacted as `<repo>` and `<scratch>`; the SHA-256 of
each verbatim original is given in its entry and kept with the owner.

## The reconstruction being specified

| File | SHA-256 |
|---|---|
| `native/portrait/swi/SWIDecoder.cpp` | `553ecfd112d25dc7c50cb9c57725ba7fb6ba748a2233e649e00d7c5dfce39320` |
| `native/portrait/swi/SWIDecoder.h` | `c7ccf112f18cd70286bdfa3a0d9f2e1bdc33f67019cab8f56090b6659a399714` |

These are Jansen's files with safety fixes (see [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)). The
original attachment's SHA-256 is `a1efd69e3972d80b7fa78df031de3266ed2f0336855317d029ba8f56019a9087`.

## Orchestrator's prior exposure (before the clean room started)

Before the decision to do this, the orchestrator had integrated the reconstruction as a library. In doing so it
saw:

- The codec's API as used by a wrapper: the entry points (create/destroy the raw image, compressed image and
  options; decompress; free the raw image data) and the fields set or read (compressed data and size; the Fast,
  Smoothing and Sharpening options; the raw pixels, width, height, bits per pixel and colour flag).
- The first 60 lines of `SWIDecoder.cpp`: includes, global counters, and two small helper functions (an integer
  base-2 logarithm and a zeroing allocator).
- A diff of two expressions changed from `unsigned long` to `uint32_t` for 64-bit parity.
- Line numbers of the entry points, and a gcov summary (function signatures and executed line counts) used to
  size the work.
- The accepted header values, from an earlier wrapper's comments.

It did not read the codec's algorithm. It writes no part of the new decoder.

## Entries

### 2026-09-26, about 15:35: decision

Francois Stander asked how hard reverse-engineering the codec would be. After the measurement (58 of 189 functions,
about 1,400 of 8,745 executable lines run for licence portraits), he chose: **clean room first, then commit**. The
repository is not yet published. Its single commit will contain the clean-room decoder, not the reconstruction.

### 2026-09-26, about 15:40: analyst briefed

A fresh Claude Opus 5.5 subagent was briefed as the analyst: [briefs/01-analyst-brief.md](briefs/01-analyst-brief.md)
(verbatim SHA-256 `4b7e5e07a9490d2ece17c4de585d66cfb2c7e6e3e436446a41695bc52e29dcba`). Summary of the brief:

- Specify, in its own words, everything the licence-portrait decoding path computes, precisely enough for bit-exact
  reimplementation.
- No pasted or transliterated code, no decompiled identifiers, no source comments.
- Constant tables that belong to the format are to be given in full.
- Define numbered checkpoints and publish their hashes for the six public vectors.
- Prove completeness with a throwaway prototype written from the spec, which is not delivered.
- Privacy: no pixels or decoded values printed or saved.

### 2026-09-26 15:45: log started

At Francois Stander's request, this log and the private record of verbatim briefs were started.

### 2026-09-26 16:10: specification revision 1 delivered (file times: spec 16:10, checkpoints 16:04)

The analyst delivered:

| File | SHA-256 |
|---|---|
| `spec/wi-codec.md` (revision 1, 671 lines) | `dfdc3de02f51025e079a7878507ea1471a323171b496f9728de6656d9ff88eaa` |
| `spec/test-vectors/wi-checkpoints.json` | `066906faaac42f0ac5459edac865d778854d86a7bcfd0ebce5edda5043148b1f` |

- **What it specifies:** the codec as a 5-level LeGall 5/3 wavelet, reconstructed in the image domain (bilinear
  upsampling plus a basis function added per detail coefficient), with a zerotree-style significance map coded as
  run lengths along a recursive quadrant scan, FGK adaptive Huffman coding and mid-point uniform quantisation.
  It gives numbered checkpoints C1 to C8.
- **How the analyst verified it:**
  - a throwaway prototype, written from the specification, matched the instrumented reference on the six public
    vectors, on 1,200 synthetic streams and on 1,800 corrupted streams;
  - a helper agent, briefed by the analyst to work from the specification alone, matched every checkpoint on its
    first run. The details of that helper are requested; see the next entry.
- **Code and notes:** the prototype, the analyst's notes and its scratch encoder stay outside the repository. The
  analyst's dumps of intermediate data were deleted.
- **A finding against the current C wrapper:** the gate does not check the lowest bit of header byte 8 (an odd
  value selects an entropy coder that portraits do not use). The specification requires rejecting it.

### 2026-09-26 16:12: orchestrator's audit of revision 1

- **Automated scan:** searched for the reconstruction's identifier patterns (`sub_` addresses, `T_size` types,
  `arg_` and `vN` locals), x86 register names, mangled symbols, the codec's API names and its global variables.
  None were found.
- **Reading:** the orchestrator read the whole specification. It is written by format and algorithm, in prose,
  tables and fresh pseudocode, and has no C syntax. The constant tables it contains are facts about the format:
  the initial Huffman codes as self-test values, the kernel weights, the scan orders and the level sizes.
- **Result:** accepted for implementation.
- **Requested from the analyst:**
  - purely synthetic WI conformance vectors, covering the paths the public vectors do not reach, with expected
    values from the reference;
  - a verbatim record of the helper agent's brief and access.

### 2026-09-26 16:18: languages decided

Francois Stander decided the implementer writes the decoder in **Rust** (a native library with the same C API as
today, for FFI and WebAssembly), **Kotlin** (JVM and Android, no native code), **Python** (the readable reference)
and **C#** (.NET). In C#, the rest of the licence pipeline is written too (decryption, parsing, checks, canonical
JSON), from the existing specification pages.

### 2026-09-26 16:23: specification revision 2 and synthetic vectors delivered

| File | SHA-256 |
|---|---|
| `spec/wi-codec.md` (revision 2, 684 lines) | `1b6aafecb11c999b35f4694ce0349ac480aa3a0897003b554a02f869d1ed1f73` |
| `spec/test-vectors/wi-synthetic.json` (63 cases: 41 valid, 22 that must fail) | `278aed6aa55a6fe611b5f686f0d94e1747d50270a04548546d199cf87a5733be` |
| `spec/test-vectors/wi-checkpoints.json` (unchanged) | `066906faaac42f0ac5459edac865d778854d86a7bcfd0ebce5edda5043148b1f` |

- **Decoding rules:** unchanged from revision 1.
- **What revision 2 adds:**
  - section 11: which wrap-around cases can and cannot be reached;
  - section 14: the synthetic vector file;
  - section 15: how the blind check was done.
- **The synthetic streams:**
  - generated by the analyst's encoder from made-up content;
  - expected values and error codes taken from an instrumented copy of the reference;
  - the analyst's prototype agrees with every one;
  - coverage: skip counts 0 to 5, LL widths 0 to 31, escape widths 0 to 10, both large-value leaves, nested run
    extensions, Q = 0 and 255, the truncating upsampling cases, and the header, end-of-data, skip, escape-width and
    run errors.
- **Orchestrator's checks:**
  - no stream equals a public vector;
  - no 12-byte run of coded data from the six public portraits occurs in any synthetic stream (0 of 63 streams);
  - the scan for decompiled identifiers is still clean.
- **A gap in today's C wrapper:** it accepts an odd header byte 8. That case is recorded as E1 in the vectors.

### 2026-09-26 16:25: the analyst's blind check, recorded

Before revision 1, the analyst had a helper agent implement the specification blind, to find gaps in it.

- **Brief:** [briefs/01a-analyst-helper-brief.md](briefs/01a-analyst-helper-brief.md) (SHA-256 of the unredacted
  text: `552f19e9fce1fbb922ae65f70f912e6564ff1cb90046eb0ed71eeb1eebdd0cd9`). The orchestrator did not see the brief
  when it was sent. It is recorded here as the analyst reported it.
- **Access:**
  - allowed: the specification, the checkpoint hashes, spec/portrait.md, and six WI inputs;
  - forbidden: native/, python/, third_party/, decoder/, portrait/, build/, the analyst's notes, and any existing
    decoder;
  - enforcement was by instruction only: the helper ran on the same machine with ordinary tools. Its compliance is
    self-reported.
- **Result:**
  - it saw a draft of the specification. The draft's hash was not recorded;
  - it reported two draft defects (no upper size limit; `R > 0` where `R ≠ 0` is right), which were fixed
    before revision 1;
  - on the final vectors it matched 61 of 63 synthetic cases; the two it misses are exactly those two defects.
- **Status:** the helper's code stays in the analyst's scratch area and is not part of this repository. This check is
  the analyst's own evidence. The independent implementation is the implementer's, below.

### 2026-09-26 16:26: implementer briefed

A fresh Claude Opus 5.5 subagent, with no context from this session, was briefed as the implementer:
[briefs/02-implementer-brief.md](briefs/02-implementer-brief.md) (SHA-256 of the text as sent:
`d96b31cb6e9c7986a9110ee1998489809875e359ec944b8154ce7a36532b9011`).

- **Its workspace:** a copy of the repository made by the orchestrator. It contains 55 files outside the public
  vectors (manifest SHA-256 `70c8bd321dc1ab458515813a005cb2132067bc4084ed8b7a2c2a046c4158c6f8`, kept with the owner).
  It includes:
  - specification revision 2;
  - all the test vectors;
  - the Python and Kotlin licence decoders.
- **Removed from the workspace:**
  - `native/` (the reconstruction, its C wrapper and its CLI);
  - `portrait/` (the JNI wrapper);
  - `demo/`;
  - `cleanroom/`;
  - `THIRD_PARTY_NOTICES.md`, which names two functions of the reconstruction in its list of fixes.
- **The public vectors:** copied in so that tests can run. They were fetched from Reply.Net.SADL, which holds no
  codec code.
- **Rules:**
  - read nothing outside the workspace except toolchains and package registries;
  - look up no existing WI implementation, anywhere;
  - report every specification ambiguity rather than resolve it elsewhere.
- **Enforcement:** by instruction. The subagent runs on the same machine with ordinary tools. Its brief names the
  forbidden locations, and it is asked to confirm compliance in its report.
- **Deliverables:** Rust (a safe core, the same C API as today), pure Kotlin, pure Python and C# (.NET, with the
  whole licence pipeline).

### 2026-09-26 16:58: implementation delivered, and the orchestrator's verification

The implementer delivered four decoders written from specification revision 2:

- **Rust:** `native/portrait/`, a safe core with the C API in a small `ffi` module.
- **Kotlin:** `WiPortrait.kt` in `decoder/`.
- **Python:** `python/wi.py`.
- **C#:** `WiPortrait.cs`, and the whole licence pipeline, in `dotnet/`.

A manifest of the delivered workspace (SHA-256 of every file) is kept with the owner; the manifest file's own
hash is `e0bf3ac943a0676d045231543ab8812fe2576900124916b6e7faa391bdb079ad`.

**What the implementer reported:**

- **Checkpoints:** every checkpoint matched on the first run, in all four languages: all six public vectors
  (C1 to C8 and the scalars), all 41 valid synthetic streams, and the expected error for all 22 invalid ones.
- **Test suites:** Rust 14, Kotlin 23, Python 22, C# 23; all pass.
- **Robustness, on synthetic streams:** Rust and C# 100,000, Kotlin 20,000 and Python 1,500 inputs, with no panics or
  crashes.
- **Two clarification questions**, not blocking: the error when data runs out inside an over-long skip prefix
  (E2 or E3), and whether stopping at the first error is allowed. Both were relayed verbatim to the analyst.
- **Two borderline actions,** self-reported:
  - it listed three entries of the local NuGet package cache;
  - it briefly wrote a comparison script one level above its workspace, reading only workspace files, and then
    deleted it.
- **A file it chose not to open:** it noticed `third_party/Reply.Net.SADL/.../DriversLicenceImage.cs` in the
  public-vector repository, which it thought might be an image decoder, and did not open it.

**The orchestrator checked that file.** It is a 19-line data class whose comment reads "Image processing is not
implemented". The service that uses it reads only the width and height, and marks decoding as a TODO. So it
contains no decoder, and nothing was exposed. Lesson: a future workspace should include only the vector file,
`UnitTest1.cs`, not the whole repository.

**The orchestrator's own checks:**

- **Rust and Python suites:** rerun; they pass.
- **`unsafe` code:** none outside `ffi.rs`, and `#![deny(unsafe_code)]` is at the crate root.
- **Copied identifiers:** none of the reconstruction's identifier patterns occur in any delivered source.
- **Differential test against the reconstruction:**
  - **Inputs:** 200,000 random mutations of the synthetic valid streams (4 seeds × 50,000). Inputs with an odd byte 8
    were skipped, because the old gate does not check it.
  - **What was compared:** the new Rust library and the old C reconstruction were both called through their C
    API, and their status and output were compared.
  - **Result:** they agreed on every input: the same status every time, and identical pixels for all 32,839 that
    both decoded. There were 0 disagreements.

### 2026-09-26 16:58: questions relayed to the analyst

The orchestrator relayed the implementer's two questions verbatim, with what it needed back
([briefs/03-question-relay.md](briefs/03-question-relay.md); verbatim record SHA-256
`3872bf2f356bdac017895ff04d401dab88366dd134746e51871623f3c4eaf52f`). It asked for answers in words and rules only.

### 2026-09-26 17:01: specification revision 3 delivered

The analyst's answer: [briefs/03-analyst-answer.md](briefs/03-analyst-answer.md) (verbatim record SHA-256
`c3b5dbe24ea5403e187e93654a294b454b638c60b5e600bd17cd67808d594e69`).

- **Question 1:** the implementer was right. The unary skip count is compared with 5 only after its 0 bit, so data
  that ends first is E2 even after six or more 1 bits; six or more 1 bits followed by a 0 bit are E3 (section 7).
- **Question 2:** a decoder may stop at the first error and must report the first condition met in reading order
  (section 12). The reference, which keeps running, can meet E3 or E5 after E2; E2 is canonical in both cases.
  Checked on 9,733 truncated synthetic streams.
- **No output changes.** The decoding rules are unchanged since revision 1; `wi-checkpoints.json` is unchanged.
- **Four new failing vectors:** `e2-skip-prefix-cut`, `e2-skip-5-cut`, `e3-skip-6-at-end` and
  `e2-then-e5-run-cut`. The file now has 67 cases (41 valid, 26 failing), and failing cases carry
  `reference_error_sequence` where the reference meets more than one condition.
- **Delivered hashes:** `spec/wi-codec.md` `95482ba037c52b39818dfa0d08e3e0e596d617299b1770c0493fb5e25817b358`,
  `wi-synthetic.json` `3c9a5a7d8d938cb5c1323387023c458f2e4f7a479470883a594ef5906794ee72`.

**Not relayed to the implementer.** All four implementations already give the expected error on the four new
vectors with no code change, so the answer did not need to reach the implementer. The orchestrator changed only
the expected count of failing cases in each test suite, from 22 to 26 (`checkpoint_tests.rs`, `WiPortraitTest.kt`,
`test_wi.py`, `WiPortraitTests.cs`). Afterwards: Rust 14 passed (plus 1 timing test, ignored by default), Kotlin
23/23, Python 22/22 (with the native library built), C# 23/23.

### 2026-09-26 17:15: integration into the repository

**Removed**, so that the repository contains no code from the reconstruction:

- `native/portrait/swi/` (the reconstruction, with the safety fixes listed below);
- the C++ wrapper `native/portrait/src/*.cpp`, its CMake build and CLI;
- the `portrait/` Android JNI module;
- `scripts/test-portrait-sanitizers.sh`.

For the record, since [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) no longer describes them, the fixes that
copy carried were: a use-after-free in its image cleanup; a leaked coefficient-index allocation; a bit reader that
truncated reads of more than eight bits; comment-buffer ownership; and two `unsigned long` expressions made 32-bit
for LP64 builds. The original Android JNI wrapper was never used.

**Added:** the implementer's `native/portrait/` Rust crate, `WiPortrait.kt` in `decoder/`, `python/wi.py`, and
`dotnet/`. The demo now decodes the portrait with the pure-Kotlin `WiPortrait`, and it has a device test
(`PortraitOnDeviceTest`) that checks the six public portraits' hashes and every synthetic stream on the phone.

**Editorial changes by the orchestrator** to the analyst's files, none of them to a rule:

- `spec/wi-codec.md`: the introduction pointed at the removed `native/portrait/swi/` and `src/`; it now names
  Jansen's attachment as "the reference" and links this log. Sentences about "the repository's C API" not checking
  an odd byte 8 now describe the reference's former C wrapper, and say that this repository's decoders reject it (E1).
  Section 13 says the reference was called with three options fixed.
- `wi-synthetic.json`: the description and note of `e1-byte8-odd` say "the reference's C wrapper" instead of "the
  current C API".
- Hashes after these edits: `spec/wi-codec.md` `782c002d4e36bc96bb3c82a4553592cb8db7fcfc00b7542d68da4dffbc6907a8`,
  `wi-synthetic.json` `515e0daa2a70c62aeadc54c9aaff10c0197eb6df78f7d2916babdf4931818543`.

**On the phone** (Blackview BV5300, Android 12, arm64): `./gradlew :demo:connectedDebugAndroidTest` passed 2 of 2
tests; the six public portraits decoded in 76, 17, 14, 10, 11 and 10 ms (the first includes warm-up).

### 2026-09-26 17:18: a real card

The owner scanned their own licence card with the updated demo on the BV5300. The log (outcomes only) reads:
"decoded card (720 bytes) by ml-kit after 23 frames, 792 ms", then "portrait decoded in 95 ms". The clean-room
Kotlin decoder produced the portrait. No pixels or values were logged or kept.

### 2026-09-26 17:23: final review, and errata

A final review of the whole repository (a separate subagent, read-only) found wording to fix; the fixes touch no
decoding rule and no decoder logic. One touches the analyst's file:

- `spec/wi-codec.md`, introduction: it credited "an independent implementation" with the comparison against the
  reference on 1,200 synthetic and 549 corrupted streams. Section 15 says the analyst's own prototype ran that
  comparison, on 1,800 corrupted copies of which 549 decoded; the introduction now says so. New SHA-256:
  `21597b66e5c5e835759c9fc1b38337d9d87617efbfa73009a97b32dbb60303a0`.

Errata to earlier parts of this log, which is not edited in place:

- "Roles and access" says the implementer's workspace had no `native/portrait/swi/`. In fact it had no `native/`
  at all (the 16:26 entry lists what was removed), which is stricter.
- "The reconstruction being specified" points to THIRD_PARTY_NOTICES.md for the safety fixes. That file no longer
  lists them; the 17:15 entry does.
