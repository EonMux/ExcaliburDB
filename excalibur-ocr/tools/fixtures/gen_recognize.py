"""Fixtures for the pipeline's recognition: Indra OCR's OrtOCR.recognize and
box_groups on the pages draw_pages.py drew, upright and as a vertical page.

    python gen_recognize.py <pages.npz> <out dir> <model dir>
"""

import json
import sys
from pathlib import Path

import numpy as np
from indra_ocr import lm as lm_module
from indra_ocr.pipeline import OrtOCR


def token_json(tk):
    if isinstance(tk[0], str):
        return ["text", tk[0], float(tk[1])]
    return ["id", int(tk[0]), float(tk[1])]


def main():
    pages = np.load(sys.argv[1])
    out, models = Path(sys.argv[2]), Path(sys.argv[3])
    out.mkdir(parents=True, exist_ok=True)
    beams = []
    real = lm_module.beam_search

    def counting(*args, **kwargs):
        beams.append(1)
        return real(*args, **kwargs)

    lm_module.beam_search = counting
    result = {}
    for math in (False, True):
        ocr = OrtOCR(models, threads=1, math=math)
        for k in range(len([n for n in pages.files if n.startswith("page")])):
            gray, boxes = pages[f"page{k}"], pages[f"boxes{k}"]
            for vertical in (False, True):
                rec = ocr.recognize(gray, boxes, vertical=vertical)
                result[f"page{k}_vertical{int(vertical)}_math{int(math)}"] = [
                    [t, float(c), [token_json(x) for x in toks]] for t, c, toks in rec]
            groups, sure = ocr.box_groups(gray, boxes)
            result[f"groups{k}"] = [groups, [bool(s) for s in sure]]
    result["beam_searches"] = len(beams)
    (out / "recognize.json").write_text(json.dumps(result, ensure_ascii=False, indent=0))


if __name__ == "__main__":
    main()
