"""Writes what Indra OCR makes of whole synthetic PDFs, page by page, for DocumentParityTest.

Usage: python gen_documents.py <model dir> <out dir> <pdf>...

For each PDF and each case (mode, math, keep_furniture), the markdown of every page as the
indra-ocr command line writes it: a page's markdown, or its text without one. Every page image
pdfium drew on the way is kept too (documents.npz, listed under "renders"), so the test reads
the same pixels and compares the conversion itself, not two renderers. Run with indra-ocr 1.1.2
and the model revision IndraModels pins; the PDFs are synthetic (MakeTextLayerPdfs.java,
MakeScanPdfs.java).
"""

import json
import sys
from pathlib import Path

import numpy as np

import indra_ocr.document as document
from indra_ocr.document import Converter
from indra_ocr.pipeline import OrtOCR

RENDERS: dict[tuple, np.ndarray] = {}
CURRENT: list = [None, None]


def recording(render):
    """_render, keeping each image under (pdf, page, rotation, scale)."""

    def wrapped(page, scale):
        gray = render(page, scale)
        key = (CURRENT[0], CURRENT[1], page.get_rotation(), float(scale))
        if key in RENDERS:
            assert np.array_equal(RENDERS[key], gray), key
        RENDERS[key] = gray
        return gray

    return wrapped


def tracking(pdf_page):
    """Converter.pdf_page, noting the page it reads."""

    def wrapped(self, pdf_path, doc, index):
        CURRENT[0], CURRENT[1] = Path(pdf_path).name, index
        return pdf_page(self, pdf_path, doc, index)

    return wrapped


document._render = recording(document._render)
Converter.pdf_page = tracking(Converter.pdf_page)

CASES = [
    {"mode": "auto", "math": False, "furniture": False},
    {"mode": "ocr", "math": False, "furniture": False},
    {"mode": "text", "math": False, "furniture": False},
    {"mode": "auto", "math": True, "furniture": True},
]


def main() -> None:
    model_dir, out_dir, pdfs = sys.argv[1], Path(sys.argv[2]), sys.argv[3:]
    out = {"cases": []}
    for case in CASES:
        ocr = OrtOCR(model_dir, threads=1, furniture=case["furniture"], math=case["math"])
        conv = Converter(ocr, dpi=200.0, mode=case["mode"], image_ocr=True)
        docs = {}
        for pdf in pdfs:
            pages = []
            for _, page in conv.convert(pdf):
                assert page.source != "error", (pdf, page.note)
                pages.append(
                    {
                        "part": page.markdown if page.markdown is not None else page.text,
                        "source": page.source,
                    }
                )
            docs[Path(pdf).name] = pages
        out["cases"].append({**case, "documents": docs})
    renders, arrays = [], {}
    for n, ((name, index, rotation, scale), gray) in enumerate(sorted(RENDERS.items())):
        renders.append(
            {"pdf": name, "page": index, "rotation": rotation, "scale": scale, "key": f"r{n}"}
        )
        arrays[f"r{n}"] = gray
    out["renders"] = renders
    out_dir.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out_dir / "documents.npz", **arrays)
    (out_dir / "documents.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8"
    )


if __name__ == "__main__":
    main()
