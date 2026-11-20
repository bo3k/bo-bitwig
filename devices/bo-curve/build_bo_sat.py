"""Build "BO Sat.bwdevice" (was BO Sat Test; same UUID) from its description. No template files needed.

    python build_bo_sat.py            -> out/BO Sat.bwdevice
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "tools" / "device-gen"))  # bwgen, the device generator

from bwgen import Device, Domain, Origin, ButtonStyle  # noqa: E402


def with_oversampling(nitro: str) -> str:
    """Add BO Magnetic's 1x / 2x / 4x half-band oversampling (HIIR, see os/hiir_design.py) around the
    curve. The allpass code is taken from bo_magnetic.nitro, so both devices share one implementation."""
    mag = (HERE / "bo_magnetic.nitro").read_text()
    members = mag[mag.index("    // oversampling: half-band"):mag.index("    f32 up_1 = 0.0;\n") + len("    f32 up_1 = 0.0;\n")]
    funcs = mag[mag.index("    // HIIR 2x upsampler"):mag.index("    // the curve at 1x / 2x / 4x")]

    def sub(old, new):
        nonlocal nitro
        assert nitro.count(old) == 1, old
        nitro = nitro.replace(old, new)
    sub("    value_inport<bool> link;\n", "    value_inport<bool> link;\n    value_inport<i32> os;\n")
    sub("    f32 peak_out = 0.0;\n", "    f32 peak_out = 0.0;\n\n" + members + """    // curve parameters of the current sample, for the oversampled path
    f32 m_hr = 1.0;
    f32 m_g = 1.0;
    f32 m_k = 2.0;
    f32 m_cu = 1.0;
    f32 m_cd = 1.0;
    f32 m_comp = 1.0;
""")
    sub("    reset\n", funcs + """    f32 sat_one(f32 v)
    {
        return m_hr * shape_mode(v * m_g, m_mode, m_k, m_cu, m_cd, m_c01, m_as) * m_comp;
    }

    // the curve at 1x / 2x / 4x for one channel, each oversampled path with its own filter states
    f32 sat_path(i32 mode, i32 ch, f32 v)
    {
        f32 y = 0.0;
        if (mode == 1)
        {
            i32 oa = ch * 8;
            up_a(oa, v);
            f32 a0 = up_0;
            f32 a1 = up_1;
            y = down_a(oa, sat_one(a0), sat_one(a1));
        }
        else if (mode == 2)
        {
            i32 oa = 16 + ch * 8;
            up_a(oa, v);
            f32 a0 = up_0;
            f32 a1 = up_1;
            up_b(ch, a0);
            f32 b0 = up_0;
            f32 b1 = up_1;
            up_b(ch, a1);
            f32 b2 = up_0;
            f32 b3 = up_1;
            f32 d0 = down_b(ch, sat_one(b0), sat_one(b1));
            f32 d1 = down_b(ch, sat_one(b2), sat_one(b3));
            y = down_a(oa, d0, d1);
        }
        else
            y = sat_one(v);
        return y;
    }

    // crossfaded switching, as in BO Magnetic
    f32 sat_os(i32 ch, f32 v)
    {
        f32 y = sat_path(os_now, ch, v);
        if (xf_on == 1)
        {
            f32 yo = sat_path(os_old, ch, v);
            f32 gx = clampf(cast(xf_n - 64, f32) / 512.0, 0.0, 1.0);
            y = yo + gx * (y - yo);
        }
        return y;
    }

    reset
""")
    sub("        peak_out = 0.0;\n    }\n", "        peak_out = 0.0;\n        init_magic = 0;\n    }\n")
    sub("        f32 kc = fmin(1.0, 1.0 / (0.005 * rate));\n", """        f32 kc = fmin(1.0, 1.0 / (0.005 * rate));
        if (init_magic != 715203)
        {
            set_os_coefs();
            clear_os();
            os_now = -1;
            xf_on = 0;
            init_magic = 715203;
        }
        i32 os_want = os;
        if (os_want < 0)
            os_want = 0;
        if (os_want > 2)
            os_want = 2;
        if ((os_want != os_now) && (xf_on == 0))
        {
            if (os_now < 0)
                os_now = os_want;
            else
            {
                os_old = os_now;
                os_now = os_want;
                if (os_now > 0)
                    clear_path(os_now);
                xf_n = 0;
                xf_on = 1;
            }
        }
""")
    sub("""        f32 sat_l = hr * shape2(em_l * s_drive / hr, k, c_up, c_dn) * comp;
        f32 sat_r = hr * shape2(em_r * s_drive / hr, k, c_up, c_dn) * comp;
""", """        m_hr = hr;
        m_g = s_drive / hr;
        m_k = k;
        m_cu = c_up;
        m_cd = c_dn;
        m_comp = comp;
        f32 sat_l = sat_os(0, em_l);
        f32 sat_r = sat_os(1, em_r);
        os_tick();
""")
    return nitro


MODE_TEXT = ["Smooth symmetric saturation, soft to hard with Curve", "Asymmetric tube-style curve, warm even harmonics",
             "Saturation, then fewer amplitude steps with Curve: 15 down to 1 bit"]


def mode_descriptions(root):
    """Descriptions for the Mode menu items (field 0x2660 of each option, as the Test Tone menu shows),
    and the arrow style for the Mode menu."""
    from bwobj import Obj
    from bwgen import encode_str

    def walk(o):
        if isinstance(o, Obj):
            if o.cls == 0xB4 and any(f.fid == 0x2BD and f.value.endswith(b"MODE") for f in o.fields):
                opts = o.get(0x2BE).get(0x189)
                for item, desc in zip(opts, MODE_TEXT):
                    item.field(0x2660).value = encode_str(desc)
            for f in o.fields:
                for x in (f.value if isinstance(f.value, list) else [f.value]):
                    walk(x)
    walk(root)
    # the Mode menu in the Test Tone style: arrows either side (field 0x187A = 6, found with BO Popup Test A)
    items = root.get(0x1845)[0].get(0x1844).get(0x184D)
    for w in items:
        if w.cls == 0x6B2 and w.get(0x184C).target.get_str(0x2BD) == "MODE":
            w.field(0x187A).value = bytes([6])


SKIN_COVER = """// BO Sat skin panel: a flat grey plate under the graph and the controls (Bright / Modern skins)
uniform float4 audioLow;
uniform float4 audioMedium;
uniform float4 audioHigh;

half4 main(float2 fragCoord) {
    return half4(PANEL_GREY, PANEL_GREY, PANEL_GREY, 1.);
}
"""


def add_skins(dev):
    """Inspector menu "Skin": Bitwig (Bitwig's standard look) / Modern (light grey panel, light knobs) /
    Vintage (default, see add_vintage). Panel expressions switch items live; hidden knobs are also
    disabled, so only one set takes the mouse. The grey plate is drawn first, so the graph, the Link
    button and the Mode menu stay on top."""
    from bwobj import Obj, Field, Ref
    from bwgen import text, ref, u16, u8
    dev.nitro = dev.nitro.replace("    value_inport<i32> mode;\n", "    value_inport<i32> mode;\n    value_inport<i32> skin;\n", 1)
    dev.inspector_size = (40, 20)
    dev.choice("skin", "Skin", ["Bitwig", "Modern", "Vintage"], default=2, rect=(1, 10, 24, 4),
               inspector=True, buttons=False,
               tooltip="Bitwig: Bitwig's standard look. Modern: light grey panel with light knobs. "
                       "Vintage: the printed transfer-characteristics panel (the screw switches to Modern)")
    full = (0, 0) + tuple(dev.panel_size)
    dev.display(SKIN_COVER.replace("PANEL_GREY", ".60"), rect=full, inputs=["ui_a", "ui_b", "ui_c"], id="BO_SAT_SKIN_MODERN")

    def clone(w):
        fs = []
        for f in w.fields:
            v = f.value
            if isinstance(v, Obj):
                v = Obj(v.cls, [Field(g.fid, g.type, g.value) for g in v.fields])
            elif isinstance(v, Ref):
                v = Ref(v.target)
            fs.append(Field(f.fid, f.type, v))
        return Obj(w.cls, fs)

    def hook(root):
        panel = root.get(0x1845)[0]
        items = panel.get(0x1844).get(0x184D)

        def drawing_id(w):
            return w.get(0x184C).target.get_str(0x2BD)
        cover = next(w for w in items if w.cls == 0x158D and drawing_id(w) == "BO_SAT_SKIN_MODERN")
        items.remove(cover)
        items[0:0] = [cover]                   # drawn first
        knobs = [w for w in items if w.cls == 0x697]
        style = (0x1B8F, 0x1B90, 0x1B91)       # knob style, label colour, indicator colour
        modern_knobs = [clone(k) for k in knobs]
        for k in modern_knobs:                 # style 1: light grey body; black labels
            k.fields = [f for f in k.fields if f.fid not in style] + [u8(0x1B8F, 1), u16(0x1B90, 7)]
        items += modern_knobs
        hook.knobs = knobs

        def rule(expr, w, attr):
            return Obj(0x6C0, [text(0x1882, expr), ref(0x1883, w), u16(0x18AC, attr)])
        exprs = list(panel.get(0x1885) or [])
        exprs.append(rule("SKIN==1", cover, 0x18A5))
        for group, n in ((knobs, 0), (modern_knobs, 1)):
            for w in group:
                exprs += [rule(f"SKIN=={n}", w, 0x18A5), rule(f"SKIN=={n}", w, 0x18A6)]
        panel.field(0x1885).value = exprs
        print(f"  skin: 2 x {len(knobs)} knobs, {len(exprs)} rules")
    return hook


VINTAGE_DIR = HERE / "satv5"         # Vintage: reference image set (satv5, aligned to satv3's frame), config, geom, bg2x.png
VW, VH = __import__("json").loads((VINTAGE_DIR / "config.json").read_text())["units"]   # 89 x 53 units
BITWIG_PANEL = .2706                 # Bitwig's device panel grey (69 / 255, measured on a screenshot)
# full-panel drawings appear this far (px) from where the panel image puts the same point: measured on a
# Bitwig screenshot (six knob pointers, residual < .3 px), compensated in the vintage overlay
DRAW_OFFSET = (-1.1, -0.94)


def add_vintage(dev):
    """Vintage skin (SKIN==2) from the user's reference image, prepared by make_sat_background.py:
    the image above the controls (its pointers and the lit lamp painted out), copies of the knobs under
    its knobs, a three-segment Mode button group under the S / T / B keys, and transparent overlays:
    turning pointers, the lamp over the active mode, the transfer curve (positive half) in the chart."""
    import base64
    import io
    import json
    import math
    from PIL import Image
    D = VINTAGE_DIR
    g = json.loads((D / "geom.json").read_text())
    sw, sh = g["src_size"]
    sx, sy = VW * 4 / sw, VH * 4 / sh            # source px -> device px (1x)

    def unit_rect(x, y, w, h):
        x0, y0 = math.floor(x / 4), math.floor(y / 4)
        x1, y1 = math.ceil((x + w) / 4), math.ceil((y + h) / 4)
        return (max(x0, 0), max(y0, 0), min(x1, VW) - max(x0, 0), min(y1, VH) - max(y0, 0))

    def f2(x, y):
        return f"float2({x:.3f}, {y:.3f})"

    VARIANTS = [(VINTAGE_DIR, 2, "V")]   # folder, skin index, id tag (more lightings: add (folder, 3, "VB"), ...)
    head = "uniform float4 audioLow;\nuniform float4 audioMedium;\nuniform float4 audioHigh;\n\n"
    dev.images = []
    dev.images_above_controls = True

    def variant(D, tag):
        """Background image and overlays of one vintage lighting (same geometry as VINTAGE_DIR)."""
        g = json.loads((D / "geom.json").read_text())
        b = io.BytesIO()
        Image.open(D / "bg2x.png").convert("RGB").save(b, "JPEG", quality=90)
        uri = "data:image/jpeg;base64," + base64.b64encode(b.getvalue()).decode()
        pw, ph = VW * 4, VH * 4
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="{pw}" '
               f'height="{ph}" viewBox="0 0 {pw} {ph}"><image x="0" y="0" width="{pw}" height="{ph}" href="{uri}" '
               f'xlink:href="{uri}"/></svg>')
        dev.images.append((svg, (0, 0, VW, VH), 1000, 1.0, 1.0))

        # knob positions 0..1 from the display data (defaults before the first data arrive)
        value = {
            "curve": "audioHigh.w",
            "asym": "(audioHigh.x + 1.) * .5",
            "drive": "pow(clamp(8.685889638 * log(max(audioLow.x, 1e-6)) / 48., 0., 1.), 1. / 3.)",
            "output": "(8.685889638 * log(max(audioLow.w, 1e-6)) + 24.) / 36.",
            "tone": "(audioMedium.z + 12.) / 24.",
            "mix": "audioLow.z",
        }
        default = {"curve": .25, "asym": .5, "drive": 0., "output": 24 / 36, "tone": .5, "mix": 1.}
        bar = g["bar_rgb"]
        sweep = g.get("sweep", 180)
        knob_rects = {}
        # Everything turning or lighting up is drawn in one full-panel overlay, in panel px. Small
        # per-knob drawings came out shifted by up to 5 px in Bitwig; the full-panel drawing sits at a
        # constant offset from the image, measured on a screenshot and corrected by DRAW_OFFSET.
        calls = []
        kx1 = 0.
        for port, k in g["knobs"].items():
            cx, cy, r = k["c"][0] * sx, k["c"][1] * sy, k["r"] * sx
            knob_rects[port] = unit_rect(cx - r - 3, cy - r - 3, 2 * r + 6, 2 * r + 6)
            kx1 = max(kx1, cx + r + 2)
            calls.append(f"        cov = max(cov, boPtr(p, {f2(cx, cy)}, {r - 0.45:.3f}, live ? clamp({value[port]}, 0., 1.) : "
                         f"{default[port]:.4f}, sc));")
        # lamp over the active mode (S / T / B), from the polynomial fit of the lit lamp
        lamp = g["lamp"]
        cs = [(c[0] * sx, c[1] * sy) for c in lamp["centres"]]
        R = lamp["r"] * sx
        terms = lamp["terms"]
        chans = []
        for ci in range(3):
            parts = []
            for (i, j), c in zip(terms, lamp["coef"][ci]):
                m = "*".join(["u"] * i + ["v"] * j) or "1."
                parts.append(f"{c:+.5f}*{m}")
            chans.append(" ".join(parts))
        lx0, lx1 = cs[0][0] - 3 * R, cs[2][0] + 3 * R
        ly0, ly1 = cs[0][1] - 3 * R, cs[0][1] + 3 * R
        # transfer curve in the chart (input amplitude a0..a1 -> output b0..b1), dark grey ink
        ch = g["chart"]
        X0, X1, Y0, Y1 = ch["x0"] * sx, ch["x1"] * sx, ch["y0"] * sy, ch["y1"] * sy
        a0, a1, b0, b1 = ch["a0"], ch["a1"], ch["b0"], ch["b1"]
        # zero lines (piecewise-linear axes: the printed -1 and +1 are not always symmetric round 0)
        XZ = ch.get("xz", (ch["x0"] + ch["x1"]) / 2) * sx
        YZ = ch.get("yz", (ch["y0"] + ch["y1"]) / 2) * sy
        az, bz = (a0 + a1) / 2, (b0 + b1) / 2
        disp = (HERE / "bo_sat_display.sksl").read_text()
        funcs = disp[disp.index("// same curve as the DSP"):disp.index("float boLine(")]
        # pointer: from just off the centre to the cap edge over the printed scale's sweep
        # (270 degrees: 0 at 7:30, the middle at 12, 1 at 4:30)
        src = head + funcs + f"""float boPtr(float2 p, float2 c, float len, float v, float sc) {{
    float2 q = p - c;
    if (dot(q, q) > (len + 2.) * (len + 2.)) {{
        return 0.;
    }}
    float a = radians({-sweep / 2:.1f} + {sweep:.1f} * v);
    float2 d = float2(sin(a), -cos(a));
    float2 n = float2(-d.y, d.x);
    float along = dot(q, d);
    float dist = max(max({0.75 * sx:.3f} - along, along - len), abs(dot(q, n)) - {g['bar_half_width'] * sx:.3f});
    return clamp(.5 - dist * sc, 0., 1.);
}}

half4 main(float2 fragCoord) {{
    float sc = iResolution.x / {VW * 4}.;
    float2 p = fragCoord / sc + {f2(*DRAW_OFFSET)};          // panel-image px
    // knob pointers
    if (p.x < {kx1:.1f}) {{
        bool live = audioLow.x > .5;
        float cov = 0.;
""" + "\n".join(calls) + f"""
        return half4(float3({bar[0]:.4f}, {bar[1]:.4f}, {bar[2]:.4f}) * cov, cov);
    }}
    // lamp
    if (p.x > {lx0:.1f} && p.x < {lx1:.1f} && p.y > {ly0:.1f} && p.y < {ly1:.1f}) {{
        float m = floor(audioHigh.z + .5);
        float2 c = m < .5 ? {f2(*cs[0])} : (m < 1.5 ? {f2(*cs[1])} : {f2(*cs[2])});
        float2 q = (p - c) / {R:.4f};
        float u = clamp(q.x, -1., 1.);
        float v = clamp(q.y, -1., 1.);
        float3 lit = clamp(float3({chans[0]}, {chans[1]}, {chans[2]}), 0., 1.);
        float rr = length(q) * {R:.4f};
        float cov = clamp(({R:.4f} - .15 - rr) * sc + .5, 0., 1.);
        // a faint warm halo on the plate
        float halo = .22 * exp(-max(rr - {R:.4f}, 0.) * .9) * (1. - cov);
        return half4(lit * cov + float3(1., .78, .25) * halo, cov + halo);
    }}
    // transfer curve
    if (p.x > {X0 - 2:.1f} && p.x < {X1 + 2:.1f} && p.y > {Y1 - 2:.1f} && p.y < {Y0 + 2:.1f}) {{
        float sX = p.x < {XZ:.4f} ? {(XZ - X0) / (az - a0):.4f} : {(X1 - XZ) / (a1 - az):.4f};    // px per unit
        float sY = p.y > {YZ:.4f} ? {(Y0 - YZ) / (bz - b0):.4f} : {(YZ - Y1) / (b1 - bz):.4f};
        float x = {az:.3f} + (p.x - {XZ:.4f}) / sX;
        float y = {bz:.3f} + ({YZ:.4f} - p.y) / sY;
        float4 st = audioLow;
        float comp = audioMedium.w > 0. ? audioMedium.w : 1.;
        if (st.x < 0.5) {{
            st = float4(1., 4.23, 1., 1.);
        }}
        float xc = clamp(x, {a0:.1f}, {a1:.1f});
        float fy = boTransfer(xc, st, comp);
        float h = .002;
        float slope = (boTransfer(xc + h, st, comp) - boTransfer(xc - h, st, comp)) / (2. * h) * sY / sX;
        float dist = abs(y - fy) * sY / length(float2(1., slope));
        float inside = step({a0:.1f}, x) * step(x, {a1:.1f}) * step({b0:.1f}, y) * step(y, {b1:.1f}) * step(abs(fy), 1.0005);
        float cov = inside * clamp(({0.6:.2f} - dist) * sc + .5, 0., 1.) * .92;
        return half4(float3(.20, .20, .20) * cov, cov);
    }}
    return half4(0.);
}}
"""
        dev.display(src, rect=(0, 0, VW, VH), inputs=["ui_a", "ui_b", "ui_c"], id=f"BO_SAT{tag}_ART", overlay=True)
        return knob_rects

    for D_, k_, tag_ in VARIANTS:
        knob_rects = variant(D_, tag_)

    btn = g["buttons"]
    brect = unit_rect(btn[0] * sx, btn[1] * sy, (btn[2] - btn[0]) * sx, (btn[3] - btn[1]) * sy)
    sw = g.get("switch")
    sc_ = g.get("screw", [441.7, 296.5, 29.0])            # screw head: centre, radius (source px)
    scx, scy, scr = sc_[0] * sx, sc_[1] * sy, sc_[2] * sx
    seg = 5                                               # units per skin button
    screw_rect = (int(round((scx - seg * 2) / 4 - seg)), int(round((scy - scr) / 4)), (2 + len(VARIANTS)) * seg,
                  max(int(round(2 * scr / 4)), 4))
    if sw:
        # the lever down (image 2), shown while Link is on; the background has it up (image 1)
        b2 = io.BytesIO()
        Image.open(D / "switch_on2x.png").save(b2, "PNG", optimize=True)
        uri2 = "data:image/png;base64," + base64.b64encode(b2.getvalue()).decode()
        uw, uh = sw["units"][2] * 4, sw["units"][3] * 4
        dev.images.append((f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" '
                           f'width="{uw}" height="{uh}" viewBox="0 0 {uw} {uh}"><image x="0" y="0" width="{uw}" '
                           f'height="{uh}" href="{uri2}" xlink:href="{uri2}"/></svg>', tuple(sw["units"]), 1000, 1.0, 1.0))
        x0, y0, x1, y1 = sw["box"]
        switch_rect = unit_rect(x0 * sx, y0 * sy, (x1 - x0) * sx, (y1 - y0) * sy)
        # the other skins: their own panel grey over the lever image (images can only follow one
        # condition, so the lever image follows Link in every skin and these hide it)
        # the other skins: their own panel grey over the lever image (images can only follow one
        # condition, so the lever image follows Link in every skin and these hide it). Full-panel
        # drawings with the measured DRAW_OFFSET, as the vintage overlay.
        ux, uy, uw_, uh_ = (v * 4 for v in sw["units"])
        lx = min(x0 * sx, ux) - 3                      # 3 px left of the lever image: fully opaque before its edge
        link_btn = next(q.button for q in dev.params if q.port == "link")
        bx, by, bw, bh = (v * 4 for v in link_btn)
        for n, grey in ((0, BITWIG_PANEL), (1, .60)):
            dev.display(head + f"""half4 main(float2 fragCoord) {{
    float sc = iResolution.x / {VW * 4}.;
    float2 p = fragCoord / sc + {f2(*DRAW_OFFSET)};
    if (p.x > {lx - .5:.2f} && p.x < {ux + uw_ + 1:.1f} && p.y > {uy - 1:.1f} && p.y < {uy + uh_ + 1:.1f}) {{
        float cov = clamp((p.x - {lx - .5:.2f}) * sc, 0., 1.);
        return half4(float3({grey}) * cov, cov);
    }}
    return half4(0.);
}}
""", rect=(0, 0, VW, VH), inputs=["ui_a", "ui_b", "ui_c"], id=f"BO_SAT_LEVER_COVER_{n}", overlay=True)

    def hook(root, base_knobs):
        from bwobj import Obj, Field, Ref
        from bwgen import text, ref, u16, u8, obj, flag, lst, rect as grect
        panel = root.get(0x1845)[0]
        items = panel.get(0x1844).get(0x184D)

        def clone(w):
            fs = []
            for f in w.fields:
                v = f.value
                if isinstance(v, Obj):
                    v = Obj(v.cls, [Field(q.fid, q.type, q.value) for q in v.fields])
                elif isinstance(v, Ref):
                    v = Ref(v.target)
                fs.append(Field(f.fid, f.type, v))
            return Obj(w.cls, fs)

        def drawing_id(w):
            return w.get(0x184C).target.get_str(0x2BD)

        def rule(expr, w, attr):
            return Obj(0x6C0, [text(0x1882, expr), ref(0x1883, w), u16(0x18AC, attr)])
        exprs = list(panel.get(0x1885) or [])
        images = [w for w in items if w.cls == 0x6B7]          # one background per lighting, then the lever
        bgs, lever = images[:len(VARIANTS)], images[len(VARIANTS)] if sw else None
        popup = next(w for w in items if w.cls == 0x6B2)
        mode_param = popup.get(0x184C).target
        toggle = next(w for w in items if w.cls == 0x6A6)
        skin_param = next(c.target for c in root.get(0xAD) if isinstance(c, Ref) and c.target.get_str(0x2BD) == "SKIN")
        for (D_, k_, tag_), image in zip(VARIANTS, bgs):
            # knobs under the image's knobs
            vknobs = []
            for k0 in base_knobs:
                port = k0.get(0x184C).target.get_str(0x2BD).lower()
                k = clone(k0)
                k.field(0x1852).value = grect(*knob_rects[port])
                vknobs.append(k)
            # Mode: three buttons under the S / T / B keys
            group = Obj(0x6B0, [obj(0x1852, grect(*brect)), flag(0x18A5, True), flag(0x18A6, True), text(0x1AD3, "Mode"),
                                text(0x292F, ""), u8(0x2932, 0), ref(0x184C, mode_param), u16(0x2B04, 0x1C9),
                                flag(0x186F, True), u8(0x1870, 0), u8(0x1871, 0), u8(0x1D7C, 0), u16(0x279F, 1)])
            # the screw: invisible skin buttons, the Modern one under the screw head
            screwg = Obj(0x6B0, [obj(0x1852, grect(*screw_rect)), flag(0x18A5, True), flag(0x18A6, True),
                                 text(0x1AD3, "Skin"), text(0x292F, ""), u8(0x2932, 0), ref(0x184C, skin_param),
                                 u16(0x2B04, 0x1C9), flag(0x186F, True), u8(0x1870, 0), u8(0x1871, 0), u8(0x1D7C, 0),
                                 u16(0x279F, 1)])
            ws = vknobs + [group, screwg]
            if sw:
                vlink = clone(toggle)
                vlink.field(0x1852).value = grect(*switch_rect)
                ws.append(vlink)
            i = items.index(bgs[0])
            items[i:i] = ws
            exprs.append(rule(f"SKIN=={k_}", image, 0x18A5))
            for w in ws:
                exprs += [rule(f"SKIN=={k_}", w, 0x18A5), rule(f"SKIN=={k_}", w, 0x18A6)]
            for w in items:
                if w.cls == 0x158D and drawing_id(w).startswith(f"BO_SAT{tag_}_"):
                    exprs.append(rule(f"SKIN=={k_}", w, 0x18A5))
        if sw:
            exprs.append(rule("LINK", lever, 0x18A5))
            for w in items:
                if w.cls == 0x158D and drawing_id(w).startswith("BO_SAT_LEVER_COVER_"):
                    exprs.append(rule(f"SKIN=={drawing_id(w)[-1]}", w, 0x18A5))
        # the Mode menu and the Link button belong to the other skins: one copy for each
        for w in (popup, toggle):
            j = items.index(w)
            copies = [clone(w) for _ in range(2)]
            items[j:j + 1] = copies
            for n, c in enumerate(copies):
                exprs += [rule(f"SKIN=={n}", c, 0x18A5), rule(f"SKIN=={n}", c, 0x18A6)]
            if w is popup:
                # Modern: dark grey text on the light plate (popup_chooser_panel_item.color 0x1D68,
                # float_core.display_color: 12 = dark_grey, 6 = white)
                copies[1].field(0x1D68).value = (12).to_bytes(2, "big")
            if w is toggle:
                # the Link buttons of the other skins sit where the lever is: Bitwig's above the
                # lever covers; Modern's under the images, where its drawn face (link_img) covers it
                for c in copies:
                    items.remove(c)
                # (a nested grid with brightness 4 "bright" around Modern's button was tried: the face
                # stays dark, only the outline changes, so both skins use the plain native button)
                items.extend(copies)
        panel.field(0x1885).value = exprs
        print(f"  vintage: {len(VARIANTS)} lightings, mode buttons {brect}, screw {screw_rect}, {len(exprs)} rules")
    return hook


def bo_sat_test() -> Device:
    dev = Device(
        name="bo Curve",
        creator="bo",
        uuid_key="bo-devices/bo-sat-test",          # stable UUID: projects keep finding the device
        nitro=with_oversampling((HERE / "bo_sat.nitro").read_text()),
        description="Saturator: Drive (clean at 0), Output, Curve (soft to hard), Asym, Tone (180 Hz pre/de-emphasis), Mix, Link",
        tags="saturation distortion clipper drive tone asymmetry",
        panel_size=(89, 55),                         # width from the vintage panel image (89 x 53)                         # 53 = standard Bitwig device height
    )
    # Layout (grid units, 1 unit = 4 px), panel 102 x 55 (the vintage image is 102 x 53); 5 units margin round the knobs:
    # the positions of the vintage panel (satv3):
    #   Curve  Tone  |   < Mode >
    #   Asym   Mix   |    graph (in the chart)
    #   Drive [Link] Output |
    #   Inspector: Oversampling Off / 2x / 4x
    # port name in the Nitro struct, label, min, max, default
    dev.display((HERE / "bo_sat_display.sksl").read_text(), rect=(45, 9, 36, 36),
                inputs=["ui_a", "ui_b", "ui_c"], id="BO_SAT_DRAWING")
    dev.param("curve", "Curve", 0, 100, 25, unit_text="%", knob=(3, 4))
    dev.param("asym", "Asym", -100, 100, 0, unit_text="%", origin=Origin.CENTER, knob=(3, 21))
    dev.param("drive", "Drive", 0, 48, 0, unit_text="dB", domain=Domain.CUBE_ROOT, knob=(3, 37))
    dev.toggle("link", "Link", default=False, button=(15, 40, 6, 4), style=ButtonStyle.BUTTON,
               tooltip="On: saturator, Output linked to Drive (level-matched, ceiling +12 dBFS, clean at Drive 0). "
                       "Off: clipper, Drive is plain gain into a 0 dBFS ceiling")
    dev.param("output", "Output", -24, 12, 0, unit_text="dB", origin=Origin.CENTER, knob=(22, 37))
    dev.param("tone", "Tone", -12, 12, 0, unit_text="dB", origin=Origin.CENTER, knob=(22, 4))
    dev.param("mix", "Mix", 0, 100, 100, unit_text="%", knob=(22, 21))
    dev.choice("mode", "Mode", ["Smooth", "Triode", "Bit"], default=0, rect=(53, 4, 18, 4),
               tooltip="Curve type")
    dev.choice("os", "Oversampling", ["Off", "2x", "4x"], default=0, rect=(1, 1, 24, 4), inspector=True, buttons=True,
               tooltip="Oversampling of the saturation curve")
    dev.prevent_sleep = True                         # the vintage pointers follow the knobs without audio
    skin_hook = add_skins(dev)
    vintage_hook = add_vintage(dev)

    def post(root):
        mode_descriptions(root)
        skin_hook(root)
        vintage_hook(root, skin_hook.knobs)
    dev.post_graph = post
    return dev


if __name__ == "__main__":
    out = HERE / "out" / "bo Curve.bwdevice"
    out.parent.mkdir(exist_ok=True)
    data = bo_sat_test().build()
    out.write_bytes(data)
    print(f"{out.name}: {len(data)} bytes")
