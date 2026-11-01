"""Fixtures for the pipeline package's line rules: Indra OCR's own results for
tidy_lines, OrtOCR.split_line, OrtOCR.gutter_cuts and OrtOCR._decode on
synthetic lines.

    python gen_lines.py <out dir> <model dir>

The same seed gives the same file on every run.
"""

import json
import sys
from pathlib import Path

import numpy as np
from indra_ocr.pipeline import CELL_GAP, SPLIT_GAP, OrtOCR, tidy_lines
from indra_ocr.vocab import Vocab

WORDS = ["alpha", "beta", "gamma", "table", "cell", "x", "42", "3.5", "end", "(a)", "Total"]
MARKERS = ["•", "*", "1.", "12)", "a.", "B)", "(3)", "(b)", "(iv)", "٣.", "-", "◦"]
LEADERS = ["....", ".", "_", "-", "—", "==", "…", "·", "- -", "-–", "x."]
RTL = ["שלום עולם", "مرحبا بالعالم", "abc שלום 12", "(שלום)", "مرحبا 3.5"]
JAMO = ["각", "한 abc"]


def token_json(tk):
    if len(tk) == 3:
        return ["layer", tk[0], float(tk[1]), int(tk[2])]
    if isinstance(tk[0], str):
        return ["text", tk[0], float(tk[1])]
    return ["id", int(tk[0]), float(tk[1])]


def reading_json(r):
    return [r[0], float(r[1]), [token_json(t) for t in r[2]]]


def tokens_for(text, kind, x0, step, vocab, seq):
    out = []
    for k, c in enumerate(text):
        x = x0 + k * step
        if kind == "id":
            if c in vocab.index:
                out.append((vocab.index[c], x))
        elif kind == "text":
            out.append((c, x))
        else:
            out.append((c, x, seq))
    return out


def gapped(rng, kind, vocab, h, x0, seq):
    """Words with gaps of random width between them."""
    toks, x = [], x0
    for w in rng.choice(WORDS, int(rng.integers(1, 5))):
        if toks:
            sp = " " if kind != "id" else vocab.index[" "]
            toks.append((sp, x, seq) if kind == "layer" else (sp, x))
            x += float(rng.choice([0.5, 1.0, 1.7, 1.9, 2.5, 3.2, 4.0])) * h
        part = tokens_for(str(w), kind, x, 0.45 * h, vocab, seq)
        toks += part
        x += len(w) * 0.45 * h
    return toks


def tidy_cases(rng, vocab):
    cases = []
    for case in range(120):
        n = int(rng.integers(1, 9))
        boxes, rec = [], []
        kind = str(rng.choice(["id", "text", "layer"]))
        for i in range(n):
            row = int(rng.integers(0, 4))
            h = float(rng.choice([8.0, 12.0, 15.5, 20.25]))
            y0 = 30.0 * row + float(rng.uniform(-3, 3))
            x0 = float(rng.uniform(0, 400))
            pick = rng.random()
            if pick < 0.3:
                text = str(rng.choice(MARKERS))
            elif pick < 0.55:
                text = str(rng.choice(LEADERS))
            elif pick < 0.65:
                text = "  " + str(rng.choice(MARKERS)) + " "
            else:
                text = " ".join(str(w) for w in rng.choice(WORDS, int(rng.integers(1, 4))))
            width = max(h * 0.5, 0.5 * h * len(text)) * float(rng.choice([0.4, 1.0, 3.5]))
            toks = tokens_for(text, kind, x0, width / max(len(text), 1), vocab, i)
            if rng.random() < 0.1:
                toks = []
            boxes.append([x0, y0, x0 + width, y0 + h])
            rec.append((text, float(np.float32(rng.uniform(0.5, 1.0))), toks))
        rtl = bool(rng.random() < 0.25)
        arr = np.array(boxes, np.float32)
        before = [[float(v) for v in b] for b in arr]
        space = vocab.index.get(" ") if rng.random() < 0.85 else None
        out_boxes, out_rec = tidy_lines(arr.copy(), list(rec), rtl, space)
        cases.append({
            "boxes": before,
            "rec": [reading_json(r) for r in rec],
            "rtl": rtl,
            "space": space,
            "out_boxes": [[float(v) for v in b] for b in out_boxes],
            "out_rec": [reading_json(r) for r in out_rec],
        })
    return cases


def split_cases(rng, ocr, vocab):
    cases = []
    for case in range(150):
        kind = str(rng.choice(["id", "text", "layer"]))
        h = float(rng.choice([7.0, 11.0, 14.5, 23.0]))
        x0 = float(rng.uniform(0, 300))
        toks = gapped(rng, kind, vocab, h, x0, case)
        xs = [t[1] for t in toks] or [x0]
        box = np.array([min(xs) - 0.3 * h, 50.0, max(xs) + 0.3 * h, 50.0 + h, 0.9], np.float32)
        at = [float(rng.uniform(min(xs), max(xs))) for _ in range(int(rng.integers(0, 3)))]
        gap = float(rng.choice([SPLIT_GAP, CELL_GAP]))
        ocr._page_rtl = bool(rng.random() < 0.2)
        out = ocr.split_line(box, toks, gap, at)
        cases.append({
            "box": [float(v) for v in box],
            "tokens": [token_json(t) for t in toks],
            "gap": gap,
            "at": at,
            "page_rtl": ocr._page_rtl,
            "out": [[[float(np.float32(v)) for v in bx], tx, [token_json(t) for t in tk]]
                    for bx, tx, tk in out],
        })
    return cases


def gutter_cases(rng, vocab):
    cases = []
    for case in range(60):
        kind = str(rng.choice(["id", "text"]))
        h = 12.0
        cols = int(rng.integers(1, 4))
        boxes, rec = [], []
        for row in range(int(rng.integers(2, 9))):
            y0 = 20.0 * row
            toks = []
            for col in range(cols):
                start = 10.0 + col * 160.0 + float(rng.choice([0.0, 0.0, 1.0, 9.0]))
                part = tokens_for(str(rng.choice(WORDS)), kind, start, 5.5, vocab, row)
                if rng.random() < 0.6 or cols == 1:
                    # this row is one detected line across every column
                    toks += part
                else:
                    xs = [t[1] for t in part]
                    boxes.append([min(xs) - 4, y0, max(xs) + 4, y0 + h])
                    rec.append(("w", 0.9, part))
            if toks:
                xs = [t[1] for t in toks]
                boxes.append([min(xs) - 4, y0, max(xs) + 4, y0 + h])
                rec.append(("w", 0.9, toks))
        arr = np.array(boxes, np.float32).reshape(-1, 4)
        ocr = OrtOCR.__new__(OrtOCR)
        ocr.vocab = vocab
        cuts = ocr.gutter_cuts(arr, rec)
        cases.append({
            "boxes": [[float(v) for v in b] for b in arr],
            "rec": [reading_json(r) for r in rec],
            "cuts": [[float(c) for c in cs] for cs in cuts],
        })
    return cases


def decode_cases(rng, ocr, vocab):
    cases = []
    for text in RTL + JAMO + ["  spaced \t out  ", "a b", "x y z"]:
        for kind in ["text", "layer"]:
            for page_rtl in [False, True]:
                toks = tokens_for(text, kind, 0.0, 5.0, vocab, 3)
                ocr._page_rtl = page_rtl
                cases.append({"tokens": [token_json(t) for t in toks], "page_rtl": page_rtl,
                              "text": ocr._decode(toks)})
    for _ in range(20):
        ids = [int(i) for i in rng.integers(1, len(vocab.chars) + 1, int(rng.integers(0, 12)))]
        toks = [(i, float(k)) for k, i in enumerate(ids)]
        cases.append({"tokens": [token_json(t) for t in toks], "page_rtl": False,
                      "text": ocr._decode(toks)})
    return cases


def main():
    out, models = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    meta = json.loads((models / "meta.json").read_text())
    vocab = Vocab.from_json(meta["vocab"])
    ocr = OrtOCR.__new__(OrtOCR)
    ocr.vocab = vocab
    rng = np.random.default_rng(20261101)
    data = {
        "tidy": tidy_cases(rng, vocab),
        "split": split_cases(rng, ocr, vocab),
        "gutter": gutter_cases(rng, vocab),
        "decode": decode_cases(rng, ocr, vocab),
    }
    (out / "lines.json").write_text(json.dumps(data, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
