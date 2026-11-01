"""Fixtures for the array reader and GrayImage: numpy's own results.

Run with the Python environment Indra OCR is installed in:
    python tools/fixtures/gen_foundation.py src/test/resources/fixtures/foundation
"""
import sys
from pathlib import Path

import numpy as np

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(7)

np.savez_compressed(
    out / "dtypes.npz",
    f4=np.array([[1.5, -2.25], [3.0, np.float32(1e-7)]], np.float32),
    f8=np.array([0.1, -1e300], np.float64),
    i1=np.array([-128, 127], np.int8),
    u1=np.array([0, 255], np.uint8),
    i2=np.array([-32768, 32767], np.int16),
    u2=np.array([0, 65535], np.uint16),
    i4=np.array([-(2**31), 2**31 - 1], np.int32),
    u4=np.array([0, 2**32 - 1], np.uint32),
    i8=np.array([-(2**63), 2**63 - 1], np.int64),
    u8=np.array([0, 2**64 - 1], np.uint64),
    b1=np.array([True, False, True]),
    scalar=np.array(42, np.int64),
    big=np.array([1.0, 2.0], ">f4"),
)
np.savez(out / "stored.npz", a=np.arange(6, dtype=np.int64).reshape(2, 3))

img = rng.integers(0, 256, (5, 7), dtype=np.uint8)
np.savez_compressed(
    out / "rot90.npz",
    image=img,
    **{f"k{k}": np.rot90(img, k) for k in (1, 2, 3, -1)},
)
