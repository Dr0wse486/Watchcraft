"""Generates the Recon Drone mesh set (OBJ + MTL + UV manifest).

Outputs (into the directory given on the command line, default: cwd)
--------------------------------------------------------------------
    drone.obj            airframe: hull, arms, motors, rotors, skids, gimbal yoke
    drone.mtl            single material, texture driven
    drone_glow.obj       emissive parts only: gimbal lens, tail light
    drone_glow.mtl
    drone_manifest.json  UV packing + per-part pivot + bbox + face counts

Conventions
-----------
* 1.0 = 1 Minecraft block. NeoForge's OBJ loader does not scale by 1/16.
* Y is up, the nose points towards -Z, +X is the airframe's right.
* The origin is the airframe's geometric centre.
* Every vertex is snapped to a 1/128 block grid (`H`). That is far finer than
  a texture pixel, which is the point: it kills floating-point drift without
  quantising the 45 degree arm geometry into visible stair steps. Snapping on
  a coarse grid used to widen a 1U rotor blade to 2.8U, because the diagonal
  half-width (0.3536U) rounded to whatever the grid could express.
* Faces are quads or triangles, wound counter-clockwise seen from outside.

Why the parts interpenetrate instead of abutting
------------------------------------------------
Every part that touches the hull pushes *into* it by at least one U rather
than meeting it face to face. Two faces that lie in the same plane and face
the same way make the depth test a coin flip, which reads in game as a
flickering patch. Pushing parts in means the shared plane is buried inside a
solid, where back-face culling discards it before the depth test ever runs.

Run
---
    python tools/generate_drone_mesh.py [output_dir] [--install]

`output_dir` defaults to `build/meshgen`. Pass `--install` to copy the result into the
repo as well: the OBJ/MTL pair next to the mod's other resources, and the manifest into
`tools/` where the texture painter, the preview and the verifier all look for it. That
turns the whole asset pipeline into two commands:

    python tools/generate_drone_mesh.py --install
    python tools/generate_textures.py
"""

from __future__ import annotations

import json
import math
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402

U = 1.0 / 16.0          # one texture pixel unit; 1U == 8 texels
H = 1.0 / 128.0         # the smallest coordinate step

PALETTE = {
    "HULL_DARK": (46, 50, 56),
    "HULL_MID": (60, 66, 74),
    "HULL_PANEL": (74, 80, 88),
    "HULL_LIGHT": (154, 163, 173),
    "PANEL_LINE": (33, 36, 41),
    "CYAN": (34, 211, 238),
    "CYAN_DEEP": (12, 116, 138),
    # Dark tinted glass. It has to stay clearly darker than HULL_DARK to read
    # as a window, but an earlier (11, 15, 20) was so close to black that the
    # canopy read as a hole punched through the deck rather than as glazing.
    "GLASS": (38, 54, 72),
    "AMBER": (255, 107, 53),
    "BLADE": (200, 208, 216),
    "BLADE_DARK": (120, 128, 138),
}

SHEET = (512, 512)
GLOW_SHEET = (128, 128)

TEXELS_PER_U = 8.0


# --------------------------------------------------------------------- vectors

def snap(value: float) -> float:
    return round(value / H) * H


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _length(v):
    return math.sqrt(_dot(v, v))


def _unit(v):
    n = _length(v)
    if n < 1e-9:
        raise ValueError("cannot normalise a zero-length vector")
    return (v[0] / n, v[1] / n, v[2] / n)


def _fmt(value: float) -> str:
    """Shortest text that still round-trips exactly.

    A fixed "%.6f" is not enough: the grid step is 1/128 = 0.0078125, which
    needs seven decimals, and truncating it pushed 404 vertices back off the
    grid the moment the OBJ was re-read.
    """
    if abs(value) < 1e-9:
        return "0"
    return "%.9g" % value


def frame_axes(direction):
    """Returns (side, vertical), both unit and both perpendicular to `direction`.

    `vertical` is kept as close to +Y as the direction allows. The degenerate
    case - a plate running straight up or down - is handled explicitly rather
    than by leaning on a normalise() fallback, because that fallback used to
    hand back (0, 1, 0) for a zero vector and silently collapsed the plate to
    zero area.
    """
    side = _cross((0.0, 1.0, 0.0), direction)
    if _length(side) < 1e-9:
        side = (1.0, 0.0, 0.0)
    side = _unit(side)
    vertical = _unit(_cross(direction, side))
    return side, vertical


def rotate_y(point, turns):
    x, y, z = point
    for _ in range(turns % 4):
        x, z = -z, x
    return (x, y, z)


def rotate_plan(direction, degrees):
    """Rotates an (x, z) plan direction about +Y.

    Only ever called with 45 and 135, whose cosine and sine are both 1/sqrt(2).
    Turning a diagonal by 45 degrees lands it exactly on an axis, so the blade
    tips fall on whole U values instead of on 1/sqrt(2) multiples and the grid
    snap has nothing to round away.
    """
    angle = math.radians(degrees)
    c, s = math.cos(angle), math.sin(angle)
    x, z = direction
    rotated = (x * c + z * s, -x * s + z * c)
    # Flush the 1e-17 that cos/sin leave behind, so the four rotors stay
    # bit-for-bit congruent.
    return tuple(0.0 if abs(v) < 1e-12 else v for v in rotated)


# ------------------------------------------------------------------- UV layout

class ShelfPacker:
    """Deterministic shelf packing: left to right, wrap on overflow."""

    def __init__(self, width, height, margin=2, gap=1):
        self.width = width
        self.height = height
        self.margin = margin
        self.gap = gap
        self.x = margin
        self.y = margin
        self.row_height = 0

    def pack(self, width, height):
        width = max(1, int(width))
        height = max(1, int(height))
        if self.x + width > self.width - self.margin:
            self.x = self.margin
            self.y += self.row_height + self.gap
            self.row_height = 0
        if self.y + height > self.height - self.margin:
            raise ValueError("UV sheet %dx%d is too small" % (self.width, self.height))
        rect = [self.x, self.y, width, height]
        self.x += width + self.gap
        self.row_height = max(self.row_height, height)
        return rect


# ------------------------------------------------------------------------ mesh

class Mesh:
    def __init__(self, mesh_name, material, sheet):
        self.mesh_name = mesh_name
        self.material = material
        self.sheet = sheet
        self.vertices = []
        self.uvs = []
        self.normals = []
        self.faces = []
        self.groups = {}
        self.packer = ShelfPacker(sheet[0], sheet[1])

    # -- authoring ---------------------------------------------------------

    def add_face(self, group, pivot, name, points, colour, outward):
        points = [tuple(snap(c) for c in point) for point in points]
        if len(points) not in (3, 4):
            raise ValueError("%s/%s: OBJ faces must be triangles or quads" % (group, name))

        normal = _cross(_sub(points[1], points[0]), _sub(points[2], points[0]))
        if _length(normal) < 1e-9:
            raise ValueError(
                "%s/%s: degenerate face after snapping, points=%s" % (group, name, points))
        if _dot(normal, outward) < 0.0:
            points.reverse()
            normal = _cross(_sub(points[1], points[0]), _sub(points[2], points[0]))
        normal = _unit(normal)

        # Face extents in blocks, largest two axes -> the natural UV rectangle.
        extents = sorted((max(p[i] for p in points) - min(p[i] for p in points)
                          for i in range(3)), reverse=True)
        rect = self.packer.pack(math.ceil(extents[0] * 16.0 * TEXELS_PER_U),
                                math.ceil(extents[1] * 16.0 * TEXELS_PER_U))

        x, y, width, height = rect
        corners = (
            (x / self.sheet[0], y / self.sheet[1]),
            ((x + width) / self.sheet[0], y / self.sheet[1]),
            ((x + width) / self.sheet[0], (y + height) / self.sheet[1]),
            (x / self.sheet[0], (y + height) / self.sheet[1]),
        )
        uv = corners[:len(points)]

        normal_index = len(self.normals) + 1
        self.normals.append(normal)
        vertex_indices = []
        uv_indices = []
        for index, point in enumerate(points):
            self.vertices.append(point)
            self.uvs.append(uv[index])
            vertex_indices.append(len(self.vertices))
            uv_indices.append(len(self.uvs))

        face = {
            "group": group,
            "name": name,
            "colour": colour,
            "rect": rect,
            "uv": [[round(u, 6), round(v, 6)] for u, v in uv],
            "vertices": vertex_indices,
            "uv_indices": uv_indices,
            "normal": normal_index,
        }
        self.faces.append(face)
        entry = self._group(group, pivot, colour)
        entry["faces"].append(face)
        entry["points"].extend(points)
        return face

    def surface(self, group, pivot, name, points, colour, centre):
        """Adds a face of a convex solid, deriving the outward direction from `centre`."""
        mean = tuple(sum(p[i] for p in points) / len(points) for i in range(3))
        self.add_face(group, pivot, name, points, colour, _sub(mean, centre))

    def _group(self, group, pivot, colour):
        if group not in self.groups:
            self.groups[group] = {
                "group": group,
                "mesh": self.mesh_name,
                "pivot": [round(v, 6) for v in pivot],
                "colour": colour,
                "faces": [],
                "points": [],
            }
        return self.groups[group]

    def replicate(self, source, target, turns, pivot):
        """Copies `source` into `target`, rotating 90 degrees about Y `turns` times.

        Deriving the other three arms by rotation rather than by re-running the
        same arithmetic guarantees they are exactly congruent - re-running it
        would let the per-component snap round differently and break symmetry.

        The rotated face is handed `add_face` the rotated *normal* rather than a
        direction derived from the group centroid. A part sitting off to one
        side of the airframe has most of its faces pointing back towards the
        origin, and the centroid test would have flipped every one of them.
        """
        for face in list(self.groups[source]["faces"]):
            points = [rotate_y(self.vertices[i - 1], turns) for i in face["vertices"]]
            normal = _cross(_sub(points[1], points[0]), _sub(points[2], points[0]))
            name = face["name"].replace(source, target, 1)
            self.add_face(target, pivot, name, points, face["colour"], normal)

    # -- output ------------------------------------------------------------

    def manifest_parts(self):
        result = []
        for entry in self.groups.values():
            low = [min(p[i] for p in entry["points"]) for i in range(3)]
            high = [max(p[i] for p in entry["points"]) for i in range(3)]
            result.append({
                "group": entry["group"],
                "mesh": entry["mesh"],
                "pivot": entry["pivot"],
                "faces": len(entry["faces"]),
                "bbox": [[round(v, 6) for v in low], [round(v, 6) for v in high]],
            })
        return result

    def write_obj(self, path, mtllib):
        lines = ["# Recon Drone - 1.0 = 1 Minecraft block, Y up, nose towards -Z",
                 "mtllib %s" % mtllib]
        for point in self.vertices:
            lines.append("v %s %s %s" % tuple(_fmt(v) for v in point))
        for u, v in self.uvs:
            lines.append("vt %s %s" % (_fmt(u), _fmt(v)))
        for normal in self.normals:
            lines.append("vn %s %s %s" % tuple(_fmt(v) for v in normal))
        for group, entry in self.groups.items():
            lines.append("# pivot: %s %s %s" % tuple(_fmt(v) for v in entry["pivot"]))
            lines.append("g %s" % group)
            lines.append("usemtl %s" % self.material)
            for face in entry["faces"]:
                values = ["%d/%d/%d" % (v, uv, face["normal"])
                          for v, uv in zip(face["vertices"], face["uv_indices"])]
                lines.append("f " + " ".join(values))
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write("\n".join(lines) + "\n")


# ------------------------------------------------------------------- primitives

def box(mesh, cx, cy, cz, sx, sy, sz, group, name, colour, pivot=None):
    """Axis aligned box, centred on (cx, cy, cz)."""
    if pivot is None:
        pivot = (cx, cy, cz)
    sx, sy, sz = max(sx, H), max(sy, H), max(sz, H)
    hx, hy, hz = sx / 2.0, sy / 2.0, sz / 2.0
    centre = (cx, cy, cz)
    faces = [
        [(-hx, -hy, -hz), (hx, -hy, -hz), (hx, -hy, hz), (-hx, -hy, hz)],
        [(-hx, hy, -hz), (-hx, hy, hz), (hx, hy, hz), (hx, hy, -hz)],
        [(-hx, -hy, -hz), (-hx, hy, -hz), (hx, hy, -hz), (hx, -hy, -hz)],
        [(-hx, -hy, hz), (hx, -hy, hz), (hx, hy, hz), (-hx, hy, hz)],
        [(hx, -hy, -hz), (hx, hy, -hz), (hx, hy, hz), (hx, -hy, hz)],
        [(-hx, -hy, -hz), (-hx, -hy, hz), (-hx, hy, hz), (-hx, hy, -hz)],
    ]
    for index, face in enumerate(faces):
        mesh.surface(group, pivot, "%s_%d" % (name, index),
                     [(cx + p[0], cy + p[1], cz + p[2]) for p in face], colour, centre)


def taper_box(mesh, cx, cy, cz, sx, sy, sz, front, back, group, name, colour, pivot=None):
    """Box whose -Z face is `front` times as wide and whose +Z face is `back` times.

    Only the X extent tapers; the top and bottom stay horizontal and full
    height. Tapering Y as well would stop stacked slabs from meeting, because
    each slab's top edge would sit at a different height and leave a gap
    straight through the fuselage.
    """
    if pivot is None:
        pivot = (cx, cy, cz)
    sx, sy, sz = max(sx, H), max(sy, H), max(sz, H)
    hx, hy, hz = sx / 2.0, sy / 2.0, sz / 2.0
    fx, bx = hx * front, hx * back
    centre = (cx, cy, cz)

    def p(x, y, z):
        return (cx + x, cy + y, cz + z)

    front_face = [(-fx, -hy, -hz), (fx, -hy, -hz), (fx, hy, -hz), (-fx, hy, -hz)]
    back_face = [(-bx, -hy, hz), (bx, -hy, hz), (bx, hy, hz), (-bx, hy, hz)]
    faces = [
        front_face,
        back_face,
        [front_face[0], front_face[1], back_face[1], back_face[0]],   # bottom
        [front_face[3], back_face[3], back_face[2], front_face[2]],   # top
        [front_face[0], back_face[0], back_face[3], front_face[3]],   # left
        [front_face[1], front_face[2], back_face[2], back_face[1]],   # right
    ]
    for index, face in enumerate(faces):
        mesh.surface(group, pivot, "%s_%d" % (name, index),
                     [p(*v) for v in face], colour, centre)


def prism(mesh, cx, cy, cz, radius, height, sides, group, name, colour,
          pivot=None, cap_top=True, cap_bottom=True, phase=None):
    """Regular prism with its axis along Y, centred on (cx, cy, cz)."""
    if pivot is None:
        pivot = (cx, cy, cz)
    if not 3 <= sides <= 12:
        raise ValueError("prism sides must be between 3 and 12")
    if phase is None:
        # Half a segment: puts a flat edge facing +X and +Z, so the housing
        # reads the same from every approach.
        phase = math.pi / sides
    bottom = []
    top = []
    for index in range(sides):
        angle = 2.0 * math.pi * index / sides + phase
        x = radius * math.cos(angle)
        z = radius * math.sin(angle)
        bottom.append((cx + x, cy - height / 2.0, cz + z))
        top.append((cx + x, cy + height / 2.0, cz + z))
    centre = (cx, cy, cz)
    for index in range(sides):
        nxt = (index + 1) % sides
        mesh.surface(group, pivot, "%s_side_%d" % (name, index),
                     [bottom[index], bottom[nxt], top[nxt], top[index]], colour, centre)
    for index in range(1, sides - 1):
        if cap_bottom:
            mesh.add_face(group, pivot, "%s_bottom_%d" % (name, index),
                          [bottom[0], bottom[index + 1], bottom[index]], colour, (0.0, -1.0, 0.0))
        if cap_top:
            mesh.add_face(group, pivot, "%s_top_%d" % (name, index),
                          [top[0], top[index], top[index + 1]], colour, (0.0, 1.0, 0.0))


def plate(mesh, p0, p1, width, thickness, group, name, colour, pivot=None, capped=True):
    """Rectangular bar running from p0 to p1.

    `width` spans the horizontal perpendicular, `thickness` spans the remaining
    axis. Works for horizontal, diagonal and straight-up bars alike.
    """
    if pivot is None:
        pivot = p0
    width = max(width, H)
    thickness = max(thickness, H)
    direction = _unit(_sub(p1, p0))
    side, vertical = frame_axes(direction)
    hw, ht = width / 2.0, thickness / 2.0
    centre = tuple((p0[i] + p1[i]) / 2.0 for i in range(3))

    corners = []
    for endpoint in (p0, p1):
        corners.append(tuple(endpoint[i] - side[i] * hw - vertical[i] * ht for i in range(3)))
        corners.append(tuple(endpoint[i] + side[i] * hw - vertical[i] * ht for i in range(3)))
        corners.append(tuple(endpoint[i] + side[i] * hw + vertical[i] * ht for i in range(3)))
        corners.append(tuple(endpoint[i] - side[i] * hw + vertical[i] * ht for i in range(3)))

    mesh.surface(group, pivot, name + "_bottom", [corners[0], corners[1], corners[5], corners[4]], colour, centre)
    mesh.surface(group, pivot, name + "_top", [corners[3], corners[7], corners[6], corners[2]], colour, centre)
    mesh.surface(group, pivot, name + "_side_a", [corners[0], corners[4], corners[7], corners[3]], colour, centre)
    mesh.surface(group, pivot, name + "_side_b", [corners[1], corners[2], corners[6], corners[5]], colour, centre)
    if capped:
        mesh.surface(group, pivot, name + "_cap_0", [corners[0], corners[3], corners[2], corners[1]], colour, centre)
        mesh.surface(group, pivot, name + "_cap_1", [corners[4], corners[5], corners[6], corners[7]], colour, centre)


# ------------------------------------------------------------------ airframe

HULL_Z = -0.5 * U

# Horizontal planes the hull owns. Nothing else may put a face on one of these
# while overlapping the hull in plan view, or the two will fight for depth.
#   -1.5  -0.5   0.5   1.5   (all in U)

ARM_PIVOTS = ((2.0 * U, 0.0, -2.0 * U), (2.0 * U, 0.0, 2.0 * U),
              (-2.0 * U, 0.0, 2.0 * U), (-2.0 * U, 0.0, -2.0 * U))
MOTOR_PIVOTS = ((4.0 * U, 1.5 * U, -4.0 * U), (4.0 * U, 1.5 * U, 4.0 * U),
                (-4.0 * U, 1.5 * U, 4.0 * U), (-4.0 * U, 1.5 * U, -4.0 * U))
ROTOR_PIVOTS = ((4.0 * U, 2.25 * U, -4.0 * U), (4.0 * U, 2.25 * U, 4.0 * U),
                (-4.0 * U, 2.25 * U, 4.0 * U), (-4.0 * U, 2.25 * U, -4.0 * U))

GIMBAL_PIVOT = (0.0, -2.5 * U, -2.0 * U)

MOTOR_BOTTOM = 1.0 * U
MOTOR_TOP = 2.0 * U
MOTOR_RADIUS = 1.25 * U
ARM_Y = 0.5 * U              # arm centreline: clears the hull's -0.5/0.5 planes
# Two tiers, each a quarter U thick. Real rotor blades are thin; half a U of
# blade read as a slab. A quarter U is still grid aligned, because the half
# thickness lands on half a step of H.
ROTOR_LOWER_Y = 2.125 * U
ROTOR_UPPER_Y = 2.375 * U
ROTOR_THICKNESS = 0.25 * U
ROTOR_HALF_SPAN = 2.0 * U


def build_hull(mesh):
    """Three stacked slabs; the width taper on each carves the plan silhouette."""
    slabs = (
        # name      cy          sx          sz          front  back   colour
        ("belly", -1.0 * U, 4.5 * U, 5.0 * U, 0.80, 0.95, "HULL_DARK"),
        ("body",   0.0 * U, 5.0 * U, 5.5 * U, 0.72, 0.95, "HULL_MID"),
        ("deck",   1.0 * U, 4.0 * U, 4.5 * U, 0.78, 0.95, "HULL_PANEL"),
    )
    for name, cy, sx, sz, front, back, colour in slabs:
        taper_box(mesh, 0.0, cy, HULL_Z, sx, 1.0 * U, sz, front, back,
                  "frame", name, colour, (0.0, 0.0, 0.0))


def build_details(mesh):
    # canopy: a wedge sitting on the deck, its base flush with the deck top so
    # the two only ever meet back to back.
    taper_box(mesh, 0.0, 2.0 * U, -1.5 * U, 2.0 * U, 1.0 * U, 2.0 * U, 0.55, 1.0,
              "canopy", "glass", "GLASS", (0.0, 0.0, 0.0))

    # nose sensor: a wedge poking out of the fuselage front, its tail buried in
    # the body slab so no seam is exposed.
    taper_box(mesh, 0.0, 0.5 * U, -3.5 * U, 2.0 * U, 1.0 * U, 1.5 * U, 0.45, 1.0,
              "nose_sensor", "window", "CYAN_DEEP", (0.0, 0.0, 0.0))

    # antenna: mast plus a wider cap, rising off the back of the deck
    antenna_pivot = (0.0, 1.0 * U, 1.0 * U)
    box(mesh, 0.0, 2.0 * U, 1.0 * U, 1.0 * U, 1.0 * U, 1.0 * U,
        "antenna", "mast", "HULL_PANEL", antenna_pivot)
    box(mesh, 0.0, 3.0 * U, 1.0 * U, 2.0 * U, 1.0 * U, 2.0 * U,
        "antenna", "cap", "HULL_LIGHT", antenna_pivot)

    build_gimbal(mesh)
    build_skid(mesh, 1.0, "skid_l")
    build_skid(mesh, -1.0, "skid_r")


def build_gimbal(mesh):
    """A U bracket hanging under the nose: crossbar against the hull, struts, lens between.

    The crossbar stops *at* the hull's underside rather than reaching up past
    it. Its top face and the belly's bottom face then point at each other, and
    back-face culling resolves the pair before the depth test sees it. Letting
    the crossbar poke inside instead put its underside in the same plane as the
    belly's underside, facing the same way, which flickers.

    The whole bracket lives above -3U and the landing rail below -2.5U; an
    earlier version had both in one band, and because the struts reach inboard
    past the rail's inner edge their top and bottom faces collided too.
    """
    plate(mesh, (-1.0 * U, -1.75 * U, -2.0 * U), (1.0 * U, -1.75 * U, -2.0 * U),
          1.0 * U, 0.5 * U, "gimbal_yoke", "crossbar", "HULL_PANEL", GIMBAL_PIVOT)
    for name, sign in (("strut_l", 1.0), ("strut_r", -1.0)):
        x = sign * 1.0 * U
        plate(mesh, (x, -2.0 * U, -2.0 * U), (x, -3.0 * U, -2.0 * U),
              1.0 * U, 1.0 * U, "gimbal_yoke", name, "HULL_LIGHT", GIMBAL_PIVOT)


def build_skid(mesh, sign, name):
    """A landing rail outboard of the hull, held by two vertical braces.

    The rail is deliberately wider than the braces so the brace sides never
    land in the same plane as the rail sides.
    """
    pivot = (sign * 2.0 * U, -1.0 * U, 0.0)
    rail_x = sign * 2.0 * U
    plate(mesh, (rail_x, -3.0 * U, -2.0 * U), (rail_x, -3.0 * U, 2.0 * U),
          2.0 * U, 1.0 * U, name, "rail", "HULL_PANEL", pivot)
    for tag, z in (("front", -1.0 * U), ("rear", 1.0 * U)):
        plate(mesh, (rail_x, -1.5 * U, z), (rail_x, -3.0 * U, z),
              1.0 * U, 1.0 * U, name, "brace_" + tag, "HULL_LIGHT", pivot)


def build_arm(mesh, index):
    anchor = ARM_PIVOTS[index]
    motor = MOTOR_PIVOTS[index]
    rotor = ROTOR_PIVOTS[index]

    # The bar starts well inside the hull rather than at the hinge, so its end
    # cap is buried and the arm reads as growing out of the fuselage. It rides
    # at ARM_Y, halfway between the hull's 0.5U and 1.0U planes: sitting on
    # either of those would put its top face in the same plane, facing the same
    # way, as a hull face.
    inner = (anchor[0] * 0.5, ARM_Y, anchor[2] * 0.5)
    plate(mesh, inner, (motor[0], ARM_Y, motor[2]), 1.0 * U, 1.0 * U,
          "arm%d" % index, "bar", "HULL_PANEL", anchor)

    prism(mesh, motor[0], (MOTOR_BOTTOM + MOTOR_TOP) / 2.0, motor[2],
          MOTOR_RADIUS, MOTOR_TOP - MOTOR_BOTTOM, 8,
          "motor%d" % index, "housing", "HULL_PANEL", motor)

    dx = motor[0] - inner[0]
    dz = motor[2] - inner[2]
    planar = math.hypot(dx, dz)
    dx, dz = dx / planar, dz / planar
    span = ROTOR_HALF_SPAN

    # Neither blade lies along the arm: both are turned 45 degrees off it. Left
    # collinear, the lower blade and the arm read as a single bar running from
    # the fuselage out past the motor, and the rotor vanishes into the arm. An
    # earlier version offset the second blade by exactly 90 degrees, which
    # fixed the cross but left the first blade on the arm, so the arm looked
    # like it grew a spike. Turned, the arm stays a clean diagonal and the
    # rotor reads as an axis-aligned cross of its own.
    #
    # The upper blade carries the light colour: it is the one you actually see
    # from above, so making the lower blade pale just leaves the rotor reading
    # as a dark smudge.
    blade_a = rotate_plan((dx, dz), 45.0)
    blade_b = rotate_plan((dx, dz), 135.0)
    for blade, y, (ox, oz), colour in (("blade_a", ROTOR_LOWER_Y, blade_a, "BLADE_DARK"),
                                       ("blade_b", ROTOR_UPPER_Y, blade_b, "BLADE")):
        plate(mesh,
              (motor[0] - ox * span, y, motor[2] - oz * span),
              (motor[0] + ox * span, y, motor[2] + oz * span),
              1.0 * U, ROTOR_THICKNESS,
              "rotor%d" % index, blade, colour, rotor)


def build_model():
    body = Mesh("drone", "drone_mat", SHEET)
    glow = Mesh("drone_glow", "drone_glow_mat", GLOW_SHEET)

    build_hull(body)
    build_details(body)
    build_arm(body, 0)
    for index in (1, 2, 3):
        body.replicate("arm0", "arm%d" % index, index, ARM_PIVOTS[index])
        body.replicate("motor0", "motor%d" % index, index, MOTOR_PIVOTS[index])
        body.replicate("rotor0", "rotor%d" % index, index, ROTOR_PIVOTS[index])

    # emissive parts
    box(glow, 0.0, -2.5 * U, -2.0 * U, 1.0 * U, 1.0 * U, 1.0 * U,
        "gimbal_lens", "lens", "CYAN", GIMBAL_PIVOT)
    box(glow, 0.0, 0.5 * U, 2.5 * U, 1.0 * U, 1.0 * U, 1.0 * U,
        "tail_light", "lamp", "AMBER", (0.0, 0.0, 2.0 * U))

    return body, glow


# -------------------------------------------------------------------- output

def _material(name):
    return "\n".join([
        "newmtl %s" % name,
        "Ka 0.000 0.000 0.000",
        "Kd 1.000 1.000 1.000",
        "Ks 0.000 0.000 0.000",
        "d 1.0",
        "illum 1",
        "map_Kd #texture0",
        "",
    ])


def write_outputs(output_dir=None):
    if output_dir is None:
        output_dir = paths.MESH_OUT
    os.makedirs(output_dir, exist_ok=True)
    body, glow = build_model()

    body.write_obj(os.path.join(output_dir, "drone.obj"), "drone.mtl")
    glow.write_obj(os.path.join(output_dir, "drone_glow.obj"), "drone_glow.mtl")
    for filename, material in (("drone.mtl", "drone_mat"), ("drone_glow.mtl", "drone_glow_mat")):
        with open(os.path.join(output_dir, filename), "w", encoding="utf-8", newline="\n") as handle:
            handle.write(_material(material))

    faces = []
    for mesh in (body, glow):
        for face in mesh.faces:
            faces.append({
                "group": face["group"],
                "name": face["name"],
                "rect": face["rect"],
                "uv": face["uv"],
                "colour": face["colour"],
            })
    manifest = {
        "unit": U,
        "sheet": list(SHEET),
        "glow_sheet": list(GLOW_SHEET),
        "parts": body.manifest_parts() + glow.manifest_parts(),
        "faces": faces,
    }
    with open(os.path.join(output_dir, "drone_manifest.json"), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(manifest, handle, indent=2)
        handle.write("\n")

    print("wrote %d parts / %d faces to %s" % (len(manifest["parts"]), len(faces), output_dir))
    return output_dir


#: What `--install` copies, and where each file belongs.
INSTALL_LAYOUT = (
    ("drone.obj", paths.MODEL_DIR),
    ("drone_glow.obj", paths.MODEL_DIR),
    ("drone.mtl", paths.MODEL_DIR),
    ("drone_glow.mtl", paths.MODEL_DIR),
    ("drone_manifest.json", os.path.dirname(paths.MANIFEST)),
)


def install(output_dir):
    """Copies a freshly generated set into the repo, over the checked-in copy."""
    for name, destination in INSTALL_LAYOUT:
        os.makedirs(destination, exist_ok=True)
        target = os.path.join(destination, name)
        shutil.copyfile(os.path.join(output_dir, name), target)
        print("installed %s -> %s" % (name, os.path.relpath(target, paths.ROOT)))


if __name__ == "__main__":
    args = sys.argv[1:]
    do_install = "--install" in args
    args = [arg for arg in args if arg != "--install"]
    written = write_outputs(args[0] if args else None)
    if do_install:
        install(written)
