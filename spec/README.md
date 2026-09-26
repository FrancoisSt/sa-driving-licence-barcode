# The specification

Everything needed to read a South African driving-licence barcode in any programming language. No code in this
repository is required to use it.

| Page | What it covers |
|---|---|
| [card-barcode.md](card-barcode.md) | The card back: 720 bytes, the public keys, decryption, the block markers, the payload layout, every pitfall |
| [temporary-licence.md](temporary-licence.md) | The paper temporary licence: plain text, `%`-separated |
| [portrait.md](portrait.md) | The photo in the card barcode: extracting it, the decoders in this repository, calling them from any language |
| [wi-codec.md](wi-codec.md) | The portrait codec itself, bit-exact: enough to write a decoder in any language, with checkpoints |
| [checks.md](checks.md) | What to check after decoding, and what the licence number can and cannot tell you |
| [output-format.md](output-format.md) | The canonical JSON the test vectors use, and how to run them |
| [test-vectors/](test-vectors/) | Language-neutral conformance vectors (JSON) |
| [keys/](keys/) | The four published public keys as PEM files |

## Implementing it

1. Read [card-barcode.md](card-barcode.md) top to bottom, then [temporary-licence.md](temporary-licence.md).
2. Implement `identify`, `decrypt`, `parse_card` and `parse_temporary_licence`, plus an adapter to the canonical
   JSON of [output-format.md](output-format.md).
3. Run [test-vectors/parse-card.json](test-vectors/parse-card.json), then
   [test-vectors/decode.json](test-vectors/decode.json), then [test-vectors/checks.json](test-vectors/checks.json).
   All three are synthetic and need nothing else.
4. Fetch the public vectors (`scripts/fetch-test-vectors.sh`) and check the 24 values, the hashes in
   [test-vectors/public-vectors.json](test-vectors/public-vectors.json), and the negative cases.
5. For the portrait, use a decoder from this repository (Rust with a C API, Kotlin, Python, C#; see
   [portrait.md](portrait.md)), or write one from [wi-codec.md](wi-codec.md) and check it against
   [test-vectors/wi-synthetic.json](test-vectors/wi-synthetic.json), then every checkpoint in
   [test-vectors/wi-checkpoints.json](test-vectors/wi-checkpoints.json) and the pixel hashes.
6. Read [checks.md](checks.md) and [../docs/privacy.md](../docs/privacy.md) before you ship.

Three reference implementations pass all of it: [../decoder](../decoder) (Kotlin, JVM and Android),
[../python](../python) (Python, standard library only) and [../dotnet](../dotnet) (C#). Use them to compare intermediate values while you debug,
but work from the spec: it says why, and the code only says how.

## Confidence

| Part | Status |
|---|---|
| Card envelope, keys, markers, decryption | Public since 2016, used by every decoder; verified on the public vectors and on real cards |
| Card payload layout | Public (partial) since 2017; completed and verified on 39 real cards and the public vectors |
| Version 1 cards | Keys published, but no version 1 card has been seen to test |
| Temporary licence layout | Inferred from 57 real licences; fields 1, 2 and 4 not understood |
| Portrait codec | Specified clean-room from Donald Jansen's reconstruction; four independent decoders match it on the six public portraits and 67 synthetic streams, and the Rust one matched the reconstruction on 200,000 corrupted inputs |

Sources and credits: [../docs/sources.md](../docs/sources.md).
