"""bo LUFS without undo entries: its values reach bo Master through two of Bitwig's oscilloscopes
instead of four hidden readout parameters the DSP wrote every 100 ms (each write was an undoable
"change made by controller").

  - the Nitro code (stored as source) gets four audio outputs bm_a .. bm_d (ports 3 .. 6) carrying
    M, S, I and TP max + 1000 as steady signals, and no longer sends m_val .. tp_val;
  - two oscilloscope atoms (cloned from bo Scope's) take bm_a / bm_b and bm_c / bm_d as inputs A / B.

usage: lufs_scopes.py <bo LUFS.bwdevice> <bo Scope.bwdevice> <new nitro source> <out.bwdevice>
"""
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bwdevice import BwDevice  # noqa: E402
from bwobj import Ref, parse_body, write_body, walk, clone  # noqa: E402

PORTS = [(3, 4), (5, 6)]           # bm_a, bm_b -> first oscilloscope; bm_c, bm_d -> second
# Bitwig numbers a Nitro atom's audio outputs first (out 0, ui_a 1, ui_b 2, bm_a .. bm_d 3 .. 6), then its
# value outputs (m_val .. tp_val 7 .. 10); an oscilloscope only connects to audio outputs.
READOUT_SHIFT = 4                    # the old readout atoms: m_val .. tp_val moved from 3 .. 6 to 7 .. 10


def main(lufs_path, scope_path, nitro_path, out_path):
    data = Path(lufs_path).read_bytes()
    dev = BwDevice(data)
    root = parse_body(bytes(dev.body))
    nitro = next(o for o in walk(root) if o.cls == 0x6B9)
    assert nitro.get(0x3918) == b"\x00", "expected Nitro stored as source"
    nitro.set(0x28B5, b"\x00" + Path(nitro_path).read_text().encode("utf-8"))
    scope_root = parse_body(bytes(BwDevice(Path(scope_path).read_bytes()).body))
    template = next(o for o in walk(scope_root) if o.cls == 0x676)
    atoms = root.get(0xAD)
    assert not any(getattr(a, "cls", None) == 0x676 for a in atoms), "already has oscilloscopes"
    for n, (pa, pb) in enumerate(PORTS):
        osc = clone(template)
        ins = osc.get(0x1832).get(0x266)
        for conn, port in ((ins[0], pa), (ins[1], pb)):
            conn.field(0xF8).value = Ref(nitro)
            conn.field(0xF9).value = port.to_bytes(len(conn.field(0xF9).value), "big")
        pos = osc.get(0x1832).get(0x264)                      # where it sits in the device's editor
        pos.field(0x11).value = (1000 + 200 * n).to_bytes(len(pos.field(0x11).value), "big")
        atoms.append(osc)
    for o in walk(root):
        if o.cls == 0x7F5:                                   # settable_decimal_value_atom: the old readouts
            for conn in o.get(0x1832).get(0x266):
                if conn.get(0xF8) is not None:
                    f = conn.field(0xF9)
                    f.value = (int.from_bytes(f.value, "big") + READOUT_SHIFT).to_bytes(len(f.value), "big")
    dev.body = bytearray(write_body(root))
    dev.set_meta("revision_id", hashlib.sha1(bytes(dev.body)).hexdigest())
    Path(out_path).write_bytes(dev.to_bytes())
    print(f"{out_path}: 2 oscilloscopes on ports {PORTS}")


if __name__ == "__main__":
    main(*sys.argv[1:5])
