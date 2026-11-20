"""Build "BO LUFS.bwdevice": loudness / true-peak meter (BS.1770-4, EBU R128).

    python build_bo_lufs.py        -> out/BO LUFS.bwdevice

Panel: columns M, S, I, TP. Each has a Bitwig label (title font), a readout box (value drawn
by the display, orange at or above Reference for S / I, red at or above 0 dBTP for TP) and an
orange bar (-36 .. 0, Bitwig-meter look). Bitwig labels for the scale. Clicking a readout or a bar
resets (Bitwig trigger buttons under the drawings, which are overlays and let clicks through).
Inspector: the Reference knob.

Sources: lufs/bo_lufs.nitro.in + lufs/tp_fill.inc (-> bo_lufs.nitro), lufs/bo_lufs_bar.sksl,
lufs/bo_lufs_row.sksl + lufs/bo_lufs_digits.sksl.
Checks: nitrocheck.sh (Bitwig's own Nitro compiler, offline), lufs/test_bo_lufs.py,
lufs/test_literal.py, lufs/sksl_preview (shader previews).
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "tools" / "device-gen"))  # bwgen, the device generator

from bwgen import Device, Origin, Trigger, Readout  # noqa: E402

LUFS = HERE / "lufs"
COL_X, COL_W, COL_STEP = 9, 10, 11      # column boxes (scale at 1..7, 2 units gap to the first bar)
BAR_W = COL_W                           # bar as wide as its readout
READ_Y, READ_H = 6, 5
BAR_Y, BAR_H = 14, 36                   # -36 .. 0: 1 grid unit per dB (the shaders draw 1 px higher: 3 px lower than at 13)
FONT_TITLE, FONT_LARGE_TITLE = 5, 6     # label_panel_item.font (enum MBW)
INPUTS = ["ui_a", "ui_b"]

# name, readout value, readout kind (0 plain, 1 reference, 2 clip), bar value
COLUMNS = [("M", "audioLow.x", 0, "audioLow.x", "Momentary loudness (400 ms), LUFS"),
           ("S", "audioLow.y", 1, "audioLow.y", "Short-term loudness (3 s), LUFS; orange at or above Reference"),
           ("I", "audioLow.z", 1, "audioLow.z", "Integrated loudness since Reset, LUFS; orange at or above Reference"),
           ("TP", "audioMedium.x", 2, "audioMedium.w", "True peak: readout = max since Reset, bar = last 100 ms (dBTP)")]


def nitro_source() -> str:
    src = (LUFS / "bo_lufs.nitro.in").read_text()
    return src.replace("TP_FILL\n", (LUFS / "tp_fill.inc").read_text())


def row_shader(value: str, kind: int) -> str:
    s = (LUFS / "bo_lufs_row.sksl").read_text().replace("BO_DIGITS", (LUFS / "bo_lufs_digits.sksl").read_text())
    return s.replace("BO_VALUE", value).replace("BO_KIND", f"{kind}.").replace("BO_RIGHT", ".86")


def bar_shader(value: str, tp: bool, ref: bool) -> str:
    s = (LUFS / "bo_lufs_bar.sksl").read_text()
    return s.replace("BO_VALUE", value).replace("BO_TP", "1." if tp else "0.").replace("BO_REF", "1." if ref else "0.")


def bo_lufs() -> Device:
    nitro = nitro_source()
    (HERE / "bo_lufs.nitro").write_text(nitro)
    dev = Device(
        name="bo LUFS",
        creator="bo",
        uuid_key="bo-devices/bo-lufs",
        nitro=nitro,
        description="Loudness meter (ITU-R BS.1770-4 / EBU R128): momentary, short-term, integrated, true peak",
        tags="loudness lufs meter true peak r128 analysis",
        panel_size=(54, 53),                # 2 units right margin
        inspector_size=(40, 15),
        inspector_in_panels=True,
        prevent_sleep=True,
    )
    # the meter strip behind the bars and their scale, as the Tool device's (drawn first, under the labels)
    dev.display((LUFS / "bo_lufs_strip.sksl").read_text(), rect=(0, BAR_Y - 2, 53, BAR_H + 4), inputs=INPUTS,
                id="BO_LUFS_STRIP")
    click = []
    for i, (name, rval, kind, bval, tip) in enumerate(COLUMNS):
        x = COL_X + COL_STEP * i
        dev.labels.append((name, (x, 1, COL_W, 4), 6, 1, FONT_TITLE))
        r_read = (x, READ_Y, COL_W, READ_H)
        r_bar = (x + (COL_W - BAR_W) // 2, BAR_Y, BAR_W, BAR_H)
        dev.display(row_shader(rval, kind), rect=r_read, inputs=INPUTS, id=f"BO_LUFS_READ_{name}", overlay=True)
        dev.display(bar_shader(bval, name == "TP", name in ("S", "I")), rect=r_bar, inputs=INPUTS, id=f"BO_LUFS_BAR_{name}", overlay=True)
        click += [r_read, r_bar]
    for db in range(0, -37, -6):
        dev.labels.append((str(-db), (0, BAR_Y + (-db) * BAR_H // 36 - 2, 6, 4), 6, 1, 1))  # bold, no minus (as the Tool meter)

    # hidden readout atoms: Master Bo finds this device and reads them by name
    for port, name, lo, hi in (("m_val", "BO LUFS M", -100, 0), ("s_val", "BO LUFS S", -100, 0),
                               ("i_val", "BO LUFS I", -100, 0), ("tp_val", "BO LUFS TP", -100, 20)):
        dev.readouts.append(Readout(port, name, (0, 0, 1, 1), unit="", min=lo, max=hi, default=-100, hidden=True))

    dev.param("reference", "Ref.LUFS", -36, 0, -14, unit_text="LUFS", origin=Origin.MIN, knob=(14, 1),
              inspector=True)
    dev.triggers.append(Trigger("reset_btn", "", click,
                                tooltip="Click to reset: integrated loudness, maxima and true peak start again",
                                style=2))   # borderless: invisible if the drawings aren't shown yet
    return dev


if __name__ == "__main__":
    out = HERE / "out" / "bo LUFS.bwdevice"
    out.parent.mkdir(exist_ok=True)
    data = bo_lufs().build()
    out.write_bytes(data)
    print(f"{out.name}: {len(data)} bytes")
