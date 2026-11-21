"""Reset buttons that don't show: no Bitwig button style draws nothing, and a hidden button takes no
clicks (tested with bo Button Test). So:
  - lufs:     only the reset buttons under the four readouts stay (the readouts' dark boxes cover
              them); the ones under the bars go.
  - spectrum: the hidden buttons on the scales and their covers go; one ordinary Reset button
              (style BUTTON) takes their place in the bottom row, left of Display.

usage: reset_buttons.py lufs|spectrum <in.bwdevice> <out.bwdevice>
"""
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bwdevice import BwDevice  # noqa: E402
from bwobj import parse_body, write_body, walk, encode_str  # noqa: E402

READ_Y = 6                      # bo LUFS: the readouts' row
SPECTRUM_RESET = (49, 47, 14, 5)  # bo Spectrum: in the bottom row, 2 units left of Display (65)


def rect(item):
    r = item.get(0x1852)
    return tuple(r.get_int(k) for k in (0x1847, 0x1848, 0x1849, 0x184A))


def set_rect(item, x, y, w, h):
    r = item.get(0x1852)
    for k, v in zip((0x1847, 0x1848, 0x1849, 0x184A), (x, y, w, h)):
        f = r.field(k)
        f.value = v.to_bytes(len(f.value), "big")


def patch(kind, src, dst):
    data = Path(src).read_bytes()
    dev = BwDevice(data)
    root = parse_body(bytes(dev.body))
    panel = next(o for o in walk(root) if o.cls == 0x690 and o.get_str(0x1843) == "Main")
    items = panel.get(0x1844).get(0x184D)
    triggers = [w for w in items if w.cls == 0x758]
    if kind == "lufs":
        for t in triggers:
            if rect(t)[1] != READ_Y:
                items.remove(t)
            else:
                t.set_str(0x1AD3, "Click to reset: integrated loudness, maxima and true peak start again")
        print(f"  kept {sum(1 for w in items if w.cls == 0x758)} reset buttons under the readouts")
    elif kind == "spectrum":
        keep = triggers[0]
        for t in triggers[1:]:
            items.remove(t)
        set_rect(keep, *SPECTRUM_RESET)
        f = keep.field(0x1A26)
        f.value = (0).to_bytes(len(f.value), "big")            # BUTTON
        keep.set_str(0x1861, "Reset")
        keep.set_str(0x1AD3, "Reset the spectrum: levels and peak hold start again")
        covers = [w for w in items if w.cls == 0x158D and w.get(0x184C).target.get_str(0x2BD).startswith("BO_SPECTRUM_COVER_")]
        for c in covers:
            items.remove(c)
        print(f"  one Reset button at {SPECTRUM_RESET}; removed {len(triggers) - 1} hidden button(s) and {len(covers)} covers")
    dev.body = bytearray(write_body(root))
    dev.set_meta("revision_id", hashlib.sha1(bytes(dev.body)).hexdigest())
    Path(dst).write_bytes(dev.to_bytes())


if __name__ == "__main__":
    patch(*sys.argv[1:4])
