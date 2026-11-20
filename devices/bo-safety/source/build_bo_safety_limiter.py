"""Build "BO Safety.bwdevice": a speaker / headphone / hearing protection device.

    python build_bo_safety_limiter.py      -> out/BO Safety.bwdevice

Zero latency. The sound passes unchanged, or the output is muted: one sample over the trip level
(or a NaN / Inf sample) mutes on that very sample; after 4 s of clean input the volume comes back
over 2 s (half-cosine). Parameters: Limit dB (-18 .. +10 dBFS, default 0) and TP Safety Margin
(on: trips 1 dB lower, for peaks between samples; on by default).
Panel: the Limit knob with the TP Margin button under it; on the right an output meter in the
look of the Tool device's (+10 .. -80 dB), with the state readout (green passing, red muted with
the hold's progress, orange recovering) above its bars, drawn by the same shader.

Sources: limiter/bo_safety_limiter.nitro, limiter/bo_sl_meter.sksl, limiter/bo_sl_output.sksl.
Checks: nitrocheck.sh (Bitwig's own Nitro compiler, offline), limiter/test_limiter.py (runs the
Nitro text itself, sample by sample).
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "tools" / "device-gen"))  # bwgen, the device generator

from bwgen import Device  # noqa: E402

LIM = HERE / "limiter"
FONT_TITLE = 5
FONT_BOLD = 1


def bo_safety_limiter() -> Device:
    dev = Device(
        name="bo Safety",
        creator="bo",
        uuid_key="bo-devices/bo-safety",
        nitro=(LIM / "bo_safety_limiter.nitro").read_text(),
        description="Protects speakers, headphones and ears: mutes instantly on any sample over the limit, "
                    "comes back over 2 s after 4 s of clean input. Zero latency, no coloration.",
        tags="limiter safety protection mute speaker headphone",
        panel_size=(38, 53),
        prevent_sleep=True,   # keep checking (and feeding the meter) when silent
    )
    dev.param("limit", "Limit dB", -18, 10, 0, unit_text="dB", knob=(5, 8))
    dev.toggle("tp_margin", "TP Margin", default=True, button=(2, 26, 18, 5),
               tooltip="TP Safety Margin. On: trips 1 dB below the limit, for peaks between samples (inter-sample peaks)")
    # the output meter, as the Tool device's (a dark strip at the right edge), +10 .. -80 dB
    dev.display((LIM / "bo_sl_meter.sksl").read_text(), rect=(22, 0, 16, 53), inputs=["ui_a", "ui_b"], id="BO_SL_METER")
    # the scale's numbers (Bitwig labels, bold), set against the shader's marks (bars 25 .. height - 6 px)
    top, bottom = 25, 53 * 4 - 6
    for db in (10, 0, -20, -40, -60, -80):
        y = top + (bottom - top) * (10 - db) / 90
        dev.labels.append((str(abs(db)), (22, round(y / 4 - 2) - 1, 6, 4), 6, 1, FONT_BOLD))
    # the state readout is drawn by the meter's shader, above its bars and as wide as both
    return dev


if __name__ == "__main__":
    out = HERE / "out" / "bo Safety.bwdevice"
    out.parent.mkdir(exist_ok=True)
    data = bo_safety_limiter().build()
    out.write_bytes(data)
    print(f"{out.name}: {len(data)} bytes")
