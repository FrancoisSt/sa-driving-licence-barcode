#!/usr/bin/env python3
"""South African driving-licence barcode decoder: a Python reference of spec/.

Standard library only. Decodes the raw PDF417 bytes of a licence card back (720 bytes, RSA with published public
keys) or of a paper temporary driving licence (plain text) into the canonical JSON of spec/output-format.md, and
decodes the card's portrait with the pure-Python codec in wi.py (spec/wi-codec.md), or optionally through the
native library in native/portrait (via ctypes).

    python3 sadl.py decode barcode.bin            # canonical JSON on stdout
    python3 sadl.py portrait barcode.bin out.png  # pure Python; --library to use libsadl_portrait instead

The input must be the barcode reader's raw bytes, never its text. The output holds personal data: do not log it.
"""
import argparse
import ctypes
import datetime
import json
import os
import re
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wi  # noqa: E402  (the pure-Python portrait codec, next to this file)

CARD_BARCODE_SIZE = 720
CARD_PAYLOAD_SIZE = 684

VERSIONS = {bytes.fromhex('01e10245'): 1, bytes.fromhex('019b0945'): 2}

# Published public keys (Stack Overflow 17549231, 2016): (modulus, exponent) for the five 128-byte blocks, then for
# the last 74-byte block. They only undo the encryption; they are not secrets.
KEYS = {
    1: ((int('fed2e1c27e3363316e77317a7a52c54981395186be4974760c72518d63e0544a'
             '48d088b332c5b0c370c765d65d983c1f9de0a42b310ccc07ae770bd2b61d6a4d'
             'cceac757689bdcbf608478faf312f6087cc496c3762cf5c4651caecda3499fae'
             '7edb7eb40e3e18eb304170e91ed5b156aace6f432d6eca6cc35851de8c678f67', 16),
         int('bb797ffdec7f9e42c9d6f79b137059db', 16)),
        (int('ff3cec6b5f40e3c3661451b9fcfaef3aeb06dc2329c0e6f4dccc9279726716ce'
             '15bbe05eed2c5711bcf8f5b6c8f7276db5c43bfaa3040dc01ab14b9c4d16f71c'
             '0ce5ea953f0c754c6b17', 16),
         int('db05ba822d9acc33fab7d8f427f9ce65', 16))),
    2: ((int('ca9f18ef6c3f3fa4c5a461fea54ab19406ba5ecd746d60a27492dca3d74e3b5c'
             '1d315f7b10383241809b029ebbd5de4d116030cc57f7d5a6c9a16f373bb14a50'
             '8523f7e80a4c744d9085663a4a1472d7af2c56ae41b5065f7efa0293bd3278ad'
             '693546f9f16219b79ff471a3636824cffcdb63a8ed8059e6b9a4f0db895381cb', 16),
         int('187092da6454ceb1853e6915f8466a05', 16)),
        (int('b404a0df11d1cacff1a1a048d4d573f953a62c583d74925927561a6d7a1e2b14'
             '042526af70b550547390ea6ec748d30fdb81adb490e0c36a1986b404b2f5f69e'
             'f5da1b663e59509130e7', 16),
         int('309cfed9719fe2a5e20c9bb44765382b', 16))),
}


class DecodeError(Exception):
    """Not a valid licence barcode. `reason` is one of the canonical error codes; the message holds no values."""

    def __init__(self, reason, message):
        super().__init__(message)
        self.reason = reason


def identify(raw):
    """'card', 'temporary_licence' or 'other', from the first bytes only."""
    raw = bytes(raw)
    if raw[:4] in VERSIONS:
        return 'card'
    if re.match(rb'%TDL\d\d%', raw[:8]):
        return 'temporary_licence'
    return 'other'


def decode(raw):
    """Raw barcode bytes to the canonical dict. Raises DecodeError."""
    raw = bytes(raw)
    kind = identify(raw)
    if kind == 'card':
        return parse_card(decrypt(raw), VERSIONS[raw[:4]])
    if kind == 'temporary_licence':
        return parse_temporary_licence(raw)
    raise DecodeError('not_a_licence', 'not a South African driving-licence barcode')


def decrypt(raw):
    """720 barcode bytes to the 684-byte payload: textbook RSA per block, then check and strip each block marker."""
    raw = bytes(raw)
    if len(raw) != CARD_BARCODE_SIZE:
        raise DecodeError('wrong_length', f'length {len(raw)}, expected {CARD_BARCODE_SIZE}')
    version = VERSIONS.get(raw[:4])
    if version is None:
        raise DecodeError('unknown_version', 'unknown version bytes')
    out = bytearray()
    for k in range(6):
        size = 128 if k < 5 else 74
        start = 6 + 128 * k
        n, e = KEYS[version][0 if k < 5 else 1]
        c = int.from_bytes(raw[start:start + size], 'big')
        if c >= n:
            raise DecodeError('block_check_failed', f'block {k + 1} outside the modulus')
        block = pow(c, e, n).to_bytes(size, 'big')
        if block[:5] != bytes((j << k) & 0x7F for j in range(1, 6)):
            raise DecodeError('block_check_failed', f'block {k + 1} marker mismatch')
        out += block[5:]
    return bytes(out)


def photo_section(payload):
    """The portrait section of a 684-byte payload, including its 'WI' header."""
    start = 10 + payload[5] + payload[7]
    size = ((payload[8] << 8) | payload[9]) & 0x0FFF
    if start + size > len(payload):
        raise DecodeError('malformed_payload', 'section lengths exceed the payload')
    return payload[start:start + size]


class _Nibbles:
    def __init__(self, data):
        self.v = [x for b in data for x in (b >> 4, b & 0x0F)]
        self.at = 0

    def _next(self):
        if self.at >= len(self.v):
            raise DecodeError('malformed_payload', 'ran out of nibbles')
        self.at += 1
        return self.v[self.at - 1]

    def pair(self):
        return f'{self._next():x}{self._next():x}'

    def date(self):
        if self.at < len(self.v) and self.v[self.at] == 0xA:
            self.at += 1
            return None
        d = [self._next() for _ in range(8)]
        if any(x > 9 for x in d):
            raise DecodeError('malformed_payload', 'date nibble above 9')
        year = int(''.join(map(str, d[:4])))
        if year < 1:
            raise DecodeError('malformed_payload', 'not a real date')
        try:
            return datetime.date(year, d[4] * 10 + d[5], d[6] * 10 + d[7]).isoformat()
        except ValueError:
            raise DecodeError('malformed_payload', 'not a real date') from None

    def done_or_pad(self):
        return self.at == len(self.v) or (self.at == len(self.v) - 1 and self.v[self.at] == 0xA)


def parse_card(p, version=2):
    """The 684-byte payload to the canonical dict."""
    if len(p) < 10:
        raise DecodeError('malformed_payload', 'payload too short')
    s1, s2, s3 = p[5], p[7], ((p[8] << 8) | p[9]) & 0x0FFF
    if 10 + s1 + s2 + s3 > len(p):
        raise DecodeError('malformed_payload', 'section lengths exceed the payload')

    # Section 1: 14 Latin-1 strings, then the 13-character ID number. 0xE0 ends a string; 0xE1 ends a string and
    # marks the next one empty (n x 0xE1 in a row = n + 1 string ends).
    strings, cur, end1, i = [], bytearray(), 10 + s1, 10
    while i < end1 and len(strings) < 14:
        c = p[i]
        if c in (0xE0, 0xE1):
            strings.append(cur.decode('latin-1'))
            cur = bytearray()
            if c == 0xE1 and i + 1 < end1 and p[i + 1] != 0xE1:
                strings.append('')
        else:
            cur.append(c)
        i += 1
    if len(strings) != 14:
        raise DecodeError('malformed_payload', f'{len(strings)} strings, expected 14')
    id_number = p[i:end1].decode('latin-1')

    # Section 2: nibbles, high nibble first.
    n = _Nibbles(p[end1:end1 + s2])
    id_type = n.pair()
    first_issue = [n.date() for _ in range(4)]
    driver_restrictions = n.pair()
    prdp_expiry = n.date()
    issue_number = n.pair()
    birth, valid_from, valid_to = n.date(), n.date(), n.date()
    gender = n.pair()
    if not n.done_or_pad():
        raise DecodeError('malformed_payload', 'nibbles left over')
    if None in (birth, valid_from, valid_to):
        raise DecodeError('malformed_payload', 'a required date is missing')

    return {
        'kind': 'card',
        'version': version,
        'licence_number': strings[13],
        'licence_country': strings[8],
        'surname': strings[4],
        'initials': strings[5],
        'id_number': id_number,
        'id_type': id_type,
        'id_country': strings[7],
        'birth_date': birth,
        'gender': gender,
        'valid_from': valid_from,
        'valid_to': valid_to,
        'issue_number': issue_number,
        'vehicle_codes': [{'code': strings[k], 'vehicle_restriction': strings[9 + k], 'first_issue': first_issue[k]}
                          for k in range(4) if strings[k]],
        'driver_restrictions': driver_restrictions,
        'prdp_categories': [x.strip() for x in strings[6].split(',') if x.strip()],
        'prdp_expiry': prdp_expiry,
        'photo_length': s3,
    }


def _iso(s):
    if not s:
        return None
    try:
        if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', s):
            raise ValueError
        return datetime.date.fromisoformat(s).isoformat()
    except ValueError:
        raise DecodeError('malformed_payload', 'not a yyyy-MM-dd date') from None


def _add_six_months(iso):
    d = datetime.date.fromisoformat(iso)
    month = d.month + 6
    year, month = d.year + (month - 1) // 12, (month - 1) % 12 + 1
    days = [31, 29 if year % 4 == 0 and (year % 100 != 0 or year % 400 == 0) else 28,
            31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
    return datetime.date(year, month, min(d.day, days)).isoformat()


def parse_temporary_licence(raw):
    """A temporary licence's plain-text barcode to the canonical dict."""
    text = raw.decode('latin-1')
    if not re.match(r'%TDL\d\d%', text):
        raise DecodeError('not_a_licence', 'not a temporary licence barcode')
    f = text.split('%')
    if len(f) < 16:
        raise DecodeError('malformed_payload', f'{len(f)} fields, expected 16')
    codes = []
    for k in range(9, 13):
        if f[k]:
            c = f[k].split('/') + ['', '']
            codes.append({'code': c[0], 'vehicle_restriction': c[2], 'first_issue': _iso(c[1])})
    prdp = f[13].split('/') + ['']
    issue = _iso(f[14])
    if issue is None:
        raise DecodeError('malformed_payload', 'no issue date')
    return {
        'kind': 'temporary_licence',
        'tag': f[1],
        'field2': f[2],
        'serial': f[3],
        'field4': f[4],
        'licence_number': f[5],
        'id_type': f[6],
        'id_number': f[7],
        'name': f[8],
        'vehicle_codes': codes,
        'prdp_categories': list(prdp[0]),
        'prdp_expiry': _iso(prdp[1]),
        'issue_date': issue,
        'valid_to': _add_six_months(issue),
    }


# ---------------------------------------------------------------------------------------------------------------------
# Checks after decoding (spec/checks.md). Each returns a list of finding codes; BLOCKING ones mean reject the read.

KNOWN_CODES = {'A1', 'A', 'B', 'EB', 'C1', 'C', 'EC1', 'EC'}
BLOCKING = {'licence_number_format', 'id_number_check_digit', 'id_number_birth_date', 'validity_order'}


def licence_number_looks_valid(s):
    return bool(re.fullmatch(r'\d{7}[0-9A-Z]{5}', s or ''))


def id_number_valid(id_number):
    if not re.fullmatch(r'\d{13}', id_number or ''):
        return False
    total = 0
    for k, c in enumerate(reversed(id_number)):
        d = int(c)
        if k % 2 == 1:
            d = d * 2 - 9 if d * 2 > 9 else d * 2
        total += d
    return total % 10 == 0


def check(licence):
    """Findings for a canonical card or temporary-licence dict, as in spec/checks.md."""
    found = []
    if not licence_number_looks_valid(licence['licence_number']):
        found.append('licence_number_format')
    if licence['kind'] == 'card':
        if not id_number_valid(licence['id_number']):
            found.append('id_number_check_digit')
        birth = licence['birth_date']
        if licence['id_number'][:6] != birth[2:4] + birth[5:7] + birth[8:10]:
            found.append('id_number_birth_date')
        start = datetime.date.fromisoformat(licence['valid_from'])
        end = datetime.date.fromisoformat(licence['valid_to'])
        if start >= end:
            found.append('validity_order')
        else:
            try:
                five = start.replace(year=start.year + 5)
            except ValueError:  # 29 February
                five = start.replace(year=start.year + 5, day=28)
            if end != five - datetime.timedelta(days=1):
                found.append('validity_not_five_years')
    elif licence['id_type'] == '02' and not id_number_valid(licence['id_number']):
        found.append('id_number_check_digit')
    if any(c['code'] not in KNOWN_CODES for c in licence['vehicle_codes']):
        found.append('unknown_code')
    return found


# ---------------------------------------------------------------------------------------------------------------------
# The portrait. By default the pure-Python codec in wi.py (spec/wi-codec.md); optionally the native library's C API
# (native/portrait/include/sadl_portrait.h) through ctypes.

PORTRAIT_WIDTH, PORTRAIT_HEIGHT = 200, 250


def _load_portrait_library(path=None):
    release = Path(__file__).resolve().parent.parent / 'native/portrait/target/release'
    if path is not None:
        candidates = [path]  # an explicit path is the only one tried
    else:
        candidates = [os.environ.get('SADL_PORTRAIT_LIB'), release / 'libsadl_portrait.so',
                      release / 'libsadl_portrait.dylib', release / 'sadl_portrait.dll']
    for c in candidates:
        if c and Path(c).is_file():
            lib = ctypes.CDLL(str(c))
            lib.sadl_portrait_decode.argtypes = [ctypes.c_char_p, ctypes.c_size_t, ctypes.c_char_p]
            lib.sadl_portrait_decode.restype = ctypes.c_int
            return lib
    raise FileNotFoundError('libsadl_portrait not found: run cargo build --release in native/portrait, '
                            'or set SADL_PORTRAIT_LIB')


def decode_portrait(wi_bytes, library=None):
    """WI bytes to 50,000 upright greyscale samples (200 x 250, row by row). Raises ValueError on failure.

    Uses the pure-Python codec (wi.py) unless `library` is given (a path to libsadl_portrait, or True to search the
    Rust release directory) or the SADL_PORTRAIT_LIB environment variable names the library: then the native C API
    is called through ctypes, and a missing library raises FileNotFoundError. wi.WiError, raised by the Python codec,
    is a ValueError whose .code is E1 to E5.
    """
    if library is None and not os.environ.get('SADL_PORTRAIT_LIB'):
        return wi.decode(wi_bytes)
    lib = _load_portrait_library(None if library is True else library)
    out = ctypes.create_string_buffer(PORTRAIT_WIDTH * PORTRAIT_HEIGHT)
    status = lib.sadl_portrait_decode(bytes(wi_bytes), len(wi_bytes), out)
    if status != 0:
        raise ValueError(f'portrait decode failed (status {status})')
    return out.raw


def png(pixels, width=PORTRAIT_WIDTH, height=PORTRAIT_HEIGHT):
    """8-bit greyscale samples to a PNG file's bytes."""
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    rows = b''.join(b'\0' + pixels[y * width:(y + 1) * width] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 0, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b''))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    d = sub.add_parser('decode', help='print the canonical JSON')
    d.add_argument('input', type=Path, help='raw barcode bytes (binary, not text or hex)')
    p = sub.add_parser('portrait', help='write the card portrait as a PNG')
    p.add_argument('input', type=Path, help='raw barcode bytes (720), or a 684-byte decrypted payload')
    p.add_argument('output', type=Path)
    p.add_argument('--library', help='path to libsadl_portrait (default: the pure-Python codec in wi.py)')
    args = parser.parse_args()
    raw = args.input.read_bytes()
    try:
        if args.command == 'decode':
            json.dump(decode(raw), sys.stdout, indent=2)
            print()
        else:
            payload = raw if len(raw) == CARD_PAYLOAD_SIZE else decrypt(raw)
            pixels = decode_portrait(photo_section(payload), args.library)
            with args.output.open('xb') as out:  # never overwrite an existing file
                out.write(png(pixels))
    except DecodeError as e:
        json.dump({'error': e.reason}, sys.stdout)
        print()
        sys.exit(1)
    except (ValueError, OSError) as e:
        sys.exit(str(e))


if __name__ == '__main__':
    main()
