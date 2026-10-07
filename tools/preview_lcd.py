"""Offline preview of the LCD filter, so the look can be judged without launching the game.

This is the feedback loop for the visor's screen. ``DroneLcd`` feeds a handful of uniforms to
``assets/watchcraft/shaders/program/lcd.fsh``; this proves the result reads as *a picture on a
panel* rather than as *dirt on a picture*, and lets the strength and the pixel pitch be picked
without starting a client.

It is a line by line port of the shader, in the same order and with the same constants:

1. sample the scene three times, once per channel, with the corners fringed outward;
2. mix in a half pixel cross average, the way glass takes the digital edge off;
3. multiply by the panel - a one device pixel black matrix on the right and bottom of every cell,
   and a three stripe subpixel mask across it, with both means divided back out so the picture
   neither darkens nor loses its colour balance;
4. multiply by the backlight falloff and by the bezel;
5. add the rolling band;
6. tint the panel and lift the black;
7. jitter the brightness once per twenty-fourth of a second.

Everything is computed in **device pixels**, which is the one thing about this effect that is easy
to get wrong: the panel is a property of the screen, not of the scene, so it must not scale with
the GUI. The detail tiles are therefore cut at 1:1 from a 960x540 frame - one device pixel of the
preview is one device pixel of the game, not a magnification.

Two outputs, because they answer different questions. The detail sheet is a 1:1 crop and shows the
panel structure; the frame sheet is the whole picture, reduced, and is the only place the bezel,
the vignette and the rolling band can be seen at all.

The backdrop is a stand-in, not a screenshot, so treat the preview as a guide to *structure* - how
coarse the grid reads, how much the corners darken - rather than as a colour match.

Outputs
-------
    <out>/lcd-preview.png         six 320x180 crops at 1:1, stacked
    <out>/lcd-preview-frame.png   the whole frame, off beside on

Usage
-----
    python tools/preview_lcd.py [out_dir]
"""

from __future__ import annotations

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import generate_textures as g  # noqa: E402  (Canvas / write_png)
import paths  # noqa: E402
from preview_static import scene  # noqa: E402  (the shared stand-in for the world)

# ------------------------------------------------------------------ the shader's recipe
# Kept in step with lcd.fsh. If a constant moves there, move it here too or the preview stops
# predicting the real thing.

GRID = 0.62
MASK = 0.40
FRINGE = 1.6
SOFTEN = 0.40
BAND_WIDTH = 0.10
BAND = 0.10
VIGNETTE = 0.30
BEZEL = 0.65
BEZEL_FRACTION = 0.012
TINT = (0.955, 1.000, 1.030)
LIFT = 0.028
FLICKER = 0.030

#: The subpixel stripes are one device pixel wide and cycle every three, independent of the cell.
STRIPE_PERIOD = 3.0
#: What ``LcdPitch = 0`` resolves to: one cell is this fraction of the screen height, clamped.
PITCH_PER_HEIGHT = 270.0
PITCH_MIN, PITCH_MAX = 4.0, 8.0
#: The auto rule evaluated at 1080p, which is the resolution this preview is standing in for. The
#: frame below is only 540 tall, so the rule would resolve to the 4.0 floor here anyway - but the
#: detail sheet would then show the floor twice and never the shipped value.
AUTO_PITCH = 4.0

FRAME_W, FRAME_H = 960, 540
CROP_W, CROP_H = 320, 180
CROP_X, CROP_Y = 250, 200
#: Two pixels of margin so the fringe and the soften taps at the crop's edge read real neighbours.
MARGIN = 3

#: A band phase that puts the rolling band inside the crop. In game the phase advances once every
#: five seconds, so most of the time the band is somewhere else entirely.
PHASE = 0.42
#: Only used for the brightness jitter, which is one value per frame anyway.
SECONDS = 12.0


def clamp(value, low, high):
    return low if value < low else (high if value > high else value)


def smoothstep(edge0, edge1, x):
    t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def sample(px, width, height, x, y):
    """Bilinear tap at device pixel coordinates, clamped at the edges like the sampler is."""
    x = clamp(x - 0.5, 0.0, width - 1.0)
    y = clamp(y - 0.5, 0.0, height - 1.0)
    x0, y0 = int(math.floor(x)), int(math.floor(y))
    x1, y1 = min(x0 + 1, width - 1), min(y0 + 1, height - 1)
    fx, fy = x - x0, y - y0
    out = []
    for channel in range(3):
        top = px[y0][x0][channel] * (1.0 - fx) + px[y0][x1][channel] * fx
        bottom = px[y1][x0][channel] * (1.0 - fx) + px[y1][x1][channel] * fx
        out.append(top * (1.0 - fy) + bottom * fy)
    return out


def filter_lcd(px, origin_x, origin_y, strength, pitch_override):
    """One pass of lcd.fsh over a window of a larger frame.

    ``px`` is the window, ``origin_x``/``origin_y`` where it sits in the frame. The distinction
    matters: the panel pattern is local to the pixel, but the fringe, the vignette and the bezel
    are functions of the position inside the whole screen, so a crop filtered as if it were the
    whole screen would put all three in the wrong places.
    """
    height = len(px)
    width = len(px[0])
    s = clamp(strength, 0.0, 1.0)
    if s <= 0.001:
        return px

    mask_compensation = 1.0 - MASK * s * 2.0 / 3.0
    pitch = (pitch_override if pitch_override > 0.5 else AUTO_PITCH)
    gap_edge = 1.0 - 1.0 / pitch
    gap_span = min(0.14, 1.0 - gap_edge)

    aspect = FRAME_W / float(FRAME_H)
    bezel_width = max(FRAME_H * BEZEL_FRACTION, 4.0)
    tick = math.floor(SECONDS * 24.0)
    jitter = math.sin(tick * 12.9898) * 43758.5453
    jitter -= math.floor(jitter)
    flicker = 1.0 + (jitter - 0.5) * FLICKER * s

    out = [[None] * width for _ in range(height)]
    for y in range(height):
        frame_y = origin_y + y + 0.5
        v = frame_y / FRAME_H
        for x in range(width):
            frame_x = origin_x + x + 0.5
            u = frame_x / FRAME_W

            # ---- sampling: fringe the corners, then take the digital edge off.
            # Two coordinate systems, and they are not interchangeable: the sampler addresses the
            # window it was handed, while the fringe is a function of the whole screen.
            local_x, local_y = x + 0.5, y + 0.5
            cx, cy = u - 0.5, v - 0.5
            # Normalised radius: 0 at the middle, 1 at the corners. The doubling is required -
            # dot(centred, centred) only reaches 0.5 at a corner.
            offset = (cx * cx + cy * cy) * 2.0 * FRINGE * s
            colour = [
                sample(px, width, height, local_x + cx * offset, local_y + cy * offset)[0],
                sample(px, width, height, local_x, local_y)[1],
                sample(px, width, height, local_x - cx * offset, local_y - cy * offset)[2],
            ]

            soft = [0.0, 0.0, 0.0]
            for dx, dy in ((0.5, 0.0), (-0.5, 0.0), (0.0, 0.5), (0.0, -0.5)):
                tap = sample(px, width, height, local_x + dx, local_y + dy)
                for channel in range(3):
                    soft[channel] += tap[channel] * 0.25
            for channel in range(3):
                colour[channel] += (soft[channel] - colour[channel]) * SOFTEN * s

            # ---- panel: one device pixel of black matrix along the bottom of every cell, and the
            # subpixel stripes as a fixed three pixel cycle. There is deliberately no vertical
            # matrix column: a one pixel column always lands wholly inside one stripe (the last
            # one), so that channel gets darkened twice and the picture goes yellow-green.
            gap_edge = 1.0 - 1.0 / pitch
            gap_span = min(0.14, 1.0 - gap_edge)
            gap_row = smoothstep(gap_edge, gap_edge + gap_span, (frame_y / pitch) % 1.0)
            grid = (1.0 - GRID * s * gap_row) / (1.0 - GRID * s / pitch)

            subpixel = [1.0 - MASK * s] * 3
            stripe = (frame_x / STRIPE_PERIOD) % 1.0
            if stripe < 1.0 / 3.0:
                subpixel[0] = 1.0
            elif stripe < 2.0 / 3.0:
                subpixel[1] = 1.0
            else:
                subpixel[2] = 1.0
            for channel in range(3):
                colour[channel] *= subpixel[channel] / mask_compensation * grid

            # ---- backlight falloff, measured in aspect corrected space
            ax, ay = cx * aspect, cy
            falloff = 1.0 - VIGNETTE * s * (ax * ax + ay * ay)

            # ---- bezel: the outermost strip, which is what actually says "screen"
            to_edge = min(frame_x, FRAME_W - frame_x, frame_y, FRAME_H - frame_y)
            falloff *= 1.0 - BEZEL * s * (1.0 - smoothstep(0.0, bezel_width, to_edge))
            for channel in range(3):
                colour[channel] *= falloff

            # ---- rolling band
            band_pos = v - PHASE + 0.5
            band_pos -= math.floor(band_pos)
            band_pos -= 0.5
            band = BAND * s * math.exp(-(band_pos * band_pos) / (BAND_WIDTH * BAND_WIDTH))
            colour[0] += band * 0.95
            colour[1] += band * 1.00
            colour[2] += band * 1.05

            # ---- panel tint, lifted black, and the once-a-frame jitter
            for channel in range(3):
                value = colour[channel] * TINT[channel] * (1.0 - LIFT) + LIFT
                colour[channel] = clamp(value * flicker, 0.0, 1.0)

            out[y][x] = tuple(colour)

    return out


def to_unit(px):
    return [[(r / 255.0, gg / 255.0, b / 255.0) for r, gg, b in row] for row in px]


def downscale(px, width, height, factor):
    """Box average, which is roughly what an eye does to a pattern it cannot resolve."""
    out_w, out_h = width // factor, height // factor
    out = [[None] * out_w for _ in range(out_h)]
    for y in range(out_h):
        for x in range(out_w):
            r = gg = b = 0.0
            for dy in range(factor):
                row = px[y * factor + dy]
                for dx in range(factor):
                    pixel = row[x * factor + dx]
                    r += pixel[0]
                    gg += pixel[1]
                    b += pixel[2]
            n = float(factor * factor)
            out[y][x] = (r / n, gg / n, b / n)
    return out


def blit(sheet, px, ox, oy):
    for y, row in enumerate(px):
        for x, (r, gg, b) in enumerate(row):
            sheet.set(ox + x, oy + y, (
                int(round(clamp(r, 0.0, 1.0) * 255)),
                int(round(clamp(gg, 0.0, 1.0) * 255)),
                int(round(clamp(b, 0.0, 1.0) * 255)),
                255))


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else paths.PREVIEW_OUT
    os.makedirs(out_dir, exist_ok=True)

    frame = to_unit(scene(FRAME_W, FRAME_H))
    gap = 8

    # ---------------------------------------------------------------- detail sheet
    # The pitch at full strength, then the strength at the default pitch. Both knobs, and the "off"
    # case is in there so the effect has something to be compared against.
    rows = [
        ("pitch 4 - floor and the 1080p default", 1.0, 4.0),
        ("pitch 5", 1.0, 5.0),
        ("pitch 8 - 4K", 1.0, 8.0),
        ("strength 0 - off", 0.0, 0.0),
        ("strength 0.5", 0.5, 0.0),
        ("strength 1.0 - shipped", 1.0, 0.0),
    ]

    ox, oy = CROP_X - MARGIN, CROP_Y - MARGIN
    window_w, window_h = CROP_W + MARGIN * 2, CROP_H + MARGIN * 2
    window = [[frame[oy + y][ox + x] for x in range(window_w)] for y in range(window_h)]

    sheet = g.Canvas(CROP_W + gap * 2, len(rows) * (CROP_H + gap) + gap)
    for i in range(sheet.w):
        for j in range(sheet.h):
            sheet.set(i, j, (24, 26, 30, 255))

    for index, (_, strength, pitch) in enumerate(rows):
        filtered = filter_lcd(window, ox, oy, strength, pitch)
        detail = [[filtered[y + MARGIN][x + MARGIN] for x in range(CROP_W)] for y in range(CROP_H)]
        blit(sheet, detail, gap, gap + index * (CROP_H + gap))
    sheet.write_png(os.path.join(out_dir, "lcd-preview.png"))

    # ---------------------------------------------------------------- whole frame sheet
    # Reduced by two, because the point here is the bezel, the vignette and the overall tone - the
    # things a crop cannot show. The grid mostly averages away at this size, which is honest: it is
    # also what happens to it at a normal viewing distance.
    factor = 2
    small_w, small_h = FRAME_W // factor, FRAME_H // factor
    frame_sheet = g.Canvas(small_w * 2 + gap * 3, small_h + gap * 2)
    for i in range(frame_sheet.w):
        for j in range(frame_sheet.h):
            frame_sheet.set(i, j, (24, 26, 30, 255))

    off = downscale(frame, FRAME_W, FRAME_H, factor)
    on = downscale(filter_lcd(frame, 0, 0, 1.0, 0.0), FRAME_W, FRAME_H, factor)
    blit(frame_sheet, off, gap, gap)
    blit(frame_sheet, on, gap * 2 + small_w, gap)
    frame_sheet.write_png(os.path.join(out_dir, "lcd-preview-frame.png"))

    print("detail tiles, top to bottom:", ", ".join(label for label, _, _ in rows))
    print("frame sheet: off on the left, on the right")


if __name__ == "__main__":
    main()
