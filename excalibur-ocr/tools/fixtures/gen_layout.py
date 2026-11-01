"""Fixtures for the reading-order and layout port (order_post.py, layout_post.py).

Two kinds of fixture, both written to the output directory:

  pages      Synthetic page images (two columns, lists, a ruled table, a picture,
             page furniture) read by Indra's real pipeline (OrtOCR).  The functions
             the pipeline calls are wrapped, so every call's exact inputs and outputs
             are recorded: line_features, order.onnx, decode_blocks_order and the
             decode_order calls inside it, page_blocks and page_markdown.  The
             markdown is also rendered again from the same inputs with and without
             keep_furniture, the table heads and the pictures.
  synthetic  Hand-made and random inputs that reach what real pages rarely do:
             CJK and astral text, Unicode 16 characters, ties, degenerate tables,
             display math, lone list markers, title rows and the numeric kernels.

Run with the Python environment Indra OCR is installed in:
    python tools/fixtures/gen_layout.py src/test/resources/fixtures/layout [model_dir]

model_dir defaults to $INDRA_OCR_MODEL_DIR, else ~/.cache/excalibur/indra-ocr/v1.1.0.
"""
import json
import math
import os
import sys
import unicodedata
from pathlib import Path

import cv2
import numpy as np

import indra_ocr.layout_post as lp
import indra_ocr.order_post as op
import indra_ocr.pipeline as pl
from indra_ocr import OrtOCR

OUT = Path(sys.argv[1])
MODEL_DIR = Path(
    sys.argv[2]
    if len(sys.argv) > 2
    else os.environ.get("INDRA_OCR_MODEL_DIR")
    or Path.home() / ".cache/excalibur/indra-ocr/v1.1.0"
)
OUT.mkdir(parents=True, exist_ok=True)
FONT = cv2.FONT_HERSHEY_SIMPLEX
W, H = 1100, 1400


# ---------------------------------------------------------------------------
# Page images
# ---------------------------------------------------------------------------


def blank():
    return np.full((H, W), 255, np.uint8)


def put(img, s, x, y, scale=0.72, thick=2):
    cv2.putText(img, s, (x, y), FONT, scale, 0, thick, cv2.LINE_AA)


def paragraph(img, lines, x, y, step=32, scale=0.72):
    for i, s in enumerate(lines):
        put(img, s, x, y + i * step, scale)
    return y + len(lines) * step


def furniture(img, page):
    put(img, "Journal of Synthetic Pages", 70, 50, 0.6, 1)
    put(img, "Vol. 2 (2026)", 900, 50, 0.6, 1)
    put(img, str(page), 540, 1360, 0.65, 2)


def page_two_column():
    img = blank()
    furniture(img, 7)
    put(img, "Reading Order on Synthetic Pages", 190, 125, 1.25, 3)
    put(img, "A. Author and B. Writer", 400, 170, 0.7, 2)
    y = 240
    put(img, "1 Introduction", 70, y, 0.9, 2)
    y = paragraph(img, [
        "Documents set in two columns are",
        "read down the left column first and",
        "then down the right one. A model",
        "that orders lines must learn where",
        "one column ends and the next one",
        "begins, even when a short last line",
        "sits beside the other column.",
    ], 70, y + 45)
    put(img, "2 Related Work", 70, y + 40, 0.9, 2)
    paragraph(img, [
        "Earlier systems cut the page at its",
        "widest gaps, first across and then",
        "down, which works for clean pages",
        "but fails on ragged layouts.",
    ], 70, y + 85)
    y = 240
    put(img, "3 Method", 580, y, 0.9, 2)
    y = paragraph(img, [
        "We score every pair of lines with a",
        "small network and decode an order",
        "from the pairwise scores. Lines that",
        "belong together, such as the tumori-",
        "genesis example, form one block.",
    ], 580, y + 45)
    put(img, "4 Results", 580, y + 40, 0.9, 2)
    paragraph(img, [
        "The decoded order matches the true",
        "order on most pages; errors cluster",
        "around captions and footnotes.",
    ], 580, y + 85)
    return img


def page_lists():
    img = blank()
    furniture(img, 8)
    put(img, "5 Checklist", 70, 120, 0.95, 2)
    y = paragraph(img, [
        "Before a page is accepted it is checked against the",
        "following points, in this order:",
    ], 70, 170)
    items = [
        ["- the page is upright and straight;"],
        ["- every line has been read, including lines that are", "  very short or very long;"],
        ["* tables keep their rows and columns;"],
    ]
    y += 20
    for it in items:
        for k, s in enumerate(it):
            put(img, s if k == 0 else s.strip(), 90 if k == 0 else 112, y)
            y += 32
    y += 30
    put(img, "Steps to reproduce", 70, y, 0.85, 2)
    y += 45
    for s in ["1. Render the page at 150 dots per inch.", "2) Run the detector and the recognizer.",
              "(a) Order the lines and group them.", "b) Write the markdown."]:
        put(img, s, 90, y)
        y += 32
    y += 30
    paragraph(img, [
        "The list above is numbered; the one before it uses dashes and",
        "stars, and both are written one item per line.",
    ], 70, y)
    return img


def page_table():
    img = blank()
    furniture(img, 9)
    paragraph(img, [
        "The measurements below were taken on three machines over a week;",
        "each value is the median of five runs.",
    ], 70, 120)
    put(img, "Table 2: Synthetic measurements", 330, 230, 0.8, 2)
    cols = [80, 300, 470, 640, 820, 1020]
    rows = [260, 310, 360, 440, 490, 540, 590, 640]
    for x in cols:
        cv2.line(img, (x, rows[0]), (x, rows[-1]), 0, 2)
    for y in rows:
        cv2.line(img, (cols[0], y), (cols[-1], y), 0, 2)
    data = [
        ["Machine", "Cores", "Memory", "Time (s)", "Notes"],
        ["alpha", "8", "32 GB", "12.4", "baseline"],
        ["beta", "16", "64 GB", "7.9", None],
        ["gamma", "32", "128 GB", "4.1", "a & b <fast>"],
        ["delta", "4", "16 GB", "25.0", ""],
        ["epsilon", "12", "48 GB", "9.6", "noisy"],
        ["zeta", "24", "96 GB", "5.3", "ok"],
    ]
    for r, row in enumerate(data):
        for c, s in enumerate(row):
            if s:
                put(img, s, cols[c] + 14, rows[r] + 34, 0.68, 2)
    # the taller band's note wraps onto a second line
    put(img, "beta has", cols[4] + 14, rows[2] + 32, 0.68, 2)
    put(img, "a long note", cols[4] + 14, rows[2] + 64, 0.68, 2)
    paragraph(img, [
        "Table 2 shows that time falls as cores are added, until memory",
        "bandwidth limits the larger machines.",
    ], 70, 720)
    return img


def page_table_unruled():
    """A table without rules whose columns sit close: the detector reads a row's
    first cells as one line, which the pipeline cuts into cells (CELL_GAP)."""
    img = blank()
    furniture(img, 11)
    put(img, "Results per machine are listed below.", 70, 150)
    rows = [["Machine", "Cores", "Time"], ["alpha", "8", "12.4"], ["beta", "16", "7.9"],
            ["gamma", "32", "4.1"], ["delta", "4", "25.0"]]
    for r, row in enumerate(rows):
        x = 120
        for s in row:
            put(img, s, x, 230 + 40 * r)
            (w, _), _ = cv2.getTextSize(s, FONT, 0.72, 2)
            x += w + 30 + (r * 7) % 13
    put(img, "Times are wall-clock seconds for one full pass.", 70, 480)
    return img


# det.onnx v1.1.0 has no picture channel, so the pipeline finds no pictures; the
# picture page's markdown is also rendered with the figure's own box as one.
INJECTED_PICTURES = {"picture": np.array([[200, 260, 900, 720]], np.float32)}


def page_picture():
    img = blank()
    furniture(img, 10)
    put(img, "6 Appearance", 70, 120, 0.95, 2)
    paragraph(img, [
        "Figure 1 shows a rendered sample page. The detector marks",
        "the picture region so that its text is not read as lines.",
    ], 70, 170)
    x0, y0, x1, y1 = 200, 260, 900, 720
    grad = np.tile(np.linspace(60, 200, x1 - x0, dtype=np.float32), (y1 - y0, 1))
    img[y0:y1, x0:x1] = grad.astype(np.uint8)
    cv2.circle(img, (420, 480), 120, 20, -1)
    cv2.rectangle(img, (600, 330), (820, 640), 235, -1)
    cv2.ellipse(img, (700, 500), (80, 140), 30, 0, 360, 90, -1)
    rng = np.random.default_rng(11)
    noise = rng.integers(-25, 25, (y1 - y0, x1 - x0))
    img[y0:y1, x0:x1] = np.clip(img[y0:y1, x0:x1].astype(int) + noise, 0, 255).astype(np.uint8)
    put(img, "Figure 1: A rendered sample with shapes.", 300, 760, 0.7, 2)
    paragraph(img, [
        "The caption sits below the figure and is read after it; the",
        "paragraph that follows continues the section.",
    ], 70, 840)
    return img


def page_furniture():
    img = blank()
    put(img, "SYNTHETIC REPORT", 70, 50, 0.6, 1)
    put(img, "Page 12 of 40", 920, 50, 0.6, 1)
    put(img, "Appendix B", 70, 130, 0.95, 2)
    y = paragraph(img, [
        "This appendix repeats the setup in full. Running headers and",
        "footers repeat on every page and are left out of the text unless",
        "they are asked for; the page number is one of them.1",
    ], 70, 185)
    paragraph(img, [
        "A second paragraph follows after a blank line, so that the two",
        "paragraphs are read as separate blocks of text.",
    ], 70, y + 30)
    put(img, "1 The footnote explains a detail of the first paragraph.", 70, 1250, 0.55, 1)
    put(img, "Confidential draft - do not distribute", 70, 1350, 0.55, 1)
    put(img, "12", 1000, 1350, 0.65, 2)
    return img


PAGES = {
    "two_column": page_two_column,
    "lists": page_lists,
    "table": page_table,
    "table_unruled": page_table_unruled,
    "picture": page_picture,
    "furniture": page_furniture,
}


# ---------------------------------------------------------------------------
# Capturing the pipeline's calls
# ---------------------------------------------------------------------------


class Capture:
    """Records the calls of one OCR run."""

    def __init__(self):
        self.runs = []  # one per reading_order call that ran the model
        self.blocks = None
        self.markdown = None


CAP = Capture()


def wrap_line_features(orig):
    def f(boxes, page_wh, texts=None):
        out = orig(boxes, page_wh, texts)
        CAP.runs.append({
            "boxes": np.asarray(boxes, np.float32).copy(),
            "wh": [int(page_wh[0]), int(page_wh[1])],
            "texts": list(texts),
            "feats": out.copy(),
            "inner": [],
        })
        return out
    return f


class SessionProxy:
    def __init__(self, sess):
        self.sess = sess

    def run(self, names, feeds):
        outs = self.sess.run(names, feeds)
        run = CAP.runs[-1]
        assert np.array_equal(feeds["feats"][0], run["feats"])
        assert feeds["mask"].all()
        run["model"] = [o[0].copy() for o in outs]
        return outs

    def __getattr__(self, name):
        return getattr(self.sess, name)


def wrap_decode_blocks_order(orig):
    def f(a, same, boxes, cls=None):
        out = orig(a, same, boxes, cls)
        run = CAP.runs[-1]
        assert np.array_equal(a, run["model"][0]) and np.array_equal(same, run["model"][2])
        assert np.array_equal(cls, run["model"][1])
        assert np.array_equal(np.asarray(boxes, np.float32), run["boxes"])
        run["order"] = [int(v) for v in out]
        return out
    return f


def wrap_inner_decode_order(orig):
    def f(a, sweeps=4):
        out = orig(a, sweeps)
        if CAP.runs:
            CAP.runs[-1]["inner"].append((np.asarray(a).copy(), [int(v) for v in out]))
        return out
    return f


def wrap_page_blocks(orig):
    def f(boxes, texts, order, cls_logits, same):
        out = orig(boxes, texts, order, cls_logits, same)
        CAP.blocks = {"boxes": np.asarray(boxes, np.float32).copy(), "texts": list(texts),
                      "order": [int(v) for v in order], "cls": cls_logits, "same": same,
                      "out": [dict(b) for b in out]}
        return out
    return f


def wrap_page_markdown(orig):
    def f(boxes, texts, order, cls_logits, same, keep_furniture=False, cells=None,
          tab=None, pictures=None):
        out = orig(boxes, texts, order, cls_logits, same, keep_furniture, cells, tab, pictures)
        CAP.markdown = {
            "boxes": np.asarray(boxes, np.float32).copy(), "texts": list(texts),
            "order": [int(v) for v in order], "cls": cls_logits, "same": same,
            "keep_furniture": bool(keep_furniture), "cells": cells, "tab": tab,
            "pictures": None if pictures is None else np.asarray(pictures, np.float32).copy(),
            "out": out,
        }
        return out
    return f


pl.line_features = wrap_line_features(pl.line_features)
pl.decode_blocks_order = wrap_decode_blocks_order(pl.decode_blocks_order)
op.decode_order = wrap_inner_decode_order(op.decode_order)
pl.page_blocks = wrap_page_blocks(pl.page_blocks)
pl.page_markdown = wrap_page_markdown(pl.page_markdown)


def markdown_variants(md, run_tab, injected):
    """page_markdown again from the captured inputs: furniture kept or not, the
    table heads (the model's tab) or not, the pictures (found or injected) or not."""
    out = []
    pictures = md["pictures"] if md["pictures"] is not None else injected
    pics_options = [True, False] if pictures is not None else [False]
    for keep in (False, True):
        for use_tab in (False, True):
            for use_pics in pics_options:
                text = lp.page_markdown(
                    md["boxes"], md["texts"], md["order"], md["cls"], md["same"],
                    keep_furniture=keep, cells=md["cells"],
                    tab=run_tab if use_tab else None,
                    pictures=pictures if use_pics else None,
                )
                out.append({"keep_furniture": keep, "tab": use_tab, "pictures": use_pics,
                            "markdown": text})
    return out


def read_page(name, img, table_heads):
    global CAP
    CAP = Capture()
    ocr = OrtOCR(MODEL_DIR, threads=1, table_heads=table_heads)
    ocr.order = SessionProxy(ocr.order)
    page = ocr(img, turn=False)
    arrays, runs = {}, []
    for r, run in enumerate(CAP.runs):
        a, cls, same, tab = run["model"]
        arrays.update({f"r{r}_boxes": run["boxes"], f"r{r}_feats": run["feats"],
                       f"r{r}_a": a, f"r{r}_cls": cls, f"r{r}_same": same, f"r{r}_tab": tab})
        inner = []
        for j, (ab, res) in enumerate(run["inner"]):
            arrays[f"r{r}_ab{j}"] = ab.astype(np.float32)
            inner.append(res)
        runs.append({"wh": run["wh"], "texts": run["texts"], "order": run["order"],
                     "inner_orders": inner,
                     "xycut_blocks": [[int(i) for i in b] for b in op.xycut_blocks(run["boxes"])],
                     "order_xycut": [int(i) for i in op.order_xycut(run["boxes"])]})
    last = CAP.runs[-1]
    md, blocks = CAP.markdown, CAP.blocks
    # page_blocks and page_markdown read the last run's layout heads
    for cap in (md, blocks):
        assert np.array_equal(cap["cls"], last["model"][1])
        assert np.array_equal(cap["same"], last["model"][2])
        assert np.array_equal(cap["boxes"], last["boxes"])
        assert cap["texts"] == last["texts"] and cap["order"] == last["order"]
    assert (md["tab"] is None) == (not table_heads)
    if md["tab"] is not None:
        assert np.array_equal(md["tab"], last["model"][3])
    cell_line, cell_boxes, cell_texts = [], [], []
    for i, cs in sorted(md["cells"].items()):
        for bx, tx in cs:
            cell_line.append(i)
            cell_boxes.append([float(v) for v in bx])
            cell_texts.append(tx)
    arrays["cell_boxes"] = np.array(cell_boxes, np.float32).reshape(-1, 4)
    injected = INJECTED_PICTURES.get(name)
    if md["pictures"] is not None:
        arrays["pictures"] = md["pictures"]
    elif injected is not None:
        arrays["pictures"] = injected
    variants = markdown_variants(md, last["model"][3], injected)
    actual = next(v for v in variants if v["keep_furniture"] == md["keep_furniture"]
                  and v["tab"] == table_heads and v["pictures"] == (md["pictures"] is not None))
    assert actual["markdown"] == md["out"] == page.markdown
    doc = {
        "page": name, "table_heads": table_heads, "runs": runs,
        "cells": {"line": cell_line, "texts": cell_texts},
        "page_blocks": [{"label": b["label"], "bbox": b["bbox"], "text": b["text"]}
                        for b in blocks["out"]],
        "markdown": variants,
    }
    stem = f"page_{name}" + ("_heads" if table_heads else "")
    np.savez_compressed(OUT / f"{stem}.npz", **arrays)
    (OUT / f"{stem}.json").write_text(json.dumps(doc, indent=1))
    print(stem, "runs", len(runs), "lines", [len(r["texts"]) for r in runs],
          "pictures", 0 if md["pictures"] is None else len(md["pictures"]))


# ---------------------------------------------------------------------------
# Synthetic cases
# ---------------------------------------------------------------------------

rng = np.random.default_rng(20261101)

TEXT_POOL = [
    "Plain text line", "  padded both sides \t", "", "   ", " nbsp start", "\x1cfile sep\x1f",
    "中文的段落", "日本語のテキスト。", "한국어 문장", "ＦＵＬＬ　ｗｉｄｔｈ１２", "😀 emoji start",
    "x²³ superscripts¹", "①②③ circled", "٣٤ Arabic-Indic digits", "\U0001d7ce\U0001d7cf math digits",
    "Ᲊ Unicode 16 capital", "Ɤꟍ new letters", "tumori-", "genesis continues", "end-",
    "• bullet item", "- dash item", "– en dash", "▪ square", "◦ white", "* star", "· middle dot",
    "→ arrow", "1. numbered", "12) numbered", "123 numbers only", "(3) paren", "1.)", "10.5 percent",
    "Title Case", "lower case", "Ending with a question?", "ending colon:", "semi;", "！", "fin．",
    "$x^2+y^2$", "$a$ and $b$", "  $E = mc^2$  ", "$$", "$x$\n", "<b>&amp;</b>", "Ünïcödé Çase",
    "ǅungla titlecase", "𐐀 Deseret capital", "𐐨 deseret small", "ⅫRoman", "ß straße",
]


def synthetic_text():
    joins = [
        ["tumori-", "genesis"], ["end-", "Start"], ["a-", "b"], ["x-", "1abc"], ["-", "lower"],
        ["中文", "继续"], ["中文", " next"], ["word", "中文"], ["。", "日本"], ["", " ", "only"],
        ["\U00020000", "\U0002a6df"], ["😀-", "next"], ["é-", "été"], ["Ω-", "ωmega"],
        ["Ａ", "　x"], ["한국", "어"], ["ab-", "Ǆ"], ["ab-", "ǅ"], ["two  spaces ", " in"],
        ["\x1c", "x\x1d", "y"], [], ["single"], ["line one", "line two", "line three-", "four"],
    ]
    cps = [0x2fff, 0x3000, 0x30ff, 0x3100, 0x33ff, 0x3400, 0x9fff, 0xa000, 0xf8ff, 0xf900,
           0xfaff, 0xfb00, 0xfeff, 0xff00, 0xffef, 0xfff0, 0x1ffff, 0x20000, 0x2fa1f, 0x2fa20,
           0x41, 0xac00, 0x1f600]
    disp = TEXT_POOL + ["".join(chr(int(c)) for c in rng.integers(0x20, 0x3000, 12)) for _ in range(20)]
    return {
        "display_len": [{"text": t, "len": op.display_len(t)} for t in disp],
        "join_lines": [{"lines": j, "out": lp.join_lines(j)} for j in joins],
        "cjk": [{"cp": c, "cjk": lp._cjk(chr(c))} for c in cps],
        "log1p": [float(np.float32(math.log1p(n) / 5.0)) for n in range(0, 4097)],
    }


def synthetic_features(arrays):
    cases = []
    for k in range(6):
        n = [1, 2, 7, 8, 24, 60][k]
        x0 = rng.uniform(0, 900, n).astype(np.float32)
        y0 = rng.uniform(0, 1300, n).astype(np.float32)
        w = rng.uniform(0, 300, n).astype(np.float32)
        h = rng.uniform(0, 40, n).astype(np.float32)
        if k == 3:
            w[:3] = 0  # degenerate boxes hit the 1e-3 floor
            h[3:5] = 0
        boxes = np.stack([x0, y0, x0 + w, y0 + h], 1)
        texts = [TEXT_POOL[int(i)] for i in rng.integers(0, len(TEXT_POOL), n)]
        wh = (int(rng.integers(500, 3000)), int(rng.integers(500, 3000)))
        arrays[f"feat{k}_boxes"] = boxes
        arrays[f"feat{k}_out"] = op.line_features(boxes, wh, texts)
        arrays[f"feat{k}_out_notext"] = op.line_features(boxes, wh, None)
        cases.append({"wh": list(wh), "texts": texts})
    arrays["feat_empty"] = op.line_features(np.zeros((0, 4), np.float32), (100, 100), [])
    return cases


def synthetic_decode(arrays):
    cases = []
    for k in range(14):
        n = [2, 3, 5, 8, 9, 16, 17, 31, 40, 64, 4, 6, 10, 130][k]
        if k == 10:
            a = np.zeros((n, n), np.float32)  # all ties
        elif k == 11:
            a = rng.integers(-2, 3, (n, n)).astype(np.float32)  # many ties
        elif k == 12:
            m = rng.standard_normal((n, n)).astype(np.float32) * 6
            a = (m - m.T).astype(np.float32)  # antisymmetric
        else:
            order = rng.permutation(n)
            pos = np.empty(n, int)
            pos[order] = np.arange(n)
            a = ((pos[None, :] - pos[:, None]) * rng.uniform(0.2, 3)).astype(np.float32)
            a += rng.standard_normal((n, n)).astype(np.float32) * rng.uniform(0.5, 8)
            a = a.astype(np.float32)
        arrays[f"dec{k}_a"] = a
        cases.append({"order": op.decode_order(a), "order_1sweep": op.decode_order(a, 1)})
    return cases


def synthetic_cluster(arrays):
    cases = []
    for k in range(10):
        n = [1, 2, 3, 6, 12, 25, 40, 70, 5, 9][k]
        groups = rng.integers(0, max(1, n // 3), n)
        s = np.where(groups[:, None] == groups[None, :], 4.0, -4.0)
        s = s + rng.standard_normal((n, n)) * [0.5, 1, 2, 3, 4, 5, 6, 3, 0, 0][k]
        if k == 8:
            s = np.ones((n, n))  # ties everywhere
        if k == 9:
            s = rng.integers(-1, 2, (n, n)).astype(float)
        s = s.astype(np.float32)
        arrays[f"clu{k}"] = s
        cases.append({"clusters": op._cluster(s)})
    return cases


def grid_boxes(n_cols, n_rows, jitter, x_gap=40, tie=False):
    out = []
    for c in range(n_cols):
        for r in range(n_rows):
            x0 = 60 + c * (300 + x_gap) + (0 if tie else rng.uniform(-jitter, jitter))
            y0 = 80 + r * 30 + (0 if tie else rng.uniform(-jitter, jitter))
            w = rng.uniform(150, 300) if not tie else 280
            out.append([x0, y0, x0 + w, y0 + 18 + (0 if tie else rng.uniform(0, 4))])
    return np.array(out, np.float32)


def synthetic_xycut(arrays):
    cases = []
    layouts = [
        grid_boxes(2, 8, 3), grid_boxes(3, 5, 2), grid_boxes(1, 12, 1), grid_boxes(2, 6, 0, tie=True),
        np.array([[10, 10, 50, 30], [10, 10, 50, 30], [60, 10, 90, 30]], np.float32),
        np.array([[0, 0, 10, 10]], np.float32), np.zeros((0, 4), np.float32),
        rng.uniform(0, 1000, (40, 2)).astype(np.float32).repeat(2, 1) + np.array([0, 0, 120, 20], np.float32),
        np.array([[100, 100, 400, 120], [100, 125, 400, 145], [500, 100, 800, 120],
                  [100, 300, 800, 320], [500, 125, 800, 145]], np.float32),
    ]
    for k, b in enumerate(layouts):
        b = np.asarray(b, np.float32)
        arrays[f"xy{k}"] = b
        cases.append({
            "top_down": [int(i) for i in op.order_top_down(b)],
            "xycut": [int(i) for i in op.order_xycut(b)],
            "blocks": [[int(i) for i in g] for g in op.xycut_blocks(b)],
            "blocks_gap3": [[int(i) for i in g] for g in op.xycut_blocks(b, 3.0)],
            "blocks_gap0": [[int(i) for i in g] for g in op.xycut_blocks(b, 0.0)],
        })
    return cases


def synthetic_blocks_order(arrays):
    cases = []
    for k in range(6):
        b = grid_boxes(2, 6 + k, 2)
        n = len(b)
        block = np.arange(n) // 3
        same = np.where(block[:, None] == block[None, :], 3.0, -3.0) + rng.standard_normal((n, n))
        pos = np.arange(n)
        a = (pos[None, :] - pos[:, None]) * 0.8 + rng.standard_normal((n, n)) * 2
        cls = rng.standard_normal((n, 10)) * 2
        if k % 2:
            cls[: n // 2, 5] += 6  # grid blocks go row by row
        arrays[f"bo{k}_boxes"] = b
        arrays[f"bo{k}_a"] = a.astype(np.float32)
        arrays[f"bo{k}_same"] = same.astype(np.float32)
        arrays[f"bo{k}_cls"] = cls.astype(np.float32)
        cases.append({
            "order": op.decode_blocks_order(a.astype(np.float32), same.astype(np.float32), b,
                                            cls.astype(np.float32)),
            "order_nocls": op.decode_blocks_order(a.astype(np.float32), same.astype(np.float32), b),
        })
    return cases


def table_geometry(n_rows, n_cols, title=False, wrap=False, missing=0, span=False):
    boxes, texts = [], []
    if title:
        boxes.append([60, 40, 60 + 140 * n_cols * 0.9, 60])
        texts.append("Table 7: A title & more")
    y = 80
    for r in range(n_rows):
        for c in range(n_cols):
            if missing and r > 0 and (r * n_cols + c) % 7 == missing:
                continue
            x0 = 60 + c * 140 + rng.uniform(-3, 3)
            if span and r == 2 and c == 1:
                boxes.append([x0, y, x0 + 260, y + 18])
                texts.append("spans <two> columns")
                continue
            if span and r == 2 and c == 2:
                continue
            boxes.append([x0, y + rng.uniform(-1, 1), x0 + rng.uniform(60, 120), y + 18])
            texts.append(f"r{r}c{c}" if r else f"Head {c}")
        if wrap and r == 1:
            y += 22
            boxes.append([60 + 140 * (n_cols - 1), y, 60 + 140 * (n_cols - 1) + 80, y + 18])
            texts.append("wrapped")
        y += 40
    return np.array(boxes, np.float32), texts


def synthetic_tables(arrays):
    cases = []
    specs = [dict(n_rows=4, n_cols=3), dict(n_rows=5, n_cols=4, title=True),
             dict(n_rows=6, n_cols=3, wrap=True), dict(n_rows=5, n_cols=5, missing=3),
             dict(n_rows=4, n_cols=4, span=True), dict(n_rows=1, n_cols=3),
             dict(n_rows=2, n_cols=1), dict(n_rows=3, n_cols=3, title=True, wrap=True)]
    for k, spec in enumerate(specs):
        b, t = table_geometry(**spec)
        arrays[f"tab{k}_boxes"] = b
        rows = lp._rows(b)
        cases.append({
            "texts": t, "html": lp.table_html(b, t), "rows": rows,
            "merged": lp._merge_wrapped(rows, b),
        })
        # the table heads: logits from the true cell, row and column structure
        n = len(b)
        cy = (b[:, 1] + b[:, 3]) / 2
        row_id = np.round(cy / 40).astype(int)
        col_id = np.round((b[:, 0] - 60) / 140).astype(int)
        tab = np.stack([
            np.where(row_id[:, None] == row_id[None, :], 2.5, -2.5),
            np.where(col_id[:, None] == col_id[None, :], 2.5, -2.5),
            np.where(np.arange(n)[:, None] == np.arange(n)[None, :], 2.5, -2.5),
        ]).astype(np.float32) + rng.standard_normal((3, n, n)).astype(np.float32) * 0.8
        if k == 3:
            tab[2] = 3.0  # one cell of side-by-side lines: implausible heads
        arrays[f"tab{k}_heads"] = tab.astype(np.float32)
        cases[-1]["heads_plausible"] = bool(lp.heads_plausible(b, tab))
        cases[-1]["heads_html"] = lp.table_html_heads(b, t, tab)
    return cases


MD_TEXTS = [
    ("RUNNING HEAD", 2), ("Page 3", 3), ("An Interesting Title", 1), ("1 Introduction", 1),
    ("The first para-", 0), ("graph goes on here.", 0), ("中文段落的第一行", 0), ("继续第二行。", 0),
    ("$x^2 + y^2 = z^2$", 0), ("  $a = b$ ", 0), ("•", 4), ("first item text", 4), ("- second item", 4),
    ("continues here", 4), ("3.", 4), ("(b) lettered item", 4), ("Figure 2: caption", 6),
    ("a footnote 1", 7), ("Contents", 8), ("Field: value", 9), ("Name", 5), ("Age", 5), ("Ann", 5),
    ("31 & <b>", 5), ("Bob", 5), ("42", 5), ("A last paragraph", 0), ("with two lines", 0),
]


def synthetic_markdown(arrays):
    n = len(MD_TEXTS)
    texts = [t for t, _ in MD_TEXTS]
    labels = np.array([c for _, c in MD_TEXTS])
    boxes = []
    y = 40
    for i, (t, c) in enumerate(MD_TEXTS):
        if c == 5:  # the table: two columns of cells
            k = i - texts.index("Name")
            x0 = 80 + (k % 2) * 200
            yy = y + (k // 2) * 30
            boxes.append([x0, yy, x0 + 120, yy + 20])
            continue
        boxes.append([80, y, 80 + 10 * len(t), y + 20])
        y += 30
    boxes[1] = [480, 1300, 540, 1320]  # page number at the bottom
    boxes = np.array(boxes, np.float32)
    order = list(range(n))
    cls = (np.eye(10)[labels] * 5 + rng.standard_normal((n, 10)) * 0.3).astype(np.float32)
    # same block: neighbours with the same label, except the section headers and math lines
    same = np.full((n, n), -3.0, np.float32)
    for i in range(n - 1):
        if labels[i] == labels[i + 1] and labels[i] != 1 and not texts[i].strip().startswith("$"):
            same[i, i + 1] = same[i + 1, i] = 3.0
    same[8, 9] = same[9, 8] = 3.0  # two display equations in one block
    pictures = np.array([[60, 150, 400, 300], [600, 1000, 900, 1200], [50, 1350, 90, 1390]],
                        np.float32)
    tab = (rng.standard_normal((3, n, n)) * 2).astype(np.float32)
    ann = boxes[texts.index("Ann")]
    cells = {  # one line holds two cells; an empty list falls back to the line itself
        texts.index("Ann"): [([ann[0], ann[1], ann[0] + 40, ann[3]], "An"),
                             ([ann[0] + 45, ann[1], ann[2], ann[3]], "n")],
        texts.index("Bob"): [],
    }
    arrays["md_boxes"] = boxes
    arrays["md_cls"] = cls
    arrays["md_same"] = same
    arrays["md_pictures"] = pictures
    arrays["md_tab"] = tab
    cell_line = [i for i in sorted(cells) for _ in cells[i]]
    arrays["md_cell_boxes"] = np.array([bx for i in sorted(cells) for bx, _ in cells[i]],
                                       np.float32).reshape(-1, 4)
    cell_texts = [tx for i in sorted(cells) for _, tx in cells[i]]
    variants = []
    for keep in (False, True):
        for use_tab in (False, True):
            for use_pics in (False, True):
                for use_cells in (False, True):
                    variants.append({
                        "keep_furniture": keep, "tab": use_tab, "pictures": use_pics,
                        "cells": use_cells,
                        "markdown": lp.page_markdown(
                            boxes, texts, order, cls, same, keep_furniture=keep,
                            cells=cells if use_cells else None,
                            tab=tab if use_tab else None,
                            pictures=pictures if use_pics else None),
                    })
    shuffled = list(rng.permutation(n))
    arrays["md_order_shuffled"] = np.array(shuffled, np.int64)
    return {
        "texts": texts, "cells": {"line": cell_line, "texts": cell_texts},
        "variants": variants,
        "blocks": lp.page_blocks(boxes, texts, order, cls, same),
        "split": lp.split_blocks(order, same),
        "split_shuffled": lp.split_blocks([int(v) for v in shuffled], same),
        "shuffled_markdown": lp.page_markdown(boxes, texts, [int(v) for v in shuffled], cls, same),
        "empty": lp.page_markdown(np.zeros((0, 4), np.float32), [], [], np.zeros((0, 10), np.float32),
                                  np.zeros((0, 0), np.float32)),
    }


def synthetic_numeric(arrays):
    a = (rng.standard_normal(4000) * rng.choice([0.3, 3, 30, 120], 4000)).astype(np.float32)
    a[:6] = [0.0, -0.0, 88.5, -104.0, 1e-30, -1e-30]
    arrays["lae_in"] = a
    arrays["lae_out"] = np.logaddexp(0.0, -a)
    v = np.exp(rng.uniform(-30, 30, 4000)).astype(np.float32)
    v[:4] = [1.0, np.float32(1e-40), np.float32(0.70710677), np.float32(0.7071068)]
    arrays["log_in"] = v
    arrays["log_out"] = np.log(v)
    meds = []
    for k in range(40):
        x = (rng.standard_normal(int(rng.integers(1, 30))) * 50).astype(np.float32)
        arrays[f"med{k}"] = x
        meds.append(float(np.median(x)))
    return {"medians": meds}


def unicode_samples():
    cps = [0x09, 0x0a, 0x1c, 0x1f, 0x20, 0x85, 0xa0, 0x1680, 0x2007, 0x200b, 0x202f, 0x3000, 0xfeff,
           0x41, 0x61, 0x1c5, 0x2160, 0x1c89, 0x1c8a, 0xa7cb, 0xa7cd, 0x10d50, 0x10d70, 0x10400,
           0x10428, 0xaa, 0x2b0, 0x345, 0x30, 0xb2, 0x2460, 0x1369, 0x660, 0x10d40, 0x116d0,
           0x1d7ce, 0xbd, 0x4e00, 0x1100, 0x231a, 0x1f600, 0xff21, 0xff61, 0xac00, 0x20000,
           0x3fffd, 0xe000, 0x16fe4, 0x1f1e6]
    return [{"cp": c, "space": chr(c).isspace(), "alpha": chr(c).isalpha(),
             "lower": chr(c).islower(), "upper": chr(c).isupper(), "digit": chr(c).isdigit(),
             "decimal": chr(c).isdecimal(),
             "wide": unicodedata.east_asian_width(chr(c)) in "WF"} for c in cps]


def synthetic():
    arrays = {}
    doc = {
        "text": synthetic_text(),
        "features": synthetic_features(arrays),
        "decode": synthetic_decode(arrays),
        "cluster": synthetic_cluster(arrays),
        "xycut": synthetic_xycut(arrays),
        "blocks_order": synthetic_blocks_order(arrays),
        "tables": synthetic_tables(arrays),
        "markdown": synthetic_markdown(arrays),
        "numeric": synthetic_numeric(arrays),
        "unicode": unicode_samples(),
    }
    np.savez_compressed(OUT / "synthetic.npz", **arrays)
    (OUT / "synthetic.json").write_text(json.dumps(doc, indent=1))
    print("synthetic", len(arrays), "arrays")


for name, make in PAGES.items():
    read_page(name, make(), table_heads=False)
for name in ("table", "table_unruled", "two_column"):
    read_page(name, PAGES[name](), table_heads=True)
synthetic()
