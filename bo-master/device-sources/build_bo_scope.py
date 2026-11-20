"""Build "BO Scope.bwdevice": a pass-through device holding Bitwig's own oscilloscope (L on A, R on B).

    python build_bo_scope.py      -> out/BO Scope.bwdevice

Master Bo finds it by name and reads the oscilloscope's two sample buffers for its goniometer and
correlation meter.
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "tools" / "device-gen"))  # bwgen, the device generator

from bwgen import Device  # noqa: E402

FONT_BOLD = 1


def bo_scope() -> Device:
    dev = Device(
        name="bo Scope",
        creator="bo",
        uuid_key="bo-devices/bo-scope",
        nitro=(HERE / "scope" / "bo_scope.nitro").read_text(),
        description="Goniometer source for Master Bo: passes the audio through and feeds L and R to Bitwig's own oscilloscope",
        tags="goniometer scope lissajous phase correlation stereo master",
        panel_size=(34, 53),
        prevent_sleep=True,
    )
    # the oscilloscope's 11 inputs: A, B, then Frequency, ScaleA, ScaleB, Trigger Index, Trigger Level,
    # Hold off, Slope, Show B, Note Trigger left unconnected (connecting values there crashes the engine)
    dev.scopes.append(["sc_l", "sc_r"] + [None] * 9)
    # its own panel: the goniometer's outline and the correlation (128 x 168 px at 100 %: plot, 22 px strip)
    dev.display((HERE / "scope" / "bo_scope_gonio.sksl").read_text(), rect=(1, 1, 32, 42),
                inputs=["ui_a", "ui_b", "ui_c"], id="BO_SCOPE_GONIO")
    for text, x in (("L", 2), ("R", 28), ("M", 15)):
        dev.labels.append((text, (x, 1, 4, 4), 6, 1, FONT_BOLD))
    for text, x in (("-1", 1), ("0", 15), ("+1", 28)):
        dev.labels.append((text, (x, 43, 5, 4), 6, 1, FONT_BOLD))
    dev.toggle("show", "Display", default=True, button=(8, 47, 18, 5),
               tooltip="Draw the goniometer here. Off: only this panel stops; Master Bo's goniometer goes on")
    return dev


if __name__ == "__main__":
    out = HERE / "out" / "bo Scope.bwdevice"
    out.parent.mkdir(exist_ok=True)
    data = bo_scope().build()
    out.write_bytes(data)
    print(f"{out.name}: {len(data)} bytes")
