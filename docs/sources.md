# Sources and credits

This work stands on years of public reverse-engineering by others. Thank you.

## The format

| What | Source |
|---|---|
| Keys, version bytes and block layout | Stack Overflow question [17549231, "Decode South African (ZA) Drivers License"](https://stackoverflow.com/questions/17549231/decode-south-african-za-drivers-license), answer of 29 November 2016 |
| Layout of the decrypted data | [ugommirikwe/sa-license-decoder SPEC.md](https://github.com/ugommirikwe/sa-license-decoder/blob/master/SPEC.md) (2017, partial, inferred from samples) |
| The block markers | [the-mars-rover/rsa_identification](https://github.com/the-mars-rover/rsa_identification) (Dart, MIT) |
| Test vectors | [DanieLeeuwner/Reply.Net.SADL](https://github.com/DanieLeeuwner/Reply.Net.SADL) (C#, MIT): the only public encrypted card vectors. Its decoder has known bugs (signed integers, no PrDP string): use its tests, not its code |
| Keys as PEM files | [dalion619/sadl-pdf417-decryption](https://github.com/dalion619/sadl-pdf417-decryption) (MIT) |
| A Python parser | [yushulx/South-Africa-driving-license](https://github.com/yushulx/South-Africa-driving-license) (MIT); its barcode reading needs a commercial Dynamsoft licence |
| Commercial readers | [Dynamsoft](https://www.dynamsoft.com/codepool/decode-south-africa-driving-license.html) (which notes the keys are "not an official publication by the South African authorities"), [Scandit](https://docs.scandit.com/6.28/data-capture-sdk/web/id-capture/api/south-africa-dl-barcode-result.html) |
| Background | [Driving licence in South Africa](https://en.wikipedia.org/wiki/Driving_licence_in_South_Africa) (Wikipedia) |

## Reading the mirrored symbol

- zxing-cpp [pull request #175](https://github.com/zxing-cpp/zxing-cpp/pull/175), "Support PDF417 barcode images
  flipped along the horizontal axis", and [issue #152](https://github.com/zxing-cpp/zxing-cpp/issues/152).
- zxing-cpp wrappers: [Android](https://github.com/zxing-cpp/zxing-cpp/tree/master/wrappers/android),
  [iOS](https://github.com/zxing-cpp/zxing-cpp/tree/master/wrappers/ios), and Python, .NET, WebAssembly and others
  in the same repository.

## The portrait

- **Donald Jansen**'s C++ reconstruction of the Summus WI decoder, shared on 22 November 2024 in
  [the-mars-rover/rsa_identification issue #2](https://github.com/the-mars-rover/rsa_identification/issues/2#issuecomment-2493025394)
  ([original attachment](https://github.com/user-attachments/files/17866332/sadldecoder-only.zip), SHA-256
  `a1efd69e3972d80b7fa78df031de3266ed2f0336855317d029ba8f56019a9087`). It made the portrait decodable.
  [../spec/wi-codec.md](../spec/wi-codec.md) was written clean-room from its behaviour, and this repository's
  decoders from that specification; none of its code is included ([../cleanroom/LOG.md](../cleanroom/LOG.md)).
- [Businessware/Swi](https://github.com/Businessware/Swi): an earlier WI codec wrapper and the reference DLL. Its
  README records unsuccessful licence-photo decoding; it led to the later discussion and the native
  reconstruction.

## What is new here

- The completed payload layout (all 14 strings, the `0xE1` rule, every nibble field) and the field checks,
  verified on real cards.
- The temporary licence barcode's layout, which we could not find published anywhere, inferred from 57 real
  temporary licences.
- A functional specification of the WI portrait codec ([../spec/wi-codec.md](../spec/wi-codec.md)), with
  checkpoint hashes and synthetic streams, and clean-room decoders in Rust (with a C API), Kotlin, Python and C#.
- Language-neutral conformance vectors, and the scanning lessons in [scanning.md](scanning.md).

The code in this repository was written from these descriptions and from [../spec/wi-codec.md](../spec/wi-codec.md);
no third-party code was copied.
