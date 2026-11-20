"""Object-graph reader/writer for the body of a plain Bitwig document.

  object      := u32 class_id, field*, u32 0
  field       := u32 field_id, u8 type, value
  0x09 object := u32 0 (null) | u32 1 + u32 ref | u32 class_id + object body
  0x12 list   := items until u32 3; item = u32 1 + u32 ref | u32 class_id + object body
  0x0b        := u32 reference
Object ids are 1-based positions in pre-order (checked against ~1,900 references
in the community devices). References are resolved to Obj instances on parse and
renumbered on write, so objects can be added, removed and moved freely.
"""
from __future__ import annotations

import copy
import struct

FIXED = {0x01: 1, 0x02: 2, 0x03: 4, 0x04: 8, 0x05: 1, 0x06: 4, 0x07: 8, 0x0A: 0, 0x15: 16, 0x16: 16}
T_U8, T_U16, T_I32, T_BOOL, T_F32, T_F64, T_STR, T_OBJ, T_NULL, T_REF, T_BYTES, T_LIST = (
    0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0D, 0x12)


class Ref:
    """A reference to another object (resolved after parsing)."""
    __slots__ = ("target", "_id")

    def __init__(self, target=None, _id=None):
        self.target, self._id = target, _id

    def __repr__(self):
        return f"Ref({self.target!r})"


class Field:
    __slots__ = ("fid", "type", "value")

    def __init__(self, fid, type_, value):
        self.fid, self.type, self.value = fid, type_, value

    def __repr__(self):
        return f"Field({self.fid:#x}, {self.type:#x}, {self.value!r:.60})"


class Obj:
    def __init__(self, cls, fields=None):
        self.cls = cls
        self.fields: list[Field] = fields or []
        self.oid = None  # pre-order index, set by parse/number

    def __repr__(self):
        return f"<obj #{None if self.oid is None else self.oid + 1} cls {self.cls:#x}>"

    # convenience
    def get(self, fid, default=KeyError):
        for f in self.fields:
            if f.fid == fid:
                return f.value
        if default is KeyError:
            raise KeyError(hex(fid))
        return default

    def field(self, fid) -> Field:
        return next(f for f in self.fields if f.fid == fid)

    def set(self, fid, value):
        self.field(fid).value = value

    def get_str(self, fid):
        return decode_str(self.get(fid))

    def set_str(self, fid, s):
        self.set(fid, encode_str(s))

    def get_int(self, fid):
        f = self.field(fid)
        return int.from_bytes(f.value, "big", signed=(f.type == T_I32))

    def set_int(self, fid, n):
        f = self.field(fid)
        size = FIXED[f.type]
        f.value = n.to_bytes(size, "big", signed=(f.type == T_I32 and n < 0))

    def children(self):
        for f in self.fields:
            if isinstance(f.value, Obj):
                yield f.value
            elif isinstance(f.value, list):
                for x in f.value:
                    if isinstance(x, Obj):
                        yield x


# strings: u32 length, high bit set = UTF-16BE with length in code units
def decode_str(raw: bytes) -> str:
    n = struct.unpack_from(">I", raw, 0)[0]
    if n & 0x80000000:
        return raw[4:].decode("utf-16-be")
    return raw[4:].decode("utf-8")


def encode_str(s: str) -> bytes:
    try:
        b = s.encode("ascii")
        return struct.pack(">I", len(b)) + b
    except UnicodeEncodeError:
        b = s.encode("utf-16-be")
        return struct.pack(">I", 0x80000000 | (len(b) // 2)) + b


# ------------------------------------------------------------------ parse

class _Parser:
    def __init__(self, data: bytes):
        self.d = data
        self.objs: list[Obj] = []
        self.refs: list[Ref] = []

    def u32(self, i):
        return struct.unpack_from(">I", self.d, i)[0]

    def obj(self, i, cls):
        o = Obj(cls)
        o.oid = len(self.objs)
        self.objs.append(o)
        while True:
            fid = self.u32(i)
            i += 4
            if fid == 0:
                return o, i
            t = self.d[i]
            v, i = self.value(i + 1, t, o, fid)
            o.fields.append(Field(fid, t, v))

    def value(self, i, t, o, fid):
        d = self.d
        if t in FIXED:
            n = FIXED[t]
            return bytes(d[i:i + n]), i + n
        if t == T_REF:
            r = Ref(_id=self.u32(i))
            self.refs.append(r)
            return r, i + 4
        if t == T_STR:
            n = self.u32(i)
            size = (n & 0x7FFFFFFF) * 2 if n & 0x80000000 else n
            return bytes(d[i:i + 4 + size]), i + 4 + size
        if t == T_BYTES:
            n = self.u32(i)
            return bytes(d[i + 4:i + 4 + n]), i + 4 + n
        if t == T_OBJ:
            c = self.u32(i)
            i += 4
            if c == 0:
                return None, i
            if c == 1:
                r = Ref(_id=self.u32(i))
                self.refs.append(r)
                return r, i + 4
            return self.obj(i, c)
        if t == T_LIST:
            items = []
            while True:
                c = self.u32(i)
                i += 4
                if c == 3:
                    return items, i
                if c == 1:
                    r = Ref(_id=self.u32(i))
                    self.refs.append(r)
                    items.append(r)
                    i += 4
                    continue
                sub, i = self.obj(i, c)
                items.append(sub)
        raise ValueError(f"unknown type {t:#x} at {i - 1} (field {fid:#x} in {o})")


def parse_body(body: bytes) -> Obj:
    p = _Parser(body)
    root, end = p.obj(4, p.u32(0))
    if end != len(body):
        raise ValueError(f"trailing data after root object: {end} of {len(body)}")
    for r in p.refs:
        r.target = p.objs[r._id - 1]
    return root


# ------------------------------------------------------------------ write

def number(root: Obj) -> list[Obj]:
    """Assign pre-order ids; returns objects in order."""
    order = []

    def walk(o):
        o.oid = len(order)
        order.append(o)
        for f in o.fields:
            if isinstance(f.value, Obj):
                walk(f.value)
            elif isinstance(f.value, list):
                for x in f.value:
                    if isinstance(x, Obj):
                        walk(x)
    walk(root)
    return order


def write_body(root: Obj) -> bytes:
    order = number(root)
    seen = set()
    out = bytearray()

    def ref_id(r: Ref):
        t = r.target
        if t is None or t.oid is None or t.oid >= len(order) or order[t.oid] is not t:
            raise ValueError(f"reference to an object that is not in the document: {t!r}")
        if id(t) not in seen:
            raise ValueError(f"forward reference to {t!r}: Bitwig expects targets to come first")
        return t.oid + 1

    def obj(o: Obj):
        seen.add(id(o))
        out.extend(struct.pack(">I", o.cls))
        for f in o.fields:
            out.extend(struct.pack(">IB", f.fid, f.type))
            v = f.value
            if f.type == T_REF:
                out.extend(struct.pack(">I", ref_id(v)))
            elif f.type == T_OBJ:
                if v is None:
                    out.extend(struct.pack(">I", 0))
                elif isinstance(v, Ref):
                    out.extend(struct.pack(">II", 1, ref_id(v)))
                else:
                    obj(v)
            elif f.type == T_LIST:
                for x in v:
                    if isinstance(x, Ref):
                        out.extend(struct.pack(">II", 1, ref_id(x)))
                    else:
                        obj(x)
                out.extend(struct.pack(">I", 3))
            elif f.type == T_BYTES:
                out.extend(struct.pack(">I", len(v)) + v)
            else:
                out.extend(v)
        out.extend(struct.pack(">I", 0))

    obj(root)
    return bytes(out)


def walk(root: Obj):
    """All objects in pre-order."""
    return number(root)


def clone(o: Obj) -> Obj:
    """Deep copy of an object subtree. References keep pointing at the original
    targets (callers re-point them as needed)."""
    inside = set()

    def mark(x):
        inside.add(id(x))
        for c in x.children():
            mark(c)
    mark(o)
    memo = {}
    for r in walk_refs(o):  # references leaving the subtree keep their target
        if id(r.target) not in inside:
            memo[id(r.target)] = r.target
    return copy.deepcopy(o, memo)


def walk_refs(o: Obj):
    out = []

    def visit(x):
        for f in x.fields:
            vals = f.value if isinstance(f.value, list) else [f.value]
            for v in vals:
                if isinstance(v, Ref):
                    out.append(v)
                elif isinstance(v, Obj):
                    visit(v)
    visit(o)
    return out
