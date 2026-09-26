# South African driving-licence barcodes

How to read the PDF417 barcode on a South African driving licence (the back of the card, and the paper temporary
driving licence) in **any programming language**: the complete format, the published public keys, the pitfalls,
language-neutral test vectors, the portrait stored in the barcode, and a working Android demo.

| | |
|---|---|
| **Specification** | [spec/](spec): the card's 720-byte envelope, RSA with public keys, the 684-byte payload, the temporary licence, checks |
| **Test vectors** | [spec/test-vectors/](spec/test-vectors): JSON inputs and expected outputs, synthetic, for any language; plus hashes for the six public encrypted vectors |
| **For coding agents** | [AGENTS.md](AGENTS.md): implementation steps with gates, 28 lessons learned, platform notes |
| **Scanning** | [docs/scanning.md](docs/scanning.md): camera settings and image-file recipes that make worn cards read |
| **Portrait** | [spec/wi-codec.md](spec/wi-codec.md), a bit-exact specification of the photo's codec, written clean-room ([cleanroom/](cleanroom/LOG.md)); decoders in safe Rust with a C API ([native/portrait/](native/portrait)), Kotlin, Python and C# |
| **Reference decoders** | [python/](python) (standard library only), [decoder/](decoder) (Kotlin, JVM and Android, no dependencies) and [dotnet/](dotnet) (C#, .NET Standard 2.1 and .NET 8) |
| **Demo** | [demo/](demo): an Android app that scans a card and shows its details and portrait |

## What is in the barcode

**The card back** (720 bytes, encrypted with RSA using public keys published in 2016): licence number, ID number,
surname and initials, birth date, gender, valid from and to, each vehicle code with its first-issue date and
restriction, driver restrictions (such as glasses), PrDP categories and expiry, and a small greyscale photo.

**The temporary licence** (plain text): licence number, ID number, name, codes with first-issue dates, PrDP and the
issue date.

## Quick start

Python, no dependencies:

```sh
python3 python/sadl.py decode barcode.bin      # the raw bytes your barcode reader returned
```

Kotlin / Java:

```kotlin
when (val result = SaLicenceBarcode.decode(rawBytes)) {       // throws SaLicenceBarcodeException on a misread
    is LicenceBarcode.Card -> {
        result.licence.licenceNumber                           // validTo, codes, prdpCategories, photo, ...
        val portrait = result.licence.photo?.let(WiPortrait::decode)   // 200 x 250 greyscale bytes, upright
    }
    is LicenceBarcode.Temporary -> result.licence.issueDate
}
```

C#: `LicenceBarcode.Decode(rawBytes)` and `WiPortrait.Decode(photo)`; see [dotnet/](dotnet). From C or any language
with an FFI: the Rust library's C API, [native/portrait/include/sadl_portrait.h](native/portrait/include/sadl_portrait.h).

Anything else: follow [AGENTS.md](AGENTS.md) section 3, then check yourself against the vectors.

## The three things everyone gets wrong

1. **Use the barcode reader's raw bytes, not its text.** The card payload is binary.
2. **The card's symbol is printed mirrored.** zxing-cpp (since 2020) and Google ML Kit read it; check any other
   reader with a real card.
3. **It is textbook RSA with no padding, on unsigned big-endian integers.** A crypto library's "decrypt" fails, and
   a signed big integer silently breaks some cards. Each decrypted block starts with 5 known bytes: check them, and
   a misread cannot pass as data.

## Build and test everything

```sh
scripts/fetch-test-vectors.sh                                   # public vectors into third_party/ (git-ignored)
python3 -m unittest discover -s python -v
./gradlew :decoder:test -Psadl.requireVectors=true              # JDK 17
(cd native/portrait && cargo test && cargo build --release)       # Rust portrait codec and its C API
dotnet test dotnet/Sadl.sln                                     # C# (.NET 10 SDK)
./gradlew :demo:installDebug                                    # Android SDK
./gradlew :demo:connectedDebugAndroidTest                       # the portrait decoder on a connected phone
```

## Status

The card format and decryption are verified on the six public vectors and on real cards; the payload layout on 39
real cards; the temporary licence layout, inferred from 57 real ones, on the synthetic vectors; the portrait decoders
on the six public vectors and 67 synthetic streams, on x86-64 and (Kotlin) on an arm64 phone, and on a real card. The demo reads real cards on a low-end Android phone.
Version 1 cards are untested (none seen). See [spec/README.md](spec/README.md#confidence).

## Privacy

This barcode is personal information. Do not log, store or upload decoded values or portraits you do not need.
Read [docs/privacy.md](docs/privacy.md) before shipping.

## Credits and licence

Built on public work by many people: see [docs/sources.md](docs/sources.md). The portrait could be decoded only
because **Donald Jansen** reconstructed the Summus WI decoder and shared it. This repository contains none of that
code: its specification was written clean-room from the reconstruction's behaviour, and the decoders from the
specification ([cleanroom/LOG.md](cleanroom/LOG.md), [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)).

Everything here is [MIT](LICENSE) licensed, except the Gradle wrapper (Apache-2.0).

Contributions are welcome: read [CONTRIBUTING.md](CONTRIBUTING.md) first (no real licence data, ever). Report
security problems privately: [SECURITY.md](SECURITY.md).

This project is not affiliated with the South African government, the RTMC or the issuers of the licence. The
public keys are not an official publication.
