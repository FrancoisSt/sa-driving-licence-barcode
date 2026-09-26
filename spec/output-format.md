# The canonical output

The test vectors in [test-vectors/](test-vectors/) state their expected results in this JSON form. An
implementation passes when, for every case, it produces the same JSON *value*. Key order and whitespace do not
matter; types do (strings stay strings: `"02"`, not `2`).

Your own API can look however suits your language. Write a small adapter that turns its results into this form for
the conformance tests.

## A card

```json
{
  "kind": "card",
  "version": 2,
  "licence_number": "0000000000BC",
  "licence_country": "ZA",
  "surname": "TESTER",
  "initials": "T",
  "id_number": "0001010000089",
  "id_type": "02",
  "id_country": "ZA",
  "birth_date": "2000-01-01",
  "gender": "02",
  "valid_from": "2025-01-01",
  "valid_to": "2029-12-31",
  "issue_number": "01",
  "vehicle_codes": [
    { "code": "EC", "vehicle_restriction": "0", "first_issue": "2010-01-01" }
  ],
  "driver_restrictions": "00",
  "prdp_categories": ["G", "P"],
  "prdp_expiry": "2027-01-01",
  "photo_length": 594
}
```

| Key | Type | From ([card-barcode.md](card-barcode.md)) |
|---|---|---|
| `kind` | `"card"` | |
| `version` | integer, 1 or 2 | the version bytes |
| `licence_number` | string | string 14 |
| `licence_country` | string | string 9 |
| `surname` | string | string 5 |
| `initials` | string | string 6 |
| `id_number` | string | the 13 characters after string 14 |
| `id_type` | string, 2 hex digits | nibble field 1 |
| `id_country` | string | string 8 |
| `birth_date` | `yyyy-MM-dd` | nibble field 9 |
| `gender` | string, 2 hex digits | nibble field 12 (`01` male, `02` female) |
| `valid_from`, `valid_to` | `yyyy-MM-dd` | nibble fields 10, 11 |
| `issue_number` | string, 2 hex digits | nibble field 8 |
| `vehicle_codes` | array, one entry per non-empty code position, in position order | strings 1 to 4, 10 to 13, nibble fields 2 to 5 |
| `vehicle_codes[].first_issue` | `yyyy-MM-dd` or `null` | |
| `driver_restrictions` | string, 2 hex digits | nibble field 6 |
| `prdp_categories` | array of strings; `[]` when none | string 7 split on `,`, trimmed, empties dropped |
| `prdp_expiry` | `yyyy-MM-dd` or `null` | nibble field 7 |
| `photo_length` | integer | `s3`, the photo section's length |

Strings are the Latin-1 text of the bytes, as they are (not trimmed).

## A temporary licence

```json
{
  "kind": "temporary_licence",
  "tag": "TDL01",
  "field2": "0105",
  "serial": "0000A00B",
  "field4": "1",
  "licence_number": "0000000000BC",
  "id_type": "02",
  "id_number": "0001010000089",
  "name": "T TESTER",
  "vehicle_codes": [
    { "code": "EC", "vehicle_restriction": "0", "first_issue": "2010-01-01" }
  ],
  "prdp_categories": ["G", "P"],
  "prdp_expiry": "2027-01-01",
  "issue_date": "2025-01-01",
  "valid_to": "2025-07-01"
}
```

Fields as in [temporary-licence.md](temporary-licence.md). `valid_to` is calculated: the issue date plus six
months, clamped to the month's last day.

## A rejection

```json
{ "error": "block_check_failed" }
```

| `error` | When |
|---|---|
| `wrong_length` | a card barcode that is not exactly 720 bytes |
| `unknown_version` | `decrypt` given bytes whose first four are not a known version |
| `block_check_failed` | a block is not below its modulus, or does not start with its marker after decryption |
| `malformed_payload` | the payload or the temporary licence text does not follow the layout (any rule marked `malformed_payload` in the spec) |
| `not_a_licence` | the whole pipeline was given a barcode that is neither a card nor a temporary licence |

Note that through the whole pipeline (`decode.json`), 720 bytes with unknown version bytes are `not_a_licence`,
because identification comes first; `unknown_version` comes from the decrypt step on its own.

## The test vector files

| File | Input | Run |
|---|---|---|
| [test-vectors/parse-card.json](test-vectors/parse-card.json) | `payload_hex`: a 684-byte decrypted payload (or shorter, for rejections) | your parse step |
| [test-vectors/decode.json](test-vectors/decode.json) | `raw_hex` (binary) or `raw_text` (Latin-1 text) | your whole pipeline |
| [test-vectors/checks.json](test-vectors/checks.json) | `licence`: a licence in the canonical form | your checks ([checks.md](checks.md)); `expected` is the list of finding codes |
| [test-vectors/public-vectors.json](test-vectors/public-vectors.json) | the six public encrypted cards, which you fetch yourself | decrypt, photo extraction, portrait |
| [test-vectors/wi-synthetic.json](test-vectors/wi-synthetic.json) | `wi_hex`: a synthetic WI stream (41 valid, 26 that must fail) | your portrait decoder; checkpoint hashes or `expected_error` ([wi-codec.md](wi-codec.md) sections 12 and 14) |
| [test-vectors/wi-checkpoints.json](test-vectors/wi-checkpoints.json) | the photo sections of the six public vectors | your portrait decoder, stage by stage ([wi-codec.md](wi-codec.md) section 14) |

`parse-card.json`, `decode.json` and `checks.json` are
`{"about", "all_values_are_synthetic": true, "cases": [{"name", "description", <input>, "expected"}]}`
(`description` is sometimes absent). Every value in them is made up, and they are generated by
[../scripts/make-test-vectors.py](../scripts/make-test-vectors.py), which *encodes* fields into the layout
rather than decoding, so the expected values do not come from any decoder. The two `wi-*` files are described in
[wi-codec.md](wi-codec.md) section 14; their synthetic streams come from an encoder, not from any card.

### The public vectors

The only real encrypted card barcodes that are public are six in
[DanieLeeuwner/Reply.Net.SADL](https://github.com/DanieLeeuwner/Reply.Net.SADL) (MIT), as hex constants in
`Reply.Net.SADL/Reply.Net.SADL.Tests/UnitTest1.cs`. New encrypted vectors cannot be made without the private key.
They hold what look like real people's details, so this repository does not copy them:
[../scripts/fetch-test-vectors.sh](../scripts/fetch-test-vectors.sh) fetches them into the git-ignored
`third_party/`. Test with them; never commit them, ship them, or print their decoded values.

- The field values to expect are that file's own `Assert.AreEqual` lines: surname, initials, licence number and
  expiry (valid to) for each of the six: 24 values.
- [test-vectors/public-vectors.json](test-vectors/public-vectors.json) is `{"about", "source", "commit", "cases"}`,
  with one case per vector: `{"index", "version", "raw_sha256", "payload_sha256", "photo_length", "photo_sha256",
  "portrait_sha256"}`. `index` is the order of the hex constants in the file; the hashes are SHA-256 of the 720
  input bytes, the 684-byte payload, the photo section, and the 50,000 upright portrait pixels. They let you check
  your decryption and portrait byte for byte.
- Negative tests to build from them: flip one bit in each block (`block_check_failed`); change the version bytes
  to `01 9b 09 46` (`unknown_version` from decrypt) or to the *other* version (`block_check_failed`: wrong key);
  drop the last byte (`wrong_length`).
