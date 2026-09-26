# Third-party notices

Everything in this repository is MIT licensed ([LICENSE](LICENSE)), except the Gradle wrapper (Apache-2.0).

## The portrait specification's source

[spec/wi-codec.md](spec/wi-codec.md) describes the behaviour of **Donald Jansen**'s C++ reconstruction of the Summus
wavelet-image (WI) decoder, attached to
[the-mars-rover/rsa_identification issue #2](https://github.com/the-mars-rover/rsa_identification/issues/2#issuecomment-2493025394)
on 22 November 2024 (`sadldecoder-only.zip`, SHA-256
`a1efd69e3972d80b7fa78df031de3266ed2f0336855317d029ba8f56019a9087`), which was published without a stated
licence. Without it the portrait could not be decoded at all: thank you.

None of its code is in this repository. The specification was written clean-room: one agent studied the
reconstruction and wrote a functional description, with no code or identifiers from it; a second agent, which never
saw the reconstruction, wrote the four decoders in `native/portrait/`, `decoder/`, `python/wi.py` and `dotnet/` from
that description alone. [cleanroom/LOG.md](cleanroom/LOG.md) records the process, the briefs and the checks. If you
are the reconstruction's author and have a concern, please open an issue.

## Included in this repository

### Gradle wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/`)

Gradle, Apache License 2.0.

## Format knowledge

The public keys, envelope and payload layout come from public write-ups and MIT-licensed projects; no code was
copied from them. See [docs/sources.md](docs/sources.md).

## Test data (not included)

The six public encrypted vectors come from [DanieLeeuwner/Reply.Net.SADL](https://github.com/DanieLeeuwner/Reply.Net.SADL)
(MIT), commit `292402a9d437fbb44568dd5076eb50cc86040245`. `scripts/fetch-test-vectors.sh` fetches them into the
git-ignored `third_party/`. This repository publishes only SHA-256 hashes derived from them.

## Dependencies (resolved by the build tools, not included)

| Dependency | Used by | Licence |
|---|---|---|
| zxing-cpp Android wrapper (`io.github.zxing-cpp:android`) | demo | Apache License 2.0 |
| Google ML Kit barcode scanning (`com.google.mlkit:barcode-scanning`) | demo (optional engine) | [ML Kit terms](https://developers.google.com/ml-kit/terms) |
| AndroidX (CameraX, activity, core) | demo | Apache License 2.0 |
| Kotlin standard library | all Kotlin modules | Apache License 2.0 |
| JUnit 4, Gson, AndroidX Test | Kotlin and Android tests only | EPL 1.0, Apache License 2.0 |
| `serde_json`, `sha2`, `num-bigint` (crates.io) | Rust tests only | MIT or Apache-2.0 (their dependencies also Unicode-3.0, Unlicense) |
| xUnit, Microsoft.NET.Test.Sdk (NuGet) | C# tests only | Apache License 2.0, MIT |

No library in this repository has a runtime dependency: `decoder/` needs only the Kotlin standard library,
`native/portrait/` only Rust's standard library, `python/` only Python's, and `dotnet/` only the .NET base class
library.
