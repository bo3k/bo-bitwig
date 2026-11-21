"""Build "BO Spectrum.bwdevice": a pass-through device holding Bitwig's own spectrum analyser, with
its own 31-band spectrum on the panel.

    python build_bo_spectrum.py      -> out/BO Spectrum.bwdevice

Master Bo finds it by name (on the master or any track) and reads the analysers' spectra (linear
FFT bins, as Bitwig's spectrum displays get them) for its 31-band spectrum. The panel shows the same
31 third-octave bands from the device's own filter bank (three drawings side by side, a shader's 12
inputs each), with the slope (0 / 3 / 4.5 dB per octave) and a Display switch (off: the filter bank
stops, Master Bo's feed goes on). Sources: spectrum/bo_spectrum.nitro, spectrum/bo_spectrum_bars.sksl.
"""
import math
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "tools" / "device-gen"))  # bwgen, the device generator

from bwgen import Device, Trigger  # noqa: E402

FONT_BOLD = 1
GX, GY, BAND_W, GH = 1, 1, 2.5, 34       # the graph: 2.5 grid units (10 px) per band, 34 units (136 px) high
GROUPS = [(0, 8), (8, 8), (16, 8), (24, 7)]   # first band, bands: one drawing each (whole grid units wide)


def bars_shader(first, count):
    s = (HERE / "spectrum" / "bo_spectrum_bars.sksl").read_text()
    return s.replace("BO_FIRST", f"{first}.").replace("BO_COUNT", f"{count}.")


def band_of(hz):
    return 17 + 10 * math.log10(hz / 1000)


def bo_spectrum() -> Device:
    graph_w = round(31 * BAND_W + 0.5)
    dev = Device(
        name="bo Spectrum",
        creator="bo",
        uuid_key="bo-devices/bo-spectrum",
        nitro=(HERE / "spectrum" / "bo_spectrum.nitro").read_text(),
        description="31-band spectrum (20 Hz .. 20 kHz) and the source of Master Bo's spectrum",
        tags="spectrum analyser analyzer fft meter master third octave",
        panel_size=(GX + graph_w + 8, 53),
        prevent_sleep=True,
        fft_order=13,   # HUGE: 8192-point FFT at 48 kHz (5.9 Hz bins), so the low third-octave bands get their own bins
    )
    dev.spectra.append(("ana", None, 4.5))     # the stereo signal, for Master Bo
    dev.spectra.append(("ana_m", None, 4.5))   # mid, for Master Bo's M/S mode
    dev.spectra.append(("ana_s", None, 4.5))   # side
    for g, (first, count) in enumerate(GROUPS):
        ins = [f"sp{3 * g}", f"sp{3 * g + 1}", f"sp{3 * g + 2}"]
        x0, x1 = round(GX + first * BAND_W), round(GX + (first + count) * BAND_W + (0.5 if g == 3 else 0))
        dev.display(bars_shader(first, count), rect=(x0, GY, x1 - x0, GH), inputs=ins,
                    id=f"BO_SPECTRUM_BARS_{g}")
    # the dB scale at the right, every 12 dB (as Master Bo's)
    for db in range(0, -73, -12):
        y = GY + GH * (-db) / 72
        dev.labels.append((str(db), (GX + graph_w, max(0, round(y) - 2), 7, 4), 6, 1, FONT_BOLD))
    # the octaves under the graph
    for hz, text in ((20, "20"), (40, "40"), (80, "80"), (160, "160"), (315, "315"), (630, "630"), (1250, "1.2k"),
                     (2500, "2.5k"), (5000, "5k"), (10000, "10k"), (20000, "20k")):
        x = GX + (band_of(hz) + 0.5) * BAND_W
        dev.labels.append((text, (max(0, round(x) - 3), GY + GH, 6, 4), 6, 1, FONT_BOLD))
    row1, row2 = GY + GH + 6, GY + GH + 12
    dev.choice("slope", "Slope", ["0 dB", "3 dB", "4.5 dB"], default=2, rect=(GX + 9, row1, 28, 5),
               buttons=True, tooltip="Tilt of the graph, dB per octave around 1 kHz (as Master Bo's Slope)")
    right = GX + graph_w                       # Display's right edge: View and Mode align to it
    dev.choice("mode", "Mode", ["Stereo", "M/S"], default=0, rect=(right - 13 - 22, row1, 22, 5), buttons=True,
               tooltip="Stereo: the stereo signal (orange). M/S: mid (orange) and side (blue)")
    dev.choice("view", "View", ["Plot", "Bars"], default=1, rect=(right - 13 - 22, row2, 22, 5), buttons=True,
               tooltip="Plot: a curve through the bands. Bars: the 31 bands as bars")
    dev.toggle("hold", "Hold", default=False, button=None,     # not on the panel: hold_time 0 is Off
               tooltip="Peak hold (no longer used by the DSP; kept for bo Master's settings sync)")
    dev.labels.append(("Hold", (GX, row2 + 1, 8, 4), 6, 1, FONT_BOLD))
    dev.labels.append(("Slope", (GX, row1 + 1, 8, 4), 6, 1, FONT_BOLD))
    dev.choice("hold_time", "Hold time", ["Off", "1 s", "3 s", "10 s", "\u221e"], default=0, rect=(GX + 9, row2, 32, 5),
               buttons=True, tooltip="Peak hold: each band's highest level, kept this long, then falling (Off: no hold)")
    dev.toggle("show", "Display", default=True, button=None,  # not on the panel any more: always on
               tooltip="Draw the spectrum here. Off: only this panel stops; Master Bo's spectrum goes on")
    # Reset: a square button two rows high at the graph's right edge (no Bitwig button can be hidden
    # and still take clicks, so it is an ordinary one)
    dev.triggers.append(Trigger("reset_btn", "Reset", [(right - 11, row1, 11, 11)],
                                tooltip="Reset the spectrum: levels and peak hold start again", style=0))
    return dev


if __name__ == "__main__":
    out = HERE / "out" / "bo Spectrum.bwdevice"
    out.parent.mkdir(exist_ok=True)
    data = bo_spectrum().build()
    out.write_bytes(data)
    print(f"{out.name}: {len(data)} bytes")
