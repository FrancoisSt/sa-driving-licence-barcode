"""Tests of the pure-Python WI codec (wi.py) against spec/test-vectors/wi-*.json. python3 -m unittest discover -s python

Only hashes, sizes, counts and error codes are compared or reported: never decoded pixels or values. The public
vectors are skipped until scripts/fetch-test-vectors.sh has run.
"""
import hashlib
import json
import random
import unittest
from pathlib import Path

import public_vectors
import sadl
import wi

VECTORS = Path(__file__).resolve().parent.parent / 'spec/test-vectors'
SCALARS = ('q', 'll_bits', 'skip', 'bytes_consumed')
IMAGES = ('C1_ll', 'C2_map', 'C6_sharpened', 'C7_raster', 'C8_portrait')
LEVEL_HASHES = ('C3_upsampled', 'C4_coefficients', 'C5_reconstructed')


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def trace_of(data):
    trace = {}
    wi._decode(bytes(data), trace)
    return trace


def mismatches(trace, case):
    """The names of the checkpoints that differ; empty when all match."""
    bad = [k for k in SCALARS if trace[k] != case[k]]
    bad += [k for k in IMAGES if sha256(trace[k]) != case[k]]
    if [r['level'] for r in trace['levels']] != [r['level'] for r in case['levels']]:
        return bad + ['levels']
    for got, expected in zip(trace['levels'], case['levels']):
        bad += [f"level {expected['level']} {k}" for k in LEVEL_HASHES if sha256(got[k]) != expected[k]]
        bad += [f"level {expected['level']} {k}" for k in ('escape_bits', 'C4_coefficient_count')
                if got[k] != expected[k]]
    return bad


def synthetic_cases():
    return json.loads((VECTORS / 'wi-synthetic.json').read_text())['cases']


class HuffmanSelfTest(unittest.TestCase):
    """Section 4.8: the initial code of every symbol, read from the root."""

    @staticmethod
    def code(model, symbol):
        bits, i = [], model.node_of[symbol]
        while model.parent[i] != -1:
            p = model.parent[i]
            bits.append(str(i - model.payload[p]))
            i = p
        return ''.join(reversed(bits))

    def test_coefficient_model(self):
        m = wi.AdaptiveHuffman(2048, 3)
        self.assertEqual({s: self.code(m, s) for s in (0, 1, 2, 2048, 2049)},
                         {0: '10', 1: '000', 2: '001', 2048: '01', 2049: '11'})
        self.assertEqual(m.next, 9)
        table = [(m.parent[i], m.leaf[i], m.payload[i], m.weight[i]) for i in range(9)]
        self.assertEqual(table, [(-1, False, 1, 5), (0, False, 3, 3), (0, False, 5, 2), (1, False, 7, 2),
                                 (1, True, 2048, 1), (2, True, 0, 1), (2, True, 2049, 1), (3, True, 1, 1),
                                 (3, True, 2, 1)])

    def test_map_model(self):
        m = wi.AdaptiveHuffman(256, 64)
        self.assertEqual(m.next, 131)
        self.assertEqual(m.weight[0], 66)
        expected = {0: '010001', 1: '110001', 13: '111101', 29: '111111', 30: '000010', 60: '111110',
                    61: '0000000', 62: '0000010', 63: '0000011', 256: '0000001', 257: '100001'}
        self.assertEqual({s: self.code(m, s) for s in expected}, expected)


class Geometry(unittest.TestCase):
    def test_level_sizes(self):
        self.assertEqual([(wi.size(250, l), wi.size(200, l)) for l in range(6)],
                         [(250, 200), (126, 101), (64, 51), (33, 26), (17, 14), (9, 8)])


class PublicVectorCheckpoints(unittest.TestCase):
    def test_every_checkpoint(self):
        vectors = public_vectors.load()
        if vectors is None:
            self.skipTest('run scripts/fetch-test-vectors.sh')
        cases = json.loads((VECTORS / 'wi-checkpoints.json').read_text())['cases']
        hashes = json.loads((VECTORS / 'public-vectors.json').read_text())['cases']
        self.assertEqual(len(vectors), 6)
        for (raw, _), case, h in zip(vectors, cases, hashes):
            with self.subTest(vector=case['index']):
                photo = sadl.photo_section(sadl.decrypt(raw))
                self.assertEqual(len(photo), case['wi_length'])
                self.assertEqual(sha256(photo), case['wi_sha256'])
                self.assertEqual(mismatches(trace_of(photo), case), [], f"vector {case['index']}")
                self.assertEqual(sha256(wi.decode(photo)), h['portrait_sha256'], f"vector {case['index']}")
                self.assertEqual(sha256(wi.decode_native_order(photo)), case['C7_raster'])


class SyntheticVectors(unittest.TestCase):
    def test_valid_streams(self):
        valid = [c for c in synthetic_cases() if 'expected_error' not in c]
        self.assertEqual(len(valid), 41)
        for case in valid:
            with self.subTest(case['name']):
                data = bytes.fromhex(case['wi_hex'])
                self.assertEqual(len(data), case['wi_length'])
                self.assertEqual(sha256(data), case['wi_sha256'])
                self.assertEqual(mismatches(trace_of(data), case), [], case['name'])
                self.assertEqual(sha256(wi.decode(data)), case['C8_portrait'], case['name'])

    def test_invalid_streams(self):
        invalid = [c for c in synthetic_cases() if 'expected_error' in c]
        self.assertEqual(len(invalid), 26)
        for case in invalid:
            with self.subTest(case['name']):
                with self.assertRaises(wi.WiError) as caught:
                    wi.decode(bytes.fromhex(case['wi_hex']))
                self.assertEqual(caught.exception.code, case['expected_error'], case['name'])


class HeaderGate(unittest.TestCase):
    """Section 2: exactly the licence profile passes the gate; every other header byte is E1."""

    def setUp(self):
        self.valid = bytes.fromhex(next(c for c in synthetic_cases() if c['name'] == 'random-01')['wi_hex'])

    def code(self, data):
        try:
            wi.decode(data)
            return None
        except wi.WiError as e:
            return e.code

    def test_every_other_value_of_every_fixed_byte(self):
        for at in (0, 1, 2, 3, 4, 5, 6, 9, 10, 11):
            for value in range(256):
                if value == self.valid[at]:
                    continue
                data = bytearray(self.valid)
                data[at] = value
                self.assertEqual(self.code(bytes(data)), 'E1', f'byte {at}')

    def test_byte_7(self):
        for value in range(256):
            data = bytearray(self.valid)
            data[7] = value
            if value in (0x42, 0x43):
                self.assertNotEqual(self.code(bytes(data)), 'E1')
            else:
                self.assertEqual(self.code(bytes(data)), 'E1', 'byte 7')

    def test_byte_8_must_be_even(self):
        for value in range(256):
            data = bytearray(self.valid)
            data[8] = value
            if value % 2:
                self.assertEqual(self.code(bytes(data)), 'E1', 'byte 8')
            else:
                self.assertNotEqual(self.code(bytes(data)), 'E1')

    def test_size_limits(self):
        header = self.valid[:12]
        self.assertEqual(self.code(b''), 'E1')
        self.assertEqual(self.code(header), 'E1')
        self.assertEqual(self.code(header + b'\0'), 'E2')  # 13 bytes pass the gate, then run out
        self.assertEqual(self.code(self.valid + bytes(674 - len(self.valid))), None)  # trailing zeros are ignored
        self.assertEqual(self.code(self.valid + bytes(675 - len(self.valid))), 'E1')

    def test_bytearray_and_memoryview_inputs(self):
        expected = wi.decode(self.valid)
        self.assertEqual(wi.decode(bytearray(self.valid)), expected)
        self.assertEqual(wi.decode(memoryview(self.valid)), expected)
        self.assertEqual(wi.decode_native_order(self.valid), expected[::-1])


class Robustness(unittest.TestCase):
    """Random byte strings and mutations of the synthetic streams (never the public vectors) decode or fail cleanly."""

    ITERATIONS = 1500

    def test_random_inputs(self):
        rng = random.Random(20260926)
        seeds = [bytes.fromhex(c['wi_hex']) for c in synthetic_cases() if 'expected_error' not in c]
        header = seeds[0][:12]
        outcomes = {}
        for n in range(self.ITERATIONS):
            kind = n % 3
            if kind == 0:  # random bytes behind a valid header, so the codec itself runs
                data = header + bytes(rng.getrandbits(8) for _ in range(rng.randrange(1, 663)))
            elif kind == 1:  # a few bit flips, byte changes or a truncation of a synthetic stream
                data = bytearray(rng.choice(seeds))
                for _ in range(rng.randrange(1, 6)):
                    at = rng.randrange(12, len(data))
                    if rng.random() < 0.5:
                        data[at] ^= 1 << rng.randrange(8)
                    else:
                        data[at] = rng.getrandbits(8)
                if rng.random() < 0.3:
                    data = data[:rng.randrange(13, len(data) + 1)]
                data = bytes(data)
            else:  # fully random bytes, mostly rejected by the gate
                data = bytes(rng.getrandbits(8) for _ in range(rng.randrange(0, 700)))
            try:
                out = wi.decode(data)
                self.assertEqual(len(out), wi.PIXELS)
                outcomes['ok'] = outcomes.get('ok', 0) + 1
            except wi.WiError as e:
                self.assertIn(e.code, ('E1', 'E2', 'E3', 'E4', 'E5'))
                outcomes[e.code] = outcomes.get(e.code, 0) + 1
        self.assertEqual(sum(outcomes.values()), self.ITERATIONS)
        self.assertGreater(outcomes.get('ok', 0), 0)


if __name__ == '__main__':
    unittest.main()
