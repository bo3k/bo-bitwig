"""bwgen: build native Bitwig devices (.bwdevice, plain format) from a description.

No template files: every object is created here, with the field layout taken from
working community devices and names from Bitwig's own schema where known.

    dev = Device(name="BO Sat Test", creator="BO", uuid_key="bo-devices/bo-sat-test",
                 nitro=Path("bo_sat.nitro").read_text())
    dev.param("drive", "Drive", 0, 36, 0, unit_text="dB", domain=Domain.CUBE_ROOT, knob=(37, 3))
    dev.display(shader_text, rect=(1, 2, 34, 30), inputs=["ui_a", "ui_b"])
    Path("BO Sat Test.bwdevice").write_bytes(dev.build())

Parameters are wired to Nitro input ports by name; the Nitro struct decides the
port order. The first audio input is the device input, the first audio output
named `out` is the device output; other f32<4> outputs can feed displays.
"""
from __future__ import annotations

import hashlib
import struct
import sys
import uuid as uuidlib
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import bwobj  # noqa: E402
from bwobj import Obj, Field, Ref, encode_str  # noqa: E402
import nitroc  # noqa: E402
import nitro_text  # noqa: E402
from bitwig_nitro import serialize_nitrobin  # noqa: E402


# --------------------------------------------------------------- enums (codes as stored)

class Domain:  # decimal_value_type.domain / engine_domain (enum fW codes)
    LINEAR, DECIBEL, PITCH, LOG10, RECIPROCAL, CUBE_ROOT, PITCH_RELATIVE, LOG, DAMP_COEFFICIENT_60 = range(9)


def to_domain(domain, v):
    """Engine (linear) value -> stored value. Bitwig keeps min/max/value in the
    parameter's domain and converts to engine_domain for the DSP (class fY)."""
    import math
    v = float(v)
    if domain == Domain.LINEAR:
        return v
    if domain == Domain.CUBE_ROOT:
        return math.copysign(abs(v) ** (1.0 / 3.0), v)
    if domain == Domain.DECIBEL:
        return 20.0 * math.log10(abs(v))
    if domain == Domain.LOG:
        return math.log(v)
    if domain == Domain.LOG10:
        return math.log10(v)
    raise NotImplementedError(f"domain {domain}")


class Unit:  # decimal_value_type.unit (enum fX codes)
    NONE, PERCENT, DECIBEL, SECONDS, HERTZ, SEMITONES, OCTAVES, BPM, BEAT_TIME, CENTS = range(10)
    SEMITONES_RELATIVE, RATIO, RATIO_INVERTED, MIDI128, METER, DEGREES, PERCENTAGE_OF_RANGE = range(10, 17)
    MODULE_SEMITONES, MILLISECONDS = 17, 18


class ButtonStyle:  # toggle_button_panel_item.style (enum BrZ codes)
    BUTTON, DISPLAY_BUTTON, BORDERLESS_BUTTON, INSIDE_DISPLAY, CHECKBOX, BORDERLESS_HOVER, UNDERLINE = range(7)


class Origin:  # decimal_value_type.value_origin: where the knob arc starts
    MIN, CENTER = 0, 2


# --------------------------------------------------------------- field helpers

def u8(fid, v): return Field(fid, 0x01, int(v).to_bytes(1, "big"))
def u16(fid, v): return Field(fid, 0x02, int(v).to_bytes(2, "big"))
def i32(fid, v): return Field(fid, 0x03, int(v).to_bytes(4, "big", signed=True))
def flag(fid, v): return Field(fid, 0x05, bytes([1 if v else 0]))
def f64(fid, v): return Field(fid, 0x07, struct.pack(">d", float(v)))
def text(fid, s): return Field(fid, 0x08, encode_str(s))
def obj(fid, o): return Field(fid, 0x09, o)
def null(fid): return Field(fid, 0x0A, b"")
def ref(fid, target): return Field(fid, 0x0B, Ref(target))
def blob(fid, b): return Field(fid, 0x0D, b)
def lst(fid, items): return Field(fid, 0x12, list(items))
def uuidf(fid, u): return Field(fid, 0x15, u.bytes)
def rgba(fid, r, g, b, a): return Field(fid, 0x16, struct.pack(">4f", r, g, b, a))


# --------------------------------------------------------------- object builders

def desktop(x, y, wide=False):           # float_core.desktop_settings (node position on the device canvas)
    # Bitwig accepts either width; i32/u8 is what most devices use, u16/u16 what OTT's drawing uses.
    xs, ys = (u16(0x11, x), u16(0x12, y)) if wide else (i32(0x11, x), u8(0x12, y))
    return Obj(0x11, [xs, ys, rgba(0x13, .5, .5, .5, 1.)])


def editor_state(x, y, connections=(), wide=False):  # float_core.component_editor_state
    return Obj(0xEC, [obj(0x264, desktop(x, y, wide)), lst(0x266, connections), flag(0x267, False)])


def connection(source=None, port=0):     # an input connection: source component (inline Obj or Ref) + its output index
    if source is None:
        f = null(0xF8)
    elif isinstance(source, Ref):
        f = Field(0xF8, 0x0B, source)
    else:
        f = obj(0xF8, source)
    return Obj(0x69, [f, u8(0xF9, port), f64(0x192B, 0.)])


def audio_port(decorated, is_inport):    # float_core.audio_port
    return Obj(0xF2, [text(0x1F2, ""), text(0x1285, ""), text(0x1F3, decorated), flag(0x1F4, is_inport),
                      flag(0x568, False), flag(0x1C06, False), u8(0x174, 3), i32(0x340C, 0)])


def note_port(decorated):                # float_core.note_port
    return Obj(0x3D, [text(0x1F2, ""), text(0x1285, ""), text(0x1F3, decorated), flag(0x1F4, False),
                      flag(0x568, False), flag(0x1C06, False), i32(0x340E, 0)])


def proxy_in(port_obj, x, y):            # float_core.proxy_in_port_component (device input inside the graph)
    return Obj(0x9A, [obj(0x1832, editor_state(x, y)), obj(0x12D, port_obj)])


def proxy_out(port_obj, x, y, connections):  # device output inside the graph
    return Obj(0x32, [obj(0x1832, editor_state(x, y, connections)), obj(0x12D, port_obj)])


def port_descriptor(name, channels, index, is_input, kind=1):  # Nitro port as seen by the device graph
    # kind: 1 = audio-rate port, 3 = value port (e.g. value_inport<bool>)
    return Obj(0x97C, [text(0x216D, name), text(0x216E, ""), u8(0x216F, channels), u8(0x2170, index),
                       u8(0x2171, kind), flag(0x2172, is_input), flag(0x2757, False), i32(0x2173, 0xFFFFFF),
                       flag(0x2174, False), u8(0x2175, 0), text(0x2176, "")])


def rect(x, y, w, h):
    return Obj(0x69E, [u16(0x1847, x), u16(0x1848, y), u16(0x1849, w), u16(0x184A, h)])


# --------------------------------------------------------------- description

@dataclass
class Param:
    port: str
    label: str
    min: float
    max: float
    default: float
    unit_text: str = ""
    unit: int = Unit.NONE
    domain: int = Domain.LINEAR
    engine_domain: int = Domain.LINEAR
    origin: int = Origin.MIN
    knob: tuple = (0, 0, 11, 11)
    id: str = ""
    knob_size: int = None        # per-knob override of Device.knob_size (1 small, 2 medium, 3 large)


@dataclass
class Toggle:
    port: str
    label: str
    default: bool
    button: tuple = (0, 0, 11, 5)
    tooltip: str = ""
    id: str = ""
    style: int = 0


@dataclass
class Choice:
    port: str
    label: str
    options: list
    default: int = 0
    rect: tuple = (0, 0, 20, 5)
    tooltip: str = ""
    id: str = ""
    inspector: bool = False   # True: shown in the Inspector panel instead of the device panel
    buttons: bool = False     # True: Bitwig's segmented button group (button_group_chooser_panel_item)
    color: int = 1            # button group highlight (enum AlY codes): 0 red, 1 orange, 6 white, 999 default


@dataclass
class Display:
    shader: str
    rect: tuple
    inputs: list
    id: str
    overlay: bool = False    # True: listed after the controls, i.e. drawn on top of them


@dataclass
class Device:
    name: str
    creator: str
    uuid_key: str
    nitro: str
    category: str = "Audio FX"
    description: str = ""
    tags: str = ""
    panel_size: tuple = (62, 34)
    params: list = field(default_factory=list)
    displays: list = field(default_factory=list)
    inspector_size: tuple = (40, 12)
    # look (None = Bitwig default, output unchanged):
    color_tint: tuple = None     # device_contents.color_tint rgba, e.g. (0.86, 0.82, 0.70, 1.0)
    brightness: int = None       # grid_panel_item.brightness (enum Hfk codes): 0 DARK, 1 NORMAL, 2 BRIGHTER, 3 DISPLAY, 4 BRIGHT, 5 DARKER
    revision: int = 1             # metadata revision_no: raise it with each released change
    knob_style: int = None       # knob_panel_item.knob_style (enum nVz): 0 NORMAL, 1 LIGHTER, 2 LIGHTEST, 3 DARKEST
    knob_pie_color: int = None   # knob_panel_item.pie_color (enum AlY): 0 red, 1 orange, 6 white, 999 default
    label_color: int = None      # knob_panel_item.label_color (enum AlY)
    knob_size: int = 2           # knob_panel_item.knob_size: 1 small, 2 medium, 3 large
    prevent_sleep: bool = False  # device_contents.prevent_sleep: keep processing (and feeding displays) when silent
    labels: list = field(default_factory=list)
    # image_panel_item (class 0x6B7, names from bitwig.jar): (svg_text, (x, y, w, h), color AlY, scale, opacity)
    # drawn first (under everything). color 1000 = IMAGE (own colours), 6 = white (the default)
    images: list = field(default_factory=list)
    images_above_controls: bool = False
    # compact view: if set, the panel built from everything becomes device_contents.maximized_panel
    # (Bitwig's expand button in the device header) and compact(widgets) -> ((w, h), widgets) builds
    # the normal, smaller panel. compact_shown: start expanded (is_maximized_panel_shown).
    compact: object = None
    expanded_by_default: bool = True
    post_graph: object = None
    compact_mode: str = "maximized"   # "maximized": wide panel = maximized_panel (opens in a window);
                                      # "panels": both panels only in device_contents.panels (test)   # True: images cover the Bitwig controls (overlays still on top)   # (text, (x, y, w, h), color AlY, h_align) label_panel_items

    def param(self, port, label, min, max, default, knob=(0, 0), **kw):
        k = tuple(knob) + (11, 11) if len(knob) == 2 else tuple(knob)
        self.params.append(Param(port, label, min, max, default, knob=k, id=kw.pop("id", port.upper()), **kw))

    def toggle(self, port, label, default=False, button=(0, 0, 11, 5), tooltip="", id=None,
               style=ButtonStyle.BUTTON):
        """On/off parameter (boolean_value_atom) shown as a labelled toggle_button_panel_item;
        feeds value_inport<bool>. style: ButtonStyle.BUTTON is Bitwig's standard button."""
        self.params.append(Toggle(port, label, bool(default), tuple(button), tooltip, id or port.upper(), style))

    def choice(self, port, label, options, default=0, rect=(0, 0, 20, 5), tooltip="", id=None,
               inspector=False, buttons=False):
        """Choice parameter (indexed_value_atom) shown as a popup chooser; feeds value_inport<i32>.
        inspector=True puts it in the device's Inspector panel (device field inspector_panel)."""
        self.params.append(Choice(port, label, list(options), int(default), tuple(rect), tooltip,
                                  id or port.upper(), inspector, buttons))

    def display(self, shader, rect, inputs, id=None, overlay=False):
        self.displays.append(Display(shader, tuple(rect), list(inputs), id or f"DRAWING_{len(self.displays)}",
                                     overlay))

    @property
    def uuid(self):
        return uuidlib.uuid5(uuidlib.NAMESPACE_URL, self.uuid_key)

    # ----------------------------------------------------------- build
    def build(self) -> bytes:
        nitrobin, ports = compile_nitro(self.nitro)
        root = self.graph(nitrobin, ports)
        if self.post_graph is not None:     # experiments: edit the object tree before writing
            self.post_graph(root)
        body = bwobj.write_body(root)
        meta = [
            ("application_version_name", "s", "6.1.3"), ("branch", "s", ""), ("comment", "s", ""),
            ("creator", "s", self.creator), ("device_category", "s", self.category),
            ("device_creator", "s", self.creator), ("device_description", "s", self.description),
            ("device_id", "s", str(self.uuid)), ("device_name", "s", self.name),
            ("device_type", "s", "audio_to_audio"), ("device_uuid", "u", self.uuid),
            ("has_audio_input", "b", True), ("has_audio_output", "b", True),
            ("has_note_input", "b", False), ("has_note_output", "b", False),
            ("preset_name", "s", self.name), ("revision_id", "s", hashlib.sha1(body).hexdigest()),
            ("revision_no", "i", self.revision), ("suggest_for_audio_input", "b", True),
            ("suggest_for_note_input", "b", False), ("tags", "s", self.tags),
            ("type", "s", "application/bitwig-device"),
        ]
        return document(meta, body)

    def graph(self, nitrobin, ports) -> Obj:
        ins = [p for p in ports if p["input"]]
        outs = [p for p in ports if not p["input"]]
        audio_in = next(p for p in ins if p["audio"] and p["channels"] == 2)
        audio_out = next(p for p in outs if p["name"] == "out")
        by_port = {p.port: p for p in self.params}
        missing = [p["name"] for p in ins if p is not audio_in and p["name"] not in by_port]
        if missing:
            raise ValueError(f"Nitro inputs without a parameter: {missing}")
        unknown = set(by_port) - {p["name"] for p in ins}
        if unknown:
            raise ValueError(f"parameters without a Nitro input: {sorted(unknown)}")

        # device input terminal and parameters, inline in the Nitro module's input list
        device_in = proxy_in(audio_port(" Audio out (PARENT)", False), 0, 80)
        param_objs = {}
        conns = []
        n = 0
        for p in ins:
            if p is audio_in:
                conns.append(connection(device_in, 0))
                continue
            spec = by_port[p["name"]]
            param_objs[spec.port] = self.param_obj(spec, 72 + 56 * n, 160)
            n += 1
            conns.append(connection(param_objs[spec.port], 0))

        nitro = Obj(0x6B9, [
            obj(0x1832, editor_state(392, 80, conns)),
            u8(0x25F, 0), u8(0x260, 0), u8(0x19A7, 0), lst(0x26F4, []),
            lst(0x3D8C, [port_descriptor(p["name"], p["channels"], i, True, p["kind"]) for i, p in enumerate(ins)]),
            lst(0x3D8D, [port_descriptor(p["name"], p["channels"], i, False, p["kind"]) for i, p in enumerate(outs)]),
            # the code as source in Bitwig's syntax (codec 0, plain text), not the binary AST: Bitwig
            # compiles it itself, and source loads in 6.1.4 and 6.1.7 alike (6.1.7 changed the binary)
            blob(0x28B5, b"\x00" + nitro_text.to_bitwig(self.nitro).encode("utf-8")),
            flag(0x3918, False), flag(0x2C60, False), text(0x2C5D, ""),   # is_code_storage_binary_ast
        ])

        out_index = {p["name"]: i for i, p in enumerate(outs)}
        drawings = []
        for d in self.displays:
            slots = [connection(Ref(nitro), out_index[name]) for name in d.inputs]
            slots += [connection(None) for _ in range(9 - len(slots))]
            drawings.append(Obj(0x1588, [
                obj(0x1832, editor_state(0, 0, slots, wide=True)),
                u8(0x25F, 0), text(0x176, ""), flag(0x2AFA, False), text(0x2BD, d.id), text(0x3FBC, d.shader),
            ]))

        children = [nitro] + [Ref(param_objs[p.port]) for p in self.params] + drawings

        widgets = []
        image_items = []
        for svg, r, color, scale, opacity in self.images:
            image_items.append(Obj(0x6B7, [obj(0x1852, rect(*r)), flag(0x18A5, True), flag(0x18A6, True),
                                       text(0x1AD3, ""), text(0x1872, ""), text(0x1873, svg),
                                       u16(0x1979, color), f64(0x1A6F, scale), f64(0x1A70, opacity)]))
        if not self.images_above_controls:
            widgets += image_items
        inspector_widgets = []
        overlays = []
        for d, comp in zip(self.displays, drawings):
            (overlays if d.overlay else widgets).append(Obj(0x158D, [obj(0x1852, rect(*d.rect)), flag(0x18A5, True), flag(0x18A6, True),
                                        text(0x1AD3, ""), text(0x292F, ""), u8(0x2932, 0), ref(0x184C, comp)]))
        for p in self.params:
            if isinstance(p, Choice):
                items = [Obj(0x693, [obj(0x1852, rect(p.rect[0], p.rect[1], p.rect[2], 4)), text(0x184B, p.label),
                                     u16(0x1978, 6), u8(0x1900, 1), u8(0x1901, 1)])] if p.inspector else []
                cy = p.rect[1] + (4 if p.inspector else 0)
                if p.buttons:            # field layout as in Polarity's Rivet / FILAR
                    items.append(Obj(0x6B0, [obj(0x1852, rect(p.rect[0], cy, p.rect[2], p.rect[3])),
                                             flag(0x18A5, True), flag(0x18A6, True), text(0x1AD3, p.tooltip),
                                             text(0x292F, ""), u8(0x2932, 0), ref(0x184C, param_objs[p.port]),
                                             u16(0x2B04, 0x1C9), flag(0x186F, True), u8(0x1870, 0),
                                             u8(0x1871, 0), u8(0x1D7C, 0), u16(0x279F, p.color)]))
                    (inspector_widgets if p.inspector else widgets).extend(items)
                    continue
                items.append(Obj(0x6B2, [obj(0x1852, rect(p.rect[0], cy, p.rect[2], p.rect[3])),
                                         ref(0x184C, param_objs[p.port]), u16(0x2B04, 0x1C9), u8(0x1876, 0),
                                         # 0x187A: 0 = boxed menu with arrow (as Rivet), 7 = plain text (Prism, OTT)
                                         u8(0x187A, 0), u16(0x1D68, 6), text(0x1AD3, p.tooltip)]))
                (inspector_widgets if p.inspector else widgets).extend(items)
                continue
            if isinstance(p, Toggle):
                widgets.append(Obj(0x6A6, [obj(0x1852, rect(*p.button)), flag(0x18A5, True), flag(0x18A6, True),
                                           text(0x1AD3, p.tooltip), ref(0x184C, param_objs[p.port]),
                                           text(0x1861, p.label), text(0x185F, ""), i32(0x1860, p.style), i32(0x187B, 0),
                                           flag(0x1998, False), flag(0x1A3F, False), i32(0x1D67, 999),
                                           i32(0x1D6A, 0)]))
                continue
            kf = [obj(0x1852, rect(*p.knob)), flag(0x18A5, True), flag(0x18A6, True),
                  text(0x1AD3, ""), ref(0x184C, param_objs[p.port]), text(0x1861, p.label),
                  u8(0x184F, p.knob_size or self.knob_size), u16(0x2B04, 255)]
            if self.knob_style is not None:
                kf.append(u8(0x1B8F, self.knob_style))
            if self.label_color is not None:
                kf.append(u16(0x1B90, self.label_color))
            if self.knob_pie_color is not None:
                kf.append(u16(0x1B91, self.knob_pie_color))
            widgets.append(Obj(0x697, kf))
        for txt, r, color, align in self.labels:          # free text labels, drawn above everything
            overlays.append(Obj(0x693, [obj(0x1852, rect(*r)), text(0x184B, txt), u16(0x1978, color),
                                        u8(0x1900, align), u8(0x1901, 1)]))
        if self.images_above_controls:
            widgets += image_items
        widgets += overlays
        bright = 1 if self.brightness is None else self.brightness
        layout = Obj(0x691, [null(0x1852), lst(0x184D, widgets), u8(0x1854, 0), text(0x1862, ""),
                             flag(0x1863, False), u8(0x197A, 6), u8(0x1865, bright)])
        panel = Obj(0x690, [text(0x1843, "Main"), obj(0x1844, layout), u16(0x1858, self.panel_size[0]),
                            u8(0x1859, self.panel_size[1]), lst(0x1885, [])])
        maximized = null(0x1D7D)
        panels = [panel]
        if self.compact is not None:
            (cw, ch), cwidgets = self.compact(widgets)
            clayout = Obj(0x691, [null(0x1852), lst(0x184D, cwidgets), u8(0x1854, 0), text(0x1862, ""),
                                  flag(0x1863, False), u8(0x197A, 6), u8(0x1865, bright)])
            # both panels live in device_contents.panels; maximized_panel refers to one of them
            # (as Multiband FX-4 does for its inspector panel)
            expanded = Obj(0x690, [text(0x1843, "Expanded"), obj(0x1844, layout),
                                   u16(0x1858, self.panel_size[0]), u8(0x1859, self.panel_size[1]),
                                   lst(0x1885, []), flag(0x28A4, False), flag(0x28A5, False)])
            panel = Obj(0x690, [text(0x1843, "Main"), obj(0x1844, clayout), u16(0x1858, cw),
                                u8(0x1859, ch), lst(0x1885, [])])
            panels = [panel, expanded]
            if self.compact_mode == "maximized":
                maximized = ref(0x1D7D, expanded)

        inspector = null(0x265C)
        if inspector_widgets:
            ilayout = Obj(0x691, [null(0x1852), lst(0x184D, inspector_widgets), u8(0x1854, 0), text(0x1862, ""),
                                  flag(0x1863, False), u8(0x197A, 6), u8(0x1865, 1)])
            # the Inspector panel lives in device_contents.panels and inspector_panel refers to it, as in
            # Multiband FX-4; embedded inline it shows its controls but they never reach the device
            ipanel = Obj(0x690, [text(0x1843, "Inspector"), obj(0x1844, ilayout),
                                 u16(0x1858, self.inspector_size[0]),
                                 u8(0x1859, self.inspector_size[1]), lst(0x1885, [])])
            panels.append(ipanel)
            inspector = ref(0x265C, ipanel)

        note_in = proxy_in(note_port(" Note out"), 0, 0)
        device_out = proxy_out(audio_port(" Audio in (PARENT)", True), 640, 80,
                               [connection(Ref(nitro), out_index[audio_out["name"]])])

        return Obj(0x97, [
            lst(0xAD, children), lst(0x1845, panels),
            lst(0xB1, [Ref(device_in), note_in]), lst(0xB2, [device_out]),
            uuidf(0x181, self.uuid), text(0x182, self.name), text(0x809, self.description),
            text(0x183, self.creator), text(0x186, self.category),
            flag(0x12EF, True), flag(0x12EE, False), null(0x1832), u8(0x19A6, 0), u8(0x2B27, 11), u8(0x2B66, 1),
            null(0x1AB2), inspector, u8(0x354F, 1), f64(0x7B9, -96.), f64(0x7BA, .05), flag(0x3910, self.prevent_sleep),
            text(0x184, ""), text(0x185, self.tags), u8(0x187, 0), flag(0x188, True), rgba(0x342A, *(self.color_tint or (0, 0, 0, 0))),
            null(0x1911), maximized, null(0x29D9), null(0x29E7), null(0x2AF0), flag(0x29A7, True),
        ] + ([flag(0x25B9, self.expanded_by_default)] if self.compact is not None and self.compact_mode == "maximized" else []))

    @staticmethod
    def param_obj(p, x, y) -> Obj:
        if isinstance(p, Choice):              # float_core.indexed_value_atom (as in Polarity's Normalizer)
            items = [Obj(0x102, [u8(0x28A, i), text(0x28B, name), text(0x265F, ""), text(0x2660, ""),
                                 flag(0x2A2B, False)]) for i, name in enumerate(p.options)]
            value_type = Obj(0x9B, [lst(0x189, items), u8(0x1B2E, p.default), flag(0x411B, False)])
            return Obj(0xB4, [obj(0x1832, editor_state(x, y)), u8(0x25F, 0), u8(0x260, 0),
                              text(0x2BD, p.id), text(0x176, p.label), text(0x335, p.label), flag(0x2941, False),
                              text(0xEF2, ""), obj(0x2BE, value_type), flag(0x1D41, False), flag(0x1D42, False),
                              u8(0x1C9, p.default)])
        if isinstance(p, Toggle):              # float_core.boolean_value_atom
            value_type = Obj(0xC6, [text(0x1293, ""), flag(0x1B2D, p.default)])
            return Obj(0x57, [obj(0x1832, editor_state(x, y)), u8(0x25F, 0), u8(0x260, 0),
                              text(0x2BD, p.id), text(0x176, p.label), text(0x335, ""), text(0xEF2, ""),
                              obj(0x2BE, value_type), flag(0x1D41, True), flag(0x1D42, False),
                              flag(0xD2, p.default)])
        return Device.decimal_obj(p, x, y)

    @staticmethod
    def decimal_obj(p: Param, x, y) -> Obj:   # float_core.decimal_value_atom
        # min/max/default are given in engine units; stored in the parameter's domain
        lo, hi, dflt = (to_domain(p.domain, v) for v in (p.min, p.max, p.default))
        if p.engine_domain != Domain.LINEAR:
            lo, hi, dflt = p.min, p.max, p.default   # engine works in the domain itself: store as given
        value_type = Obj(0x7B, [f64(0x124, lo), f64(0x125, hi), f64(0x37B, dflt),
                                u8(0x126, p.domain), u8(0x127, p.engine_domain), u8(0xBC6, p.origin),
                                f64(0x7C4, -1.), u8(0x128, p.unit), flag(0x1151, False), flag(0x1152, True)])
        return Obj(0x121, [obj(0x1832, editor_state(x, y)), u8(0x25F, 0), u8(0x260, 0),
                           text(0x176, p.label), text(0x335, p.unit_text), text(0x2BD, p.id), text(0xEF2, ""),
                           obj(0x2BE, value_type), f64(0x2C8, dflt), flag(0x1D41, True), flag(0x1D42, True)])


# --------------------------------------------------------------- Nitro ports

def compile_nitro(source: str):
    ast = nitroc.parse(source)
    comp = ast.children[0][0]
    ports = []
    for m in comp.children[1]:
        if m.kind != "PortDeclaration":
            continue
        ptype, name = m.children[0], m.children[1]
        inner = ptype.children[0]
        if inner is None:
            channels = 2
        elif inner.kind == "VectorType":
            width = inner.children[1]
            channels = 2 if width is None else width.children[1]
        else:
            channels = 1
        kind = 1 if "Audio" in ptype.kind else (2 if "Event" in ptype.kind else 3)
        ports.append({"name": name, "input": ptype.kind.startswith("Input"),
                      "audio": "Audio" in ptype.kind, "channels": channels, "kind": kind})
    return serialize_nitrobin(ast), ports


# --------------------------------------------------------------- document

BODY_OFFSET = 0x16ED


def document(meta, body: bytes) -> bytes:
    m = bytearray(struct.pack(">II", 4, 4) + b"meta")
    for key, kind, value in meta:
        kb = key.encode()
        m += struct.pack(">II", 1, len(kb)) + kb
        if kind == "s":
            m += b"\x08" + encode_str(value)
        elif kind == "b":
            m += b"\x05" + bytes([1 if value else 0])
        elif kind == "i":
            m += b"\x03" + struct.pack(">i", value)
        elif kind == "u":
            m += b"\x15" + value.bytes
    m += struct.pack(">I", 0)
    header = b"BtWg" + b"0003" + b"0002" + b"00cf" + f"{BODY_OFFSET:08x}".encode() + b"0" * 18
    pad = BODY_OFFSET - 1 - len(header) - len(m)
    if pad < 0:
        raise ValueError("meta block too large")
    return bytes(header + m + b" " * pad + b"\n" + body)
