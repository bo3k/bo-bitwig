"""Read and patch plain (format 0002) Bitwig .bwdevice files.

Layout, as decoded from the community devices:
    "BtWg" + ascii hex header: version(4) format(4) ????(4) body_offset(8) zeros
        format 0002 = plain, 0004 = encrypted (Bitwig's own devices)
    meta block: u32 4, u32 4 + "meta", then entries
        u32 1, u32 keylen, key, type byte, value      ... terminated by u32 0
    spaces up to body_offset - 1, then "\n"
    body: a stream of fields  u32 field_id, type byte, value
Value types seen: 0x01 u8, 0x03 i32, 0x05 bool(u8), 0x07 f64, 0x08 string(u32 len + utf8),
0x0d bytes(u32 len + data), 0x15 uuid(16 bytes). Big-endian throughout.
The Nitro module sits in field 0x28B5 (type 0x0D): u32 length, then 0x00 + .nitrobin.
"""
from __future__ import annotations

import struct
import uuid as uuidlib

T_U8, T_I32, T_BOOL, T_F64, T_STR, T_BYTES, T_UUID = 0x01, 0x03, 0x05, 0x07, 0x08, 0x0D, 0x15
NITRO_FIELD = 0x28B5


def _enc_value(t, v) -> bytes:
    if t == T_STR:
        b = v.encode("utf-8")
        return struct.pack(">I", len(b)) + b
    if t == T_BOOL or t == T_U8:
        return bytes([int(v)])
    if t == T_I32:
        return struct.pack(">i", v)
    if t == T_F64:
        return struct.pack(">d", v)
    if t == T_UUID:
        return v.bytes
    if t == T_BYTES:
        return struct.pack(">I", len(v)) + v
    raise ValueError(t)


def _dec_value(d, i, t):
    if t == T_STR:
        n = struct.unpack_from(">I", d, i)[0]
        if n & 0x80000000:                      # non-ASCII: UTF-16BE, length in code units
            n = (n & 0x7FFFFFFF) * 2
            return d[i + 4:i + 4 + n].decode("utf-16-be"), i + 4 + n
        return d[i + 4:i + 4 + n].decode("utf-8"), i + 4 + n
    if t in (T_BOOL, T_U8):
        return d[i], i + 1
    if t == T_I32:
        return struct.unpack_from(">i", d, i)[0], i + 4
    if t == T_F64:
        return struct.unpack_from(">d", d, i)[0], i + 8
    if t == T_UUID:
        return uuidlib.UUID(bytes=bytes(d[i:i + 16])), i + 16
    if t == T_BYTES:
        n = struct.unpack_from(">I", d, i)[0]
        return bytes(d[i + 4:i + 4 + n]), i + 4 + n
    raise ValueError(f"unknown meta type {t:#x} at {i}")


class BwDevice:
    def __init__(self, data: bytes):
        if data[:4] != b"BtWg":
            raise ValueError("not a Bitwig document")
        self.header = data[4:42].decode("ascii")
        self.version, self.fmt = self.header[0:4], self.header[4:8]
        if self.fmt != "0002":
            raise ValueError(f"format {self.fmt}: only plain (0002) documents can be edited")
        self.body_offset = int(self.header[12:20], 16)
        # meta
        i = 42
        self.meta_tag = struct.unpack_from(">I", data, i)[0]  # 4 in every file seen
        i += 4
        n = struct.unpack_from(">I", data, i)[0]
        if data[i + 4:i + 4 + n] != b"meta":
            raise ValueError("no meta block")
        i += 4 + n
        self.meta: list[tuple[str, int, object]] = []
        while True:
            marker = struct.unpack_from(">I", data, i)[0]
            i += 4
            if marker == 0:
                break
            kl = struct.unpack_from(">I", data, i)[0]
            key = data[i + 4:i + 4 + kl].decode()
            i += 4 + kl
            t = data[i]
            v, i = _dec_value(data, i + 1, t)
            self.meta.append((key, t, v))
        self.meta_end = i
        if data[self.body_offset - 1:self.body_offset] != b"\n":
            raise ValueError("body separator not found where the header says")
        self.body = bytearray(data[self.body_offset:])

    # ---- meta
    def get_meta(self, key):
        return next(v for k, t, v in self.meta if k == key)

    def set_meta(self, key, value):
        for idx, (k, t, v) in enumerate(self.meta):
            if k == key:
                self.meta[idx] = (k, t, value)
                return
        raise KeyError(key)

    # ---- body fields
    def find_fields(self, fid, t, value=None, start=0, end=None):
        """Offsets (into body) of fields fid/type t, optionally with a given current value."""
        pat = struct.pack(">I", fid) + bytes([t])
        if value is not None:
            pat += _enc_value(t, value)
        out, i = [], start
        stop = len(self.body) if end is None else end
        while True:
            j = self.body.find(pat, i, stop)
            if j < 0:
                return out
            out.append(j)
            i = j + 1

    def replace_field_at(self, off, fid, t, new_value) -> int:
        """Replace the field starting at body offset `off`. Returns the size change."""
        head = struct.pack(">I", fid) + bytes([t])
        if bytes(self.body[off:off + 5]) != head:
            raise ValueError(f"no field {fid:#x}/{t:#x} at {off}")
        _, end = _dec_value(self.body, off + 5, t)
        new = head + _enc_value(t, new_value)
        delta = len(new) - (end - off)
        self.body[off:end] = new
        return delta

    def replace_field(self, fid, t, old, new, expect=1, start=0, end=None):
        offs = self.find_fields(fid, t, old, start, end)
        if len(offs) != expect:
            raise ValueError(f"field {fid:#x} = {old!r}: found {len(offs)}, expected {expect}")
        for off in reversed(offs):
            self.replace_field_at(off, fid, t, new)

    # ---- nitro
    def nitro_modules(self):
        """(offset, nitrobin bytes) for every embedded Nitro module."""
        out = []
        for off in self.find_fields(NITRO_FIELD, T_BYTES):
            blob, _ = _dec_value(self.body, off + 5, T_BYTES)
            if blob[:1] != b"\x00":
                raise ValueError("unexpected Nitro field prefix")
            out.append((off, blob[1:]))
        return out

    def set_nitro_module(self, index, nitrobin: bytes):
        off, _ = self.nitro_modules()[index]
        self.replace_field_at(off, NITRO_FIELD, T_BYTES, b"\x00" + nitrobin)

    # ---- write
    def to_bytes(self) -> bytes:
        meta = bytearray(struct.pack(">II", self.meta_tag, 4) + b"meta")
        for k, t, v in self.meta:
            kb = k.encode()
            meta += struct.pack(">II", 1, len(kb)) + kb + bytes([t]) + _enc_value(t, v)
        meta += struct.pack(">I", 0)
        pre = 42 + len(meta)
        if pre > self.body_offset - 1:
            raise ValueError("meta block grew past the body offset")
        out = bytearray(b"BtWg" + self.header.encode("ascii"))
        out += meta
        out += b" " * (self.body_offset - 1 - pre) + b"\n"
        out += self.body
        return bytes(out)
