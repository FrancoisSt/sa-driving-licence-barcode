# The temporary driving licence barcode

The paper temporary driving licence (an A4 form, valid for six months while the card is made) has its own PDF417.
It is **plain text, not encrypted**: 103 to 122 bytes, fields separated by `%`.

**This layout is not published anywhere that we could find.** It was inferred from 57 real temporary licences.
Fields 1, 2 and 4 are not understood.

```
%TDLnn%dddd%<serial>%1%<licence number>%<ID type>%<ID number>%<initials and surname>%<code 1>%<code 2>%<code 3>%<code 4>%<PrDP>%<issue date>%
```

## Parsing

```
text = Latin-1 decode of the raw bytes
require text starts with "%TDL" + two digits + "%"          else error not_a_licence
f = split text on "%", keeping empty fields                  # f[0] is the empty string before the first %
require len(f) >= 16                                         else error malformed_payload
```

| `f[i]` | Content | Seen on real licences |
|---|---|---|
| 1 | `TDL` + 2 digits (`tag`) | the digits vary; meaning unknown |
| 2 | 4 digits (`field2`) | `0105` to `0110`; meaning unknown |
| 3 | The temporary licence's serial, 8 characters | the testing centre's 4 digits, a letter (mostly `A`), 3 characters |
| 4 | 1 digit (`field4`) | `1`; meaning unknown |
| 5 | **Licence number**, 12 characters | see below |
| 6 | ID type | `02` (54 of 57), `01` (3) |
| 7 | ID number, 13 digits | |
| 8 | Initials and surname, one string | `T TESTER` |
| 9 to 12 | Up to four codes, each `CODE/yyyy-MM-dd/RESTRICTION`; empty when unused | `EC/2010-01-01/0` |
| 13 | PrDP: `CATEGORIES/yyyy-MM-dd`; categories **without commas** (`GP`, `DGP`); empty when none | |
| 14 | Issue date, `yyyy-MM-dd` | required |
| 15 | empty: the text ends with `%` | |

- Code fields: split on `/`. The restriction uses the card's vehicle-restriction legend (`0` none, `1` automatic,
  `2` electrically powered, `3` physically disabled, `4` bus over 16 000 kg GVM). There is **no driver-restriction
  field** (glasses and so on) on the temporary licence.
- PrDP: each letter of the categories is one category, so `GP` is `[G, P]`.
- Dates must be real dates written exactly `yyyy-MM-dd`: four year digits, year 1 to 9999 (so not `0000-01-01`,
  not `+20250-01-01`). Anything else is `malformed_payload`.
- **Valid to = issue date + 6 months**, calculated: the barcode does not carry it. When the day does not exist in
  the target month, use the month's last day (31 August + 6 months = 28 or 29 February).

## The licence number is not the printed "No."

Field 5 is the number of the driving licence. The form also prints its own "No.", which differs from it by 4 to 6
characters. Use field 5. It was also not the number of the card that was issued later, in every case we could
compare (32 cards).

## Capturing it

The symbol is small on an A4 page. Photos of the whole page decoded only 9 of 45 times in our study; flat scans
did better (42 of 69). Ask for a close-up of the barcode. See [../docs/scanning.md](../docs/scanning.md).

## Trust

Unlike the card, this barcode has no integrity check at all: anyone can print one. Passing the parse means "well
formed", not "genuine". Checks that still help are in [checks.md](checks.md).
