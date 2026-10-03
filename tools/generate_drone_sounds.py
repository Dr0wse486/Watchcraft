"""Generates the drone's motor loop and its attack-run boom.

Outputs
-------
src/main/resources/assets/watchcraft/sounds/drone_motor.ogg   mono, 44.1 kHz, 1.0 s, seamless loop
src/main/resources/assets/watchcraft/sounds/drone_boom.ogg    mono, 44.1 kHz, 0.60 s

Why these are synthesised rather than borrowed
----------------------------------------------
Vanilla has no small-electric-motor loop. The nearest candidates all carry the
wrong character: ``BEE_LOOP`` is an insect buzz with a wobble that reads as
organic, ``ELYTRA_FLYING`` is wind with no machinery in it, and
``MINECART_INSIDE`` is a rolling clatter on rails. For the attack run the mod
used to play ``FIREWORK_ROCKET_LAUNCH``, which is a rocket: a rising hiss with a
launch report, where a charge is a short supersonic crack.

How the motor spectrum was arrived at
-------------------------------------
The first attempt here was hand-tuned and wrong in a way that is easy to miss
without measuring: it built a low harmonic stack around 116 Hz, which is what a
large slow rotor sounds like, and put almost no energy above 1 kHz. A real
small quadcopter is the opposite - a bright, tearing hiss centred around 3 kHz
with a tonal core down at a couple of hundred hertz.

So the spectrum is not guessed. It is measured off a reference FPV recording
(the first two seconds, which is steady flight), high-passed at 80 Hz to drop
the wind and handling rumble that the microphone picked up but the aircraft did
not make. Then:

1. The reference's magnitude spectrum is smoothed into an envelope.
2. A candidate is built from that envelope - see below - and its energy in eight
   octave bands is compared with the reference's.
3. Each band is corrected by ``sqrt(target / actual)`` (energy goes as amplitude
   squared), the correction is interpolated across frequency so the envelope
   stays smooth, and the loop repeats.

Forty iterations of that put every band inside 0.5% of the target and the
spectral centroid at 2946 Hz against the reference's 3085 Hz. The resulting
96-point envelope is baked into ``SPECTRUM`` below, so this script needs no
audio file to run and the numbers it produces are auditable against the comment.

Only the *envelope* was taken. No samples from the reference are copied - which
matters, because it is a licensed stock effect and this repository is public.

Two ways to build the motor
---------------------------
``drone_motor.ogg`` can be produced either from the synthesis described below,
or directly from a reference recording passed on the command line::

    python tools/generate_drone_sounds.py --reference <fpv-recording.mp3>

The second mode is what the shipped asset actually uses. It sounds better - a
real recording carries the rotor beating, the prop wash and the small
irregularities that a two-layer synthesis cannot invent - and it is what the
project owner asked for. The catch is that the reference is a licensed stock
effect, so it is **not** in this repository and the shipped asset cannot be
regenerated from a fresh clone without it. That is a deliberate, known
limitation rather than an oversight. The synthesised path needs nothing but
``numpy`` and remains the fallback when no reference is given.

Two things have to be done to a recording before it can be looped:

* **Flatten the level.** The reference's own loudness drifts by nearly 9 dB over
  the two seconds - it starts quiet and swells. Loop that as-is and you get a
  half-hertz throb, which is far more noticeable than any seam. A slow RMS
  envelope is measured and divided out, so the loop sits at one level.
* **Crossfade at the START, not at the end.** The usual advice is to blend the
  tail into the head, and it is wrong: the head then gets played twice, which is
  a stutter. The correct place is the beginning of the loop, blended with the
  material that *follows* the loop's end. Written out, with a loop of ``n``
  samples and a crossfade of ``x``::

      out[i]     = body[i]                                  for i >= x
      out[0:x]   = body[n:n+x] * (1 - ramp) + body[0:x] * ramp

  At ``j = 0`` the loop therefore begins on ``body[n]``, which is exactly what
  followed ``body[n-1]`` in the original - so the seam is continuous in the
  original timeline, and the crossfade happens in the middle of the loop where
  a listener has nothing to compare it against.

How the candidate is built
--------------------------
Two layers, both periodic by construction (see "The loop seam" below):

* **Noise** - sinusoids on the integer-hertz grid with random phases and
  amplitudes from the envelope. This is the broadband hiss, and it carries most
  of the energy.
* **Comb** - harmonics of the rotor frequency, amplitudes also read off the
  envelope, so the tonal peaks sit exactly where the reference's do. Two banks,
  four hertz apart, because a real four-rotor machine never has its rotors in
  agreement and that beat is most of what makes it sound mechanical.

``COMB_LEVEL`` sets how much of the comb is mixed in, and was fitted the same
way as the envelope.

The loop seam
-------------
``drone_motor.ogg`` is meant to be played looping for as long as a drone is in
the air, so the seam has to be inaudible, and that constrains *every* layer.

The tone is easy: the clip is exactly one second long, so any partial whose
frequency is a whole number of hertz completes a whole number of cycles inside
it and lines up end to end. A detune of 0.7 Hz - what a real machine sounds like,
the rotors never quite agree - would leave the waveform 0.7 of a cycle out at the
seam and click once a second. Both rotor banks here are therefore whole hertz.

The noise layer is the harder half, and the obvious fix does not work. Blending
the tail into the head - the usual crossfade loop - makes the seam *continuous*
but not *correct*: the head ends up played twice, which is a stutter rather than
a click. Synthesising the noise on the integer-hertz grid instead makes it
periodic by definition, so the seam is indistinguishable from any other sample.

Reproducibility
---------------
The synthesis is deterministic - every random source is a seeded
``default_rng`` - so re-running this script always produces the same audio.
The *file bytes* are another matter: an Ogg page header carries a bitstream
serial number that the encoder picks at random, so two runs give two different
md5s for identical sound. Do not use ``md5sum`` as the regression check here;
decode and compare the PCM instead. (``generate_textures.py`` can be checked by
hash because PNG has no such field.)

Requires ``numpy`` and ``soundfile``: ``pip install numpy soundfile``. Every
other script under ``tools/`` is stdlib only, but Python ships no Vorbis
encoder and Minecraft only reads Ogg Vorbis.

Run:  python tools/generate_drone_sounds.py
"""

from __future__ import annotations

import argparse
import os
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import paths  # noqa: E402

# --------------------------------------------------------------------------- shared

SAMPLE_RATE = 44100


def _periodic_noise(count: int, seed: int, shape: np.ndarray) -> np.ndarray:
    """Noise that is exactly periodic over ``count`` samples, shaped by ``shape``.

    ``shape`` is one amplitude per ``rfftfreq(count)`` bin, so for a one-second
    clip at this sample rate every bin is a whole number of hertz and the result
    can be looped without a seam.
    """
    frequencies = np.fft.rfftfreq(count, 1.0 / SAMPLE_RATE)
    rng = np.random.default_rng(seed)
    spectrum = shape * np.exp(1j * rng.uniform(0.0, 2.0 * np.pi, frequencies.size))
    return np.fft.irfft(spectrum, n=count)


# --------------------------------------------------------------------------- motor

MOTOR_DURATION = 1.0

#: Amplitude envelope, 96 points evenly spaced in log frequency from
#: :data:`SPECTRUM_LOW_HZ` to :data:`SPECTRUM_HIGH_HZ`, normalised to a peak of 1.
#: Measured off a reference FPV recording as described in the module docstring -
#: do not hand-edit without re-running the fit, or the balance will drift.
SPECTRUM_LOW_HZ = 20.0
SPECTRUM_HIGH_HZ = 16000.0
SPECTRUM = (
    0.0272, 0.0274, 0.0275, 0.0276, 0.0277, 0.0278, 0.0277, 0.0273,
    0.0274, 0.0279, 0.0285, 0.0289, 0.0297, 0.0307, 0.0311, 0.0314,
    0.0328, 0.0348, 0.0379, 0.0406, 0.0438, 0.0466, 0.0522, 0.0791,
    0.1128, 0.1484, 0.1873, 0.2325, 0.2967, 0.3792, 0.4867, 0.7156,
    0.8872, 0.9603, 1.0000, 0.9797, 0.8910, 0.7151, 0.6807, 0.6362,
    0.5250, 0.4003, 0.2600, 0.2169, 0.1886, 0.1348, 0.0933, 0.0855,
    0.0841, 0.0932, 0.1483, 0.2762, 0.2782, 0.2033, 0.1015, 0.1059,
    0.2058, 0.3752, 0.3499, 0.1871, 0.1648, 0.2008, 0.2180, 0.1835,
    0.2608, 0.2449, 0.1483, 0.1757, 0.1238, 0.1304, 0.1193, 0.1280,
    0.1235, 0.1353, 0.1399, 0.1164, 0.0659, 0.0724, 0.0663, 0.0826,
    0.0601, 0.0479, 0.0452, 0.0425, 0.0397, 0.0252, 0.0161, 0.0131,
    0.0122, 0.0120, 0.0063, 0.0056, 0.0050, 0.0035, 0.0017, 0.0003,
)

#: Rotor fundamental. The reference's tonal peaks cluster between 164 and 174 Hz;
#: 165 lands on the strongest of them. Whole hertz, per the loop constraint.
ROTOR_HZ = 165
#: The second rotor bank, four hertz up. The beat between the banks is a quarter
#: of a second, which is roughly what the reference's clusters are spaced by.
ROTOR_DETUNE_HZ = 4
ROTOR_DETUNE_LEVEL = 0.7
#: How much comb to mix into the noise. Fitted the same way as the envelope.
COMB_LEVEL = 0.40
#: Peak after normalisation. The runtime scales this by speed, so the asset
#: itself sits well under full scale.
PEAK = 0.80

#: Where in the recording the loop is taken from, how long it is, and how much
#: material is reserved for the crossfade. The reference's flight section runs
#: from 0 to about 2.8 s before the impact; 0.10 + 2.00 + 0.30 stays well inside.
REFERENCE_SKIP_SECONDS = 0.10
REFERENCE_LOOP_SECONDS = 2.00
REFERENCE_CROSSFADE_SECONDS = 0.30
#: Clamp on the level-flattening gain, so a very quiet stretch cannot be lifted
#: until its noise floor becomes the loudest thing in the loop.
REFERENCE_GAIN_LIMIT = (0.4, 3.0)
#: Peak when the loop comes from a recording. Lower than :data:`PEAK` because a
#: real recording is denser - more energy for the same peak - and the first
#: version of this asset came back as "too loud".
REFERENCE_PEAK = 0.55


def _slow_envelope(signal: np.ndarray, rate: int, window_seconds: float = 0.10) -> np.ndarray:
    """Per-sample RMS envelope, measured in blocks and interpolated between them.

    A convolution would be the obvious way to do this and is far too slow over
    a couple of seconds of audio; block RMS is cheap and the envelope is only
    ever used to correct slow drift.
    """
    step = max(1, int(window_seconds * rate))
    blocks = max(1, len(signal) // step)
    rms = np.array([np.sqrt(np.mean(signal[i * step:(i + 1) * step] ** 2)) for i in range(blocks)])
    centres = (np.arange(blocks) + 0.5) * step
    return np.interp(np.arange(len(signal)), centres, rms)


def render_motor_from_reference(path: str) -> tuple[np.ndarray, int]:
    """Build the rotor loop out of a recording. See the module docstring."""
    data, rate = sf.read(path, dtype="float32")
    mono = data.mean(axis=1) if data.ndim > 1 else data

    start = int(REFERENCE_SKIP_SECONDS * rate)
    loop = int(REFERENCE_LOOP_SECONDS * rate)
    fade = int(REFERENCE_CROSSFADE_SECONDS * rate)
    body = mono[start:start + loop + fade]
    if len(body) < loop + fade:
        raise SystemExit(
            "reference is too short: %.2f s of flight audio needed, file has %.2f s"
            % (REFERENCE_SKIP_SECONDS + REFERENCE_LOOP_SECONDS + REFERENCE_CROSSFADE_SECONDS,
               len(mono) / rate))

    # Flatten the recording's own loudness drift, or the loop throbs at half a hertz.
    envelope = _slow_envelope(body, rate)
    gain = np.clip(float(np.median(envelope)) / np.maximum(envelope, 1.0E-9),
                   *REFERENCE_GAIN_LIMIT)
    body = body * gain

    # Crossfade at the START, against the material that follows the loop's end.
    out = body[:loop].copy()
    ramp = np.linspace(0.0, 1.0, fade, endpoint=False)
    out[:fade] = body[loop:loop + fade] * (1.0 - ramp) + body[:fade] * ramp

    out /= np.max(np.abs(out))
    return (out * REFERENCE_PEAK).astype(np.float32), rate


def _envelope(frequencies: np.ndarray | float) -> np.ndarray:
    """Interpolate :data:`SPECTRUM` onto the given frequency or frequencies."""
    points = np.geomspace(SPECTRUM_LOW_HZ, SPECTRUM_HIGH_HZ, len(SPECTRUM))
    return np.interp(frequencies, points, SPECTRUM, left=0.0, right=0.0)


def render_motor() -> np.ndarray:
    """Build the rotor loop as a mono float array in [-1, 1]."""
    count = int(SAMPLE_RATE * MOTOR_DURATION)
    grid = np.fft.rfftfreq(count, 1.0 / SAMPLE_RATE)
    shape = _envelope(grid)
    shape[0] = 0.0

    noise = _periodic_noise(count, 20261003, shape)
    noise /= np.max(np.abs(noise))

    t = np.linspace(0.0, MOTOR_DURATION, count, endpoint=False)
    comb = np.zeros(count)
    for bank, level in ((ROTOR_HZ, 1.0), (ROTOR_HZ + ROTOR_DETUNE_HZ, ROTOR_DETUNE_LEVEL)):
        for harmonic in range(1, int(SPECTRUM_HIGH_HZ / bank) + 1):
            amplitude = float(_envelope(float(bank * harmonic)))
            if amplitude > 1.0E-4:
                comb += level * amplitude * np.sin(2.0 * np.pi * bank * harmonic * t)
    comb /= np.max(np.abs(comb))

    signal = noise + COMB_LEVEL * comb
    signal /= np.max(np.abs(signal))
    return (signal * PEAK).astype(np.float32)


# --------------------------------------------------------------------------- boom

BOOM_DURATION = 0.60
#: The pressure spike. Very short - this is the part that reads as "crack".
CRACK_TAU = 0.014
CRACK_LEVEL = 0.55
CRACK_LOW_HZ = 400.0
CRACK_CUTOFF_HZ = 3200.0
#: The low thump under it, sweeping down as the wave passes.
THUMP_HZ_FROM = 118.0
THUMP_HZ_TO = 46.0
THUMP_TAU = 0.085
THUMP_LEVEL = 1.00
#: A little second-order boom tail, so it is not over the instant it starts.
TAIL_DELAY = 0.055
TAIL_TAU = 0.11
TAIL_LEVEL = 0.30
TAIL_LOW_HZ = 200.0
TAIL_CUTOFF_HZ = 1500.0
#: Peak. The user asked for a *slight* boom rather than a detonation, so this is
#: deliberately below the tinnitus clip's 0.72.
PEAK_BOOM = 0.62


def render_boom() -> np.ndarray:
    """Build the attack-run crack as a mono float array in [-1, 1]."""
    count = int(SAMPLE_RATE * BOOM_DURATION)
    t = np.linspace(0.0, BOOM_DURATION, count, endpoint=False)

    def band(seed: int, low_hz: float, high_hz: float) -> np.ndarray:
        frequencies = np.fft.rfftfreq(count, 1.0 / SAMPLE_RATE)
        scaled = np.maximum(frequencies, 0.0) / max(low_hz, 1.0)
        shape = scaled / np.sqrt(1.0 + scaled ** 2)
        shape /= np.sqrt(1.0 + (frequencies / high_hz) ** 2)
        shape[0] = 0.0
        return _periodic_noise(count, seed, shape)

    # Frequency integrated into phase rather than multiplied into it, or the
    # sweep jumps phase every sample and turns to gravel.
    sweep = THUMP_HZ_FROM + (THUMP_HZ_TO - THUMP_HZ_FROM) * np.minimum(t / 0.18, 1.0)
    thump = THUMP_LEVEL * np.sin(2.0 * np.pi * np.cumsum(sweep) / SAMPLE_RATE) * np.exp(-t / THUMP_TAU)

    crack = CRACK_LEVEL * band(20261004, CRACK_LOW_HZ, CRACK_CUTOFF_HZ) * np.exp(-t / CRACK_TAU)

    # The tail starts a beat late so it arrives as a second wavefront behind the
    # first rather than thickening the initial hit.
    shifted = np.maximum(t - TAIL_DELAY, 0.0)
    tail = TAIL_LEVEL * band(20261005, TAIL_LOW_HZ, TAIL_CUTOFF_HZ) * np.exp(-shifted / TAIL_TAU)
    tail[t < TAIL_DELAY] = 0.0

    signal = thump + crack + tail
    signal /= np.max(np.abs(signal))

    # Both ends get a ramp: the crack starts at full amplitude, and a file that
    # begins on a non-zero sample clicks on every playback.
    attack = int(0.0012 * SAMPLE_RATE)
    signal[:attack] *= np.linspace(0.0, 1.0, attack)
    release = int(0.05 * SAMPLE_RATE)
    signal[-release:] *= np.linspace(1.0, 0.0, release)

    return (signal * PEAK_BOOM).astype(np.float32)


# --------------------------------------------------------------------------- main


def write(name: str, signal: np.ndarray, duration: float, rate: int = SAMPLE_RATE) -> None:
    destination = os.path.join(paths.ASSETS, "sounds", name)
    os.makedirs(os.path.dirname(destination), exist_ok=True)
    sf.write(destination, signal, rate, format="OGG", subtype="VORBIS")
    peak = float(np.max(np.abs(signal)))
    print(f"wrote {destination}")
    print(f"  {len(signal)} samples, {duration:.2f} s, {rate} Hz, peak {peak:.3f}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate the drone's sound assets.")
    parser.add_argument(
        "--reference",
        metavar="PATH",
        help="an FPV recording to take the rotor loop from. Without it the loop is "
             "synthesised from the baked spectrum instead, which needs no external file "
             "but is less convincing - see the module docstring.")
    args = parser.parse_args()

    if args.reference:
        motor, rate = render_motor_from_reference(args.reference)
        print(f"motor: taken from {args.reference}")
        write("drone_motor.ogg", motor, REFERENCE_LOOP_SECONDS, rate)
    else:
        print("motor: synthesised (no --reference given)")
        write("drone_motor.ogg", render_motor(), MOTOR_DURATION)

    write("drone_boom.ogg", render_boom(), BOOM_DURATION)


if __name__ == "__main__":
    main()
