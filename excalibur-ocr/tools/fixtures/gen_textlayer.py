"""Fixtures for the PDF text layer: Indra OCR's own verdicts on the PDFs that
MakeTextLayerPdfs.java writes.

Run with the Python environment Indra OCR is installed in, after the PDFs exist:
    python tools/fixtures/gen_textlayer.py src/test/resources/fixtures/textlayer

For every page this stores what pypdfium2 reports about its geometry and
renders, and each text layer that Converter.pdf_page reads on the way to its
decision (the first read, then the read without /Rotate, turned, or vertical
when the first read calls for it).  document.json holds the document.py
helpers' results on inputs built from those layers.
"""
import json
import sys
from pathlib import Path

import numpy as np
import pypdfium2 as pdfium

from indra_ocr import document as D
from indra_ocr import textlayer as T

out = Path(sys.argv[1])
SCALES = (1.0, 150 / 72, 200 / 72, 300 / 72)


def box(b):
    """A box to 1e-4 pt: far finer than the agreement the tests ask for."""
    return [round(float(v), 4) for v in b]


def layer(tl):
    def line(r):
        return {
            "bbox": box(r.bbox),
            "text": r.text,
            "quarter": r.quarter,
            "seq": r.seq,
            "chars": [[c, box(b)] for c, b in r.chars],
        }

    return {
        "reason": tl.reason,
        "trusted": tl.trusted,
        "size": list(tl.size),
        "stats": tl.stats,
        "lines": [line(r) for r in tl.lines],
        "rotated": [line(r) for r in tl.rotated],
    }


def reads(page):
    """The text layers pdf_page reads, in order, with the step that read each."""
    s = D.CHECK_DPI / 72.0
    check = D._render(page, s)
    tl = T.read_text_layer(page, check, s)
    steps = [("first", tl)]
    k = T.page_turn(tl.stats)
    rot = page.get_rotation()
    if k and (rot + 90 * k) % 360 == 0:
        page.set_rotation(0)
        steps.append(("unrotated", T.read_text_layer(page, D._render(page, s), s)))
        page.set_rotation(rot)
        turned = np.ascontiguousarray(np.rot90(check, -k))
        steps.append(("turned", T.read_text_layer(page, turned, s, k)))
    elif k:
        turned = np.ascontiguousarray(np.rot90(check, -k))
        steps.append(("turned", T.read_text_layer(page, turned, s, k)))
    elif T.vertical_layout(tl.stats):
        upright = np.ascontiguousarray(np.rot90(check, 1))
        steps.append(("vertical", T.read_text_layer(page, upright, s, 0, 3)))
    return [{"step": name, "layer": layer(t)} for name, t in steps]


def geometry(page):
    renders = []
    for s in SCALES:
        gray = D._render(page, s)
        renders.append({
            "scale": s,
            "width": gray.shape[1],
            "height": gray.shape[0],
            "ink": float((gray < T.DARK).mean()),
        })
    return {
        "size": list(page.get_size()),
        "rotation": page.get_rotation(),
        "affine": T._device_affine(page).tolist(),
        "invisible": T.invisible_text_frac(page),
        "page_scale": min(200 / 72.0, (D.MAX_PAGE_PIXELS / max(
            page.get_size()[0] * page.get_size()[1], 1.0)) ** 0.5),
        "renders": renders,
    }


def document_cases(pages):
    """assign_runs on detected boxes made from each final layer's own runs."""
    cases = []
    for name, info in pages.items():
        runs_json = info["reads"][-1]["layer"]["lines"]
        if not runs_json:
            continue
        runs = [
            T.TextLine(tuple(r["bbox"]), r["text"],
                       [(c, tuple(b)) for c, b in r["chars"]], r["quarter"], r["seq"])
            for r in runs_json
        ]
        k = 200 / 72.0
        boxes = []
        for i, r in enumerate(runs[:12]):
            x0, y0, x1, y1 = (v * k for v in r.bbox)
            # Every third box is cut in half, so one run splits between two lines.
            if i % 3 == 2:
                mid = (x0 + x1) / 2
                boxes += [[x0 - 2, y0 - 2, mid, y1 + 2], [mid, y0 - 2, x1 + 2, y1 + 2]]
            else:
                boxes.append([x0 - 2, y0 - 2, x1 + 2, y1 + 2])
        boxes = np.array(boxes, np.float32)
        toks, lost = D.assign_runs(runs, boxes, k)
        cases.append({
            "page": name,
            "k": k,
            "boxes": boxes.tolist(),
            "tokens": [[[t[0], float(t[1]), t[2]] for t in line] for line in toks],
            "lost": lost,
            "covered": [D._covered(b, boxes[1:] if len(boxes) > 1 else boxes)
                        for b in boxes[:4]],
            "none": list(D.assign_runs(runs, np.zeros((0, 4), np.float32), k)[1:]),
        })
    texts = ["plain ascii", "Mixed καί ascii", "שלום world", "", "123 !?", "Ελληνικά"]
    return {
        "assign": cases,
        "latin_share": [[t, D.latin_share(t)] for t in texts],
        "right_to_left": [[t, D.right_to_left([(c, 0.0, 0) for c in t])] for t in texts],
        "layer_agrees": [[a, D.layer_agrees(a)] for a in (
            [], [True], [False], [False, False, False, True], [False] * 5 + [True])],
        "constants": {
            "CHECK_DPI": D.CHECK_DPI, "MAX_PAGE_PIXELS": D.MAX_PAGE_PIXELS,
            "READABLE_MIN": D.READABLE_MIN, "AGREE_LINES": D.AGREE_LINES,
            "AGREE_SHARE": D.AGREE_SHARE, "LATIN_CODES": D.LATIN_CODES,
            "NOT_THE_PAGE": D.NOT_THE_PAGE,
        },
    }


pages = {}
for pdf in sorted(out.glob("*.pdf")):
    doc = pdfium.PdfDocument(str(pdf))
    for i in range(len(doc)):
        page = doc[i]
        pages[f"{pdf.name}#{i}"] = {"geometry": geometry(page), "reads": reads(page)}
    doc.close()

(out / "expected.json").write_text(json.dumps(pages, ensure_ascii=False, separators=(",", ":")))
(out / "document.json").write_text(json.dumps(document_cases(pages), ensure_ascii=False, separators=(",", ":")))
