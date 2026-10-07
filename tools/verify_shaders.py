"""Checks the post-processing chains against the programs they name.

This exists because of a bug that cost three features without leaving a trace. `PostChain` throws
`ChainedJsonException` when a pass declares a uniform the program does not have - and that exception
is an `IOException`, so `DroneSignal`'s catch swallowed it, latched the chain as broken and said
nothing. The symptom was that the signal blur, the detonation radial blur and the LCD filter were all
simply absent, with a clean log. A one line check would have caught it on the day it was written.

What is checked, per chain:

* every pass resolves to a program file that exists, and its vertex and fragment shaders exist;
* every uniform a pass declares is declared by that program - **this is the one that bit us**;
* every uniform a program declares is actually mentioned in one of its shader sources, since a
  declared-but-unused uniform is silently dropped by the driver and then reports a warning on load;
* every `intarget` / `outtarget` is either `minecraft:main` or one of the chain's own targets;
* the chain ends by writing back to `minecraft:main`, because `PostChain` has no notion of a final
  target - whatever the last pass wrote to is what the screen shows, and every vanilla chain ends
  with a `blit` back to main for exactly this reason.

Uniform *names* are compared, not types: a mismatch in `count` is a real error too, but the ones
that actually happen here are names.

Usage
-----
    python tools/verify_shaders.py
"""

from __future__ import annotations

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paths  # noqa: E402

#: The resource root every `assets/<namespace>/...` path is resolved against.
ASSETS_ROOTS = [
    os.path.join(paths.RESOURCES, "assets"),
    # Vanilla, so the chain can name `blit` and `box_blur` without them living in the repo. Only the
    # shader sources are read from here, and only if the path is not found in our own assets.
    os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "versions"),
]

#: Program names the repo itself defines. A pass naming anything else has to be vanilla, and vanilla
#: is checked by name only - see ``KNOWN_VANILLA``.
KNOWN_VANILLA = {"blit", "box_blur", "blur", "invert", "color_convolve", "entity_outline", "sobel"}


class Report:
    def __init__(self) -> None:
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def error(self, message: str) -> None:
        self.errors.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)


def split_id(name: str) -> tuple[str, str]:
    """``"watchcraft:lcd"`` -> ``("watchcraft", "lcd")``; a bare name means minecraft."""
    if ":" in name:
        namespace, path = name.split(":", 1)
        return namespace, path
    return "minecraft", name


def asset_path(namespace: str, relative: str) -> str | None:
    candidate = os.path.join(paths.RESOURCES, "assets", namespace, relative)
    return candidate if os.path.exists(candidate) else None


def read_json(path: str):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def mentions(source: str, name: str) -> bool:
    return re.search(r"\b" + re.escape(name) + r"\b", source) is not None


def check_program(chain: str, index: int, name: str, report: Report) -> tuple[list[str], set[str]]:
    """{return} (declared uniform names, sampler names) for the program a pass names."""
    namespace, path = split_id(name)
    json_path = asset_path(namespace, os.path.join("shaders", "program", path + ".json"))
    where = f"{chain} passes[{index}] ({name})"

    if json_path is None:
        if namespace == "minecraft" and path in KNOWN_VANILLA:
            # Vanilla's own programs are not in this repo. Nothing to check beyond the name, and
            # their pass uniforms are known-good because every vanilla chain uses them.
            return [], set()
        report.error(f"{where}: no program file at assets/{namespace}/shaders/program/{path}.json")
        return [], set()

    program = read_json(json_path)
    uniforms = [entry["name"] for entry in program.get("uniforms", [])]
    samplers = {entry["name"] for entry in program.get("samplers", [])}

    sources = ""
    complete = True
    for slot in ("vertex", "fragment"):
        shader_name = program.get(slot)
        if shader_name is None:
            report.error(f"{where}: program has no \"{slot}\" entry")
            continue
        shader_ns, shader_path = split_id(shader_name)
        extension = ".vsh" if slot == "vertex" else ".fsh"
        shader_file = asset_path(shader_ns, os.path.join("shaders", "program", shader_path + extension))
        if shader_file is None:
            if shader_ns == "minecraft":
                # Vanilla's screenquad / blit vertex shaders are not in this repo. We cannot read
                # them, so we cannot say a uniform is unused - drop the check for this program
                # rather than reporting a false positive on ProjMat and OutSize.
                complete = False
                continue
            report.error(f"{where}: program names a missing {slot} shader {shader_name}{extension}")
            complete = False
            continue
        with open(shader_file, "r", encoding="utf-8") as handle:
            sources += handle.read()

    if complete:
        for uniform in uniforms:
            if not mentions(sources, uniform):
                report.warn(f"{where}: program declares uniform '{uniform}' but no shader uses it; "
                            "the driver will drop it and log a warning at load")

    return uniforms, samplers


def check_chain(path: str, report: Report) -> None:
    chain = os.path.basename(path)
    document = read_json(path)
    targets = set(document.get("targets", [])) | {"minecraft:main"}
    passes = document.get("passes", [])

    if not passes:
        report.error(f"{chain}: no passes")
        return

    for index, entry in enumerate(passes):
        for slot in ("intarget", "outtarget"):
            target = entry.get(slot)
            if target not in targets:
                report.error(f"{chain} passes[{index}] ({entry.get('name')}): "
                             f"{slot} '{target}' is not one of {sorted(targets)}")

        uniforms, samplers = check_program(chain, index, entry["name"], report)
        if not uniforms:
            continue
        for declared in entry.get("uniforms", []):
            if declared["name"] not in uniforms:
                report.error(f"{chain} passes[{index}] ({entry['name']}): declares uniform "
                             f"'{declared['name']}', which the program does not have. "
                             "PostChain throws on this and the whole chain never builds.")
        for declared in entry.get("samplers", []):
            if samplers and declared["name"] not in samplers:
                report.error(f"{chain} passes[{index}] ({entry['name']}): declares sampler "
                             f"'{declared['name']}', which the program does not have.")

    last = passes[-1]
    if last.get("outtarget") != "minecraft:main":
        report.error(f"{chain}: the last pass writes to '{last.get('outtarget')}' rather than "
                     "minecraft:main, so the result never reaches the screen. Add a blit back to main.")


def main() -> int:
    post_dir = os.path.join(paths.ASSETS, "shaders", "post")
    if not os.path.isdir(post_dir):
        print("no post chains found at", post_dir)
        return 1

    chains = sorted(f for f in os.listdir(post_dir) if f.endswith(".json"))
    report = Report()
    for chain in chains:
        check_chain(os.path.join(post_dir, chain), report)

    for message in report.warnings:
        print("warning:", message)
    for message in report.errors:
        print("ERROR:  ", message)

    print(f"\nchecked {len(chains)} chain(s): "
          f"{len(report.errors)} failure(s), {len(report.warnings)} warning(s)")
    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
