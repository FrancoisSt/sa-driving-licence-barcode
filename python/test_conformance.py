"""Runs the Python reference against spec/test-vectors/. python3 -m unittest discover -s python

The public-vector tests are skipped until scripts/fetch-test-vectors.sh has run, and the native portrait test until
native/portrait is built. Failures report field names, never decoded values.
"""
import hashlib
import json
import unittest
from pathlib import Path

import public_vectors
import sadl

VECTORS = Path(__file__).resolve().parent.parent / 'spec/test-vectors'


def run(fn, data):
    try:
        return fn(data)
    except sadl.DecodeError as e:
        return {'error': e.reason}


def differing(got, expected):
    return sorted(k for k in set(got) | set(expected) if got.get(k) != expected.get(k))


class SyntheticVectors(unittest.TestCase):
    def test_parse_card(self):
        for case in json.loads((VECTORS / 'parse-card.json').read_text())['cases']:
            with self.subTest(case['name']):
                got = run(sadl.parse_card, bytes.fromhex(case['payload_hex']))
                self.assertEqual(got, case['expected'], differing(got, case['expected']))

    def test_decode(self):
        for case in json.loads((VECTORS / 'decode.json').read_text())['cases']:
            with self.subTest(case['name']):
                raw = bytes.fromhex(case['raw_hex']) if 'raw_hex' in case else case['raw_text'].encode('latin-1')
                got = run(sadl.decode, raw)
                self.assertEqual(got, case['expected'], differing(got, case['expected']))


    def test_checks(self):
        for case in json.loads((VECTORS / 'checks.json').read_text())['cases']:
            with self.subTest(case['name']):
                self.assertEqual(sadl.check(case['licence']), case['expected'])

    def test_bytearray_input(self):
        case = json.loads((VECTORS / 'decode.json').read_text())['cases'][0]
        self.assertEqual(sadl.decode(bytearray(case['raw_text'].encode('latin-1'))), case['expected'])


class PublicVectors(unittest.TestCase):
    def setUp(self):
        self.vectors = public_vectors.load()
        if self.vectors is None:
            self.skipTest('run scripts/fetch-test-vectors.sh')
        self.hashes = json.loads((VECTORS / 'public-vectors.json').read_text())['cases']

    def test_the_24_expected_values(self):
        self.assertEqual(len(self.vectors), 6)
        for i, (raw, expected) in enumerate(self.vectors, 1):
            got = sadl.decode(raw)
            self.assertFalse(set(sadl.check(got)) & sadl.BLOCKING, f'vector {i}: a blocking check failed')
            for field in expected:
                with self.subTest(vector=i, field=field):
                    self.assertTrue(got[field] == expected[field], f'vector {i}: {field} differs')

    def test_payload_hashes(self):
        for (raw, _), h in zip(self.vectors, self.hashes):
            self.assertEqual(hashlib.sha256(raw).hexdigest(), h['raw_sha256'])
            payload = sadl.decrypt(raw)
            self.assertEqual(hashlib.sha256(payload).hexdigest(), h['payload_sha256'], f"vector {h['index']}")
            self.assertEqual(hashlib.sha256(sadl.photo_section(payload)).hexdigest(), h['photo_sha256'])

    def test_every_flipped_block_is_rejected(self):
        for i, (raw, _) in enumerate(self.vectors, 1):
            for k in range(6):
                bad = bytearray(raw)
                bad[6 + 128 * k + 40] ^= 0x01
                with self.subTest(vector=i, block=k + 1):
                    self.assertTrue(run(sadl.decrypt, bytes(bad)) == {'error': 'block_check_failed'},
                                    f'vector {i} block {k + 1}')

    def test_wrong_version_and_length_are_rejected(self):
        # assertTrue, not assertEqual: a failure must not print a decrypted payload.
        for i, (raw, _) in enumerate(self.vectors, 1):
            got = run(sadl.decrypt, b'\x01\x9b\x09\x46' + raw[4:])
            self.assertTrue(got == {'error': 'unknown_version'}, f'vector {i}: other version bytes')
            self.assertTrue(run(sadl.decrypt, raw[:719]) == {'error': 'wrong_length'}, f'vector {i}: 719 bytes')
            # Version 1 bytes on a version 2 card: the wrong key, so the markers fail.
            got = run(sadl.decrypt, bytes.fromhex('01e10245') + raw[4:])
            self.assertTrue(got == {'error': 'block_check_failed'}, f'vector {i}: version 1 bytes')

    def test_portraits(self):
        # The default path: the pure-Python codec of wi.py.
        for (raw, _), h in zip(self.vectors, self.hashes):
            pixels = sadl.decode_portrait(sadl.photo_section(sadl.decrypt(raw)))
            self.assertEqual(hashlib.sha256(pixels).hexdigest(), h['portrait_sha256'], f"vector {h['index']}")

    def test_portraits_native(self):
        try:
            sadl._load_portrait_library()
        except FileNotFoundError:
            self.skipTest('build native/portrait (cargo build --release)')
        for (raw, _), h in zip(self.vectors, self.hashes):
            pixels = sadl.decode_portrait(sadl.photo_section(sadl.decrypt(raw)), library=True)
            self.assertEqual(hashlib.sha256(pixels).hexdigest(), h['portrait_sha256'], f"vector {h['index']}")

if __name__ == '__main__':
    unittest.main()
