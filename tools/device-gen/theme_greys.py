"""Theme greys for bo devices: patches built .bwdevice files so the greys their drawings use follow
Bitwig's theme instead of being fixed.

A display's drawing (SkSL) can't read the theme: it only gets iResolution, iTime and audioLow/Medium/
High. What it can do is let Bitwig's own drawing show through. Each listed drawing is wrapped:
  - a coloured pixel (orange, blue, green ...) is drawn as before;
  - a grey pixel v is drawn relative to the grey Bitwig shows under the display (BASE, its value in the
    default theme): darker than BASE as black with alpha 1 - v / BASE, lighter as white with alpha
    (v - BASE) / (1 - BASE). In the default theme the result is exactly v; in another theme the greys
    follow the theme's panel grey (a grey equal to BASE becomes fully transparent).
Bitwig buttons hidden under drawings (the reset triggers of bo LUFS and bo Spectrum, style 2
INSIDE_DISPLAY) get style 3 (BORDERLESS_BUTTON): with no text they draw nothing, so the opaque
covers that hid them can go transparent.

usage: theme_greys.py <device> <in.bwdevice> <out.bwdevice>     device: lufs spectrum scope safety curve magnetic
"""
import hashlib
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from bwdevice import BwDevice  # noqa: E402
import bwobj  # noqa: E402
from bwobj import Obj, Ref, parse_body, write_body, walk, clone, encode_str  # noqa: E402

P = .2706            # Bitwig's device panel grey in the default theme (69 / 255)
STRIP = .106         # bo LUFS's dark meter strip, under its bars

WRAP = """
// ---- theme greys (theme_greys.py): the greys above are the default theme's; they are drawn relative
// to what Bitwig shows under this display (BO_BASE in the default theme), so they follow the theme.
half4 main(float2 boThemeCoord) {
    half4 boThC = boThDrawn(boThemeCoord);
    float boThA = float(boThC.a);
    if (boThA <= 0.) return half4(0.);
    float3 boThU = float3(boThC.rgb) / boThA;
    float boThHi = max(boThU.r, max(boThU.g, boThU.b));
    float boThLo = min(boThU.r, min(boThU.g, boThU.b));
    if (boThHi - boThLo > .06) return boThC;                      // a colour: as drawn
    float boThV = (boThU.r + boThU.g + boThU.b) / 3.;
    if (boThV < BO_BASE) return half4(0., 0., 0., (1. - boThV / BO_BASE) * boThA);
    float boThK = (boThV - BO_BASE) / (1. - BO_BASE) * boThA;
    return half4(boThK, boThK, boThK, boThK);
}
"""

MAIN = re.compile(r"half4\s+main\s*\(\s*float2\s+(\w+)\s*\)")


def themed(src: str, base: float) -> str:
    assert "theme_greys.py" not in src, "already themed"
    assert len(MAIN.findall(src)) == 1, "expected one main"
    return MAIN.sub(r"half4 boThDrawn(float2 \1)", src) + WRAP.replace("BO_BASE", f"{base:.4f}")


def shaders(root):
    return {o.get_str(0x2BD): o for o in walk(root) if o.cls == 0x1588 and o.get(0x3FBC, None) is not None}


def main_panel(root):
    return next(o for o in walk(root) if o.cls == 0x690 and o.get_str(0x1843) == "Main")


def wrap(root, ids_bases):
    sh = shaders(root)
    for sid, base in ids_bases.items():
        o = sh[sid]
        o.set_str(0x3FBC, themed(o.get_str(0x3FBC), base))
        print(f"  themed {sid} (base {base})")


def borderless_triggers(root):
    n = 0
    for o in walk(root):
        if o.cls == 0x758:                       # trigger_panel_item
            f = o.field(0x1A26)                  # style (float_core.panel_button_style)
            assert int.from_bytes(f.value, "big") == 2, "expected INSIDE_DISPLAY"
            f.value = (3).to_bytes(len(f.value), "big")   # BORDERLESS_BUTTON
            n += 1
    print(f"  {n} reset triggers: style BORDERLESS_BUTTON")


def rules(panel):
    return panel.get(0x1885)


def rule(expr, w, attr):
    return Obj(0x6C0, [bwobj.Field(0x1882, bwobj.T_STR, encode_str(expr)), bwobj.Field(0x1883, bwobj.T_REF, Ref(w)),
                       bwobj.Field(0x18AC, bwobj.T_U16, attr.to_bytes(2, "big"))])


def curve(root):
    panel = main_panel(root)
    items = panel.get(0x1844).get(0x184D)
    sh = shaders(root)
    # the graph: the Modern skin keeps the original, the Bitwig skin gets a themed copy (the vintage
    # skin's image covers the original, as before)
    draw = next(w for w in items if w.cls == 0x158D and w.get(0x184C).target is sh["BO_SAT_DRAWING"])
    shader = sh["BO_SAT_DRAWING"]
    holder = next(f for o in walk(root) for f in o.fields
                  if isinstance(f.value, list) and any(x is shader for x in f.value))
    tshader = clone(shader)
    tshader.set_str(0x2BD, "BO_SAT_DRAWING_THEME")
    tshader.set_str(0x3FBC, themed(shader.get_str(0x3FBC), P))
    holder.value.insert(holder.value.index(shader) + 1, tshader)
    tdraw = clone(draw)
    tdraw.field(0x184C).value = Ref(tshader)
    items.insert(items.index(draw) + 1, tdraw)
    rs = rules(panel)
    rs += [rule("SKIN==1", draw, 0x18A5), rule("SKIN==0", tdraw, 0x18A5)]
    print("  BO_SAT_DRAWING: Modern only; BO_SAT_DRAWING_THEME (themed) for the Bitwig skin")
    # the lever image (Link on) only in the vintage skin, so the Bitwig skin needs no cover over it
    lever = [r for r in rs if r.get_str(0x1882) == "LINK"]
    assert len(lever) == 1
    lever[0].set_str(0x1882, "LINK && SKIN==2")
    print("  lever image: LINK && SKIN==2")
    wrap(root, {"BO_SAT_LEVER_COVER_0": P})      # its panel grey: fully transparent now


PLANS = {
    "lufs": lambda r: (wrap(r, {"BO_LUFS_STRIP": P, **{f"BO_LUFS_READ_{k}": P for k in ("M", "S", "I", "TP")},
                                **{f"BO_LUFS_BAR_{k}": STRIP for k in ("M", "S", "I", "TP")}}),
                       borderless_triggers(r)),
    "spectrum": lambda r: (wrap(r, {**{f"BO_SPECTRUM_BARS_{i}": P for i in range(4)},
                                    **{f"BO_SPECTRUM_COVER_{i}": P for i in range(2)}}),
                           borderless_triggers(r)),
    "scope": lambda r: wrap(r, {"BO_SCOPE_GONIO": P}),
    "safety": lambda r: wrap(r, {"BO_SL_METER": P}),
    "curve": curve,
    "magnetic": lambda r: wrap(r, {"BO_SKIN_DARK": P}),
}


def patch(kind, src: Path, dst: Path):
    data = src.read_bytes()
    dev = BwDevice(data)
    assert dev.to_bytes() == data
    root = parse_body(bytes(dev.body))
    assert write_body(root) == bytes(dev.body)
    PLANS[kind](root)
    dev.body = bytearray(write_body(root))
    dev.set_meta("revision_id", hashlib.sha1(bytes(dev.body)).hexdigest())
    dst.write_bytes(dev.to_bytes())
    print(f"  {dst.name}: {len(data)} -> {dst.stat().st_size} bytes")


if __name__ == "__main__":
    patch(sys.argv[1], Path(sys.argv[2]), Path(sys.argv[3]))
