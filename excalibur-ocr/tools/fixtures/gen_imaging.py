"""Fixtures for the imaging port: OpenCV's, numpy's and Indra OCR's own results.

Covers cv2.resize, getRotationMatrix2D, warpAffine and connectedComponentsWithStats on uint8
images; numpy's float32 exp, argsort tie order and float arange, and CPython's set order;
det_post.page_to_input and decode; and pipeline.picture_boxes, _ink_thumb, _row_sharpness,
estimate_skew, preview, rotate_page, unrotate_box and orient_input. Inputs are synthetic:
random noise, cv2.putText pages and pages rotated by known angles.

OpenCV picks its vector code at run time, and some 8-bit results depend on it: these
fixtures are made on an x86-64 machine with AVX2 (and without AVX-512), the target the Java
port reproduces. Run with the Python environment Indra OCR is installed in
(opencv-python-headless 5.0, numpy 2.5):
    python tools/fixtures/gen_imaging.py src/test/resources/fixtures/imaging
"""
import sys
from pathlib import Path

import cv2
import numpy as np
from indra_ocr.det_post import decode, page_to_input
from indra_ocr.pipeline import (
    _ink_thumb,
    _row_sharpness,
    estimate_skew,
    orient_input,
    picture_boxes,
    preview,
    rotate_page,
    unrotate_box,
)

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
rng = np.random.default_rng(20261101)
FONTS = [cv2.FONT_HERSHEY_SIMPLEX, cv2.FONT_HERSHEY_DUPLEX, cv2.FONT_HERSHEY_COMPLEX]
WORDS = "the quick brown fox jumps over a lazy dog 1234 OCR Indra page line text".split()


def noise(h, w):
    """A random test image: uniform noise, binary noise, a narrow gray band or text."""
    kind = rng.integers(0, 4)
    if kind == 0:
        return rng.integers(0, 256, (h, w), dtype=np.uint8)
    if kind == 1:
        return rng.choice(np.array([0, 255], np.uint8), (h, w))
    if kind == 2:
        return rng.integers(100, 140, (h, w), dtype=np.uint8)
    a = np.full((h, w), 255, np.uint8)
    cv2.putText(a, "Ab3", (0, max(1, h - 2)), cv2.FONT_HERSHEY_SIMPLEX, max(0.2, h / 40), 0, 1)
    return a


def text_page(h, w, lines, scale_range=(0.5, 1.2), dark_band=False):
    """A white page of putText lines at a few sizes, optionally with a light-on-dark band."""
    page = np.full((h, w), 255, np.uint8)
    y = 30
    for _ in range(lines):
        s = float(rng.uniform(*scale_range))
        text = " ".join(rng.choice(WORDS, int(rng.integers(3, 9))))
        font = FONTS[int(rng.integers(0, len(FONTS)))]
        cv2.putText(page, text, (int(rng.integers(10, 40)), y), font, s, 0, 1 + int(s > 0.9))
        y += int(30 * s) + int(rng.integers(8, 20))
        if y > h - 10:
            break
    if dark_band:
        top = h // 2
        page[top:top + 40, 20:w - 20] = 40
        cv2.putText(page, "LIGHT ON DARK BAND", (30, top + 28), FONTS[0], 0.8, 230, 2)
    return page


def flat(arrays, dtype):
    return np.concatenate([np.asarray(a, dtype).ravel() for a in arrays]) if arrays else \
        np.zeros(0, dtype)


def gen_resize():
    """cv2.resize: random sizes, whole factors both ways, 1-pixel sides, both modes."""
    cases = []
    for _ in range(260):
        sh, sw = int(rng.integers(1, 40)), int(rng.integers(1, 40))
        dh, dw = int(rng.integers(1, 60)), int(rng.integers(1, 60))
        cases.append((sh, sw, dh, dw, int(rng.integers(0, 2))))
    for f in (2, 3, 4):
        for interp in (0, 1):
            cases.append((f * 7, f * 9, 7, 9, interp))      # exact shrink
            cases.append((5, 6, 5 * f, 6 * f, interp))      # exact enlarge
            cases.append((f * 6, 11, 6, 11 * 2, interp))    # shrink one side, grow the other
    for interp in (0, 1):
        cases += [(1, 1, 5, 7, interp), (9, 13, 1, 1, interp), (1, 20, 1, 7, interp),
                  (20, 1, 7, 1, interp), (1, 9, 4, 30, interp), (30, 30, 30, 29, interp),
                  (37, 23, 17, 11, interp), (16, 16, 24, 24, interp)]
    srcs, dsts, params = [], [], []
    for sh, sw, dh, dw, interp in cases:
        src = noise(sh, sw)
        mode = [cv2.INTER_LINEAR, cv2.INTER_AREA][interp]
        srcs.append(src)
        dsts.append(cv2.resize(src, (dw, dh), interpolation=mode))
        params.append((sw, sh, dw, dh, interp))
    np.savez_compressed(out / "resize.npz", params=np.array(params, np.int32),
                        src=flat(srcs, np.uint8), dst=flat(dsts, np.uint8))


def gen_warp():
    """getRotationMatrix2D + warpAffine: widths across the 16-pixel vector block, both borders."""
    srcs, dsts, params, mats = [], [], [], []
    shapes = [(int(rng.integers(1, 30)), int(rng.integers(1, 60))) for _ in range(150)]
    shapes += [(120, 160), (1, 1), (1, 40), (40, 1), (17, 33)]
    for h, w in shapes:
        src = noise(h, w) if (h, w) != (120, 160) else text_page(h, w, 5)
        angle = float(rng.choice([rng.uniform(-50, 50), rng.integers(-90, 91) * 0.5,
                                  np.round(rng.uniform(-3, 3), 1)]))
        if rng.random() < 0.75:
            centre = (w / 2, h / 2)
        else:
            centre = (float(rng.uniform(-5, w + 5)), float(rng.uniform(-5, h + 5)))
        scale = 1.0 if rng.random() < 0.8 else float(rng.uniform(0.5, 2))
        dw, dh = (w, h) if rng.random() < 0.8 else (int(rng.integers(1, 50)), int(rng.integers(1, 30)))
        m = cv2.getRotationMatrix2D(centre, angle, scale)
        for border in (0, 1):
            mode = cv2.BORDER_CONSTANT if border == 0 else cv2.BORDER_REPLICATE
            dst = cv2.warpAffine(src, m, (dw, dh), flags=cv2.INTER_LINEAR, borderMode=mode)
            srcs.append(src)
            dsts.append(dst)
            mats.append(m)
            params.append((w, h, dw, dh, border, centre[0], centre[1], angle, scale))
    np.savez_compressed(out / "warp.npz", params=np.array(params, np.float64),
                        matrix=np.array(mats, np.float64), src=flat(srcs, np.uint8),
                        dst=flat(dsts, np.uint8))


def skew_angles():
    """Every angle estimate_skew can try on a page, as numpy computes them."""
    grid = np.arange(-45.0, 45.0 + 1e-6, 2.0)
    starts = {0.0, *(float(g) for g in grid)}
    near = {float(a) for a0 in starts for a in np.arange(a0 - 2.0, a0 + 2.0 + 1e-6, 0.5)}
    fine = {float(a) for a1 in near for a in np.arange(a1 - 0.5, a1 + 0.5 + 1e-6, 0.1)}
    return sorted(set(grid.tolist()) | near | fine)


def gen_rotation():
    """getRotationMatrix2D at every skew-search angle (both signs) and random ones."""
    angles = skew_angles()
    angles = angles + [-a for a in angles] + list(rng.uniform(-180, 180, 300))
    rows = []
    for a in angles:
        w, h = int(rng.integers(1, 3000)), int(rng.integers(1, 3000))
        m = cv2.getRotationMatrix2D((w / 2, h / 2), float(a), 1.0)
        rows.append((w / 2, h / 2, float(a), *m.ravel()))
    np.savez_compressed(out / "rotation.npz", rows=np.array(rows, np.float64))


def gen_components():
    """connectedComponentsWithStats at both connectivities; tall masks take OpenCV's
    parallel path."""
    masks, params, labels, stats = [], [], [], []
    shapes = [(int(rng.integers(1, 40)), int(rng.integers(1, 40))) for _ in range(120)]
    shapes += [(int(rng.integers(60, 100)), int(rng.integers(20, 60))) for _ in range(12)]
    for i, (h, w) in enumerate(shapes):
        p = [0.0, 0.05, 0.3, 0.5, 0.6, 0.9, 1.0][i % 7]
        m = (rng.random((h, w)) < p).astype(np.uint8)
        for conn in (4, 8):
            n, lab, st, _ = cv2.connectedComponentsWithStats(m, connectivity=conn)
            masks.append(m)
            params.append((w, h, conn, n))
            labels.append(lab)
            stats.append(st)
    np.savez_compressed(out / "components.npz", params=np.array(params, np.int32),
                        mask=flat(masks, np.uint8), labels=flat(labels, np.int32),
                        stats=flat(stats, np.int32))


def gen_numpy_order():
    """np.argsort tie order, float np.arange, CPython set order and float32 np.exp."""
    keys, sizes, orders = [], [], []
    for n in list(rng.integers(2, 60, 120)) + [46, 46, 46, 200, 256]:
        n = int(n)
        k = rng.integers(0, int(rng.integers(1, 6)), n).astype(np.float64)
        if rng.random() < 0.2:
            k = np.sort(k)
        keys.append(k)
        sizes.append(n)
        orders.append(np.argsort(k))
    aranges = [(-45.0, 45.0 + 1e-6, 2.0)]
    for a in (0.0, -44.5, 17.5, -0.5, 3.0):
        aranges.append((a - 2.0, a + 2.0 + 1e-6, 0.5))
    for a in (0.0, 0.3, -12.7, 44.9, -1.1, 2.6000000000000005):
        aranges.append((a - 0.5, a + 0.5 + 1e-6, 0.1))
    aranges += [(0.1, 1.0, 0.3), (5.0, 1.0, 0.5), (1.0, 1.5, 1.0)]
    arange_values = [np.arange(*r) for r in aranges]
    odd = np.arange(-45, 46, 2, dtype=np.float64)
    pairs, set_orders = [], []
    for a in odd:
        for b in odd:
            if a != b:
                pairs.append((a, b))
                set_orders.append(list({0.0, float(a), float(b)}))
    x = np.concatenate([
        rng.normal(0, 6, 3000), rng.uniform(-110, 95, 2000),
        [0.0, -0.0, 1e-8, -1e-8, 88.7, 88.72283935546875, 88.73, -103.9, -103.97208404541015625,
         -104.0, -87.0, -88.0, -100.0, np.inf, -np.inf, np.nan],
    ]).astype(np.float32)
    np.savez_compressed(
        out / "numpy_order.npz",
        argsort_sizes=np.array(sizes, np.int32), argsort_keys=flat(keys, np.float64),
        argsort_orders=flat(orders, np.int32),
        arange_params=np.array(aranges, np.float64),
        arange_sizes=np.array([len(v) for v in arange_values], np.int32),
        arange_values=flat(arange_values, np.float64),
        set_pairs=np.array(pairs, np.float64), set_orders=np.array(set_orders, np.float64),
        exp_x=x, exp_y=np.exp(x),
    )


def detector_output(h, w, lines):
    """A synthetic detector output [3, h, w]: core logits with line bands, noise and
    near-zero values, and positive edge distances."""
    logit = rng.normal(-4, 1.5, (h, w)).astype(np.float32)
    for _ in range(lines):
        y0 = int(rng.integers(0, h - 2))
        x0 = int(rng.integers(0, w - 3))
        logit[y0:y0 + int(rng.integers(1, 4)), x0:x0 + int(rng.integers(2, w // 2))] = \
            rng.normal(3, 1.5)
    logit[rng.random((h, w)) < 0.01] = 2.0                        # single pixels
    logit[rng.random((h, w)) < 0.01] = np.float32(1e-7)           # just above 0.5
    logit[0, :3] = [np.float32(-1e-7), np.float32(3e-8), np.float32(0.0)]
    dist = rng.uniform(1, 12, (2, h, w)).astype(np.float32)
    return np.concatenate([logit[None], dist]).astype(np.float32)


def gen_detect():
    """page_to_input on pages larger and smaller than the long side (one size lands on .5),
    decode on synthetic outputs, picture_boxes on synthetic picture logits."""
    arrays = {}
    pages = [text_page(300, 220, 8), text_page(90, 130, 3), noise(37, 51)]
    # 101 x 26 to long side 202: 26 * 2.0 = 52 exactly; 75 x 25 to long side 30:
    # 25 * 0.4 = 10.000000000000002; 125 x 35 to 50: 35 * 0.4 = 14.0; 5 x 3 to 3: 3 * 0.6 = 1.8;
    # 7 x 5 to 3: 5 * (3 / 7) = 2.142...; 50 x 5 to 45: 5 * 0.9 = 4.5 rounds half to even, to 4.
    pages += [noise(26, 101), noise(25, 75), noise(35, 125), noise(5, 50), noise(3, 5)]
    sides = [[256, 220], [96, 200, 130], [64, 33], [202], [30], [50], [45], [3]]
    k = 0
    for page, longs in zip(pages, sides):
        for long_side in longs:
            ink, scale = page_to_input(page, long_side)
            arrays[f"pi{k}_page"] = page
            arrays[f"pi{k}_long"] = np.array(long_side, np.int64)
            arrays[f"pi{k}_ink"] = ink
            arrays[f"pi{k}_scale"] = np.array(scale, np.float64)
            k += 1
    for k, (h, w, lines) in enumerate([(48, 64, 6), (30, 40, 3), (64, 96, 12), (8, 8, 0),
                                       (40, 60, 8)]):
        o = detector_output(h, w, lines)
        if lines == 0:
            o[0] = -5
        arrays[f"dec{k}_out"] = o
        arrays[f"dec{k}_boxes"] = decode(o)
    o = detector_output(48, 64, 6)
    arrays["decg_out"] = o
    arrays["decg_boxes"] = decode(o, thresh=0.3, min_area=4, grow_y=0.1, grow_x=0.05)
    for k, (h, w, scale, page_hw) in enumerate([(40, 50, 0.37, (1000, 800)),
                                                (32, 32, 1.0, (64, 64)),
                                                (24, 30, 0.5, (90, 100))]):
        logit = rng.normal(-3, 1, (h, w)).astype(np.float32)
        for _ in range(5):
            y, x = int(rng.integers(0, h - 2)), int(rng.integers(0, w - 2))
            logit[y:y + int(rng.integers(1, 15)), x:x + int(rng.integers(1, 15))] = 2.0
        logit[h - 3:, w - 3:] = 1.0                              # a region at the corner
        logit[5, 5] = 0.0                                         # exactly at the threshold
        arrays[f"pic{k}_logit"] = logit
        arrays[f"pic{k}_params"] = np.array([scale, page_hw[0], page_hw[1]], np.float64)
        arrays[f"pic{k}_boxes"] = picture_boxes(logit, scale, page_hw)
    np.savez_compressed(out / "detect.npz", **arrays)


def gen_geometry():
    """Skew pages at known tilts, a blank page, a page of little text and a rotation-invariant
    disk (every score ties); plus preview, orient_input, rotate_page and unrotate_box.

    Tilted pages are stored untilted with the tilt rotate_page gave them, which the Java test
    reapplies; rotate_page itself is checked against the rot* cases."""
    arrays = {}
    text = text_page(560, 440, 24)
    little = np.full((480, 360), 255, np.uint8)
    cv2.putText(little, "Indra", (100, 240), FONTS[0], 1.8, 0, 6)
    disk = np.full((300, 300), 255, np.uint8)
    cv2.circle(disk, (150, 150), 50, 0, -1)
    band = text_page(600, 860, 18, dark_band=True)
    bases = {"text": text, "blank": np.full((400, 320), 255, np.uint8), "little": little,
             "disk": disk, "band": band}
    cases = [("text", 0.0), ("text", 1.7), ("text", -3.2), ("text", 7.5), ("text", -12.3),
             ("text", 31.0), ("blank", 0.0), ("little", 4.0), ("disk", 0.0), ("band", -2.4)]
    for name, page in bases.items():
        arrays[f"base_{name}"] = page
    for k, (name, tilt) in enumerate(cases):
        page = rotate_page(bases[name], tilt) if tilt else bases[name]
        arrays[f"skew{k}_case"] = np.array([list(bases).index(name), tilt], np.float64)
        arrays[f"skew{k}_angle"] = np.array(estimate_skew(page), np.float64)
        arrays[f"skew{k}_side300"] = np.array(estimate_skew(page, 300), np.float64)
    tilted = rotate_page(text, 1.7)
    coarse = _ink_thumb(tilted, 400)
    arrays["thumb_fine"] = _ink_thumb(tilted, 800)
    arrays["thumb_coarse"] = coarse
    grid = np.arange(-45.0, 45.0 + 1e-6, 2.0)
    arrays["sharp_angles"] = grid
    arrays["sharp_scores"] = np.array([_row_sharpness(coarse, a) for a in grid], np.float64)

    small = text_page(240, 170, 8)
    arrays["prev_page"] = small
    arrays["prev_100"] = preview(small, 100)
    arrays["prev_77"] = preview(small, 77)
    arrays["prev_same"] = preview(small, 400)
    arrays["orient_768"] = orient_input(small)
    arrays["orient_64"] = orient_input(small, 64)
    wide = noise(20, 90)
    arrays["orient_wide_page"] = wide
    arrays["orient_wide"] = orient_input(wide, 45)
    for k, angle in enumerate((2.5, -7.3, 0.4, 30.0)):
        arrays[f"rot{k}_angle"] = np.array(angle, np.float64)
        arrays[f"rot{k}_out"] = rotate_page(small, angle)
    rows = []
    for _ in range(400):
        w, h = int(rng.integers(50, 3000)), int(rng.integers(50, 3000))
        deg = float(rng.choice([rng.uniform(-45, 45), np.round(rng.uniform(-5, 5), 1)]))
        x0, x1 = sorted(rng.uniform(-50, w + 50, 2))
        y0, y1 = sorted(rng.uniform(-50, h + 50, 2))
        box = [round(float(v), 1) for v in (x0, y0, x1, y1)]
        rows.append((*box, deg, w, h, *unrotate_box(box, deg, (w, h))))
    for box, deg, wh in [([0.0, 0.0, 10.0, 10.0], 0.0, (100, 100)),
                         ([0.25, 0.35, 99.75, 49.95], 0.0, (100, 50)),
                         ([-5.0, -5.0, 120.0, 70.0], 0.0, (100, 50))]:
        rows.append((*box, deg, *wh, *unrotate_box(box, deg, wh)))
    arrays["unrot_rows"] = np.array(rows, np.float64)
    np.savez_compressed(out / "geometry.npz", **arrays)


gen_resize()
gen_warp()
gen_rotation()
gen_components()
gen_numpy_order()
gen_detect()
gen_geometry()
