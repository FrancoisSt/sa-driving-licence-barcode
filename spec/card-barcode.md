# The licence card barcode

The back of a South African driving-licence card carries one large PDF417 symbol. It holds **720 bytes**: a
4-byte version, 2 zero bytes and six RSA blocks. Decrypted with published *public* keys, the blocks give a
**684-byte payload** with the licence details and a small portrait.

This page is the whole format. Follow it in order; each step names the checks that make a misread fail
loudly instead of producing wrong data. Every rule here is exercised by the vectors in
[test-vectors/](test-vectors/).

```
PDF417 raw bytes (720)
  │ 1. identify: first 4 bytes = a known version
  ▼
  │ 2. decrypt: six blocks, m = c^e mod n, check + strip the 5-byte marker of each
  ▼
payload (684) = header (10) │ section 1: strings + ID │ section 2: nibbles │ section 3: photo │ zero padding
  │ 3. parse
  ▼
fields (canonical output: output-format.md)       photo ──► portrait.md
```

## Step 1: read the barcode, and keep the raw bytes

- Take the barcode reader's **raw bytes**, never its text. The payload is binary; a reader's text is an escaped
  or charset-decoded rendering, and converting it back is lossy. A good read is exactly 720 bytes.
  zxing-cpp gives bytes as `Barcode.bytes` (Python), `Result.bytes` (Android), `Barcode.bytes()` (C++).
- The symbol is **printed mirrored** (flipped along its horizontal axis). zxing-cpp reads it since
  [pull request #175](https://github.com/zxing-cpp/zxing-cpp/pull/175) (November 2020): "This situation is
  apparently common with ZA driver licenses." ML Kit reads it too. Test any other reader on a real card before
  relying on it.
- See [../docs/scanning.md](../docs/scanning.md) for camera settings, and for reading scans and photos.

## Step 2: identify

| Bytes 0 to 3 | Meaning |
|---|---|
| `01 E1 02 45` | Card barcode, version 1 |
| `01 9B 09 45` | Card barcode, version 2 (every card seen so far) |
| starts with the text `%TDL` + 2 digits + `%` | A temporary licence: see [temporary-licence.md](temporary-licence.md) |
| anything else | Not a driving licence (a vehicle licence disc starts `%MVL1CC`, an ID card is plain text) |

Identify from the first bytes only, before any length check, so a truncated card read is reported as a
`wrong_length` card and not as "some other barcode".

## Step 3: decrypt the six blocks

The 720 bytes:

| Offset | Length | Content |
|---|---|---|
| 0 | 4 | Version |
| 4 | 2 | `00 00` |
| 6 | 128 | RSA block 1 |
| 134 | 128 | RSA block 2 |
| 262 | 128 | RSA block 3 |
| 390 | 128 | RSA block 4 |
| 518 | 128 | RSA block 5 |
| 646 | 74 | RSA block 6 |

Each version has two public keys: one for the five 128-byte blocks, one for the 74-byte block. They were
published on Stack Overflow in 2016 and every decoder uses them. They only *undo* the encryption: they are not
secrets. As PEM (PKCS#1) they are in [keys/](keys/); as the modulus `n` and exponent `e` in hex:

**Version 1**

```
blocks 1-5  n = fed2e1c27e3363316e77317a7a52c54981395186be4974760c72518d63e0544a
                48d088b332c5b0c370c765d65d983c1f9de0a42b310ccc07ae770bd2b61d6a4d
                cceac757689bdcbf608478faf312f6087cc496c3762cf5c4651caecda3499fae
                7edb7eb40e3e18eb304170e91ed5b156aace6f432d6eca6cc35851de8c678f67
            e = bb797ffdec7f9e42c9d6f79b137059db
block 6     n = ff3cec6b5f40e3c3661451b9fcfaef3aeb06dc2329c0e6f4dccc9279726716ce
                15bbe05eed2c5711bcf8f5b6c8f7276db5c43bfaa3040dc01ab14b9c4d16f71c
                0ce5ea953f0c754c6b17
            e = db05ba822d9acc33fab7d8f427f9ce65
```

**Version 2**

```
blocks 1-5  n = ca9f18ef6c3f3fa4c5a461fea54ab19406ba5ecd746d60a27492dca3d74e3b5c
                1d315f7b10383241809b029ebbd5de4d116030cc57f7d5a6c9a16f373bb14a50
                8523f7e80a4c744d9085663a4a1472d7af2c56ae41b5065f7efa0293bd3278ad
                693546f9f16219b79ff471a3636824cffcdb63a8ed8059e6b9a4f0db895381cb
            e = 187092da6454ceb1853e6915f8466a05
block 6     n = b404a0df11d1cacff1a1a048d4d573f953a62c583d74925927561a6d7a1e2b14
                042526af70b550547390ea6ec748d30fdb81adb490e0c36a1986b404b2f5f69e
                f5da1b663e59509130e7
            e = 309cfed9719fe2a5e20c9bb44765382b
```

The algorithm:

```
require len(raw) == 720                                   else error wrong_length
keys = KEYS[version(raw)]                                 else error unknown_version
payload = []
for k in 0..5:
    size  = 128 if k < 5 else 74
    start = 6 + 128 * k
    (n, e) = keys.blocks_1_5 if k < 5 else keys.block_6
    c = unsigned big-endian integer of raw[start : start + size]
    require c < n                                         else error block_check_failed
    m = c^e mod n                                          # modular exponentiation, nothing else
    block = m as exactly `size` big-endian bytes (left-pad with zeros)
    for j in 1..5:
        require block[j - 1] == (j << k) & 0x7F           else error block_check_failed
    payload += block[5 : size]                             # 123 bytes, or 69 from block 6
# len(payload) == 5 * 123 + 69 == 684
```

The markers each decrypted block must start with, `(j << k) & 0x7F` for `j = 1..5` in block `k = 0..5`:

| Block | Size | First five bytes |
|---|---|---|
| 1 | 128 | `01 02 03 04 05` |
| 2 | 128 | `02 04 06 08 0a` |
| 3 | 128 | `04 08 0c 10 14` |
| 4 | 128 | `08 10 18 20 28` |
| 5 | 128 | `10 20 30 40 50` |
| 6 | 74 | `20 40 60 00 20` |

**Pitfalls, each seen in a public decoder:**

- **Textbook RSA, no padding.** Do not call a crypto library's "decrypt" or "verify": they expect PKCS#1 v1.5
  or OAEP padding and will fail or throw. Use plain modular exponentiation (`BigInteger.modPow`, Python
  `pow(c, e, n)`, `BigInt` square-and-multiply in JavaScript, `System.Numerics.BigInteger.ModPow` in C#,
  `math/big` `Exp` in Go, `num-bigint` `modpow` in Rust, OpenSSL `BN_mod_exp`).
- **Unsigned in.** Java and C# big integers are signed. In Java use `new BigInteger(1, bytes)`; in C#
  `new BigInteger(bytes, isUnsigned: true, isBigEndian: true)`. Without that, a block whose first bit is set
  becomes negative and decrypts to garbage. One public C# decoder has exactly this bug.
- **Fixed length out.** Java's `toByteArray()` adds a leading zero sign byte or drops leading zero bytes.
  Normalise every decrypted block to exactly 128 or 74 bytes before checking the marker.
- **The markers are the integrity check.** 30 known bytes: a misread, a wrong key or a tampered barcode cannot
  pass them by chance. On `block_check_failed`, discard the read and keep scanning. There is no other signature.
- **Constant exponents, not 65537.** The public exponents are 125 to 128 bits, so the exponentiation takes a few
  milliseconds; that is expected.

## Step 4: parse the 684-byte payload

### Header: bytes 0 to 9

| Byte | Meaning |
|---|---|
| 0 | `0x02` on every card seen |
| 5 | `s1`, the length of section 1 (strings and ID number) |
| 7 | `s2`, the length of section 2 (nibbles) |
| 8, 9 | `s3`, the length of section 3 (photo): `((byte8 << 8) \| byte9) & 0x0FFF` |
| 1 to 4, 6 | Not understood; ignore |

Section 1 starts at byte 10, section 2 at `10 + s1`, section 3 at `10 + s1 + s2`. Reject the payload
(`malformed_payload`) if `10 + s1 + s2 + s3 > 684`. The bytes after section 3 are zero padding.

### Section 1: 14 strings, then the ID number

Strings are **Latin-1** (ISO 8859-1: each byte is one character). Two bytes end a string:

- `0xE0` ends a string.
- `0xE1` ends a string **and marks the next one as empty**. So `n` consecutive `0xE1` bytes stand for
  `n + 1` string ends: the string before them, then `n` empty strings.

```
strings = []; current = []
i = 10; end = 10 + s1
while i < end and len(strings) < 14:
    b = payload[i]
    if b == 0xE0:
        strings.append(current); current = []
    elif b == 0xE1:
        strings.append(current); current = []
        if i + 1 < end and payload[i + 1] != 0xE1:
            strings.append("")                       # the empty string this 0xE1 announced
    else:
        current.append(b)
    i += 1
require len(strings) == 14                            else error malformed_payload
id_number = Latin-1 text of payload[i : end]          # 13 characters, no delimiter
```

Reading a run: `EC E1 E1 E1 TESTER` is `"EC"`, `""`, `""`, `""`, then `TESTER` starts. The inner `0xE1`s are
followed by another `0xE1`, so they add their own end but not an extra empty string; the last one is
followed by data, so it adds one. The net effect is `n + 1` ends for `n` bytes.

| # | String | Example (synthetic) |
|---|---|---|
| 1 to 4 | Vehicle codes, one per position; empty when unused | `EC`, then three empty |
| 5 | Surname | `TESTER` |
| 6 | Initials | `T` |
| 7 | PrDP (professional driving permit) categories, comma-separated; empty when none | `G,P` |
| 8 | ID country | `ZA` |
| 9 | Licence country | `ZA` |
| 10 to 13 | Vehicle restriction for code positions 1 to 4 | `0`, then three empty |
| 14 | Licence number | `0000000000BC` |
| then | ID number, 13 characters | `0001010000089` |

**Count all 14.** Some public decoders forget string 7 (PrDP). When a PrDP is present, every later field
shifts by one, and those decoders return the ID country as the PrDP, and so on. Most professional drivers have a
PrDP, so this bug shows up quickly on real cards and never on test cards without one.

Vehicle restrictions: `0` none, `1` automatic transmission, `2` electrically powered, `3` physically disabled,
`4` bus over 16 000 kg (GVM) permitted.

### Section 2: nibbles

Read the `s2` bytes as 4-bit nibbles, **high nibble first**. Two kinds of value:

- **pair**: two nibbles, written as two lower-case hex digits (`"02"`).
- **date**: eight nibbles, each 0 to 9, spelling `yyyyMMdd`. **A single `0xA` nibble instead means "no date"**
  and takes one nibble only. A digit above 9, year 0000, or a date that does not exist (such as 2025-02-30) is
  `malformed_payload`.

In order:

| # | Field | Kind | Values |
|---|---|---|---|
| 1 | ID type | pair | `02` = South African ID number |
| 2 to 5 | First-issue date for code positions 1 to 4 | date | pairs with the code in the same position |
| 6 | Driver restrictions | pair | one restriction per nibble: `0` none, `1` glasses or contact lenses, `2` artificial limb (`10` = glasses) |
| 7 | PrDP expiry | date | `A` when there is no PrDP |
| 8 | Licence issue number | pair | `01` seen |
| 9 | Birth date | date | required |
| 10 | Valid from | date | required |
| 11 | Valid to | date | required; cards run five years |
| 12 | Gender | pair | `01` male, `02` female |

After field 12 there may be one `0xA` nibble of padding (when the count is odd). **Anything else left over means
the parse went wrong**: `malformed_payload`. Running out of nibbles early is also `malformed_payload`.

Example, from the synthetic vector: `02 | 20100101 | A | A | A | 00 | 20270101 | 01 | 20000101 | 20250101 |
20291231 | 02 | A(pad)`.

### Section 3: the photo

`s3` bytes starting with `WI` (`57 49`): a Summus wavelet image, 550 to 603 bytes on the cards seen. It decodes to
a 200 × 250 greyscale portrait: see [portrait.md](portrait.md). A decoder that only needs the fields can skip it,
but should report its length (`photo_length`).

## What the fields mean, and what to check

- Pair each vehicle code with the restriction and first-issue date **in the same position** (1 to 4), and skip
  empty positions. The codes in use are `A1`, `A`, `B`, `EB`, `C1`, `C`, `EC1`, `EC`.
- The licence's "first issue" in the everyday sense is the earliest code date.
- Checks after decoding are in [checks.md](checks.md). On real cards, every card that passed the block markers
  also passed every field check.

## Not yet known

- Version 1 cards: the keys are published but no version 1 card has been seen to test them.
- A new card design has been announced. It may change the barcode or the keys. Treat unknown version bytes as
  "cannot read yet", and log the four version bytes (never anything else) so you notice when it arrives.
- Header bytes 1 to 4 and 6.
- The `0xE1` rule is inferred; it holds on every card and vector seen.
