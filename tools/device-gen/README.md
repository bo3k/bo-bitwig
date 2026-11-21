# BO native device tools (proof of concept)

Build native Bitwig devices (`.bwdevice`) from Nitro source, for loading with Patchbay's
**Framework: Nitro Devices** on Bitwig 6.1.4.

| File | What |
|---|---|
| `nitroc.py` | Nitro text → AST → `.nitrobin` (and back with `emit`). Covers the subset used by SVF Lab and OTT; both round-trip byte-identically. |
| `bwdevice.py` | Reads and patches plain (format `0002`) `.bwdevice` files: meta block, body fields, embedded Nitro modules. |
| `bwobj.py` | Object-graph reader/writer for the document body; references are renumbered on write. Reproduces all 12 community devices byte-identically. |
| `dumptree.py` | Prints a device's object tree: `python dumptree.py "X.bwdevice"`. |
| `bo_sat.nitro` | DSP of BO Sat Test, including the `ui_a`/`ui_b` display outputs. |
| `bo_sat_display.sksl` | The curve display (SkSL shader). |
| `bwgen.py` | **Device generator.** Builds a complete `.bwdevice` from a description (name, Nitro code, parameters, knobs, displays). No template files. |
| `build_bo_sat.py` | The description of BO Sat Test → `out/BO Sat Test.bwdevice`. |
| `sim_bo_sat.py` | Float32 Python model of the BO Sat Test DSP, for offline checks (tilt inverse, link, stability). |
| `test_generator_matches_v1.py` | Regression test: the generator rebuilds the working v1 device (in `fixtures/`) object for object. |

Needs Python 3.10+ and [bitwig-nitro-tools](https://github.com/blakebratcher/bitwig-nitro-tools)
(`pip install -e .` in its folder, or a checkout named `bitwig-nitro-tools` next to this folder).
Since the generator, no Polarity files are needed to build.

## Nitro syntax notes

- Port element types are explicit: `audio_inport<f32> x;`, `audio_inport<f32<2>> in;`, `audio_outport<f32<2>> out;`
- A block's declarations come first; a declaration after a statement opens an implicit nested block.
- Negative literals are `-x` (unary minus). `cast(expr, f32)`, `EC.samplerate`, `x[0]`.
- Intrinsics seen in working devices: `fmin fmax fabs exp log10 sqrt floor sin cos`.

## Envelope rules learned so far (template era, still true)

- Envelope port names, order and kind must match the struct's ports.
- Parameter IDs (`CUTOFF_HZ`, `Q`, `MODE`, `MIX_PERCENT`) stay as they are; labels, units,
  ranges and defaults can change.
- Each device needs its own UUID (`device_uuid` in meta, field `0x181` in the body, `device_id` string).

## Displays

- A drawing component (class `0x1588`) holds an SkSL fragment shader (field `0x3FBC`,
  `half4 main(float2 fragCoord)`, `iResolution` = view size in pixels, y down).
- Its first input slots become `uniform float4 audioLow`, `audioMedium` (and `audioHigh`);
  each slot connects to a Nitro output port (`audio_outport<f32<4>>`) by component + port index.
- A view widget (class `0x158D`) on the panel shows it; widgets listed earlier are drawn underneath.
- Panel grid: 1 unit = 4 px. Panel size is `0x1858`/`0x1859`; widget rects are x `0x1847`,
  y `0x1848`, w `0x1849`, h `0x184A`.
- Object ids are pre-order positions starting at 1; references must point to earlier objects.
  SVF Lab's own references are off by 2-6, which is why it fails to load as shipped.
- Shader style that is confirmed to work (BO Sat Test): uniforms declared at the top, helper
  functions with parameters, ternaries instead of `else if`, explicit vectors (`float2(6., 6.)`
  rather than `vec - 6.`), no global `const`, names that can't clash (`bo...`). The first
  version of the display (global consts, a no-argument function, `else if`, vector-scalar
  arithmetic) compiled to nothing in Bitwig; which construct was at fault is not yet known
  (the `diag` devices D1-D4 bisect it).

## Writing a device with bwgen

```python
dev = Device(name="My Device", creator="BO", uuid_key="bo-devices/my-device",  # stable UUID from this key
             nitro=Path("my.nitro").read_text(), panel_size=(62, 34))
dev.param("drive", "Drive", 0, 36, 0, unit_text="dB", domain=Domain.CUBE_ROOT, knob=(37, 3))
dev.toggle("link", "Link", default=False, button=(50, 6, 11, 5), tooltip="...")   # value_inport<bool> link;
dev.display(shader_text, rect=(1, 2, 34, 30), inputs=["ui_a", "ui_b"], id="MY_DRAWING")
Path("out/My Device.bwdevice").write_bytes(dev.build())
```

- Toggles: `value_inport<bool>` in Nitro, `dev.toggle(...)` in the description. They become a
  `boolean_value_atom` (class `0x57`) with a `toggle_button_panel_item` (`0x6A6`). Its `style`
  (`0x1860`, enum codes): BUTTON 0 (Bitwig's standard button), DISPLAY_BUTTON 1,
  BORDERLESS_BUTTON 2, INSIDE_DISPLAY 3 (for buttons on a custom display), CHECKBOX 4,
  BORDERLESS_HOVER 5, UNDERLINE 6. Value ports have port-descriptor kind 3, audio-rate ports kind 1.
- Panel widget classes by name (from `bitwig.jar`): `toggle_button_panel_item` 0x6A6; knobs are 0x697.
- Every scalar Nitro input (`audio_inport<f32> x`) needs a `param` with the same port name; the
  stereo `audio_inport<f32<2>>` is the device input, `audio_outport<f32<2>> out` the device output.
- `min`/`max`/`default` are in the units the DSP receives. The generator converts them to the
  parameter's domain, which is how Bitwig stores them.
- Domains (knob response): `LINEAR`, `CUBE_ROOT` (fine control near the bottom), `LOG`/`LOG10`
  (frequencies, min > 0), `DECIBEL`. `origin=Origin.CENTER` starts the knob arc at 0 (bipolar).
- Display inputs are Nitro `audio_outport<f32<4>>` ports; in the shader they arrive as
  `audioLow`, `audioMedium`, `audioHigh` in the order given.
- Knob rects and the panel size are in grid units (1 unit = 4 px).

## Format notes

- Schema names come from `bitwig.jar` (`new MVg(id, "class")`, `new WzZ("field", id)`); a
  partial table is in `schema_names.json`. Parameter value type (class `0x7B`): min `0x124`,
  max `0x125`, default `0x37B`, domain `0x126`, engine_domain `0x127`, value_origin `0xBC6`,
  unit `0x128`, parameter_smoothing `0x1151`, allow_automation_curves `0x1152`.
- Enums are stored by code, not ordinal. Domain codes: LINEAR 0, DECIBEL 1, PITCH 2, LOG10 3,
  RECIPROCAL 4, CUBE_ROOT 5, PITCH_RELATIVE 6, LOG 7. Unit codes: NONE 0, PERCENT 1, DECIBEL 2,
  SECONDS 3, HERTZ 4, SEMITONES 5, ... MILLISECONDS 18.
- Nitro built-ins (from `com/bitwig/nitro/CallExpression`): acos asin atan cbrt ceil cos cosh exp
  exp10 exp2 floor fmax fmin fmod hypot log log10 log2 pow round sin sinh sqrt tan tanh.
  Seen in working devices: exp, log, pow, sqrt, fmin, fmax, fabs, floor, sin, cos, log10.

## Theme greys

`theme_greys.py <device> <in> <out>` makes a built device's greys follow Bitwig's theme (devices: `lufs`, `spectrum`, `scope`, `safety`, `curve`, `magnetic`). A drawing can't read the theme, so each grey is drawn as black or white over what Bitwig draws under it; in the default theme it looks exactly as before. bo Curve's Bitwig skin gets its own themed graph. For the reset buttons, see `reset_buttons.py` in BUILDING.md. Run it on a fresh build: the `.bwdevice` files in this repo are already patched.
