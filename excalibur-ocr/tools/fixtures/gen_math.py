"""Fixtures for the math package: Indra OCR's MathReader.flags and .read, and
detokenize, on a synthetic page of formula-like and plain lines.

    python gen_math.py <out dir> <model dir>
"""

import json
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from indra_ocr.line_mask import crop_lines
from indra_ocr.math import MathReader, detokenize

LINES = ["x^2 + y^2 = z^2", "a + b = c", "Results of the trial", "f(x) = 3x - 7",
         "E = mc^2", "sum of n over k", "y = (a + b) / 2", "Chapter 4"]


def main():
    out, models = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    g = np.full((60 * len(LINES) + 40, 900), 255, np.uint8)
    boxes = []
    for i, text in enumerate(LINES):
        y = 50 + 60 * i
        (tw, th), base = cv2.getTextSize(text, cv2.FONT_HERSHEY_COMPLEX, 1.1, 2)
        cv2.putText(g, text, (30, y), cv2.FONT_HERSHEY_COMPLEX, 1.1, 0, 2, cv2.LINE_AA)
        boxes.append([28.0, float(y - th - 2), 32.0 + tw, float(y + base), 0.9])
    boxes = np.array(boxes, np.float32)
    so = ort.SessionOptions()
    so.intra_op_num_threads = 1
    so.inter_op_num_threads = 1
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    so.enable_cpu_mem_arena = False
    reader = MathReader.load(models, so, ["CPUExecutionProvider"])
    flags = reader.flags(crop_lines(g, boxes[:, :4], 32, None))
    reads = reader.read(g, boxes)
    tokens = [["$", "x", "^", "2", "$", " is ", "$", "\\frac", "{", "a", "}", "$"],
              ["plain", "  text "], ["$", "a", "b"], ["$"], [], ["x", "$", "$", "y"]]
    np.savez_compressed(out / "math.npz", page=g, boxes=boxes)
    (out / "math.json").write_text(json.dumps({
        "flags": [bool(f) for f in flags],
        "reads": [[t, c] for t, c in reads],
        "detokenize": [[t, detokenize(t)] for t in tokens],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
