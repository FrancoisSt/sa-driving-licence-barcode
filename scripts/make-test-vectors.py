#!/usr/bin/env python3
"""Regenerates spec/test-vectors/parse-card.json and decode.json from made-up licences.

The vectors are built by *encoding* fake field values into the layout of spec/card-barcode.md, and the expected output
is written from those same values, not from a decoder. Every value is synthetic. Standard library only.

    python3 scripts/make-test-vectors.py
"""
import json
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / 'spec/test-vectors'
PAYLOAD_SIZE = 684


def luhn_complete(first12):
    """Appends the Luhn check digit to 12 digits."""
    for check in '0123456789':
        digits = [int(c) for c in first12 + check]
        total = 0
        for k, d in enumerate(reversed(digits)):
            if k % 2 == 1:
                d *= 2
                d = d - 9 if d > 9 else d
            total += d
        if total % 10 == 0:
            return first12 + check
    raise AssertionError


def encode_strings(strings):
    """14 strings to section-1 bytes: 0xE0 after a string, or n x 0xE1 when n empty strings follow it."""
    out, k = bytearray(), 0
    while k < len(strings):
        out += strings[k].encode('latin-1')
        empties = 0
        while k + 1 + empties < len(strings) and strings[k + 1 + empties] == '':
            empties += 1
        out += bytes([0xE1]) * empties if empties else bytes([0xE0])
        k += 1 + empties
    return bytes(out)


def encode_nibbles(fields):
    """A list of ('pair', 'hh') / ('date', 'yyyy-mm-dd' or None) to section-2 bytes, padded with 0xA."""
    nib = []
    for kind, value in fields:
        if kind == 'pair':
            nib += [int(c, 16) for c in value]
        elif value is None:
            nib.append(0xA)
        else:
            nib += [int(c) for c in value.replace('-', '')]
    if len(nib) % 2:
        nib.append(0xA)
    return bytes((nib[i] << 4) | nib[i + 1] for i in range(0, len(nib), 2))


def card(v):
    """Field values to (payload bytes, canonical expected dict)."""
    codes = v['codes'] + [None] * (4 - len(v['codes']))
    strings = ([c['code'] if c else '' for c in codes]
               + [v['surname'], v['initials'], ','.join(v['prdp']), v['id_country'], v['licence_country']]
               + [c['restriction'] if c else '' for c in codes]
               + [v['licence_number']])
    s1 = encode_strings(strings) + v['id_number'].encode('latin-1')
    s2 = encode_nibbles([('pair', v['id_type'])] + [('date', c['first_issue'] if c else None) for c in codes]
                        + [('pair', v['driver_restrictions']), ('date', v['prdp_expiry']), ('pair', v['issue_number']),
                           ('date', v['birth_date']), ('date', v['valid_from']), ('date', v['valid_to']),
                           ('pair', v['gender'])])
    # The photo: a WI header (format 4, 250 high, 200 wide) and zeros. It is not a decodable image.
    # Real photos are 550 to 603 bytes; a card with more text leaves less room.
    s3 = bytes.fromhex('57490400fa00c8').ljust(min(594, PAYLOAD_SIZE - 10 - len(s1) - len(s2)), b'\0')
    header = bytes([0x02, 0, 0, 0, 0, len(s1), 0, len(s2), len(s3) >> 8, len(s3) & 0xFF])
    payload = (header + s1 + s2 + s3).ljust(PAYLOAD_SIZE, b'\0')
    assert len(payload) == PAYLOAD_SIZE
    expected = {
        'kind': 'card',
        'version': 2,
        'licence_number': v['licence_number'],
        'licence_country': v['licence_country'],
        'surname': v['surname'],
        'initials': v['initials'],
        'id_number': v['id_number'],
        'id_type': v['id_type'],
        'id_country': v['id_country'],
        'birth_date': v['birth_date'],
        'gender': v['gender'],
        'valid_from': v['valid_from'],
        'valid_to': v['valid_to'],
        'issue_number': v['issue_number'],
        'vehicle_codes': [{'code': c['code'], 'vehicle_restriction': c['restriction'], 'first_issue': c['first_issue']}
                          for c in v['codes']],
        'driver_restrictions': v['driver_restrictions'],
        'prdp_categories': v['prdp'],
        'prdp_expiry': v['prdp_expiry'],
        'photo_length': len(s3),
    }
    return payload, expected


BASE = {
    'codes': [{'code': 'EC', 'restriction': '0', 'first_issue': '2010-01-01'}],
    'surname': 'TESTER', 'initials': 'T', 'prdp': ['G', 'P'], 'id_country': 'ZA', 'licence_country': 'ZA',
    'licence_number': '0000000000BC', 'id_number': '0001010000089', 'id_type': '02', 'driver_restrictions': '00',
    'prdp_expiry': '2027-01-01', 'issue_number': '01', 'birth_date': '2000-01-01', 'valid_from': '2025-01-01',
    'valid_to': '2029-12-31', 'gender': '02',
}

CARDS = [
    ('one-code-with-prdp', 'One code (EC) and a PrDP: the payload from the original research write-up.', BASE),
    ('two-codes-no-prdp', 'Two codes with their own dates and restrictions, no PrDP (empty string 7, no expiry), '
     'glasses (driver restrictions 10), male.', {
         **BASE,
         'codes': [{'code': 'B', 'restriction': '1', 'first_issue': '2005-06-15'},
                   {'code': 'EC', 'restriction': '0', 'first_issue': '2012-03-01'}],
         'surname': 'EXAMPLE', 'initials': 'AB', 'prdp': [], 'prdp_expiry': None, 'driver_restrictions': '10',
         'licence_number': '0000000001CD', 'birth_date': '1985-06-15',
         'id_number': luhn_complete('850615500008'), 'gender': '01',
         'valid_from': '2021-07-01', 'valid_to': '2026-06-30'}),
    ('four-codes', 'All four code positions used, PrDP D,G,P, and an even nibble count (no padding).', {
        **BASE,
        'codes': [{'code': 'A', 'restriction': '0', 'first_issue': '2001-02-03'},
                  {'code': 'B', 'restriction': '0', 'first_issue': '2002-03-04'},
                  {'code': 'C1', 'restriction': '1', 'first_issue': '2003-04-05'},
                  {'code': 'EC', 'restriction': '4', 'first_issue': '2004-05-06'}],
        'surname': 'SAMPLE', 'initials': 'CDE', 'prdp': ['D', 'G', 'P'], 'licence_number': '0000000002FG',
        'birth_date': '1980-12-31', 'id_number': luhn_complete('801231000008'),
        'valid_from': '2023-01-15', 'valid_to': '2028-01-14'}),
    ('leap-day-and-latin-1', 'A 29 February birth date and a Latin-1 surname (0xDC is U+00DC).', {
        **BASE,
        'surname': 'MÜLLER', 'birth_date': '2004-02-29', 'id_number': luhn_complete('040229000008'),
        'licence_number': '0000000003HJ'}),
]


def reject(name, description, payload, error):
    return {'name': name, 'description': description, 'payload_hex': payload.hex(), 'expected': {'error': error}}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for name, description, values in CARDS:
        payload, expected = card(values)
        cases.append({'name': name, 'description': description, 'payload_hex': payload.hex(), 'expected': expected})

    good, _ = card(BASE)
    s1, s2 = good[5], good[7]
    bad = bytearray(good); bad[8:10] = b'\x0f\xff'
    cases.append(reject('sections-exceed-payload', 'Section 3 claims 4095 bytes.', bytes(bad), 'malformed_payload'))
    bad = bytearray(good); bad[10 + s1 + 1] = 0x2B  # section 2 is 02 | 2010 0101 ...: the year's 2nd digit becomes 0xB
    cases.append(reject('date-nibble-above-9', 'A digit of the first code date is 0xB.', bytes(bad), 'malformed_payload'))
    bad = bytearray(good)
    # Section 2 starts 02 | 2010 0101 ...: make the first code date 2010-02-30.
    bad[10 + s1 + 3] = 0x02; bad[10 + s1 + 4] = 0x30
    cases.append(reject('not-a-real-date', 'The first code date is 2010-02-30.', bytes(bad), 'malformed_payload'))
    bad = bytearray(good); bad[7] = s2 + 1; bad[9] -= 1  # the first photo byte becomes section-2 nibbles
    cases.append(reject('nibbles-left-over', 'Section 2 is one byte too long.', bytes(bad), 'malformed_payload'))
    strings = encode_strings(['EC', 'B', 'C1', 'A', 'TESTER', 'T', 'G,P', 'ZA', 'ZA', '0', '0', '0', '0'])
    short = bytes([0x02, 0, 0, 0, 0, len(strings), 0, 0, 0, 0]) + strings
    cases.append(reject('thirteen-strings', 'Section 1 holds only 13 strings.', short.ljust(PAYLOAD_SIZE, b'\0'),
                        'malformed_payload'))
    cases.append(reject('payload-too-short', 'Nine bytes.', good[:9], 'malformed_payload'))
    bad = bytearray(good)
    # Section 2 starts 02 | 2010 0101: make the first code date 0000-01-01 (a real-looking day in year 0).
    bad[10 + s1 + 1] = 0x00; bad[10 + s1 + 2] = 0x00
    cases.append(reject('year-zero', 'The first code date is 0000-01-01: years run from 1 to 9999.', bytes(bad),
                        'malformed_payload'))
    write('parse-card.json', 'Inputs: the 684-byte payload that decryption returns (header, sections 1 to 3). '
          'Feed payload_hex to the parse step directly; expected is the canonical output or error '
          '(spec/output-format.md).', cases)

    v2 = bytes.fromhex('019b0945') + b'\0\0'
    decode = [
        {'name': 'temporary-licence', 'description': 'A synthetic temporary licence.',
         'raw_text': '%TDL01%0105%0000A00B%1%0000000000BC%02%0001010000089%T TESTER%EC/2010-01-01/0%%%%GP/2027-01-01%2025-01-01%',
         'expected': {'kind': 'temporary_licence', 'tag': 'TDL01', 'field2': '0105', 'serial': '0000A00B', 'field4': '1',
                      'licence_number': '0000000000BC', 'id_type': '02', 'id_number': '0001010000089',
                      'name': 'T TESTER',
                      'vehicle_codes': [{'code': 'EC', 'vehicle_restriction': '0', 'first_issue': '2010-01-01'}],
                      'prdp_categories': ['G', 'P'], 'prdp_expiry': '2027-01-01', 'issue_date': '2025-01-01',
                      'valid_to': '2025-07-01'}},
        {'name': 'temporary-licence-two-codes-no-prdp', 'description': 'Two codes, no PrDP; valid_to is clamped '
         'to the end of the month (31 August + 6 months = 28 February).',
         'raw_text': '%TDL02%0110%1234A56C%1%0000000001CD%02%' + luhn_complete('850615500008')
                     + '%AB EXAMPLE%B/2005-06-15/1%C1/2015-09-01/0%%%%2025-08-31%',
         'expected': {'kind': 'temporary_licence', 'tag': 'TDL02', 'field2': '0110', 'serial': '1234A56C', 'field4': '1',
                      'licence_number': '0000000001CD', 'id_type': '02',
                      'id_number': luhn_complete('850615500008'), 'name': 'AB EXAMPLE',
                      'vehicle_codes': [{'code': 'B', 'vehicle_restriction': '1', 'first_issue': '2005-06-15'},
                                        {'code': 'C1', 'vehicle_restriction': '0', 'first_issue': '2015-09-01'}],
                      'prdp_categories': [], 'prdp_expiry': None, 'issue_date': '2025-08-31',
                      'valid_to': '2026-02-28'}},
        {'name': 'temporary-licence-too-few-fields', 'description': 'The text stops after the name.',
         'raw_text': '%TDL01%0105%0000A00B%1%0000000000BC%02%0001010000089%T TESTER%',
         'expected': {'error': 'malformed_payload'}},
        {'name': 'temporary-licence-bad-date', 'description': 'The issue date is 2025-13-01.',
         'raw_text': '%TDL01%0105%0000A00B%1%0000000000BC%02%0001010000089%T TESTER%EC/2010-01-01/0%%%%GP/2027-01-01%2025-13-01%',
         'expected': {'error': 'malformed_payload'}},
        {'name': 'temporary-licence-year-zero', 'description': 'The issue date is 0000-01-01: years run from 1 to 9999.',
         'raw_text': '%TDL01%0105%0000A00B%1%0000000000BC%02%0001010000089%T TESTER%EC/2010-01-01/0%%%%GP/2027-01-01%0000-01-01%',
         'expected': {'error': 'malformed_payload'}},
        {'name': 'temporary-licence-five-digit-year', 'description': 'The issue date is +20250-01-01: exactly four year digits.',
         'raw_text': '%TDL01%0105%0000A00B%1%0000000000BC%02%0001010000089%T TESTER%EC/2010-01-01/0%%%%GP/2027-01-01%+20250-01-01%',
         'expected': {'error': 'malformed_payload'}},
        {'name': 'vehicle-licence-disc', 'description': 'A vehicle licence disc starts %MVL1CC: not a driving licence.',
         'raw_text': '%MVL1CC01%0120%4025T00D%1%40250000ABC1%XYZ123GP%', 'expected': {'error': 'not_a_licence'}},
        {'name': 'card-719-bytes', 'description': 'Version 2 bytes, then one byte short.',
         'raw_hex': (v2 + bytes(713)).hex(), 'expected': {'error': 'wrong_length'}},
        {'name': 'card-721-bytes', 'description': 'Version 2 bytes, then one byte too many.',
         'raw_hex': (v2 + bytes(715)).hex(), 'expected': {'error': 'wrong_length'}},
        {'name': 'card-unknown-version', 'description': '720 bytes whose first four are 01 9b 09 46. '
         'identify() says "other", so decode() reports not_a_licence; decrypt() on its own reports unknown_version.',
         'raw_hex': (bytes.fromhex('019b0946') + bytes(716)).hex(), 'expected': {'error': 'not_a_licence'}},
        {'name': 'card-random-ciphertext', 'description': 'Version 2 and 714 bytes of a fixed pattern: the first block '
         'does not decrypt to its marker.',
         'raw_hex': (v2 + bytes((i * 37 + 11) % 251 for i in range(714))).hex(),
         'expected': {'error': 'block_check_failed'}},
    ]
    write_checks()
    write('decode.json', 'Inputs: raw barcode bytes, as raw_hex (binary) or raw_text (Latin-1 text of a temporary '
          'licence). Run the whole pipeline (identify, decrypt, parse); expected is the canonical output or error '
          '(spec/output-format.md).', decode)


def write_checks():
    """spec/test-vectors/checks.json: canonical licences in, findings out (spec/checks.md)."""
    cases = []
    for name, _, values in CARDS:
        cases.append({'name': name, 'licence': card(values)[1], 'expected': []})
    _, base = card(BASE)
    variants = [
        ('licence-number-format', 'A letter among the first seven characters.', {'licence_number': '000000A000BC'}, ['licence_number_format']),
        ('id-check-digit', 'The last digit of the ID number is off by one.', {'id_number': '0001010000088'}, ['id_number_check_digit']),
        ('id-birth-date', 'A valid ID number for another birth date.', {'id_number': luhn_complete('010101000008')}, ['id_number_birth_date']),
        ('validity-order', 'Valid to before valid from.', {'valid_from': '2029-12-31', 'valid_to': '2025-01-01'}, ['validity_order']),
        ('not-five-years', 'Valid for five years and a day: a warning, not a rejection.', {'valid_to': '2030-01-01'}, ['validity_not_five_years']),
        ('unknown-code', 'A vehicle code outside the known list: a warning.',
         {'vehicle_codes': [{'code': 'XX', 'vehicle_restriction': '0', 'first_issue': '2010-01-01'}]}, ['unknown_code']),
    ]
    for name, description, change, expected in variants:
        cases.append({'name': name, 'description': description, 'licence': {**base, **change}, 'expected': expected})
    tdl = {'kind': 'temporary_licence', 'licence_number': '0000000000BC', 'id_type': '02', 'id_number': '0001010000089',
           'vehicle_codes': [{'code': 'EC', 'vehicle_restriction': '0', 'first_issue': '2010-01-01'}]}
    cases.append({'name': 'temporary-licence', 'description': 'Only the licence number, the Luhn check (ID type 02) '
                  'and the codes apply; only the keys used are given.', 'licence': tdl, 'expected': []})
    cases.append({'name': 'temporary-licence-bad-id', 'licence': {**tdl, 'id_number': '0001010000088'},
                  'expected': ['id_number_check_digit']})
    write('checks.json', 'Inputs: a licence in the canonical form (spec/output-format.md). Expected: the findings of '
          'spec/checks.md, in the order of its table. Blocking: licence_number_format, id_number_check_digit, '
          'id_number_birth_date, validity_order. Warnings: validity_not_five_years, unknown_code.', cases)


def write(name, about, cases):
    doc = {'about': about, 'all_values_are_synthetic': True, 'cases': cases}
    (OUT / name).write_text(json.dumps(doc, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f'{name}: {len(cases)} cases')


if __name__ == '__main__':
    main()
