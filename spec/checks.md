# Checks after decoding

The card's block markers make a misread practically impossible to pass as data. These checks catch the rest: a
barcode that decodes but does not make sense, and a licence that belongs to someone else.

| Check | Rule | On failure |
|---|---|---|
| Licence number | 12 characters, the first 7 digits: `^\d{7}[0-9A-Z]{5}$` | reject (`licence_number_format`) |
| ID number | 13 digits with a valid Luhn check digit | reject (`id_number_check_digit`) |
| ID number and birth date | the ID number's first six digits, `yyMMdd`, equal the birth date | reject (`id_number_birth_date`) |
| Validity | valid from is before valid to | reject (`validity_order`) |
| Five years | a card normally runs five years (valid to = valid from + 5 years − 1 day) | flag, do not block (`validity_not_five_years`) |
| Vehicle codes | one of `A1`, `A`, `B`, `EB`, `C1`, `C`, `EC1`, `EC` | report the unknown code, do not block (`unknown_code`) |
| The person | the ID number is that of the person you are capturing the licence for | your decision; see below |

Findings are reported in the order of this table, by the codes in brackets; the four marked "reject" are
blocking. [test-vectors/checks.json](test-vectors/checks.json) tests them.

On the temporary licence, apply the licence number and vehicle code checks, and the Luhn check when the ID type is
`02`. It has no birth date or validity range in the barcode.

## The Luhn check for South African ID numbers

```
sum = 0
for k in 0..12:                         # from the rightmost digit
    d = digit at position 12 - k
    if k is odd: d = d * 2; if d > 9: d = d - 9
    sum += d
valid = (sum % 10 == 0)
```

`0001010000089` is valid; `0001010000088` is not.

## The licence number's structure (inferred; nothing is published)

- Characters 1 to 4: the testing centre's code.
- Characters 5 to 7: usually `000` (83% of typed numbers); `001` to `003` also occur.
- Characters 8 to 12: a serial number in base 30, alphabet `0123456789BCDFGHJKLMNPRSTVWXYZ` (10 digits and 20
  consonants: no vowels and no Q). It rises with the issue date.
- **There is no check character.** Luhn, ISO 7064 variants and a search over weighted sums all scored at chance.

That is why the barcode matters: reading the number from the card's print with OCR confuses `5/S`, `8/B`, `2/Z`,
`6/G`, and nothing in the number catches it. When we compared barcodes with what people had typed for 39 cards,
17 licence numbers differed (12 typing slips, 10 a different number altogether), and 30 of 39 PrDP entries were
incomplete (people typed `G` where the card says `G,P`).

## Is it the right person?

Compare the ID number with the person the licence is being captured for, when you know it. In our sample, 8 of 93
decoded ID numbers did not match the person on record: the wrong person's licence, or a typo in the person's record.
Do not silently overwrite the person's record with the barcode's ID number; flag it.

## Genuine is a different question

A card barcode that passes the markers was produced with the issuer's key, but it can be copied from someone
else's card. A temporary licence barcode has no protection at all. Decoding tells you what the document says, not
that the person holding it is its owner. If you need that, compare the portrait with the person.
