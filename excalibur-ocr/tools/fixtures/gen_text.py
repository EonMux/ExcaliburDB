"""Fixtures for the text and lm packages: Indra OCR's own results for vocab.py,
rtl.py and lm.py.

Run with the Python environment Indra OCR is installed in, from excalibur-ocr:
    python tools/fixtures/gen_text.py src/test/resources/fixtures/text [model dir]

The models come from the model directory argument, else from
indra_ocr.models.model_dir() (INDRA_OCR_MODEL_DIR, else a Hugging Face download).
No model file is copied: the Java tests read meta.json and the lm*.npz files from
the model directory, and these fixtures hold only inputs and Python's outputs.
The output is deterministic: every random choice comes from fixed seeds.
"""
import json
import sys
import unicodedata
from pathlib import Path

import cv2
import numpy as np

import indra_ocr.lm as lm_mod
from indra_ocr.lm import BOS, NgramLM, _pack
from indra_ocr.models import model_dir
from indra_ocr.pipeline import OrtOCR
from indra_ocr.rtl import clusters, flip, has_rtl, is_rtl, normalize, rtl_base
from indra_ocr.vocab import Vocab, canonical

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
models = model_dir(sys.argv[2] if len(sys.argv) > 2 else None)
meta = json.loads((models / "meta.json").read_text())
rng = np.random.default_rng(11)
VOCABS = ("vocab", "vocab_world", "vocab_rtl", "vocab_cjk")
LMS = (("base", "lm.npz"), ("world", "lm_world.npz"), ("cjk", "lm_cjk.npz"))


def write_json(name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")))


# --- vocab.py --------------------------------------------------------------


def decode_cases(v: Vocab) -> list[list[int]]:
    n = len(v.chars)
    cases = [[], [0, 0], [-1, n + 1, n + 7], [1, 0, 1, 1]]
    for _ in range(120):
        cases.append(rng.integers(-2, n + 3, int(rng.integers(0, 30))).tolist())
    if v.has_commands:
        commands = [i + 1 for i, c in enumerate(v.chars) if len(c) > 1]
        letters = [v.index[c] for c in "axZé1 {(" if c in v.index]
        for cmd in commands:
            cases.append([cmd, int(rng.choice(letters))])
        for _ in range(60):
            pool = commands + letters
            cases.append([int(rng.choice(pool)) for _ in range(int(rng.integers(1, 12)))])
    if v.jamo:
        lead = [v.index[c] for c in map(chr, range(0x1100, 0x1113)) if c in v.index]
        vowel = [v.index[c] for c in map(chr, range(0x1161, 0x1176)) if c in v.index]
        trail = [v.index[c] for c in map(chr, range(0x11A8, 0x11C3)) if c in v.index]
        for _ in range(60):
            ids = []
            for _ in range(int(rng.integers(1, 6))):
                ids += [int(rng.choice(lead)), int(rng.choice(vowel))]
                if rng.random() < 0.5:
                    ids.append(int(rng.choice(trail)))
            cases.append(ids)
    return cases


def gen_vocab():
    result = {}
    for name in VOCABS:
        v = Vocab.from_json(meta[name])
        result[name] = {
            "n_classes": v.n_classes,
            "has_commands": v.has_commands,
            "jamo": v.jamo,
            "index": {c: v.index[c] for c in v.chars[:: max(1, len(v.chars) // 40)]},
            "cases": [{"ids": ids, "text": v.decode(ids)} for ids in decode_cases(v)],
        }
    write_json("vocab_decode.json", result)

    texts = [
        "Ș ș Ț ț ▪ ● ∙ ‣ ⁃ plain",
        "''quoted'' and ``latex'' and '''",
        "....  ..... ____ ---- ==== ···· **** ~~~~ ... ___ -- =",
        "a.....b......c-------d",
        "é Å 각 Å",
        "",
        "~~~~~~~~~~~~ x ·····",
    ]
    pool = list("._-=·*~'` aȘș▪●é")
    for _ in range(80):
        texts.append("".join(rng.choice(pool, int(rng.integers(0, 24)))))
    write_json("canonical.json", [{"in": t, "out": canonical(t)} for t in texts])


# --- rtl.py ----------------------------------------------------------------


def rtl_pool() -> list[str]:
    ranges = [
        (0x05D0, 0x05EA),  # Hebrew letters
        (0x05B0, 0x05BC),  # niqqud
        (0x0627, 0x064A),  # Arabic letters
        (0x064B, 0x0652),  # harakat
        (0x0660, 0x0669),  # Arabic-Indic digits (AN)
        (0x06F0, 0x06F9),  # extended Arabic-Indic digits (EN)
        (0x0710, 0x0715),  # Syriac (AL)
        (0x0780, 0x0785),  # Thaana (AL)
        (0x07CA, 0x07CF),  # NKo (R)
        (0xFE80, 0xFE90),  # Arabic presentation forms B
        (0xFB50, 0xFB58),  # Arabic presentation forms A
        (0xFB1D, 0xFB22),  # Hebrew presentation forms
        (0x20D0, 0x20D2),  # combining enclosing / overlay marks
    ]
    chars = [chr(c) for a, b in ranges for c in range(a, b + 1)]
    chars += list("abcXYZ0123 .,:;!?-/()[]{}<>«»‹›\"'ـ⃝́")
    chars += ["\U00010800", "\U0001E900", "\U0001E944", "\U0001F600", "\U0001D400"]
    rtl_vocab = Vocab.from_json(meta["vocab_rtl"]).chars
    return chars + list(rng.choice(rtl_vocab, 120))


def gen_rtl():
    texts = [
        "",
        "hello world",
        "שלום עולם",
        "مرحبا بالعالم",
        "abc שלום def",
        "שלום abc 123 עולם",
        "(שלום) [abc] {עולם}",
        "ـمـرحـبـا",
        "َبَ",
        "ﻣﺮﺣﺒﺎ ﷺ",
        "مرحبا 123 world (test) ٤٥٦",
        "۱۲۳ عدد",
        "\U0001E900\U0001E944\U0001E901 abc",
        "x \U00010800\U00010801 y \U0001F600",
    ]
    pool = rtl_pool()
    for _ in range(400):
        texts.append("".join(rng.choice(pool, int(rng.integers(1, 30)))))
    cases = []
    for t in texts:
        cases.append(
            {
                "text": t,
                "has_rtl": has_rtl(t),
                "rtl_base": rtl_base(t),
                "is_rtl": [is_rtl(c) for c in t],
                "clusters": clusters(t),
                "normalize": normalize(t),
                "flip": flip(t),
                "flip_plain": flip(t, mirror=False),
                "flip_rtl": flip(t, base_rtl=True),
                "flip_ltr": flip(t, base_rtl=False),
                "flip_ltr_plain": flip(t, mirror=False, base_rtl=False),
            }
        )
    write_json("rtl.json", cases)


def gen_unicode():
    """Python's general category and bidi class of every vocabulary character,
    as runs [first, last, bidi, category] of consecutive code points."""
    cps = sorted(
        {ord(c) for name in VOCABS for c in Vocab.from_json(meta[name]).chars if len(c) == 1}
    )
    runs = []
    for cp in cps:
        c = chr(cp)
        props = (unicodedata.bidirectional(c), unicodedata.category(c))
        if runs and runs[-1][1] == cp - 1 and tuple(runs[-1][2:]) == props:
            runs[-1][1] = cp
        else:
            runs.append([cp, cp, *props])
    write_json("unicode_props.json", {"unidata_version": unicodedata.unidata_version, "runs": runs})


# --- lm.py: logp and logp_keys ---------------------------------------------


def unpack(key: int, count: int, bits: int) -> list[int]:
    toks = []
    for _ in range(count):
        f = key & ((1 << bits) - 1)
        toks.append(BOS if f == 0 else f - 1)
        key >>= bits
    return toks[::-1]


def gen_logp(name: str, lm: NgramLM, n_cases: int = 1500):
    hists, ts = [], []
    for _ in range(n_cases):
        n = int(rng.integers(0, lm.order))
        r = rng.random()
        if r < 0.6:  # an n-gram the model saw, sometimes with a longer history
            key = int(rng.choice(lm.k[n]))
            toks = unpack(key, n + 1, lm.bits)
            hist, t = toks[:-1], toks[-1]
            if t == BOS:
                t = int(rng.integers(0, lm.vocab_size))
            if rng.random() < 0.3:
                hist = rng.integers(-1, lm.vocab_size, int(rng.integers(1, 4))).tolist() + hist
        elif r < 0.8:  # a seen n-gram with one token changed: a back-off
            key = int(rng.choice(lm.k[n]))
            toks = unpack(key, n + 1, lm.bits)
            hist, t = toks[:-1], int(rng.integers(0, lm.vocab_size))
            if hist and rng.random() < 0.5:
                hist[int(rng.integers(0, len(hist)))] = int(rng.integers(0, lm.vocab_size))
        else:  # random history, from the line start or not
            hist = rng.integers(0, lm.vocab_size, int(rng.integers(0, lm.order + 2))).tolist()
            if rng.random() < 0.5:
                hist = [BOS] + hist
            t = int(rng.integers(0, lm.vocab_size))
        hists.append([int(x) for x in hist])
        ts.append(int(t))
    width = max(len(h) for h in hists)
    hist_arr = np.full((n_cases, max(width, 1)), -2, np.int32)
    for i, h in enumerate(hists):
        hist_arr[i, : len(h)] = h
    logp = np.array([lm.logp(tuple(h), t) for h, t in zip(hists, ts)], np.float64)
    # logp_keys reads the last order - 1 history tokens, BOS before the line start.
    keys = [_pack(([BOS] * (lm.order - 1) + h)[-(lm.order - 1) :] + [t], lm.bits) for h, t in zip(hists, ts)]
    fresh = NgramLM(models / dict(LMS)[name])
    keyed = np.array(fresh.logp_keys(keys), np.float64)
    return {
        f"{name}_hist": hist_arr,
        f"{name}_hlen": np.array([len(h) for h in hists], np.int32),
        f"{name}_t": np.array(ts, np.int32),
        f"{name}_logp": logp,
        f"{name}_keys": np.array(keys, np.uint64),
        f"{name}_logp_keys": keyed,
        f"{name}_max_logp": np.array(lm.max_logp, np.float64),
    }


# --- lm.py: beam_search on real recognizer outputs -------------------------

SENTENCES = [
    "The quick brown fox jumps over the lazy dog",
    "Invoice 2024-117 total due: $1,284.50",
    "Please return the signed form by Friday",
    "Meeting notes: budget, hiring and schedule",
    "handwritten letters are hard to read",
    "Call me at 555-0142 after six o'clock",
    "received with thanks, J. Morrison",
    "the committee approved the proposal",
    "Room 12B, second floor, north wing",
    "temperature fell to 3 degrees overnight",
]
FONTS = [cv2.FONT_HERSHEY_SCRIPT_SIMPLEX, cv2.FONT_HERSHEY_SCRIPT_COMPLEX, cv2.FONT_HERSHEY_SIMPLEX]
LINE_W, LINE_H = 1500, 100


def hand_line(text: str, r, s: float) -> np.ndarray:
    """One line written a character at a time, handwriting-like: each character's
    size, baseline, spacing and stroke width wander, the line is sheared, read at
    low resolution and noisy, all more with the strength s."""
    line = np.full((LINE_H, LINE_W), 245, np.uint8)
    font = FONTS[int(r.integers(0, len(FONTS)))]
    x, base = 20.0, 65.0
    for ch in text:
        scale = 1.1 * (1 + r.normal(0, 0.12 * s))
        base = float(np.clip(base + r.normal(0, 1.2 * s), 50, 80))
        thick = int(np.clip(round(r.normal(2, 0.7 * s)), 1, 4))
        cv2.putText(line, ch, (int(x), int(base)), font, scale, int(r.integers(30, 120)), thick, cv2.LINE_AA)
        (w, _), _ = cv2.getTextSize(ch, font, scale, thick)
        x += w * (1 + r.normal(0, 0.15 * s)) + r.normal(0, 2 * s)
        if x > LINE_W - 50:
            break
    shear = r.normal(0, 0.25 * s)
    line = cv2.warpAffine(line, np.float32([[1, shear, -shear * 50], [0, 1, 0]]), (LINE_W, LINE_H), borderValue=245)
    f = 1 + 2.5 * s
    small = cv2.resize(line, (int(LINE_W / f), int(LINE_H / f)), interpolation=cv2.INTER_AREA)
    line = cv2.resize(small, (LINE_W, LINE_H), interpolation=cv2.INTER_LINEAR)
    noise = r.normal(0, 4 + 30 * s, line.shape)
    return np.clip(line + noise, 0, 255).astype(np.uint8)


def page(seed: int) -> np.ndarray:
    """Ten hand_line lines of varied strength, so many fall under the LM gate."""
    r = np.random.default_rng(seed)
    img = np.full((1100, LINE_W), 245, np.uint8)
    for i in range(10):
        text = SENTENCES[(seed + i) % len(SENTENCES)]
        img[i * 105 + 20 : i * 105 + 20 + LINE_H] = hand_line(text, r, float(r.uniform(0.4, 1.5)))
    return img


def capture_beam(ocr: OrtOCR):
    """Runs the pipeline's recognize on drawn pages with beam_search wrapped, so
    every call the pipeline makes is recorded with its arguments and result."""
    calls = []
    names = {}
    for name, sess in [("base", ocr.rec)] + [(g, s) for g, (s, _) in ocr.groups.items()]:
        if id(sess) in ocr.lms:
            names[id(ocr.lms[id(sess)][0])] = name
    original = lm_mod.beam_search

    def spy(blank, topv, topi, lm, alpha=0.5, beta=1.5, beam=8, blank_skip=0.999, cand_floor=1e-3):
        ids, steps = original(blank, topv, topi, lm, alpha, beta, beam, blank_skip, cand_floor)
        calls.append(
            dict(
                blank=np.array(blank),
                topv=np.array(topv),
                topi=np.array(topi),
                lm=names[id(lm)],
                alpha=alpha,
                beta=beta,
                beam=beam,
                blank_skip=blank_skip,
                cand_floor=cand_floor,
                ids=ids,
                steps=steps,
            )
        )
        return ids, steps

    lm_mod.beam_search = spy
    try:
        for seed in range(6):
            gray = page(seed)
            boxes = ocr.detect(gray)
            ocr.recognize(gray, boxes)
            for group in ("world", "cjk"):
                routed = lambda crops, batch=64, g=group: ([g] * len(crops), [True] * len(crops))
                ocr.line_groups = routed
                ocr.recognize(gray, boxes)
                del ocr.line_groups
    finally:
        lm_mod.beam_search = original
    return calls, original


def gen_beam(ocr: OrtOCR):
    calls, beam_search = capture_beam(ocr)
    per_lm = {}
    for c in calls:
        per_lm.setdefault(c["lm"], []).append(c)
    print({k: len(v) for k, v in per_lm.items()})
    limit = {"base": 30, "world": 12, "cjk": 8}
    chosen = [c for k, v in per_lm.items() for c in v[: limit[k]]]
    inputs = [(c["blank"], c["topv"], c["topi"], c["lm"]) for c in chosen]

    # Steps the blank-skip branch must take: a real line with some steps made
    # all but certainly blank.
    b, v, i, name = inputs[0]
    b = b.copy()
    b[::3] = np.float32(-1e-5)
    inputs.append((b, v, i, name))
    # Candidates exactly at the float32 rounding of log(cand_floor): numpy
    # compares a float32 with a Python float in float32.
    b, v, i, name = inputs[1]
    v = v.copy()
    v[:, 1] = np.float32(np.log(3e-3))
    v[:, 2] = np.nextafter(np.float32(np.log(3e-3)), np.float32(-np.inf))
    inputs.append((b, v, i, name))
    # An empty line.
    inputs.append((b[:0], v[:0], i[:0], name))
    # Exact ties: two classes equally likely at every step, and no LM weight to
    # tell them apart, so only the sort's stability decides between them.
    width = inputs[0][1].shape[1]
    tie_v = np.full((3, width), -20.0, np.float32)
    tie_v[:, :3] = np.log(np.float32([0.2, 0.3, 0.3]))
    tie_i = np.tile(np.arange(width) + 4, (3, 1))
    tie_i[:, 0] = 0
    inputs.append((np.log(np.full(3, 0.2, np.float32)), tie_v, tie_i, "base"))
    ties = [(len(inputs) - 1, (0.0, 0.0, beam, 0.999, 3e-3)) for beam in (1, 2, 8)]

    lms = {name: NgramLM(models / f) for name, f in LMS}
    params = {c_idx: (c["alpha"], c["beta"], c["beam"], c["blank_skip"], c["cand_floor"])
              for c_idx, c in enumerate(chosen)}
    cases = []
    for k in range(len(inputs)):
        p = params.get(k, (0.5, 1.5, 8, 0.999, 3e-3))
        cases.append((k, p))
        if k % 4 == 0:
            for variant in ((p[0], p[1], 1, p[3], p[4]), (p[0], p[1], 3, p[3], 1e-3),
                            (0.0, 0.0, 8, 0.99, 1e-2), (1.2, 0.3, 16, 0.999, 1e-4)):
                cases.append((k, variant))
    cases += ties
    result = []
    for k, (alpha, beta, beam, skip, floor) in cases:
        blank, topv, topi, name = inputs[k]
        ids, steps = beam_search(blank, topv, topi, lms[name], alpha, beta, beam, skip, floor)
        result.append((k, name, alpha, beta, beam, skip, floor, ids, steps))

    lm_ids = {name: n for n, (name, _) in enumerate(LMS)}
    lengths = np.array([len(x[0]) for x in inputs], np.int32)
    np.savez_compressed(
        out / "beam.npz",
        in_len=lengths,
        in_lm=np.array([lm_ids[x[3]] for x in inputs], np.int32),
        blank=np.concatenate([x[0] for x in inputs]).astype(np.float32),
        topv=np.concatenate([x[1] for x in inputs]).reshape(-1, width).astype(np.float32),
        topi=np.concatenate([x[2] for x in inputs]).reshape(-1, width).astype(np.int32),
        case_input=np.array([r[0] for r in result], np.int32),
        case_lm=np.array([lm_ids[r[1]] for r in result], np.int32),
        case_alpha=np.array([r[2] for r in result], np.float64),
        case_beta=np.array([r[3] for r in result], np.float64),
        case_beam=np.array([r[4] for r in result], np.int32),
        case_blank_skip=np.array([r[5] for r in result], np.float64),
        case_cand_floor=np.array([r[6] for r in result], np.float64),
        out_len=np.array([len(r[7]) for r in result], np.int32),
        out_ids=np.array([x for r in result for x in r[7]], np.int32),
        out_steps=np.array([x for r in result for x in r[8]], np.int32),
    )
    return result


def main():
    gen_vocab()
    gen_rtl()
    gen_unicode()
    arrays = {}
    for name, f in LMS:
        arrays.update(gen_logp(name, NgramLM(models / f)))
    np.savez_compressed(out / "logp.npz", **arrays)
    gen_beam(OrtOCR(models, threads=1))


if __name__ == "__main__":
    main()
