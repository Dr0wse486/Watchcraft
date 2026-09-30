"""Where everything lives, in one place.

Every script under ``tools/`` resolves its inputs through this module. The layout is
described once instead of five times, and a fresh clone can run any of the scripts
without first copying files around by hand -- which is what used to be required, since
the texture painter wants the manifest in ``tools/`` while the mesh verifier wanted it
next to the OBJ.

Nothing here imports anything from the mod. It is pure path arithmetic.
"""

from __future__ import annotations

import os

# --------------------------------------------------------------------------- layout

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

RESOURCES = os.path.join(ROOT, "src", "main", "resources")
ASSETS = os.path.join(RESOURCES, "assets", "watchcraft")
DATA = os.path.join(RESOURCES, "data", "watchcraft")

#: The OBJ/MTL pair that ships in the jar, plus the sheets they sample.
MODEL_DIR = os.path.join(ASSETS, "models", "entity")
TEXTURE_DIR = os.path.join(ASSETS, "textures")

#: UV packing written by ``generate_drone_mesh.py`` and read by the texture painter,
#: the mesh preview and the mesh verifier. It is checked in beside the scripts rather
#: than next to the OBJ because it is a hundred kilobytes of build metadata and there
#: is no reason for it to be shipped inside the mod jar.
MANIFEST = os.path.join(ROOT, "tools", "drone_manifest.json")

#: Scratch space. Everything under here is disposable and gitignored.
BUILD = os.path.join(ROOT, "build")
MESH_OUT = os.path.join(BUILD, "meshgen")
PREVIEW_OUT = os.path.join(BUILD, "preview")

# --------------------------------------------------------------------------- helpers


def model_dir(override: str | None = None) -> str:
    """Resolve a model directory, defaulting to the one that ships in the jar."""
    return os.path.abspath(override) if override else MODEL_DIR


def manifest_for(directory: str | None = None) -> str:
    """The manifest describing a model directory.

    Prefers a copy sitting next to the OBJ, so a scratch directory produced by
    ``generate_drone_mesh.py`` can be verified and previewed on its own, and falls
    back to the checked-in one under ``tools/``.
    """
    if directory:
        beside = os.path.join(directory, "drone_manifest.json")
        if os.path.exists(beside):
            return beside
    return MANIFEST
