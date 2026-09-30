"""Offline preview of the signal-loss overlay, so the look can be judged without launching the game.

This is the feedback loop for the visor's signal breakup. `DroneSignal` blurs the world for real
through a post chain; this proves the result reads as a defocus rather than as dust, and lets the
veil, the vignette and the grain be tuned without starting a client.

Two halves, and the order matters:

1. The world is blurred first, by a faithful Python port of vanilla's `box_blur` post chain. The
   shader samples with GL_LINEAR at offsets -r+0.5, -r+2.5 ... r-1.5 plus one unweighted tap at +r,
   which works out to exactly a uniform box of width 2r+1 - so a running-sum box blur reproduces
   it pixel for pixel, and runs in a fraction of the time.
2. The GUI layers go on top afterwards, exactly as `DroneHud` draws them: milky veil, stacked
   one-pixel vignette rings, one-pixel tears, one-pixel grain.

Alpha compositing is the SRC_ALPHA / ONE_MINUS_SRC_ALPHA the GUI uses.

The backdrop is a stand-in, not a screenshot, so treat the preview as a guide to *structure* -
how soft the edges go, how fine the grain sits - rather than as a colour match.

Outputs
-------
    <out>/static-preview.png   one row per candidate setting, one column per degradation grade

Usage
-----
    python tools/preview_static.py [out_dir]
"""

from __future__ import annotations

import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate_textures as g  # noqa: E402  (Canvas / write_png)
import paths  # noqa: E402

HAZE = (0xAE, 0xCD, 0xD8)
VIGNETTE = (0x06, 0x0A, 0x0E)

# Mirrors DroneSignal.MAX_RADIUS and the vanilla pass schedule the post chain uses. Keep the radius
# in step with the Java constant or the preview stops predicting the real thing.
SHIPPED_RADIUS = 6.0
MULTIPLIERS = (1.0, 1.0, 0.5, 0.5, 0.25, 0.25)


def jround(value):
    """Java's Math.round for a float: half away from zero, not Python's banker's rounding."""
    return int(math.floor(value + 0.5))


def blend(dst, src, alpha):
    a = alpha / 255.0
    return tuple(int(round(dst[i] * (1.0 - a) + src[i] * a)) for i in range(3))


# ------------------------------------------------------------------ blur


def box_h(px, radius, width, height):
    if radius <= 0:
        return px
    out = []
    for y in range(height):
        row = px[y]
        # Prefix sums with clamped edges, so the window is always 2r+1 wide.
        acc = [0.0] * (width + 1)
        for x in range(width):
            acc[x + 1] = acc[x] + row[x][0]
        accg = [0.0] * (width + 1)
        accb = [0.0] * (width + 1)
        for x in range(width):
            accg[x + 1] = accg[x] + row[x][1]
            accb[x + 1] = accb[x] + row[x][2]
        span = 2 * radius + 1
        new = [None] * width
        for x in range(width):
            lo = max(0, x - radius)
            hi = min(width - 1, x + radius)
            n = hi - lo + 1
            # Clamped samples repeat the edge pixel, so pad the missing ones with the edge value.
            r = acc[hi + 1] - acc[lo] + (span - n) * (row[lo][0] if x - radius < 0 else row[hi][0])
            gr = accg[hi + 1] - accg[lo] + (span - n) * (row[lo][1] if x - radius < 0 else row[hi][1])
            b = accb[hi + 1] - accb[lo] + (span - n) * (row[lo][2] if x - radius < 0 else row[hi][2])
            new[x] = (int(r / span), int(gr / span), int(b / span))
        out.append(new)
    return out


def box_v(px, radius, width, height):
    if radius <= 0:
        return px
    out = [[None] * width for _ in range(height)]
    span = 2 * radius + 1
    for x in range(width):
        col = [px[y][x] for y in range(height)]
        acc = [0.0] * (height + 1)
        accg = [0.0] * (height + 1)
        accb = [0.0] * (height + 1)
        for y in range(height):
            acc[y + 1] = acc[y] + col[y][0]
            accg[y + 1] = accg[y] + col[y][1]
            accb[y + 1] = accb[y] + col[y][2]
        for y in range(height):
            lo = max(0, y - radius)
            hi = min(height - 1, y + radius)
            n = hi - lo + 1
            pad = span - n
            pr = col[lo][0] if y - radius < 0 else col[hi][0]
            pg = col[lo][1] if y - radius < 0 else col[hi][1]
            pb = col[lo][2] if y - radius < 0 else col[hi][2]
            r = acc[hi + 1] - acc[lo] + pad * pr
            gr = accg[hi + 1] - accg[lo] + pad * pg
            b = accb[hi + 1] - accb[lo] + pad * pb
            out[y][x] = (int(r / span), int(gr / span), int(b / span))
    return out


def blur(px, width, height, radius):
    """The post chain: horizontal then vertical, three times over at falling radii."""
    if radius <= 0.0:
        return px
    for mult in MULTIPLIERS:
        r = jround(radius * mult)
        if r <= 0:
            continue
        px = box_h(px, r, width, height)
        px = box_v(px, r, width, height)
    return px


# ------------------------------------------------------------------ scene


def scene(width, height):
    """A stand-in for the world: sky, horizon, high frequency ground, trees, a fence."""
    px = [[(0, 0, 0) for _ in range(width)] for _ in range(height)]
    horizon = int(height * 0.45)
    for y in range(height):
        for x in range(width):
            if y < horizon:
                t = y / horizon
                px[y][x] = (int(96 + 90 * t), int(150 + 80 * t), int(220 + 30 * t))
            else:
                t = (y - horizon) / max(1, height - horizon)
                n = ((x * 7 + y * 13) % 5) * 6
                px[y][x] = (int(96 + 40 * t) + n, int(150 - 30 * t) + n, int(70 + 20 * t) + n)

    for cx, scale in ((width // 7, 1.0), (width // 3, 1.4), (int(width * 0.78), 1.1)):
        top = horizon - int(38 * scale)
        half = int(15 * scale)
        for y in range(top, horizon + 2):
            w = int(half * (y - top) / max(1, horizon + 2 - top))
            for x in range(cx - w, cx + w + 1):
                if 0 <= x < width and 0 <= y < height:
                    px[y][x] = (34, 74, 40)
        for y in range(horizon + 2, horizon + int(14 * scale)):
            for x in range(cx - 1, cx + 2):
                if 0 <= y < height:
                    px[y][x] = (72, 52, 32)

    rng = random.Random(7)
    for _ in range(220):
        x, y = rng.randrange(width), rng.randrange(horizon, height)
        px[y][x] = (230, 230, 190)

    # A fence: thin vertical lines, the kind of detail a defocus should smear away.
    for x in range(4, width, 26):
        for y in range(horizon + 6, horizon + 26):
            if 0 <= y < height:
                px[y][x] = (150, 120, 80)
                px[y][x + 1] = (110, 88, 58)
    return px


# ------------------------------------------------------------------ overlay


def draw_static(px, width, height, amount, millis, veil_max, grain_density):
    if amount <= 0.0:
        return
    rng = random.Random(millis // 40)

    veil = int(amount * veil_max)
    if veil > 0:
        for y in range(height):
            for x in range(width):
                px[y][x] = blend(px[y][x], HAZE, veil)

    rings = min(48, min(width, height) // 4)
    for i in range(rings):
        falloff = 1.0 - i / rings
        alpha = int(amount * 0x58 * falloff * falloff)
        if alpha <= 0:
            continue
        for x in range(i, width - i):
            px[i][x] = blend(px[i][x], VIGNETTE, alpha)
            px[height - i - 1][x] = blend(px[height - i - 1][x], VIGNETTE, alpha)
        for y in range(i, height - i):
            px[y][i] = blend(px[y][i], VIGNETTE, alpha)
            px[y][width - i - 1] = blend(px[y][width - i - 1], VIGNETTE, alpha)

    for _ in range(int(amount * 4.0)):
        y = rng.randrange(height)
        length = width // 5 + rng.randrange(max(1, width * 3 // 5))
        x = rng.randrange(max(1, width - length))
        alpha = 0x0A + rng.randrange(0x18)
        for xx in range(x, min(x + length, width)):
            px[y][xx] = blend(px[y][xx], (0xE4, 0xF2, 0xFA), alpha)
    for _ in range(int(amount * 1.2)):
        y = rng.randrange(height)
        alpha = 0x0C + rng.randrange(0x18)
        for xx in range(width):
            px[y][xx] = blend(px[y][xx], (0xE4, 0xF2, 0xFA), alpha)

    grains = min(int(width * height * grain_density * amount), 14000)
    for _ in range(grains):
        x, y = rng.randrange(width), rng.randrange(height)
        alpha = 0x16 + rng.randrange(0x48)
        grey = 0xE4 + rng.randrange(0x1C) if rng.random() < 0.5 else 0x0C + rng.randrange(0x1C)
        px[y][x] = blend(px[y][x], (grey, grey, grey), alpha)


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else paths.PREVIEW_OUT
    os.makedirs(out_dir, exist_ok=True)

    W, H = 240, 135
    scale = 2
    gap = 8
    amounts = [0.25, 0.5, 0.75, 1.0]
    # Each row is one candidate: blur radius, veil ceiling, grain density. Row 0 is what ships;
    # the rest are the neighbours worth comparing it against.
    rows = [
        ("shipped - blur 6, veil 0x50", SHIPPED_RADIUS, 0x50, 0.0075),
        ("blur 4", 4.0, 0x50, 0.0075),
        ("blur 8", 8.0, 0x50, 0.0075),
        ("less grain - 0.005", SHIPPED_RADIUS, 0x50, 0.005),
    ]

    base = scene(W, H)
    sheet = g.Canvas(len(amounts) * (W * scale + gap) + gap, len(rows) * (H * scale + gap) + gap)
    for i in range(sheet.w):
        for j in range(sheet.h):
            sheet.set(i, j, (24, 26, 30, 255))

    for r, (_, radius, veil_max, density) in enumerate(rows):
        for n, amount in enumerate(amounts):
            px = [row[:] for row in base]
            px = blur(px, W, H, radius * amount)
            draw_static(px, W, H, amount, 123456, veil_max, density)
            ox = gap + n * (W * scale + gap)
            oy = gap + r * (H * scale + gap)
            for y in range(H):
                for x in range(W):
                    c = px[y][x]
                    for sy in range(scale):
                        for sx in range(scale):
                            sheet.set(ox + x * scale + sx, oy + y * scale + sy, (c[0], c[1], c[2], 255))

    sheet.write_png(os.path.join(out_dir, "static-preview.png"))
    print("columns (left to right), amount:", amounts)
    for i, (label, *_rest) in enumerate(rows):
        print("  row", i, "=", label)


if __name__ == "__main__":
    main()
