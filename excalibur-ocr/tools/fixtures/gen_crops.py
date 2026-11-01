"""Fixtures for the recognize package: Indra OCR's own crop_lines, line_darkness,
_percentiles, pad_batch and width_batches on synthetic pages.

    python gen_crops.py <out dir>

The same seed gives the same file on every run.
"""

import json
import sys
from pathlib import Path

import cv2
import numpy as np
from indra_ocr.line_mask import crop_lines, line_darkness
from indra_ocr.rec_preproc import _percentiles, pad_batch, width_batches


def page(rng, h, w):
    """Paper with noise, some lines of text and a dark banner with light text."""
    g = np.clip(rng.normal(235, 2, (h, w)), 0, 255).astype(np.uint8)
    boxes = []
    for k in range(int(rng.integers(3, 9))):
        y = int(rng.integers(10, h - 30))
        x = int(rng.integers(0, w - 120))
        scale = float(rng.uniform(0.4, 1.2))
        text = "".join(rng.choice(list("abcdefghij KLMNOP 0123"), int(rng.integers(3, 14))))
        dark = rng.random() < 0.25
        (tw, th), base = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, scale, 1)
        if dark:
            g[max(0, y - th - 4): y + base + 4, max(0, x - 4): x + tw + 4] = 30
        cv2.putText(g, text, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale,
                    230 if dark else 20, 1, cv2.LINE_AA)
        boxes.append([x + float(rng.uniform(-1, 1)), y - th + float(rng.uniform(-1, 1)),
                      x + tw + float(rng.uniform(-1, 1)), y + base + float(rng.uniform(-1, 1)),
                      0.9])
    # boxes reaching past the page and degenerate ones
    boxes.append([-5.5, -3.25, 40.0, 9.0, 0.5])
    boxes.append([w - 30.0, h - 6.5, w + 12.0, h + 2.0, 0.5])
    boxes.append([10.0, 10.0, 10.0, 10.0, 0.5])
    boxes.append([0.0, 0.0, 600.0, 2.0, 0.5])
    return g, np.array(boxes, np.float32)


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(20261101)
    arrays = {}
    meta = []
    for p in range(4):
        h, w = int(rng.integers(120, 260)), int(rng.integers(220, 480))
        g, boxes = page(rng, h, w)
        arrays[f"page{p}"] = g
        arrays[f"boxes{p}"] = boxes
        dark = line_darkness(g, boxes[:, :4])
        case = {"page": p, "dark": [bool(d) for d in dark], "crops": [],
                "percentiles": []}
        for window in (1, 3):
            crops = crop_lines(g, boxes[:, :4], 32, window=window, dark=dark)
            for i, c in enumerate(crops):
                # Stored as the resized uint8 crop, which gives the ink map back exactly.
                ink = c if dark[i] else 1.0 - c
                u = np.round(ink * 255).astype(np.uint8)
                back = u.astype(np.float32) / 255.0
                assert np.array_equal(back if dark[i] else 1.0 - back, c)
                arrays[f"crop{p}_{window}_{i}"] = u
            case["crops"].append({"window": window, "count": len(crops)})
            x, widths = pad_batch(crops)
            arrays[f"widths{p}_{window}"] = widths
            arrays[f"shape{p}_{window}"] = np.array(x.shape, np.int64)
            arrays[f"sum{p}_{window}"] = x.astype(np.float64).sum(axis=(1, 2, 3))
        for b in boxes:
            x0, y0, x1, y1 = (float(v) for v in b[:4])
            r = g[max(0, int(y0)): min(h, int(y1) + 1), max(0, int(x0)): min(w, int(x1) + 1)]
            case["percentiles"].append(_percentiles(r) if r.size else None)
        meta.append(case)
    batches = []
    for _ in range(40):
        ws = [int(v) for v in rng.integers(8, 900, int(rng.integers(0, 150)))]
        mb = int(rng.choice([4, 16, 64]))
        batches.append({"widths": ws, "max_batch": mb,
                        "batches": width_batches(ws, mb),
                        "math": width_batches(ws, mb, max_ratio=4.0, max_area=16 * 64 * 640)})
    np.savez_compressed(out / "crops.npz", **arrays)
    (out / "crops.json").write_text(json.dumps({"pages": meta, "width_batches": batches}))


if __name__ == "__main__":
    main()
