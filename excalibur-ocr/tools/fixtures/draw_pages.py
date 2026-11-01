"""Synthetic pages for the pipeline's fixtures: lines of text in several scripts,
drawn with Pillow, with their boxes.  Needs only Pillow and numpy (not Indra):

    python draw_pages.py <out dir>

Writes pages.npz: page<k> (uint8 [H, W]) and boxes<k> (float32 [N, 5]: x0, y0,
x1, y1, score), the input of gen_recognize.py and the engine's tests.
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

LATIN = "/usr/share/fonts/TTF/DejaVuSans.ttf"
HEBREW = "/usr/share/fonts/noto/NotoSansHebrew-Regular.ttf"
ARABIC = "/usr/share/fonts/noto/NotoSansArabic-Regular.ttf"
CJK = "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc"

PAGES = [
    [
        (LATIN, 30, "The quick brown fox jumps over the lazy dog."),
        (LATIN, 22, "Results: 42 samples, mean 3.75 (s.d. 0.41)."),
        (LATIN, 26, "Привет, мир — Ελληνικά γράμματα"),
        (HEBREW, 28, "שלום עולם, זהו מבחן"),
        (ARABIC, 28, "مرحبا بالعالم هذا اختبار"),
        (CJK, 28, "日本語の文章を読みます。"),
        (CJK, 28, "中文文本识别测试"),
        (LATIN, 18, "• first item of a list"),
        (LATIN, 24, "Table 3   Value   12.5   Total"),
        (LATIN, 20, "faint words, hard to read", "faint"),
        (LATIN, 20, "blurred line of handwriting", "blur"),
        (LATIN, 14, "a small footnote at the bottom of the page, 2026"),
    ],
]


def draw(lines, width=1100):
    height = 40 + sum(int(size * 2.2) for _, size, *_ in lines)
    img = Image.new("L", (width, height), 255)
    d = ImageDraw.Draw(img)
    boxes = []
    y = 20
    for font_path, size, text, *style in lines:
        font = ImageFont.truetype(font_path, size)
        ink = 170 if "faint" in style else 0
        x = 40
        if font_path in (HEBREW, ARABIC):
            x = width - 40 - int(d.textlength(text, font=font))
        if "blur" in style:
            layer = Image.new("L", (width, int(size * 2.2)), 255)
            ImageDraw.Draw(layer).text((x, 0), text, font=font, fill=ink)
            layer = layer.filter(ImageFilter.GaussianBlur(1.6))
            img.paste(layer, (0, y))
            bbox = ImageDraw.Draw(layer).textbbox((x, 0), text, font=font)
            bbox = (bbox[0], bbox[1] + y, bbox[2], bbox[3] + y)
        else:
            d.text((x, y), text, font=font, fill=ink)
            bbox = d.textbbox((x, y), text, font=font)
        boxes.append([bbox[0] - 1.5, bbox[1] - 1.25, bbox[2] + 1.5, bbox[3] + 1.25, 0.9])
        y += int(size * 2.2)
    return np.asarray(img, np.uint8), np.array(boxes, np.float32)


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    arrays = {}
    for k, lines in enumerate(PAGES):
        page, boxes = draw(lines)
        arrays[f"page{k}"] = page
        arrays[f"boxes{k}"] = boxes
    np.savez_compressed(out / "pages.npz", **arrays)


if __name__ == "__main__":
    main()
