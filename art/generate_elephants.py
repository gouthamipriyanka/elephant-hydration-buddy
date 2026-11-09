"""Generates Ellie the Elephant in 4 moods as SVG + Android VectorDrawable.

Original artwork in a cute "chibi" sticker style (outlined shapes, big sparkly
eyes, pastel hearts). Single source of truth so README previews and in-app
drawables never drift. Run:
    python3 art/generate_elephants.py
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SVG_DIR = ROOT / "art"
DRAWABLE_DIR = ROOT / "android/app/src/main/res/drawable"

SKIN = "#D9E2EE"
SKIN_SHADE = "#B9C6D8"
PINK = "#F7C3CF"
PINK_DEEP = "#F09AAE"
OUT = "#3A4560"   # outline
INK = "#27304A"
WATER = "#4FB3F6"
BG = "#FFFFFF"
HEART_PINK = "#F9CBD6"
HEART_TEAL = "#C9E6EA"
GOLD = "#FFC93C"
W = 2.6  # outline width


# ---------------- primitives ----------------
def ellipse(cx, cy, rx, ry, **kw):
    d = f"M{cx - rx},{cy} a{rx},{ry} 0 1,0 {2 * rx},0 a{rx},{ry} 0 1,0 {-2 * rx},0 Z"
    return dict(d=d, **kw)


def circle(cx, cy, r, **kw):
    return ellipse(cx, cy, r, r, **kw)


def path(d, **kw):
    return dict(d=d, **kw)


def outlined(shape_fn, *args, fill):
    return shape_fn(*args, fill=fill, stroke=OUT, strokeWidth=W, cap="round")


def heart(x, y, s, color, alpha=1.0):
    """Heart with top-left at (x, y), width ~ 8*s."""
    def p(a, b):
        return f"{x + a * s:.1f},{y + b * s:.1f}"
    d = (f"M{p(4, 2)} C{p(4, 0)} {p(0, 0)} {p(0, 2.6)} C{p(0, 5)} {p(3, 6.5)} {p(4, 8)} "
         f"C{p(5, 6.5)} {p(8, 5)} {p(8, 2.6)} C{p(8, 0)} {p(4, 0)} {p(4, 2)} Z")
    return dict(d=d, fill=color, alpha=alpha)


def trunk(d, width=16):
    """Outlined trunk: dark wide stroke under a lighter one."""
    return [path(d, stroke=OUT, strokeWidth=width + 2 * W, cap="round"),
            path(d, stroke=SKIN, strokeWidth=width, cap="round")]


# ---------------- shared parts ----------------
def background():
    return [
        circle(100, 100, 98, fill=BG),
        heart(14, 26, 2.0, HEART_TEAL), heart(164, 18, 1.6, HEART_PINK),
        heart(176, 112, 1.8, HEART_PINK), heart(8, 112, 1.5, HEART_PINK),
        heart(44, 8, 1.1, HEART_PINK), heart(168, 150, 1.3, HEART_TEAL),
        circle(26, 64, 2, fill=HEART_PINK), circle(184, 76, 2, fill=HEART_TEAL),
        circle(64, 18, 1.6, fill=HEART_TEAL), circle(140, 10, 1.6, fill=HEART_PINK),
        ellipse(100, 191, 66, 5, fill="#E8EEF6"),  # ground shadow
    ]


def body(ear_droop=0.0):
    d = ear_droop
    e = [
        # tail
        path("M60,160 q-18,6 -26,-2", stroke=OUT, strokeWidth=3, cap="round"),
        path("M35,158 l-7,-5 l1,7 l-6,1 l7,4 Z", fill=OUT),
        # chubby sitting body
        outlined(ellipse, 100, 150, 46, 34, fill=SKIN),
        # front legs
        path("M80,144 v36 a8,8 0 0 0 8,8 h4 a8,8 0 0 0 8,-8 v-36 Z", fill=SKIN, stroke=OUT, strokeWidth=W),
        path("M100,144 v36 a8,8 0 0 0 8,8 h4 a8,8 0 0 0 8,-8 v-36 Z", fill=SKIN, stroke=OUT, strokeWidth=W),
    ]
    for x in (85, 91, 97, 105, 111, 117):  # front toenails
        e.append(circle(x, 185, 2.4, fill="#FFFFFF", stroke=OUT, strokeWidth=1.2))
    for cx in (58, 142):  # sitting back feet with pink soles + toenails
        e.append(outlined(ellipse, cx, 175, 19, 16, fill=SKIN))
        e.append(ellipse(cx, 177, 12, 10, fill=PINK))
        for dx in (-10, 0, 10):
            e.append(circle(cx + dx, 163, 2.8, fill="#FFFFFF", stroke=OUT, strokeWidth=1.2))
    # big floppy ears (droop lower when sad)
    e += [
        outlined(ellipse, 46, 72 + d, 38, 40 + d / 2, fill=SKIN),
        outlined(ellipse, 154, 72 + d, 38, 40 + d / 2, fill=SKIN),
        ellipse(43, 75 + d, 26, 29 + d / 2, fill=PINK),
        ellipse(157, 75 + d, 26, 29 + d / 2, fill=PINK),
    ]
    return e


def head(trunk_d, tip=None, tip_color=PINK_DEEP):
    t_out, t_fill = trunk(trunk_d)
    e = [t_out, outlined(ellipse, 100, 68, 44, 40, fill=SKIN), t_fill]  # fill blends trunk into head
    e += [
        # wrinkles on trunk + forehead
        path("M94,96 q6,3 12,0", stroke=SKIN_SHADE, strokeWidth=1.8, cap="round"),
        path("M94,103 q6,3 12,0", stroke=SKIN_SHADE, strokeWidth=1.8, cap="round"),
        path("M94,42 q6,-3 12,0", stroke=SKIN_SHADE, strokeWidth=1.8, cap="round"),
        # hair tuft
        path("M95,30 q-5,-10 1,-13", stroke=OUT, strokeWidth=2.4, cap="round"),
        path("M100,29 q0,-12 8,-12", stroke=OUT, strokeWidth=2.4, cap="round"),
        path("M105,30 q6,-7 11,-4", stroke=OUT, strokeWidth=2.4, cap="round"),
        # blush
        ellipse(70, 86, 8, 5, fill=PINK_DEEP, alpha=0.55),
        ellipse(130, 86, 8, 5, fill=PINK_DEEP, alpha=0.55),
    ]
    if tip:  # pink trunk tip
        e.append(ellipse(tip[0], tip[1], 6, 5, fill=tip_color, stroke=OUT, strokeWidth=2))
    return e


def sparkly_eyes(y=68):
    e = []
    for cx in (82, 118):
        e += [ellipse(cx, y, 8, 10, fill=INK),
              circle(cx + 3, y - 4, 3.4, fill="#FFFFFF"),
              circle(cx - 3, y + 4, 1.7, fill="#FFFFFF")]
    e += [path(f"M74,{y - 6} l-5,-3", stroke=INK, strokeWidth=2, cap="round"),  # lashes
          path(f"M126,{y - 6} l5,-3", stroke=INK, strokeWidth=2, cap="round")]
    return e


SMILE = path("M80,94 q5,6 11,2", stroke=INK, strokeWidth=2.4, cap="round")


# ---------------- moods ----------------
def neutral():
    t = "M100,84 C100,104 106,114 118,112 C126,110 128,102 124,98"  # curl to the side
    return background() + body() + head(t, tip=(123, 97)) + sparkly_eyes() + [SMILE]


def happy():
    t = "M100,84 C102,104 120,108 130,92 C136,82 140,66 150,56"  # raised, trumpeting
    return background() + body() + head(t, tip=(151, 54)) + [
        path("M74,70 q8,-11 16,0", stroke=INK, strokeWidth=3.4, cap="round"),  # ^ ^
        path("M110,70 q8,-11 16,0", stroke=INK, strokeWidth=3.4, cap="round"),
        path("M73,69 l-5,-2", stroke=INK, strokeWidth=2, cap="round"),
        path("M127,69 l5,-2", stroke=INK, strokeWidth=2, cap="round"),
        path("M80,92 q8,13 16,2 Z", fill="#E86A86", stroke=INK, strokeWidth=2.2),  # open smile
        circle(160, 42, 4.5, fill=WATER), circle(169, 52, 3.5, fill=WATER),
        circle(151, 35, 3.5, fill=WATER), circle(166, 31, 3, fill=WATER), circle(176, 42, 2.5, fill=WATER),
        path("M24,88 l3,8 l8,3 l-8,3 l-3,8 l-3,-8 l-8,-3 l8,-3 Z", fill=GOLD),
        path("M180,96 l2.5,6 l6,2.5 l-6,2.5 l-2.5,6 l-2.5,-6 l-6,-2.5 l6,-2.5 Z", fill=GOLD),
        heart(110, 2, 1.6, PINK_DEEP), heart(80, 6, 1.2, PINK_DEEP),
    ]


def thirsty():
    t = "M100,84 C100,108 100,128 98,146"  # limp, drooping
    return background() + body(ear_droop=8) + head(t, tip=(98, 148), tip_color=PINK) + [
        ellipse(82, 70, 8, 9, fill=INK), ellipse(118, 70, 8, 9, fill=INK),
        circle(85, 66, 3, fill="#FFFFFF"), circle(121, 66, 3, fill="#FFFFFF"),
        path("M72,56 l14,-5", stroke=INK, strokeWidth=2.6, cap="round"),   # worried brows
        path("M128,56 l-14,-5", stroke=INK, strokeWidth=2.6, cap="round"),
        path("M74,79 q-6,10 0,14 q6,-4 0,-14 Z", fill=WATER),              # tears
        path("M126,79 q-6,10 0,14 q6,-4 0,-14 Z", fill=WATER),
        path("M82,99 q5,-5 10,0", stroke=INK, strokeWidth=2.4, cap="round"),  # little frown
        path("M150,126 h24 l-4,34 h-16 Z", fill="#FFFFFF", stroke=OUT, strokeWidth=2.4),  # empty glass
        path("M155,155 h14", stroke=WATER, strokeWidth=2.6, cap="round"),
    ]


def bottle():
    t = "M100,84 C100,104 112,112 128,110"
    bottle_parts = [
        path("M150,82 h18 v8 h-18 Z", fill="#2E7DD7", stroke=OUT, strokeWidth=2),
        path("M146,90 h26 a4,4 0 0 1 4,4 v50 a8,8 0 0 1 -8,8 h-18 a8,8 0 0 1 -8,-8 v-50 a4,4 0 0 1 4,-4 Z",
             fill=WATER, stroke=OUT, strokeWidth=2.4),
        path("M150,110 h18 v18 h-18 Z", fill="#FFFFFF", alpha=0.7),
        heart(153, 113, 1.5, "#F25F7A"),
    ]
    curl_out, curl_fill = trunk("M128,110 q14,0 16,10")
    return background() + body() + bottle_parts + head(t) + sparkly_eyes() + [
        SMILE, curl_out, curl_fill,
        ellipse(145, 123, 6, 5, fill=PINK_DEEP, stroke=OUT, strokeWidth=2),
    ]


MOODS = {"happy": happy, "neutral": neutral, "thirsty": thirsty, "bottle": bottle}


# ---------------- writers ----------------
def to_svg(elems) -> str:
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200" width="200" height="200">']
    for e in elems:
        a = e.get("alpha", 1)
        if "stroke" in e:
            parts.append(
                f'<path d="{e["d"]}" fill="{e.get("fill", "none")}" stroke="{e["stroke"]}" '
                f'stroke-width="{e["strokeWidth"]}" stroke-linecap="{e.get("cap", "butt")}" '
                f'stroke-linejoin="round" opacity="{a}"/>')
        else:
            parts.append(f'<path d="{e["d"]}" fill="{e["fill"]}" opacity="{a}"/>')
    parts.append("</svg>\n")
    return "\n".join(parts)


def to_vector(elems) -> str:
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- GENERATED by art/generate_elephants.py. Do not edit by hand. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="200dp" android:height="200dp"',
           '    android:viewportWidth="200" android:viewportHeight="200">']
    for e in elems:
        a = e.get("alpha", 1)
        attrs = [f'android:pathData="{e["d"]}"']
        if e.get("fill"):
            attrs += [f'android:fillColor="{e["fill"]}"', f'android:fillAlpha="{a}"']
        if "stroke" in e:
            attrs += [f'android:strokeColor="{e["stroke"]}"', f'android:strokeWidth="{e["strokeWidth"]}"',
                      f'android:strokeLineCap="{e.get("cap", "butt")}"', 'android:strokeLineJoin="round"',
                      f'android:strokeAlpha="{a}"']
        out.append("    <path " + "\n        ".join(attrs) + " />")
    out.append("</vector>\n")
    return "\n".join(out)


def main():
    DRAWABLE_DIR.mkdir(parents=True, exist_ok=True)
    for name, fn in MOODS.items():
        elems = fn()
        (SVG_DIR / f"elephant_{name}.svg").write_text(to_svg(elems))
        (DRAWABLE_DIR / f"elephant_{name}.xml").write_text(to_vector(elems))
    print("generated:", ", ".join(MOODS))


if __name__ == "__main__":
    main()
