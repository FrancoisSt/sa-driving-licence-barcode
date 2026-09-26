# Handling the data

A driving-licence barcode holds a person's name, ID number, birth date, gender and photo. In South Africa that is
personal information under POPIA; elsewhere it is covered by similar laws. Decide what you need before you build.

## Rules that cost nothing

- **Keep only the fields you need.** Most apps need the licence number, codes, validity and PrDP; few need the
  photo.
- **Never log decoded values, raw bytes or portraits.** Log outcomes: "decoded", "block_check_failed", "unknown
  version bytes 01 9b 09 46". The version bytes are the one thing worth logging: they tell you when a new card
  design arrives. The reference decoders' exceptions, and the Kotlin and C# results' `toString()`, carry no values for this reason
  (the Python reference returns plain dicts: do not print them).
- **Keep them out of crash reports, analytics and saved UI state.** Saved state can be written to disk by the
  system.
- **Protect the screen.** On Android, `FLAG_SECURE` on screens that show results.
- **Do not overwrite a person's record from a barcode.** Compare the barcode's ID number with the person you are
  capturing for, and flag a mismatch. In one real sample, 8 of 93 did not match: the wrong person's licence, or a
  typo in the record.

## When the result matters, decode again on the server

A phone can be tampered with. Send the **raw barcode bytes** (Base64) with the capture, and have the server decrypt,
check and parse them itself (the Kotlin decoder runs on any JVM, the C# library on .NET; the Python reference, or a port, elsewhere). Take
the values from the server's decode, not from what the phone sent. The server keeps the fields it needs and
discards the raw bytes.

This protects against a faulty or modified app. It does not prove the document is genuine: a card barcode can be
copied from another card, and a temporary licence barcode can be printed by anyone (see
[../spec/checks.md](../spec/checks.md)).

## Test data

- The synthetic vectors in [../spec/test-vectors](../spec/test-vectors) are made up and safe to use anywhere.
- The six public encrypted vectors (Reply.Net.SADL) hold what look like real people's details. Use them in tests
  only; do not commit them, ship them in an app, or print their decoded values. `scripts/fetch-test-vectors.sh`
  puts them in the git-ignored `third_party/`.
- Never commit a real licence's bytes, decoded fields or portrait, including your own. `.gitignore` excludes
  `*.bin`, `*.wi`, `*.pgm`, `*.png` and `*.jpg` to make that harder by accident.
- When testing on a device with a real card, read results from logs that carry outcomes only, not from
  screenshots.
