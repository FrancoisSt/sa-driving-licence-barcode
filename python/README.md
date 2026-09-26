# python: a Python reference

[sadl.py](sadl.py) and the portrait codec [wi.py](wi.py), Python 3.9+ standard library only. It follows [../spec](../spec) step by step and
produces the canonical JSON directly, so it doubles as a readable second reference next to the Kotlin one.

```sh
python3 python/sadl.py decode barcode.bin               # raw barcode bytes -> canonical JSON
python3 python/sadl.py portrait barcode.bin face.png    # pure Python (wi.py)
```

```python
import sadl
result = sadl.decode(raw_bytes)          # dict, or raises sadl.DecodeError (e.reason is the canonical error)
payload = sadl.decrypt(raw_bytes)        # 684 bytes
wi = sadl.photo_section(payload)
pixels = sadl.decode_portrait(wi)        # 50,000 upright greyscale bytes, via wi.py
```

The portrait is decoded by [wi.py](wi.py), a readable implementation of [../spec/wi-codec.md](../spec/wi-codec.md)
(`wi.decode(wi_bytes)`, or `wi.decode_native_order` for the codec's upside-down raster; errors raise `wi.WiError`,
whose `.code` is `E1` to `E5`). To use the native library from [../native/portrait](../native/portrait) instead
(`cargo build --release` there), pass `decode_portrait(wi, library=path)` or `library=True`, or set
`SADL_PORTRAIT_LIB` to the library's path.

To use it elsewhere, copy `sadl.py` and `wi.py` together (sadl.py imports wi.py from its own folder). There is
nothing to install.

For reading barcodes from images in Python, `pip install zxing-cpp` and use
`zxingcpp.read_barcodes(image, formats=zxingcpp.BarcodeFormat.PDF417)[0].bytes`. For scans, see the crop reader in
[../docs/scanning.md](../docs/scanning.md).

## Tests

```sh
python3 -m unittest discover -s python -v
```

Runs the synthetic vectors (including the WI streams of `wi-synthetic.json`, in [test_wi.py](test_wi.py)), and,
once `scripts/fetch-test-vectors.sh` has run, the public vectors, their hashes, the tamper cases, every WI checkpoint
and the portrait pixel hashes. The native-library portrait test runs when the library is built.
