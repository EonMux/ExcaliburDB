"""Fixtures for NumpyOps' pairwise sums and means (NumpyPairwiseTest): numpy's own sums and
means of random float32 and float64 arrays, at every summation path's lengths.

    python gen_pairwise.py <out dir>
"""

import sys
from pathlib import Path

import numpy as np


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(20261101)
    arrays = {}
    lengths = [1, 2, 7, 8, 9, 15, 16, 17, 127, 128, 129, 255, 256, 652, 1000, 1999]
    for k, n in enumerate(lengths + [int(v) for v in rng.integers(1, 700, 16)]):
        scale = float(rng.choice([1.0, 1e3, 1e-3, 1e7]))
        a = ((rng.random(n) - 0.3) * scale).astype(np.float32)
        d = (rng.random(n) * scale).astype(np.float64)
        arrays[f"f{k}"] = a
        arrays[f"d{k}"] = d
        arrays[f"fsum{k}"] = np.array([a.sum(), a.mean()], np.float32)
        arrays[f"dsum{k}"] = np.array([d.sum(), np.mean(list(d))], np.float64)
    np.savez_compressed(out / "pairwise.npz", **arrays)


if __name__ == "__main__":
    main()
