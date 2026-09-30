"""Verifies a generated Recon Drone mesh set against the modelling spec.

Usage
-----
    python tools/verify_mesh.py [model_dir]

`model_dir` defaults to the mesh set that ships in the jar, so a bare
`python tools/verify_mesh.py` is a meaningful thing to run on a fresh clone. Point it
at a scratch directory instead to check the output of `generate_drone_mesh.py` before
installing it. The manifest is looked for beside the OBJ first and in `tools/` second,
which is what lets both of those work.

Checks performed
----------------
1.  OBJ syntax: only v / vt / vn / g / usemtl / f / mtllib / comments.
2.  Every `g` group is immediately followed by a `usemtl` line.
3.  Group names match the spec set exactly (no extras, none missing).
4.  Every `v` coordinate sits on the 1/32 block grid.
5.  Faces are triangles or quads only; indices are positive and in range.
6.  Declared `vn` agrees with the geometric normal of its face (flat shading).
7.  Winding is outward: each face normal points away from its group centroid.
8.  Per-group face count within budget; total within budget.
9.  Whole-model bounding box inside the hard limits.
10. Manifest `parts` matches the OBJ groups (names, face counts, bboxes, pivots).
11. Manifest `faces` count matches the OBJ face count.
12. Manifest UV rects do not overlap and stay inside the sheet.
13. Four-fold symmetry of arm / motor / rotor.
14. Coplanar overlapping faces (z-fighting risk) between different groups.

Exit code is 0 when every check passes, 1 otherwise.
"""

from __future__ import annotations

import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402

U = 1.0 / 16.0
H = 1.0 / 128.0

# ---------------------------------------------------------------- spec tables

# group -> (expected pivot, face budget)
SPEC = {
    "frame":       ((0.0, 0.0, 0.0), 64),
    "canopy":      ((0.0, 0.0, 0.0), 20),
    "nose_sensor": ((0.0, 0.0, 0.0), 10),
    "antenna":     ((0.0, 1 * U, 1 * U), 16),
    "gimbal_yoke": ((0.0, -2.5 * U, -2 * U), 20),
    "skid_l":      ((2 * U, -1 * U, 0.0), 18),
    "skid_r":      ((-2 * U, -1 * U, 0.0), 18),
    "arm0":        ((2 * U, 0.0, -2 * U), 10),
    "arm1":        ((2 * U, 0.0, 2 * U), 10),
    "arm2":        ((-2 * U, 0.0, 2 * U), 10),
    "arm3":        ((-2 * U, 0.0, -2 * U), 10),
    "motor0":      ((4 * U, 1.5 * U, -4 * U), 20),
    "motor1":      ((4 * U, 1.5 * U, 4 * U), 20),
    "motor2":      ((-4 * U, 1.5 * U, 4 * U), 20),
    "motor3":      ((-4 * U, 1.5 * U, -4 * U), 20),
    "rotor0":      ((4 * U, 2.25 * U, -4 * U), 16),
    "rotor1":      ((4 * U, 2.25 * U, 4 * U), 16),
    "rotor2":      ((-4 * U, 2.25 * U, 4 * U), 16),
    "rotor3":      ((-4 * U, 2.25 * U, -4 * U), 16),
    "gimbal_lens": ((0.0, -2.5 * U, -2 * U), 12),
    "tail_light":  ((0.0, 0.0, 2 * U), 6),
}

GLOW_GROUPS = {"gimbal_lens", "tail_light"}

# hard bounding box for the whole drone, in blocks. The host AABB is
# 0.8 x 0.45 x 0.8, so half-extents are 6.4U across and 3.6U vertically.
LIMITS = ((-6 * U, -3.5 * U, -6 * U), (6 * U, 3.5 * U, 6 * U))

TOTAL_BUDGET = 400

# arm i -> arm 0 after rotating k * 90 degrees about Y (CCW seen from above)
SYMMETRY_TURNS = {"arm0": 0, "arm1": 1, "arm2": 2, "arm3": 3,
                  "motor0": 0, "motor1": 1, "motor2": 2, "motor3": 3,
                  "rotor0": 0, "rotor1": 1, "rotor2": 2, "rotor3": 3}


# ---------------------------------------------------------------- utilities

def fmt(v: float) -> str:
    return "%.6f" % v


def sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def cross(a, b):
    return (a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0])


def dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def norm(v):
    n = math.sqrt(dot(v, v))
    return (0.0, 1.0, 0.0) if n == 0.0 else (v[0] / n, v[1] / n, v[2] / n)


def on_grid(v: float) -> bool:
    return abs(v / H - round(v / H)) < 1e-6


def signed_volume(faces):
    """Volume enclosed by a set of faces, via the divergence theorem.

    Positive means the winding is outward (right-handed convention), which is
    what the OBJ loader and every renderer expect. Works per group even when the
    group is a union of several disjoint shells, so no welding is needed.
    """
    total = 0.0
    for face in faces:
        v = face.verts
        triangles = [(v[0], v[1], v[2])]
        if len(v) == 4:
            triangles.append((v[0], v[2], v[3]))
        for a, b, c in triangles:
            total += dot(a, cross(b, c))
    return total / 6.0


def rot_y(point, turns):
    """Rotate a point about the Y axis by turns * 90 degrees.

    This is the same convention the generator uses to derive the four arm
    bearings, so `rot_y(arm0_vertex, k) == armk_vertex`.
    """
    x, y, z = point
    for _ in range(turns % 4):
        x, z = -z, x
    return (x, y, z)


def same(a, b, eps=1e-6) -> bool:
    """Element-wise compare that also walks nested sequences (bbox = [low, high])."""
    if len(a) != len(b):
        return False
    for x, y in zip(a, b):
        if isinstance(x, (tuple, list)) and isinstance(y, (tuple, list)):
            if not same(x, y, eps):
                return False
        elif abs(x - y) > eps:
            return False
    return True


# ---------------------------------------------------------------- OBJ parsing

class Face:
    __slots__ = ("group", "verts", "normal", "centroid")

    def __init__(self, group, verts, normal):
        self.group = group
        self.verts = verts
        self.normal = normal
        self.centroid = tuple(sum(v[i] for v in verts) / len(verts) for i in range(3))


def parse_obj(path):
    """Returns (groups, faces, vertices, problems)."""
    groups = []            # ordered group names
    faces = []             # list[Face]
    vertices = []          # list of (x, y, z)
    problems = []
    current = None
    pending_group = None

    with open(path, encoding="utf-8") as handle:
        for lineno, raw in enumerate(handle, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split()
            kind = parts[0]

            if kind == "v":
                vertices.append(tuple(float(x) for x in parts[1:4]))
            elif kind == "vt":
                pass
            elif kind == "vn":
                pass
            elif kind == "mtllib":
                pass
            elif kind == "g":
                name = " ".join(parts[1:])
                groups.append(name)
                pending_group = name
                current = name
            elif kind == "usemtl":
                if pending_group is None:
                    problems.append(f"{path}:{lineno}: usemtl outside any g group")
                pending_group = None
            elif kind == "f":
                if current is None:
                    problems.append(f"{path}:{lineno}: face outside any g group")
                    continue
                if pending_group is not None:
                    problems.append(f"{path}:{lineno}: g {pending_group} is not followed by usemtl")
                    pending_group = None
                indices = []
                for token in parts[1:]:
                    first = token.split("/")[0]
                    indices.append(int(first))
                if len(indices) not in (3, 4):
                    problems.append(f"{path}:{lineno}: face has {len(indices)} vertices (only 3 or 4 allowed)")
                    continue
                if any(i <= 0 or i > len(vertices) for i in indices):
                    problems.append(f"{path}:{lineno}: face index out of range {indices}")
                    continue
                pts = [vertices[i - 1] for i in indices]
                n = cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]))
                if math.sqrt(dot(n, n)) < 1e-12:
                    problems.append(f"{path}:{lineno}: degenerate face (zero area)")
                    continue
                faces.append(Face(current, pts, norm(n)))
            else:
                problems.append(f"{path}:{lineno}: unexpected line kind '{kind}'")

    if pending_group is not None:
        problems.append(f"{path}: trailing group {pending_group} has no usemtl")

    return groups, faces, vertices, problems


# ---------------------------------------------------------------- checks

class Report:
    def __init__(self):
        self.lines = []
        self.failures = 0
        self.warnings = 0

    def ok(self, text):
        self.lines.append(("PASS", text))

    def fail(self, text, detail=""):
        self.lines.append(("FAIL", text + ("  -> " + detail if detail else "")))
        self.failures += 1

    def warn(self, text, detail=""):
        self.lines.append(("WARN", text + ("  -> " + detail if detail else "")))
        self.warnings += 1

    def check(self, condition, text, detail=""):
        if condition:
            self.ok(text)
        else:
            self.fail(text, detail)


def group_bbox(faces):
    pts = [v for f in faces for v in f.verts]
    low = tuple(min(p[i] for p in pts) for i in range(3))
    high = tuple(max(p[i] for p in pts) for i in range(3))
    return low, high


def find_coplanar_overlaps(faces, same_group=False, tol=1e-9):
    """Reports pairs of faces that are coplanar, face the same way, and overlap.

    Coplanar faces at the same depth make the depth test a coin flip, which
    shows up in game as flickering patches. Only *same-facing* pairs matter:
    two faces meeting back to back are resolved by back-face culling before
    the depth test ever runs, so a part can sit flush against another one as
    long as the pair points in opposite directions.

    `same_group=False` looks for clashes between different parts, which is the
    interesting case. `same_group=True` looks inside one part, which catches a
    part that was assembled out of overlapping pieces.
    """
    buckets = {}
    for face in faces:
        key = (round(face.normal[0], 4), round(face.normal[1], 4), round(face.normal[2], 4))
        plane = round(dot(face.normal, face.centroid), 5)
        buckets.setdefault((key, plane), []).append(face)

    hits = []
    for bucket in buckets.values():
        if len(bucket) < 2:
            continue
        for i in range(len(bucket)):
            for j in range(i + 1, len(bucket)):
                a, b = bucket[i], bucket[j]
                if (a.group == b.group) != same_group:
                    continue
                if polygon_overlap_area(a, b, tol) > 1e-9:
                    hits.append((a.group, b.group, a.centroid))
    return hits


def project_axes(normal):
    """Returns the two axes spanning the plane of a face with the given normal."""
    axis = max(range(3), key=lambda i: abs(normal[i]))
    return tuple(i for i in range(3) if i != axis)


def _clip(subject, clipper):
    """Sutherland-Hodgman clip of a polygon against a convex clipper."""
    area = 0.0
    for i in range(len(clipper)):
        x1, y1 = clipper[i]
        x2, y2 = clipper[(i + 1) % len(clipper)]
        area += x1 * y2 - x2 * y1
    if area < 0.0:
        clipper = list(reversed(clipper))

    output = list(subject)
    for i in range(len(clipper)):
        if not output:
            return []
        ax, ay = clipper[i]
        bx, by = clipper[(i + 1) % len(clipper)]
        ex, ey = bx - ax, by - ay

        def inside(point):
            return ex * (point[1] - ay) - ey * (point[0] - ax) >= -1e-12

        kept = []
        for j in range(len(output)):
            cur = output[j]
            prev = output[j - 1]
            if inside(cur):
                if not inside(prev):
                    kept.append(_intersect(prev, cur, (ax, ay), (bx, by)))
                kept.append(cur)
            elif inside(prev):
                kept.append(_intersect(prev, cur, (ax, ay), (bx, by)))
        output = kept
    return output


def _intersect(p, q, a, b):
    dx1, dy1 = q[0] - p[0], q[1] - p[1]
    dx2, dy2 = b[0] - a[0], b[1] - a[1]
    denom = dx1 * dy2 - dy1 * dx2
    if abs(denom) < 1e-15:
        return p
    t = ((a[0] - p[0]) * dy2 - (a[1] - p[1]) * dx2) / denom
    return (p[0] + t * dx1, p[1] + t * dy1)


def polygon_overlap_area(a, b, tol):
    """Exact overlap area of two coplanar faces, projected into their plane."""
    ax, ay = project_axes(a.normal)
    poly_a = [(v[ax], v[ay]) for v in a.verts]
    poly_b = [(v[ax], v[ay]) for v in b.verts]
    clipped = _clip(poly_a, poly_b)
    if len(clipped) < 3:
        return 0.0
    total = 0.0
    for i in range(len(clipped)):
        x1, y1 = clipped[i]
        x2, y2 = clipped[(i + 1) % len(clipped)]
        total += x1 * y2 - x2 * y1
    return abs(total) / 2.0


def rects_overlap(a, b):
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    return not (ax + aw <= bx or bx + bw <= ax or ay + ah <= by or by + bh <= ay)


def main():
    model_dir = paths.model_dir(sys.argv[1] if len(sys.argv) > 1 else None)

    body_path = os.path.join(model_dir, "drone.obj")
    glow_path = os.path.join(model_dir, "drone_glow.obj")
    manifest_path = paths.manifest_for(model_dir)

    report = Report()

    body_groups, body_faces, body_verts, problems = parse_obj(body_path)
    glow_groups, glow_faces, glow_verts, glow_problems = parse_obj(glow_path)
    problems += glow_problems

    for problem in problems:
        report.fail("OBJ syntax", problem)

    all_faces = body_faces + glow_faces
    all_groups = body_groups + glow_groups

    # ---- 3. group names
    expected = set(SPEC)
    actual = set(all_groups)
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    report.check(not missing and not extra,
                 "group names match the spec",
                 f"missing={missing} extra={extra}")
    report.check(len(all_groups) == len(actual), "no duplicate group names")

    # ---- 4. grid snapping
    off_grid = [(g, v) for g in (body_groups + glow_groups)
                for v in (body_verts + glow_verts) if not all(on_grid(c) for c in v)]
    bad = [v for v in body_verts + glow_verts if not all(on_grid(c) for c in v)]
    report.check(not bad,
                 "every vertex sits on the 1/%d block grid" % round(1.0 / H),
                 f"{len(bad)} off-grid vertices, first={bad[0] if bad else None}")

    # ---- 6/7. normals and winding
    for group in all_groups:
        faces = [f for f in all_faces if f.group == group]
        if not faces:
            continue
        volume = signed_volume(faces)
        report.check(volume > 0.0,
                     f"{group} winds outward (signed volume {volume:.3e})",
                     f"signed volume {volume:.3e} <= 0, normals point inward")

    # ---- 8. face budgets
    counts = {}
    for face in all_faces:
        counts[face.group] = counts.get(face.group, 0) + 1
    over = {g: (counts.get(g, 0), SPEC[g][1]) for g in SPEC if counts.get(g, 0) > SPEC[g][1]}
    report.check(not over, "per-group face budgets respected", str(over))
    total = len(all_faces)
    report.check(total <= TOTAL_BUDGET,
                 f"total face count {total} <= {TOTAL_BUDGET}",
                 f"{total} faces")

    # ---- 9. bounding box
    low, high = group_bbox(all_faces)
    inside = all(low[i] >= LIMITS[0][i] - 1e-6 and high[i] <= LIMITS[1][i] + 1e-6 for i in range(3))
    report.check(inside, "whole model inside the hard bounding box",
                 f"low={low} high={high} limits={LIMITS}")

    # ---- 13. symmetry: rot_y(family0, k) must reproduce family_k exactly
    for family in ("arm", "motor", "rotor"):
        base = sorted(tuple(round(c, 6) for c in v)
                      for f in all_faces if f.group == f"{family}0" for v in f.verts)
        for index in (1, 2, 3):
            group = f"{family}{index}"
            actual = sorted(tuple(round(c, 6) for c in v)
                            for f in all_faces if f.group == group for v in f.verts)
            rotated = sorted(tuple(round(c, 6) for c in rot_y(v, index)) for v in base)
            report.check(actual == rotated,
                         f"{group} equals {family}0 rotated {index * 90} degrees",
                         f"{len(actual)} vs {len(rotated)} vertices")

    # ---- 14. coplanar overlaps
    overlaps = find_coplanar_overlaps(all_faces, same_group=False)
    if overlaps:
        sample = ", ".join(f"{a}/{b}" for a, b, _ in overlaps[:6])
        report.fail(f"{len(overlaps)} coplanar same-facing face pairs between groups",
                    f"z-fighting risk: {sample}")
    else:
        report.ok("no coplanar same-facing faces between groups")

    inner = find_coplanar_overlaps(all_faces, same_group=True)
    if inner:
        sample = ", ".join(f"{a}" for a, _, _ in inner[:6])
        report.fail(f"{len(inner)} coplanar same-facing face pairs inside one part",
                    f"the part is built from overlapping pieces: {sample}")
    else:
        report.ok("no coplanar same-facing faces inside any single part")

    # ---- manifest
    if not os.path.exists(manifest_path):
        report.fail("manifest exists", manifest_path)
    else:
        with open(manifest_path, encoding="utf-8") as handle:
            manifest = json.load(handle)

        parts = {p["group"]: p for p in manifest["parts"]}
        report.check(set(parts) == expected,
                     "manifest parts cover the spec groups",
                     f"missing={sorted(expected - set(parts))} extra={sorted(set(parts) - expected)}")

        for group, entry in sorted(parts.items()):
            if group not in SPEC:
                continue
            pivot, _ = SPEC[group]
            report.check(same(entry["pivot"], pivot, 1e-6),
                         f"{group} pivot matches the spec",
                         f"got {entry['pivot']} want {list(pivot)}")
            faces = [f for f in all_faces if f.group == group]
            if faces:
                low, high = group_bbox(faces)
                want = [[round(v, 6) for v in low], [round(v, 6) for v in high]]
                report.check(same([tuple(x) for x in entry["bbox"]], [low, high], 1e-5),
                             f"{group} manifest bbox matches geometry",
                             f"got {entry['bbox']} want {want}")
            report.check(entry["faces"] == len(faces),
                         f"{group} manifest face count matches the OBJ",
                         f"got {entry['faces']} want {len(faces)}")

        report.check(len(manifest["faces"]) == total,
                     "manifest face list matches the OBJ face count",
                     f"got {len(manifest['faces'])} want {total}")

        sheet = tuple(manifest["sheet"])
        glow_sheet = tuple(manifest["glow_sheet"])
        for label, size in (("sheet", sheet), ("glow_sheet", glow_sheet)):
            for face in manifest["faces"]:
                is_glow = face["group"] in GLOW_GROUPS
                if label == "sheet" and is_glow:
                    continue
                if label == "glow_sheet" and not is_glow:
                    continue
                x, y, w, h = face["rect"]
                if x < 0 or y < 0 or x + w > size[0] or y + h > size[1]:
                    report.fail(f"{label} rect inside the sheet", f"{face['group']}/{face['name']} {face['rect']}")
                    break

        for label, size in (("sheet", sheet), ("glow_sheet", glow_sheet)):
            rects = [(f["group"], f["rect"]) for f in manifest["faces"]
                     if (f["group"] in GLOW_GROUPS) == (label == "glow_sheet")]
            clashes = []
            for i in range(len(rects)):
                for j in range(i + 1, len(rects)):
                    if rects_overlap(rects[i][1], rects[j][1]):
                        clashes.append((rects[i], rects[j]))
            if clashes:
                report.fail(f"{label} UV rects do not overlap",
                            f"{len(clashes)} clashes, first={clashes[0]}")
            else:
                report.ok(f"{label} UV rects do not overlap ({len(rects)} rects)")

            used = max((r[1][0] + r[1][2], r[1][1] + r[1][3]) for r in rects) if rects else (0, 0)
            report.lines.append(("INFO", f"{label} used area: {used[0]}x{used[1]} px of {size[0]}x{size[1]}"))

        # ---- 12b. UV texel density: 1U must be 8 px, i.e. 1 block = 128 px
        # OBJ faces and manifest faces are emitted in the same order within each
        # group, so pairing them by index is safe.
        obj_by_group = {}
        for face in all_faces:
            obj_by_group.setdefault(face.group, []).append(face)
        man_by_group = {}
        for entry in manifest["faces"]:
            man_by_group.setdefault(entry["group"], []).append(entry)

        wrong_scale = []
        for group, entries in man_by_group.items():
            faces = obj_by_group.get(group, [])
            if len(faces) != len(entries):
                continue
            for face, entry in zip(faces, entries):
                extents = sorted((max(v[i] for v in face.verts) - min(v[i] for v in face.verts)
                                  for i in range(3)), reverse=True)
                want = [max(1, int(math.ceil(extents[0] * 128.0))),
                        max(1, int(math.ceil(extents[1] * 128.0)))]
                if abs(entry["rect"][2] - want[0]) > 1 or abs(entry["rect"][3] - want[1]) > 1:
                    wrong_scale.append((group, entry["name"], entry["rect"][2:], want))
        if wrong_scale:
            report.fail("UV texel density is 1U = 8px (1 block = 128px)",
                        f"{len(wrong_scale)} faces off, e.g. {wrong_scale[:3]}")
        else:
            report.ok("UV texel density is 1U = 8px (1 block = 128px)")

    # ---- report
    width = max(len(t) for _, t in report.lines) if report.lines else 0
    for status, text in report.lines:
        print(f"[{status:4s}] {text}")
    print()
    print(f"{report.failures} failure(s), {report.warnings} warning(s)")
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
