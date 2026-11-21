"""bo Spectrum's bottom rows, rearranged:

    [0 dB | 3 dB | 4.5 dB]   [Stereo | M/S  ]   [Reset]
    Hold [Off | 1 s | 3 s | 10 s | ∞]   [Plot | 1/3 Oct.]   [     ]

  - Hold: no button; a "Hold" label and an Off choice first in the hold time (hold_time 0 = Off).
  - Display button gone (show stays on).
  - View: Plot / 1/3 Oct. (Plot first: view 0 = Plot), under Stereo / M/S.
  - Reset: square, two rows high, at the right edge of the graph.
The Nitro code changes with it (hold from hold_time, Plot = view 0): pass the new source.

usage: spectrum_layout.py <bo Spectrum.bwdevice> <new nitro source> <out.bwdevice>
"""
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bwdevice import BwDevice  # noqa: E402
from bwobj import Obj, Field, parse_body, write_body, walk, clone, encode_str  # noqa: E402
import bwobj  # noqa: E402

RIGHT = 79                                  # the graph's right edge
ROW1, ROW2, H = 41, 47, 5
LAYOUT = {"MODE": (RIGHT - 13 - 22, ROW1, 22, H), "VIEW": (RIGHT - 13 - 22, ROW2, 22, H),
          "HOLD_TIME": (10, ROW2, 32, H)}
RESET = (RIGHT - 11, ROW1, 11, 11)
HOLD_LABEL = (1, ROW2 + 1, 8, 4)


def set_rect(item, x, y, w, h):
    r = item.get(0x1852)
    for k, v in zip((0x1847, 0x1848, 0x1849, 0x184A), (x, y, w, h)):
        f = r.field(k)
        f.value = v.to_bytes(len(f.value), "big")


def set_u8(o, fid, v):
    f = o.field(fid)
    f.value = v.to_bytes(len(f.value), "big")


def set_options(atom, names, default):
    vt = atom.get(0x2BE)
    items = vt.get(0x189)
    template = items[0]
    new = []
    for i, n in enumerate(names):
        it = clone(template)
        set_u8(it, 0x28A, i)
        it.set_str(0x28B, n)
        new.append(it)
    items[:] = new
    set_u8(vt, 0x1B2E, default)
    set_u8(atom, 0x1C9, default)


def main(src, nitro_path, dst):
    data = Path(src).read_bytes()
    dev = BwDevice(data)
    root = parse_body(bytes(dev.body))
    nitro = next(o for o in walk(root) if o.cls == 0x6B9)
    nitro.set(0x28B5, b"\x00" + Path(nitro_path).read_text().encode("utf-8"))
    atoms = {o.get_str(0x2BD): o for o in walk(root) if o.cls in (0xB4, 0x57) and o.get(0x2BD, None) is not None}
    set_options(atoms["HOLD_TIME"], ["Off", "1 s", "3 s", "10 s", "∞"], 0)   # Off by default (Hold was off)
    set_options(atoms["VIEW"], ["Plot", "1/3 Oct."], 1)                            # 1/3 Oct. by default (Bars was)
    panel = next(o for o in walk(root) if o.cls == 0x690 and o.get_str(0x1843) == "Main")
    items = panel.get(0x1844).get(0x184D)
    def atom_of(w):
        t = w.get(0x184C, None)
        return t.target.get_str(0x2BD) if t is not None and hasattr(t, "target") and t.target.get(0x2BD, None) is not None else None
    for w in list(items):
        a = atom_of(w)
        if w.cls == 0x6A6 and a in ("HOLD", "SHOW"):
            items.remove(w)                                   # the Hold and Display buttons
        elif w.cls == 0x6B0 and a in LAYOUT:
            set_rect(w, *LAYOUT[a])
        elif w.cls == 0x758:
            set_rect(w, *RESET)
    label = next(w for w in items if w.cls == 0x693)
    hold = clone(label)
    hold.set_str(0x184B, "Hold")
    set_rect(hold, *HOLD_LABEL)
    items.append(hold)
    rules = panel.get(0x1885) or []
    alive = {id(w) for w in items}
    assert all(id(r.get(0x1883).target) in alive for r in rules), "a rule points at a removed item"
    dev.body = bytearray(write_body(root))
    dev.set_meta("revision_id", hashlib.sha1(bytes(dev.body)).hexdigest())
    Path(dst).write_bytes(dev.to_bytes())
    print(f"{dst}: rearranged")


if __name__ == "__main__":
    main(*sys.argv[1:4])
