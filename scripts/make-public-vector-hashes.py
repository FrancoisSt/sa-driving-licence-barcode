#!/usr/bin/env python3
"""Writes spec/test-vectors/public-vectors.json: SHA-256 hashes of each public vector's input, decrypted payload,
portrait section and decoded portrait. Hashes let any implementation check itself byte for byte against the public
vectors without this repository publishing their contents.

Needs scripts/fetch-test-vectors.sh. The portrait is decoded by python/wi.py (set SADL_PORTRAIT_LIB to use the native
library from native/portrait instead).
"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'python'))
import public_vectors  # noqa: E402
import sadl  # noqa: E402


def sha(b):
    return hashlib.sha256(b).hexdigest()


vectors = public_vectors.load()
if vectors is None:
    sys.exit('run scripts/fetch-test-vectors.sh first')
cases = []
for index, (raw, _) in enumerate(vectors, 1):
    payload = sadl.decrypt(raw)
    wi = sadl.photo_section(payload)
    cases.append({
        'index': index,
        'version': sadl.VERSIONS[raw[:4]],
        'raw_sha256': sha(raw),
        'payload_sha256': sha(payload),
        'photo_length': len(wi),
        'photo_sha256': sha(wi),
        'portrait_sha256': sha(sadl.decode_portrait(wi)),
    })
doc = {
    'about': 'Hashes for the six public card vectors in DanieLeeuwner/Reply.Net.SADL (MIT) at commit '
             '292402a9d437fbb44568dd5076eb50cc86040245, file Reply.Net.SADL/Reply.Net.SADL.Tests/UnitTest1.cs. '
             'index is the order of the hex constants in that file. raw_sha256 identifies the input; payload_sha256 '
             'is the 684 bytes after decryption; photo_sha256 is section 3 including its WI header; '
             'portrait_sha256 is the 50,000 upright greyscale samples (row by row, top-left first). Their '
             'expected field values are the Assert.AreEqual lines in the same file.',
    'source': 'https://github.com/DanieLeeuwner/Reply.Net.SADL',
    'commit': '292402a9d437fbb44568dd5076eb50cc86040245',
    'cases': cases,
}
out = ROOT / 'spec/test-vectors/public-vectors.json'
out.write_text(json.dumps(doc, indent=2) + '\n')
print(f'{out.name}: {len(cases)} cases')
