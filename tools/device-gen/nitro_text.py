"""Nitro source in Bitwig's own syntax, from the bitwig-nitro-tools syntax our .nitro files use.

Bitwig 6.1.7 changed the binary form a device can store its Nitro code in (it is now deflate-
compressed), so a binary built for 6.1.4 doesn't load there and the other way round. Source text
loads in both: a device that stores source (is_code_storage_binary_ast = false) is compiled by
Bitwig itself. Bitwig's parser wants a few things written differently from our files:

- the device's top-level `struct name` is `component name`
- ports: `audio_inport f32<2> in;`, not `audio_inport<f32<2>> in;` (event ports with a handler too)
- `value_outport` needs an initial value: `value_outport f32 m = 0.0;`
- no exponent in number literals: 0.000001, not 1e-6 (written as the shortest exact decimal)

Checked with Bitwig 6.1.4's and 6.1.7's own parsers on all the bo devices: each converted source
compiles to the same program as the binary it replaces (bo LUFS / bo Scope: the same apart from a
loop body without its own braces, and the readouts' explicit initial 0).
"""
import re
from decimal import Decimal

_COMPONENT = re.compile(r"^(\s*)struct(\s+\w+)", re.M)
_PORT = re.compile(r"\b((?:audio|value|event)_(?:in|out)port)<(.*)>(\s+\w+\s*;)")
_EVENT_HANDLER = re.compile(r"\b(event_(?:in|out)port)<(.*?)>(\s+\w+)(?=\s*(//[^\n]*)?\n\s*\{)")
_VALUE_OUT = re.compile(r"\b(value_outport\s+(f32|f64|i32|bool)\s+\w+)\s*;")
_INIT = {"f32": "0.0", "f64": "0.0", "i32": "0", "bool": "false"}
_EXP = re.compile(r"(?<![\w.])(\d+)(?:\.(\d+))?[eE]([-+]?\d+)\b")


def _decimal(m):
    x = float(f"{m.group(1)}.{m.group(2) or '0'}e{m.group(3)}")
    t = format(Decimal(repr(x)), "f")          # Python's shortest exact digits, written out in full
    assert float(t) == x
    return t if "." in t else t + ".0"


def to_bitwig(src: str) -> str:
    s = _COMPONENT.sub(r"\1component\2", src, count=1)
    s = _PORT.sub(lambda m: f"{m.group(1)} {m.group(2)}{m.group(3)}", s)
    s = _EVENT_HANDLER.sub(lambda m: f"{m.group(1)} {m.group(2)}{m.group(3)}", s)
    s = _VALUE_OUT.sub(lambda m: f"{m.group(1)} = {_INIT[m.group(2)]};", s)
    return _EXP.sub(_decimal, s)
