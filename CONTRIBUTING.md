# Contributing

Issues and pull requests are welcome: corrections to the specification, new lessons, ports to other languages,
and reports of cards that do not decode.

## The rules

1. **No real licence data, ever.** No barcode bytes, images, decoded values or portraits from a real card, in
   issues, pull requests, tests, fixtures, logs or screenshots, including your own card and the public vectors'
   decoded values. Test vectors are synthetic ([scripts/make-test-vectors.py](scripts/make-test-vectors.py) encodes
   made-up values), or hashes of the public vectors that [scripts/fetch-test-vectors.sh](scripts/fetch-test-vectors.sh)
   fetches into the git-ignored `third_party/`. Assertion messages name fields, never values.
2. **The specification comes first.** Change [spec/](spec) first, then every implementation (Python, Kotlin and C#;
   the portrait also in Rust), then the vectors. Every test suite must pass: see
   [AGENTS.md](AGENTS.md#6-this-repository) for the commands. CI runs them all.
3. **The portrait codec stays clean-room.** Work from [spec/wi-codec.md](spec/wi-codec.md) only. Do not copy or
   paraphrase code from other WI decoders, including the reconstruction the specification was derived from; see
   [cleanroom/LOG.md](cleanroom/LOG.md). A question the specification cannot answer is an issue against the
   specification.
4. **Keep the libraries dependency-free** at run time, and keep results' `toString()` and exception messages free
   of personal values.

## A card that does not decode

Open an issue with what you can share without personal data: the platform and barcode reader, the error
(`wrong_length`, `block_check_failed`, `unknown_version` with its four version bytes, `malformed_payload`), and
whether it is a card or a temporary licence. A new card design will first show up as unknown version bytes.

By contributing, you agree that your contribution is licensed under the [MIT licence](LICENSE).
