"""Generates the Recon Drone textures (pure stdlib: zlib + struct).

Outputs
-------
src/main/resources/assets/watchcraft/textures/entity/recon_drone.png      512x512 entity sheet
src/main/resources/assets/watchcraft/textures/entity/recon_drone_glow.png 128x128 emissive sheet
src/main/resources/assets/watchcraft/textures/item/recon_drone.png        32x32  drone icon
src/main/resources/assets/watchcraft/textures/item/drone_chassis.png      16x16  chassis icon
src/main/resources/assets/watchcraft/textures/item/drone_propeller.png    16x16  propeller icon
src/main/resources/watchcraft.png                                        128x128 mod logo

The two entity sheets are painted straight from `tools/drone_manifest.json`,
which `generate_drone_mesh.py` writes alongside the OBJ. The manifest already
holds the UV rectangle of every face, so the texture is packed by exactly the
same code that packed the mesh: there is no second layout to keep in sync.

Run:  python tools/generate_textures.py
"""

from __future__ import annotations

import json
import os
import struct
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402

# Single source of truth for every colour in the project. Deriving the local
# names from it rather than restating the RGB values means a palette change in
# the generator reaches the textures without anyone having to remember to
# update a second table.
from generate_drone_mesh import PALETTE  # noqa: E402

ROOT = paths.ROOT
RES = paths.RESOURCES
MANIFEST = paths.MANIFEST


# --------------------------------------------------------------------------- palette

def rgba(name: str):
    return tuple(PALETTE[name]) + (255,)


DARK = rgba("HULL_DARK")
DARK2 = rgba("HULL_MID")
DARK3 = rgba("PANEL_LINE")
LIGHT = rgba("HULL_LIGHT")
POD = rgba("HULL_PANEL")
BLADE = rgba("BLADE")
BLADE_DARK = rgba("BLADE_DARK")
CYAN = rgba("CYAN")
CYAN_DEEP = rgba("CYAN_DEEP")
GLASS = rgba("GLASS")
AMBER = rgba("AMBER")

CLEAR = (0, 0, 0, 0)
# Crafting parts. The chassis shows the redstone core it is built around; the propeller is bare
# steel so it reads as a machined part rather than as a finished drone. These are not part of the
# drone's own palette - nothing in the OBJ mesh uses them.
REDSTONE = (168, 24, 10, 255)
REDSTONE_HI = (224, 72, 48, 255)
STEEL = (206, 212, 218, 255)
STEEL_DARK = (126, 134, 144, 255)
BOLT = (86, 94, 104, 255)

# Manifest colour name -> RGBA, for every name the generator can emit.
MANIFEST_COLOURS = {name: tuple(rgb) + (255,) for name, rgb in PALETTE.items()}

GLOW_GROUPS = {"gimbal_lens", "tail_light"}


class Canvas:
    def __init__(self, width: int, height: int):
        self.w = width
        self.h = height
        self.px = [[CLEAR for _ in range(width)] for _ in range(height)]

    def set(self, x: int, y: int, colour):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = colour

    def fill(self, x: int, y: int, w: int, h: int, colour):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, colour)

    def shade(self, x: int, y: int, w: int, h: int, colour, amount: int):
        """Adds deterministic pixel noise so the flat colour reads as hand made pixel art."""
        r, g, b, a = colour
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                n = ((xx * 73 + yy * 151) % (amount * 2 + 1)) - amount
                self.set(xx, yy, (
                    max(0, min(255, r + n)),
                    max(0, min(255, g + n)),
                    max(0, min(255, b + n)),
                    a,
                ))

    def outline(self, x: int, y: int, w: int, h: int, colour):
        for xx in range(x, x + w):
            self.set(xx, y, colour)
            self.set(xx, y + h - 1, colour)
        for yy in range(y, y + h):
            self.set(x, yy, colour)
            self.set(x + w - 1, yy, colour)

    def write_png(self, path: str):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        raw = bytearray()
        for row in self.px:
            raw.append(0)
            for r, g, b, a in row:
                raw += bytes((r, g, b, a))

        def chunk(tag: bytes, data: bytes) -> bytes:
            return (struct.pack(">I", len(data)) + tag + data
                    + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        png += chunk(b"IEND", b"")
        with open(path, "wb") as handle:
            handle.write(png)
        print("wrote", os.path.relpath(path, ROOT))


# --------------------------------------------------------------------------- entity sheets
def build_entity_sheet(manifest, glow: bool) -> Canvas:
    """Paints a sheet straight from the manifest: one UV rectangle per face.

    Each rectangle gets a one pixel inset border. Adjacent faces of a box land
    in separate rectangles, so those borders meet along every physical edge and
    read as panel lines - which is what keeps a flat coloured model from
    looking like painted plastic.

    No shading gradient is baked in: the rectangle's V axis follows whichever
    pair of axes happened to be the face's two largest, so "up" in the texture
    is not "up" in the world and any directional highlight would land on a
    different edge for every face.
    """
    size = manifest["glow_sheet"] if glow else manifest["sheet"]
    canvas = Canvas(size[0], size[1])
    for face in manifest["faces"]:
        if (face["group"] in GLOW_GROUPS) != glow:
            continue
        x, y, w, h = face["rect"]
        colour = MANIFEST_COLOURS.get(face["colour"], (255, 0, 255, 255))
        canvas.shade(x, y, w, h, colour, 3 if glow else 5)
        if w >= 5 and h >= 5:
            edge = tuple(max(0, c - 26) for c in colour[:3]) + (255,)
            canvas.outline(x, y, w, h, edge)
    return canvas


# --------------------------------------------------------------------------- item icon
def disc(canvas: Canvas, cx: int, cy: int, radius: int):
    for yy in range(-radius, radius + 1):
        for xx in range(-radius, radius + 1):
            dist = (xx * xx + yy * yy) ** 0.5
            if dist <= radius:
                canvas.set(cx + xx, cy + yy, BLADE_DARK if dist > radius - 1.2 else BLADE)


def build_item_icon() -> Canvas:
    canvas = Canvas(32, 32)

    corners = [(6, 6), (25, 6), (6, 25), (25, 25)]
    for cx, cy in corners:
        disc(canvas, cx, cy, 4)
        canvas.set(cx, cy, DARK3)
        canvas.set(cx - 1, cy - 1, CYAN)
        canvas.set(cx + 1, cy + 1, CYAN)

    # Diagonal arms from the hull out to each rotor.
    for cx, cy in corners:
        steps = 9
        for i in range(steps):
            t = i / (steps - 1)
            x = int(round(15.5 + (cx - 15.5) * t))
            y = int(round(15.5 + (cy - 15.5) * t))
            canvas.set(x, y, LIGHT)
            canvas.set(x + 1, y, LIGHT)

    # Hull.
    canvas.fill(11, 11, 10, 10, DARK2)
    canvas.outline(11, 11, 10, 10, DARK3)
    canvas.fill(12, 12, 8, 1, LIGHT)

    # Sensor eye.
    canvas.fill(13, 13, 6, 6, DARK3)
    canvas.fill(14, 14, 4, 4, GLASS)
    canvas.fill(15, 15, 2, 2, CYAN)
    canvas.set(14, 14, CYAN)

    # Tail accents.
    canvas.set(11, 20, AMBER)
    canvas.set(20, 11, AMBER)

    return canvas


# --------------------------------------------------------------------------- crafting parts
def build_chassis_icon() -> Canvas:
    """The drone's airframe: a machined plate with the redstone core left showing."""
    canvas = Canvas(16, 16)

    # Plate. Lit along the top, shadowed underneath, so it reads as a solid slab.
    canvas.fill(3, 4, 10, 8, DARK2)
    canvas.fill(3, 4, 10, 1, LIGHT)
    canvas.fill(3, 11, 10, 1, DARK3)
    canvas.fill(2, 5, 1, 6, DARK3)
    canvas.fill(13, 5, 1, 6, DARK3)
    for x, y in ((3, 4), (12, 4), (3, 11), (12, 11)):
        canvas.set(x, y, CLEAR)

    # Redstone core in a raised dark housing.
    canvas.fill(5, 5, 6, 6, DARK3)
    canvas.fill(6, 6, 4, 4, REDSTONE)
    canvas.fill(7, 7, 2, 2, REDSTONE_HI)

    # Corner bolts and a pair of cooling slits.
    for x, y in ((4, 6), (11, 6), (4, 9), (11, 9)):
        canvas.set(x, y, BOLT)
    canvas.fill(3, 8, 2, 1, CYAN_DEEP)
    canvas.fill(11, 8, 2, 1, CYAN_DEEP)

    return canvas


def build_propeller_icon() -> Canvas:
    """A four bladed rotor seen face on, tapering out from a cyan hub."""
    canvas = Canvas(16, 16)

    # Blades run out along the diagonals, tapering to a single pixel at the tip. Each step of the
    # 45 degree run is filled as an L rather than as a single pixel: a one pixel wide diagonal
    # comes out as a dotted chain at this size, and the extra two pixels are what make the blade
    # read as a solid surface.
    for dx, dy in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
        for step in range(1, 7):
            ax = 7 + dx * step
            ay = 7 + dy * step
            if step >= 5:
                canvas.set(ax, ay, STEEL_DARK)
                continue
            canvas.set(ax, ay, STEEL)
            canvas.set(ax + dx, ay, STEEL)
            canvas.set(ax, ay + dy, STEEL)

    # Hub.
    canvas.fill(6, 6, 4, 4, DARK2)
    canvas.outline(6, 6, 4, 4, DARK3)
    canvas.fill(7, 7, 2, 2, CYAN_DEEP)
    canvas.set(7, 7, CYAN)

    return canvas


# --------------------------------------------------------------------------- modules
# Printed circuit colours. Deliberately not in the drone's palette: these are the boards that go
# inside the airframe, and they should read as a different kind of object from the shell.
PCB = (22, 34, 40, 255)
PCB_EDGE = (36, 54, 62, 255)
PCB_TRACE = CYAN_DEEP
CHIP = (48, 56, 64, 255)
GOLD = (198, 160, 62, 255)
CORE_HOUSING = (58, 30, 26, 255)
CORE = (150, 34, 22, 255)
CORE_HOT = (240, 120, 60, 255)

# Batteries. Copper for the cheap pack, graphite for the good one - the two shells are far enough
# apart in both hue and brightness that the silhouettes do not have to carry the distinction on
# their own, which they cannot at this size.
COPPER = (176, 100, 58, 255)
COPPER_HI = (226, 152, 98, 255)
GRAPHITE = (46, 50, 56, 255)
GRAPHITE_HI = (96, 102, 112, 255)


def _module_board(canvas: Canvas) -> None:
    """The shape both boards share: a green-black PCB with a gold edge connector."""
    canvas.fill(1, 2, 14, 11, PCB)
    canvas.outline(1, 2, 14, 11, PCB_EDGE)
    for x in range(2, 14, 2):
        canvas.fill(x, 13, 1, 2, GOLD)


def build_customization_module_icon() -> Canvas:
    """The fitting bay: a chip on a board, wired out to the connector.

    The chip is a dark package with only a small die showing. An earlier pass drew the die large
    and bright and the board came out as one glowing blue tile - at sixteen pixels the working part
    has to be the thing that is *not* lit.
    """
    canvas = Canvas(16, 16)
    _module_board(canvas)

    canvas.fill(5, 4, 6, 6, CHIP)
    canvas.outline(5, 4, 6, 6, DARK3)
    canvas.fill(7, 6, 2, 2, CYAN)

    # Short traces from the package out to the board edge.
    canvas.fill(2, 6, 3, 1, PCB_TRACE)
    canvas.fill(11, 6, 3, 1, PCB_TRACE)
    canvas.fill(2, 8, 3, 1, PCB_TRACE)
    canvas.fill(11, 8, 3, 1, PCB_TRACE)

    for x in (2, 13):
        canvas.set(x, 4, PCB_TRACE)
        canvas.set(x, 10, PCB_TRACE)

    return canvas


def build_attack_module_icon() -> Canvas:
    """The warhead: the same board with a red core where the processor would be."""
    canvas = Canvas(16, 16)
    _module_board(canvas)

    # Hazard chevrons on the shoulders, in the same amber the drone's tail lights use.
    canvas.fill(2, 3, 2, 1, AMBER)
    canvas.fill(12, 3, 2, 1, AMBER)
    canvas.fill(2, 10, 2, 1, AMBER)
    canvas.fill(12, 10, 2, 1, AMBER)

    # Core in a heavy housing.
    canvas.fill(5, 4, 6, 7, CORE_HOUSING)
    canvas.fill(6, 5, 4, 5, CORE)
    canvas.fill(7, 6, 2, 3, CORE_HOT)

    return canvas


# The three sensor boards. Each gets its own hue rather than relying on shape: at sixteen pixels
# every board is the same green-black rectangle with a small figure on it, so colour is the cue that
# actually survives a crowded inventory. The hues are the ones the features themselves draw with -
# amber chests, amber-then-red radar - so the board previews its own output.
CHEST_WOOD = (132, 92, 50, 255)
CHEST_WOOD_HI = (182, 132, 78, 255)
CHEST_AMBER = (250, 199, 117, 255)
RADAR_NEAR = (240, 190, 90, 255)
RADAR_MID = (226, 140, 70, 255)
RADAR_FAR = (214, 78, 72, 255)


def build_recon_boost_module_icon() -> Canvas:
    """Recon package: a view cone widening away from the sensor.

    A cone rather than an eye or a lens. A lens with a handle reads as a magnifier - "search" - and
    an eye at this size is just an oval, which is the blob the attack board's core already makes.
    Two straight edges diverging is the one glyph that says "wider field" without help, and it
    leaves the family's cyan to the speed board's chevrons.
    """
    canvas = Canvas(16, 16)
    _module_board(canvas)

    # Narrow throat at the bottom, opening to the full width of the board.
    for step in range(6):
        canvas.set(7 - step, 10 - step, CYAN)
        canvas.set(8 + step, 10 - step, CYAN)

    # The sensor itself at the apex, and a lit bar across the far end to give the cone a direction.
    canvas.fill(7, 11, 2, 1, CHIP)
    canvas.fill(2, 4, 12, 1, CYAN_DEEP)

    return canvas


def build_container_marker_module_icon() -> Canvas:
    """Container scanner: a chest, in the same amber the on-screen markers use.

    Sharing the marker's colour is the whole point - the board and the squares it draws are one
    feature, and the amber is what ties them together in the player's head.
    """
    canvas = Canvas(16, 16)
    _module_board(canvas)

    canvas.fill(4, 5, 8, 6, CHEST_WOOD)
    canvas.outline(4, 5, 8, 6, CHEST_WOOD_HI)
    # The lid seam: one horizontal is what turns a crate into a chest.
    canvas.fill(5, 7, 6, 1, CHEST_WOOD_HI)
    canvas.fill(7, 7, 2, 2, CHEST_AMBER)

    return canvas


def build_alert_radar_module_icon() -> Canvas:
    """Radar: quarter arcs stepping outward from a corner, amber through red.

    The arcs run the same ramp the on-screen alert uses - amber for a mob, red for a player - so
    the board shows what it is going to draw.
    """
    canvas = Canvas(16, 16)
    _module_board(canvas)

    origin_x, origin_y = 3, 10
    canvas.fill(origin_x, origin_y, 2, 2, CHIP)
    canvas.set(origin_x, origin_y, RADAR_NEAR)

    # Hand-placed rather than computed: at these radii a circle routine spends more code on rounding
    # than the arc has pixels, and the rounding is exactly what makes it wobble.
    arcs = (
        (RADAR_NEAR, ((3, 0), (2, 1), (1, 2), (0, 3))),
        (RADAR_MID, ((5, 0), (5, 1), (4, 2), (4, 3), (3, 4), (2, 4), (1, 5), (0, 5))),
        (RADAR_FAR, ((7, 0), (7, 1), (6, 2), (6, 3), (5, 4), (5, 5), (4, 5), (3, 6), (2, 6),
                     (1, 7), (0, 7))),
    )
    for colour, pixels in arcs:
        for dx, dy in pixels:
            canvas.set(origin_x + dx, origin_y - dy, colour)

    return canvas


def _mast(canvas: Canvas, top: int, waves: tuple[int, ...],
          colour: tuple[int, int, int, int]) -> None:
    """An antenna mast with a lit tip and dashes of wavefront either side of it.

    Shared by both signal tiers so the pair reads as one part at two lengths. The colour is what
    actually separates them - see the second tier's docstring for why length alone does not work.
    """
    canvas.fill(7, top, 2, 12 - top, CHIP)
    canvas.set(7, top - 1, colour)
    canvas.set(8, top - 1, colour)

    # Dashes rather than arcs: at one pixel an arc is a dot, and a dot reads as dust. Stepping the
    # dashes outward as they go down gives the same "spreading" cue with shapes that survive.
    for index, y in enumerate(waves):
        reach = 5 - index
        canvas.set(7 - reach, y, colour)
        canvas.set(8 + reach, y, colour)
        canvas.set(7 - reach - 1, y + 1, colour)
        canvas.set(8 + reach + 1, y + 1, colour)


def build_signal_module_mk1_icon() -> Canvas:
    """First signal tier: a short cyan mast with a single pair of wavefronts."""
    canvas = Canvas(16, 16)
    _module_board(canvas)
    _mast(canvas, top=6, waves=(7, 9), colour=CYAN)
    return canvas


def build_signal_module_mk2_icon() -> Canvas:
    """Second signal tier: the same mast, taller and in gold.

    The colour is doing the work, not the height. The first version of this pair differed only in
    where the mast topped out - two pixels - plus a thin gold band at its foot, and at icon size
    nobody could tell them apart. Gold against cyan is legible at a glance in a crowded inventory,
    which two pixels of length never will be. The extra wavefront and the band stay, but they are
    reinforcement now rather than the whole difference.
    """
    canvas = Canvas(16, 16)
    _module_board(canvas)
    _mast(canvas, top=3, waves=(5, 7, 9), colour=GOLD)
    canvas.fill(6, 11, 4, 1, GOLD)
    return canvas


def build_speed_module_icon() -> Canvas:
    """The governor removal: a row of forward chevrons, in the family's cyan.

    Chevrons rather than a gauge or a throttle lever, because those are three or four pixel
    drawings that turn to mush at sixteen. Each one here is three wide and five tall, which is the
    smallest size at which a ">" still reads as an arrow rather than as part of a zigzag.
    """
    canvas = Canvas(16, 16)
    _module_board(canvas)

    for x in (2, 6, 10):
        canvas.set(x, 5, CYAN)
        canvas.set(x + 1, 6, CYAN)
        canvas.set(x + 2, 7, CYAN)
        canvas.set(x + 1, 8, CYAN)
        canvas.set(x, 9, CYAN)

    return canvas


def _battery_shell(canvas: Canvas, body: tuple[int, int, int, int],
                   edge: tuple[int, int, int, int]) -> None:
    """The shape both packs share: a raised terminal top and bottom around a tall shell.

    Drawn from the same footprint as the modules so the whole tab reads as one kit, but as a
    vertical cell rather than a board - a battery that looked like a circuit board would be a
    lie about what it is.
    """
    canvas.fill(6, 2, 4, 1, GOLD)
    canvas.fill(5, 3, 6, 10, body)
    canvas.outline(5, 3, 6, 10, edge)
    canvas.fill(6, 13, 4, 1, GOLD)


def build_copper_battery_icon() -> Canvas:
    """The cheap pack: a copper shell with two redstone charge marks."""
    canvas = Canvas(16, 16)
    _battery_shell(canvas, COPPER, COPPER_HI)
    canvas.fill(6, 5, 4, 1, REDSTONE)
    canvas.fill(6, 8, 4, 1, REDSTONE_HI)
    return canvas


def build_graphite_electrode_icon() -> Canvas:
    """A bare graphite rod with a diamond set into it.

    Deliberately *not* the shell the packs use - no gold terminals, and the diamond is the one
    bright thing on the icon. It is an ingredient, and it should not be mistakable for a battery
    the player can already fit.
    """
    canvas = Canvas(16, 16)
    canvas.fill(5, 3, 6, 10, GRAPHITE)
    canvas.outline(5, 3, 6, 10, GRAPHITE_HI)
    canvas.fill(6, 1, 4, 1, GRAPHITE_HI)
    canvas.fill(6, 13, 4, 1, GRAPHITE_HI)
    canvas.fill(7, 6, 2, 3, CYAN)
    return canvas


def build_graphite_battery_icon() -> Canvas:
    """The good pack: the same shell in graphite, with three gold charge marks."""
    canvas = Canvas(16, 16)
    _battery_shell(canvas, GRAPHITE, GRAPHITE_HI)
    for y in (5, 8, 11):
        canvas.fill(6, y, 4, 1, GOLD)
    return canvas


# --------------------------------------------------------------------------- workbench
def build_workbench_block() -> Canvas:
    """The bench's top plate: a machined slab with a recessed bay for the drone.

    The plate is deliberately a couple of shades lighter than the bay. Both were dark in the first
    pass and the recess simply disappeared - a recess only reads if there is a lit surface for it
    to be a hole in.
    """
    canvas = Canvas(16, 16)

    canvas.fill(0, 0, 16, 16, DARK2)
    canvas.fill(0, 0, 16, 1, LIGHT)
    canvas.fill(0, 0, 1, 16, LIGHT)
    canvas.fill(0, 15, 16, 1, DARK3)
    canvas.fill(15, 0, 1, 16, DARK3)
    canvas.outline(0, 0, 16, 16, (24, 27, 32, 255))

    # Bay. Recessed, not raised, so the block reads as something you lay a drone into.
    canvas.fill(3, 3, 10, 10, (26, 30, 36, 255))
    canvas.outline(3, 3, 10, 10, (14, 17, 21, 255))
    canvas.fill(3, 3, 10, 1, (18, 21, 26, 255))

    # The jig itself, with a lit lip along the top edge.
    canvas.fill(5, 6, 6, 4, (10, 12, 16, 255))
    canvas.fill(5, 6, 6, 1, CYAN_DEEP)

    # Status lights and corner bolts.
    canvas.set(4, 4, CYAN)
    canvas.set(11, 4, CYAN)
    for x, y in ((1, 1), (14, 1), (1, 13), (14, 13)):
        canvas.set(x, y, BOLT)

    return canvas


GUI_BG = (16, 20, 26, 255)
GUI_PANEL = (22, 28, 36, 255)
GUI_PANEL_LINE = (20, 25, 32, 255)
GUI_WELL = (9, 12, 16, 255)
GUI_WELL_EDGE = (6, 8, 11, 255)
GUI_SEAM = (44, 52, 62, 255)

# Slot wells, in image coordinates. Must match ModuleWorkbenchMenu's Slot positions; the well
# itself is drawn a pixel up and left of the slot, the way vanilla insets it.
GUI_SLOTS = ((44, 36), (80, 36), (116, 36))
GUI_SLOT_SIZE = 18
GUI_MACHINE_PANEL = (4, 18, 168, 52)

# The player's own slots, again at the coordinates the menu uses. Drawing them is not decoration:
# with no wells the whole lower half of the panel is one flat slab, and the screen reads as a blank
# oversized box rather than as a container you can put things in.
GUI_INVENTORY = (8, 84)
GUI_HOTBAR = (8, 142)
GUI_COLS = 9
GUI_ROWS = 3


def _gui_well(canvas: Canvas, slot_x: int, slot_y: int) -> None:
    """One recess. Drawn at the slot position less one pixel, so the lit seam falls outside it."""
    x, y = slot_x - 1, slot_y - 1
    canvas.fill(x, y, GUI_SLOT_SIZE, GUI_SLOT_SIZE, GUI_WELL)
    # Lit from the bottom right, shadowed top left: a recess, not a raised tile.
    canvas.fill(x, y, GUI_SLOT_SIZE, 1, GUI_WELL_EDGE)
    canvas.fill(x, y, 1, GUI_SLOT_SIZE, GUI_WELL_EDGE)
    canvas.fill(x, y + GUI_SLOT_SIZE - 1, GUI_SLOT_SIZE, 1, GUI_SEAM)
    canvas.fill(x + GUI_SLOT_SIZE - 1, y, 1, GUI_SLOT_SIZE, GUI_SEAM)


def build_workbench_gui() -> Canvas:
    """The bench screen: the same housing language as the visor, at container scale.

    Three wells left to right - drone, module, finished drone - with the flow between them drawn
    into the panel. The decoration is all in the visor's own colours so the screen belongs to the
    same machine as the drone it is fitting out.
    """
    width, height = 176, 166
    canvas = Canvas(width, height)

    canvas.fill(0, 0, width, height, GUI_BG)
    canvas.outline(0, 0, width, height, CYAN_DEEP)

    px, py, pw, ph = GUI_MACHINE_PANEL
    canvas.fill(px, py, pw, ph, GUI_PANEL)
    canvas.outline(px, py, pw, ph, DARK3)
    # Faint horizontal banding, four pixels apart. Enough to stop the panel reading as flat
    # colour without turning into a visible stripe pattern.
    for y in range(py + 2, py + ph - 1, 4):
        canvas.fill(px + 1, y, pw - 2, 1, GUI_PANEL_LINE)

    # Corner brackets, the same gesture the visor frame makes.
    for bx, by, dx, dy in ((px + 1, py + 1, 1, 1), (px + pw - 2, py + 1, -1, 1),
                           (px + 1, py + ph - 2, 1, -1), (px + pw - 2, py + ph - 2, -1, -1)):
        canvas.fill(bx if dx > 0 else bx - 6, by, 7, 1, CYAN)
        canvas.fill(bx, by if dy > 0 else by - 6, 1, 7, CYAN)

    for sx, sy in GUI_SLOTS:
        _gui_well(canvas, sx, sy)

    inv_x, inv_y = GUI_INVENTORY
    for row in range(GUI_ROWS):
        for col in range(GUI_COLS):
            _gui_well(canvas, inv_x + col * GUI_SLOT_SIZE, inv_y + row * GUI_SLOT_SIZE)
    hot_x, hot_y = GUI_HOTBAR
    for col in range(GUI_COLS):
        _gui_well(canvas, hot_x + col * GUI_SLOT_SIZE, hot_y)

    mid_y = GUI_SLOTS[0][1] + GUI_SLOT_SIZE // 2

    # Drone + module.
    plus_x = GUI_SLOTS[0][0] + GUI_SLOT_SIZE + 4
    canvas.fill(plus_x, mid_y - 1, 8, 1, CYAN_DEEP)
    canvas.fill(plus_x + 3, mid_y - 4, 1, 8, CYAN_DEEP)

    # Module -> finished drone. A shaft with a stepped head, so it reads as an arrow at this size.
    arrow_x = GUI_SLOTS[1][0] + GUI_SLOT_SIZE + 1
    canvas.fill(arrow_x, mid_y - 1, 9, 2, CYAN_DEEP)
    for step in range(4):
        canvas.fill(arrow_x + 9 + step, mid_y - (3 - step), 1, 2 * (3 - step) + 1, CYAN_DEEP)

    # Seam between the bench and the player's inventory.
    canvas.fill(6, py + ph, width - 12, 1, GUI_SEAM)
    canvas.fill(6, py + ph + 1, width - 12, 1, GUI_PANEL_LINE)

    return canvas


# --------------------------------------------------------------------------- logo
def build_logo() -> Canvas:
    canvas = Canvas(128, 128)

    canvas.fill(0, 0, 128, 128, (16, 20, 26, 255))

    for i in range(0, 128, 8):
        canvas.fill(0, i, 128, 1, (22, 28, 36, 255))

    icon = build_item_icon()
    for y in range(32):
        for x in range(32):
            canvas.fill(32 + x * 2, 32 + y * 2, 2, 2, icon.px[y][x])

    canvas.outline(8, 8, 112, 112, CYAN_DEEP)
    for cx, cy in ((8, 8), (119, 8), (8, 119), (119, 119)):
        canvas.fill(cx - 2, cy - 2, 6, 6, CYAN)
    return canvas


def main() -> None:
    with open(MANIFEST, encoding="utf-8") as handle:
        manifest = json.load(handle)

    def write(canvas, *parts: str) -> None:
        canvas.write_png(os.path.join(paths.ASSETS, *parts))

    build_entity_sheet(manifest, glow=False).write_png(
        os.path.join(paths.TEXTURE_DIR, "entity", "recon_drone.png"))
    build_entity_sheet(manifest, glow=True).write_png(
        os.path.join(paths.TEXTURE_DIR, "entity", "recon_drone_glow.png"))

    write(build_item_icon(), "textures", "item", "recon_drone.png")
    write(build_chassis_icon(), "textures", "item", "drone_chassis.png")
    write(build_propeller_icon(), "textures", "item", "drone_propeller.png")
    write(build_customization_module_icon(), "textures", "item", "customization_module.png")
    write(build_signal_module_mk1_icon(), "textures", "item", "signal_module_mk1.png")
    write(build_signal_module_mk2_icon(), "textures", "item", "signal_module_mk2.png")
    write(build_speed_module_icon(), "textures", "item", "speed_module.png")
    write(build_copper_battery_icon(), "textures", "item", "copper_battery.png")
    write(build_graphite_electrode_icon(), "textures", "item", "graphite_electrode.png")
    write(build_graphite_battery_icon(), "textures", "item", "graphite_battery.png")
    write(build_attack_module_icon(), "textures", "item", "attack_module.png")
    write(build_recon_boost_module_icon(), "textures", "item", "recon_boost_module.png")
    write(build_container_marker_module_icon(), "textures", "item", "container_marker_module.png")
    write(build_alert_radar_module_icon(), "textures", "item", "alert_radar_module.png")

    write(build_workbench_block(), "textures", "block", "module_workbench.png")
    write(build_workbench_gui(), "textures", "gui", "module_workbench.png")

    # The pack logo sits at the resource root, not under assets/.
    build_logo().write_png(os.path.join(RES, "watchcraft.png"))


if __name__ == "__main__":
    main()
