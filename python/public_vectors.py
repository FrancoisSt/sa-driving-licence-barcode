"""Loads the six public encrypted card vectors from Reply.Net.SADL's UnitTest1.cs (fetched by
scripts/fetch-test-vectors.sh), with the 24 values its tests expect (surname, initials, licence number, expiry).

The vectors hold what look like real people's details: use them in tests only, and never print decoded values.
"""
import os
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT = ROOT / 'third_party/Reply.Net.SADL/Reply.Net.SADL/Reply.Net.SADL.Tests/UnitTest1.cs'


def load(path=None):
    """A list of (raw bytes, expected dict), in the file's order of constants. None when not fetched."""
    path = Path(path or os.environ.get('SADL_PUBLIC_VECTORS') or DEFAULT)
    if not path.is_file():
        return None
    src = path.read_text(encoding='utf-8')
    constants = dict(re.findall(r'const string (\w+) = "([0-9A-Fa-f]+)"', src))
    expected = {}
    for body in re.split(r'\[Test\]', src)[1:]:
        name = re.search(r'DecryptDriversLicence\((\w+)\)', body).group(1)
        strings = re.findall(r'Assert\.AreEqual\("([^"]*)"', body)
        y, m, d = map(int, re.search(r'new DateTime\((\d+),\s*(\d+),\s*(\d+)\)', body).groups())
        expected[name] = {'surname': strings[0], 'initials': strings[1], 'licence_number': strings[2],
                          'valid_to': f'{y:04d}-{m:02d}-{d:02d}'}
    return [(bytes.fromhex(hex_), expected[name]) for name, hex_ in constants.items()]
