"""Generates the detonation tinnitus ring.

Outputs
-------
src/main/resources/assets/watchcraft/sounds/tinnitus.ogg   mono, 44.1 kHz, ~1.9 s

Why this asset exists at all
----------------------------
Vanilla has nothing that reads as tinnitus. Bells and note blocks are clean
musical tones -- fast decay, tidy harmonics, they go *ding*. Cave ambience is
low and hollow, which is space, not ringing. What an ear does after a blast is
narrow-band, dry, high, and slightly unstable, so the ring is synthesised here
rather than borrowed.

The shape it is going for:

* **3.8 kHz.** Right in the band human hearing is most sensitive to, which is
  exactly why it is the one that lingers. An octave partial at 12% gives it a
  little metal instead of leaving it a pure test tone.
* **A slow fall in pitch.** A real ring is not a fixed note; it sags as it dies.
  150 Hz over the length of the clip is enough to hear without reading as a
  slide.
* **Exponential decay, tau 0.45 s.** Long enough to outlast the explosion, short
  enough that it is gone before the snow screen is.
* **A 5.5 Hz tremolo at 6%.** Barely there. Without it the tail sounds like a
  synthesiser holding a note; with it, like something in a head.

Requires ``numpy`` and ``soundfile``: ``pip install numpy soundfile``. Every
other script under ``tools/`` is stdlib only, but Python ships no Vorbis
encoder and Minecraft only reads Ogg Vorbis.

Run:  python tools/generate_tinnitus.py
"""

from __future__ import annotations

import os
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402

# --------------------------------------------------------------------------- tuning

SAMPLE_RATE = 44100
DURATION = 1.9

#: Fundamental. See the module docstring for why this exact neighbourhood.
BASE_HZ = 3800.0
#: How far the note sags over the clip.
FALL_HZ = 150.0
#: Level of the octave partial, relative to the fundamental.
PARTIAL = 0.12
#: Exponential decay constant, seconds.
DECAY_TAU = 0.45
#: Attack ramp, seconds. Not zero, or the first sample clicks.
ATTACK = 0.003
#: Slow amplitude wobble, so the tail breathes.
TREMOLO_HZ = 5.5
TREMOLO_DEPTH = 0.06
#: Peak after normalisation. Deliberately under 1: it plays at 0.9 through the
#: master bus and is meant to be unpleasant, not to clip.
PEAK = 0.72
#: Fade at the very end, seconds, so the file does not stop mid-cycle.
TAIL_FADE = 0.06


def render() -> np.ndarray:
    """Build the ring as a mono float array in [-1, 1]."""
    count = int(SAMPLE_RATE * DURATION)
    t = np.linspace(0.0, DURATION, count, endpoint=False)

    # Frequency is integrated into phase rather than multiplied into it: a tone
    # whose pitch moves has to be built from the instantaneous frequency, or the
    # phase jumps every sample and the whole thing turns to gravel.
    frequency = BASE_HZ - FALL_HZ * (t / DURATION)
    phase = 2.0 * np.pi * np.cumsum(frequency) / SAMPLE_RATE

    tone = np.sin(phase) + PARTIAL * np.sin(2.0 * phase)

    envelope = np.where(t < ATTACK, t / ATTACK, np.exp(-(t - ATTACK) / DECAY_TAU))
    envelope *= 1.0 + TREMOLO_DEPTH * np.sin(2.0 * np.pi * TREMOLO_HZ * t)

    signal = tone * envelope

    tail = int(TAIL_FADE * SAMPLE_RATE)
    signal[-tail:] *= np.linspace(1.0, 0.0, tail)

    signal /= np.max(np.abs(signal))
    return (signal * PEAK).astype(np.float32)


def main() -> None:
    destination = os.path.join(paths.ASSETS, "sounds", "tinnitus.ogg")
    os.makedirs(os.path.dirname(destination), exist_ok=True)

    signal = render()
    sf.write(destination, signal, SAMPLE_RATE, format="OGG", subtype="VORBIS")

    peak = float(np.max(np.abs(signal)))
    print(f"wrote {destination}")
    print(f"  {len(signal)} samples, {DURATION:.2f} s, {SAMPLE_RATE} Hz, peak {peak:.3f}")


if __name__ == "__main__":
    main()
