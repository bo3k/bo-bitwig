"""nitroc: a small Nitro text compiler/decompiler on top of bitwig-nitro-tools.

parse(text)  -> AstNode   (Nitro-style source -> AST the builder/writer understand)
emit(ast)    -> str       (AST -> the same source syntax; round-trips through parse)

Covers the subset used by plain community devices: one component with ports,
member variables (optionally const), functions, reset/process blocks, nested
blocks, if/else, assignment, return, the usual operators, calls, member access,
array indexing and cast(expr, type).

Port types are written explicitly, because the AST keeps them and the stock
pretty-printer drops them:
    audio_inport in;                  // no element type (the device's audio input)
    audio_inport<f32> cutoff_hz;      // scalar control-rate audio port
    audio_inport<f32<2>> input_lr;    // stereo vector
    value_inport<f32> band;
    audio_outport out;
Optional port default:  audio_inport<f32> depth = 20.0;

Block semantics follow the AST: a block holds its declarations first, then its
statements. A declaration written after a statement starts an implicit nested
block, so source order is preserved.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

try:  # installed with `pip install -e bitwig-nitro-tools`
    import bitwig_nitro  # noqa: F401
except ImportError:  # or a checkout next to this folder / named in BITWIG_NITRO_TOOLS
    import os
    for _cand in (os.environ.get("BITWIG_NITRO_TOOLS"),
                  Path(__file__).resolve().parent.parent / "bitwig-nitro-tools",
                  Path(__file__).resolve().parent.parent / "blakebratcher" / "bitwig-nitro-tools"):
        if _cand and (Path(_cand) / "src" / "bitwig_nitro").is_dir():
            sys.path.insert(0, str(Path(_cand) / "src"))
            break

from bitwig_nitro import nitro_builder as nb  # noqa: E402
from bitwig_nitro.nitrobin_decompiler import AstNode  # noqa: E402


def _snake(name: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


def B(kind: str, *children, decl_suffix=None) -> AstNode:
    fn = getattr(nb, _snake(kind))
    if decl_suffix is not None:
        return fn(*children, decl_suffix=decl_suffix)
    return fn(*children)


# ---------------------------------------------------------------- tokenizer

_TOKEN = re.compile(
    r"""
    (?P<ws>\s+|//[^\n]*|/\*.*?\*/)
  | (?P<num>(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?)
  | (?P<id>[A-Za-z_][A-Za-z0-9_]*)
  | (?P<op>>>>|\+\+|--|<<=|>>=|<<|>>|<=|>=|==|!=|&&|\|\||[-+*/%<>=!&|^(){}\[\];,.?:])
    """,
    re.S | re.X,
)


class Tok:
    __slots__ = ("kind", "text", "pos")

    def __init__(self, kind, text, pos):
        self.kind, self.text, self.pos = kind, text, pos

    def __repr__(self):
        return f"{self.kind}:{self.text}"


def tokenize(src: str) -> list[Tok]:
    out, pos = [], 0
    while pos < len(src):
        m = _TOKEN.match(src, pos)
        if not m:
            raise SyntaxError(f"bad character {src[pos]!r} at {_where(src, pos)}")
        kind = m.lastgroup
        if kind != "ws":
            out.append(Tok(kind, m.group(), pos))
        pos = m.end()
    out.append(Tok("eof", "", pos))
    return out


def _where(src, pos):
    line = src.count("\n", 0, pos) + 1
    col = pos - (src.rfind("\n", 0, pos) + 1) + 1
    return f"line {line}, col {col}"


# ---------------------------------------------------------------- parser

SCALARS = {
    "f32": "Float32Type", "f64": "Float64Type",
    "i8": "Int8Type", "i16": "Int16Type", "i32": "Int32Type", "i64": "Int64Type",
    "u8": "UInt8Type", "u16": "UInt16Type", "u32": "UInt32Type", "u64": "UInt64Type",
    "bool": "BooleanType", "void": "VoidType",
}
PORTS = {
    "audio_inport": "InputAudioPortType", "audio_outport": "OutputAudioPortType",
    "value_inport": "InputValuePortType", "value_outport": "OutputValuePortType",
}
BINOPS = [  # lowest precedence first
    {"||": "OrOrExpression"},
    {"&&": "AndAndExpression"},
    {"|": "OrExpression"},
    {"^": "XorExpression"},
    {"&": "AndExpression"},
    {"==": "EqExpression", "!=": "NEqExpression"},
    {"<": "LTExpression", "<=": "LEqExpression", ">": "GTExpression", ">=": "GEqExpression"},
    {"<<": "LShiftExpression", ">>": "ARShiftExpression", ">>>": "LRShiftExpression"},
    {"+": "AddExpression", "-": "SubExpression"},
    {"*": "MulExpression", "/": "DivExpression", "%": "ModExpression"},
]


class Parser:
    def __init__(self, src: str):
        self.src = src
        self.toks = tokenize(src)
        self.i = 0

    # -- helpers
    @property
    def t(self) -> Tok:
        return self.toks[self.i]

    def peek(self, k=1) -> Tok:
        return self.toks[min(self.i + k, len(self.toks) - 1)]

    def next(self) -> Tok:
        tok = self.toks[self.i]
        self.i += 1
        return tok

    def accept(self, text) -> bool:
        if self.t.text == text and self.t.kind != "eof":
            self.i += 1
            return True
        return False

    def expect(self, text) -> Tok:
        if self.t.text != text:
            self.fail(f"expected {text!r}, got {self.t.text!r}")
        return self.next()

    def ident(self) -> str:
        if self.t.kind != "id":
            self.fail(f"expected a name, got {self.t.text!r}")
        return self.next().text

    def fail(self, msg):
        raise SyntaxError(f"{msg} at {_where(self.src, self.t.pos)}")

    def at_type(self) -> bool:
        return self.t.kind == "id" and self.t.text in SCALARS

    # -- types
    def type_(self) -> AstNode:
        name = self.ident()
        if name not in SCALARS:
            self.fail(f"unknown type {name!r}")
        base = B(SCALARS[name])
        if self.t.text == "[":              # fixed-size array, e.g. f32[8]
            self.next()
            n = self.next()
            if n.kind != "num":
                self.fail("expected array length")
            self.expect("]")
            return B("ArrayType", base, B("IntegerValueExpression", B("IntegerLiteralType"), int(n.text)))
        if self.t.text == "<" :
            self.next()
            if self.accept(">"):
                return B("VectorType", base, None)
            n = self.next()
            if n.kind != "num":
                self.fail("expected vector width")
            self.expect(">")
            return B("VectorType", base, B("IntegerValueExpression", B("IntegerLiteralType"), int(n.text)))
        return base

    # -- file / component
    def file(self) -> AstNode:
        decls = []
        while self.t.kind != "eof":
            decls.append(self.component())
        return B("NitroFile", decls)

    def component(self) -> AstNode:
        kw = self.ident()
        if kw != "struct":
            self.fail("expected 'struct'")
        name = self.ident()
        self.expect("{")
        members = []
        while not self.accept("}"):
            members.append(self.member())
        return B("ComponentDeclaration", name, members, decl_suffix=[])

    def member(self) -> AstNode:
        t = self.t
        if t.text in PORTS:
            return self.port()
        if t.text in ("reset", "process"):
            self.next()
            kind = "ResetBlockDeclaration" if t.text == "reset" else "ProcessBlockDeclaration"
            return B(kind, self.block(), decl_suffix=[])
        if t.text == "const" or self.at_type():
            # function or variable
            save = self.i
            mods = []
            while self.accept("const"):
                mods.append(B("ConstSpecifier"))
            ty = self.type_()
            name = self.ident()
            if self.t.text == "(" and not mods:
                return self.function(ty, name)
            self.i = save
            return self.var_decl()
        self.fail(f"unexpected {t.text!r} in struct")

    def port(self) -> AstNode:
        kw = self.ident()
        kind = PORTS[kw]
        inner = None
        if self.accept("<"):
            inner = self._port_inner_type()
        if kind in ("InputValuePortType", "OutputValuePortType") and inner is None:
            self.fail(f"{kw} needs an element type, e.g. {kw}<f32>")
        name = self.ident()
        default = None
        if self.accept("="):
            default = self.expr()
        self.expect(";")
        return B("PortDeclaration", B(kind, inner), name, None, "", None, default, decl_suffix=[])

    def _port_inner_type(self) -> AstNode:
        # handles the '>>' token in audio_inport<f32<2>>
        name = self.ident()
        base = B(SCALARS[name])
        if self.t.text == "<":
            self.next()
            if self.t.text == ">>":
                self.next()
                return B("VectorType", base, None)
            if self.accept(">"):
                vec = B("VectorType", base, None)
            else:
                n = self.next()
                width = B("IntegerValueExpression", B("IntegerLiteralType"), int(n.text))
                vec = B("VectorType", base, width)
                if self.t.text == ">>":
                    self.next()
                    return vec
                self.expect(">")
            self.expect(">")
            return vec
        self.expect(">")
        return base

    def function(self, ret, name) -> AstNode:
        self.expect("(")
        args = []
        if not self.accept(")"):
            while True:
                aty = self.type_()
                aname = self.ident()
                args.append(B("FunctionArgumentDeclaration", aty, aname, decl_suffix=[]))
                if self.accept(")"):
                    break
                self.expect(",")
        body = self.block()
        return B("FunctionDeclaration", name, ret, args, body, False, decl_suffix=[])

    def var_decl(self) -> AstNode:
        mods = []
        while self.accept("const"):
            mods.append(B("ConstSpecifier"))
        ty = self.type_()
        name = self.ident()
        init = None
        if self.accept("="):
            init = self.expr()
        self.expect(";")
        return B("VariableDeclaration", mods, ty, name, init, decl_suffix=[])

    # -- statements
    def block(self) -> AstNode:
        self.expect("{")
        return self._block_body()

    def _block_body(self) -> AstNode:
        decls, stmts = [], []
        while not self.accept("}"):
            if self._at_decl():
                if stmts:  # declaration after a statement: open an implicit nested block
                    stmts.append(self._block_body())
                    return B("BlockStatement", [], decls, stmts)
                decls.append(self.var_decl())
            else:
                stmts.append(self.statement())
        return B("BlockStatement", [], decls, stmts)

    def _at_decl(self) -> bool:
        return self.t.text == "const" or (self.at_type() and self.peek().kind in ("id", "op") and self.peek().text != "(")

    def statement(self) -> AstNode:
        t = self.t
        if t.text == "{":
            return self.block()
        if t.text == "if":
            self.next()
            self.expect("(")
            cond = self.expr()
            self.expect(")")
            then = self._stmt_as_block()
            other = None
            if self.accept("else"):
                if self.t.text == "if":
                    other = self.statement()
                else:
                    other = self._stmt_as_block()
            return B("IfStatement", cond, then, other)
        if t.text == "return":
            self.next()
            value = None if self.t.text == ";" else self.expr()
            self.expect(";")
            return B("ReturnStatement", value)
        if t.text == "for":
            # shape as in Bitwig's own bytecode: [init decl, None, cond, update, body block]
            self.next()
            self.expect("(")
            init = self.var_decl()          # consumes the ';'
            cond = self.expr()
            self.expect(";")
            upd = self.expr()
            self.expect(")")
            body = self.statement()
            if body.kind != "BlockStatement":
                body = B("BlockStatement", [], [], [body])
            return B("ForStatement", init, None, cond, upd, body)
        lhs = self.expr()
        if self.accept("="):
            rhs = self.expr()
            self.expect(";")
            return B("AssignStatement", lhs, rhs, False)
        if lhs.kind in ("CallExpression", "PreIncrementExpression", "PostIncrementExpression",
                        "PreDecrementExpression", "PostDecrementExpression"):
            self.expect(";")
            return lhs                      # expression statement (bare in the statement list)
        self.fail("only assignments, calls, ++/--, if, for, return and blocks are supported as statements")

    def _stmt_as_block(self) -> AstNode:
        # an if/else branch is a block or a single bare statement, as written
        return self.statement()

    # -- expressions
    def expr(self, level=0) -> AstNode:
        if level == len(BINOPS):
            return self.unary()
        lhs = self.expr(level + 1)
        ops = BINOPS[level]
        while self.t.kind == "op" and self.t.text in ops:
            kind = ops[self.next().text]
            rhs = self.expr(level + 1)
            lhs = B(kind, lhs, rhs)
        return lhs

    def unary(self) -> AstNode:
        if self.accept("-"):
            return B("UnaryMinusExpression", self.unary())
        if self.accept("!"):
            return B("BangExpression", self.unary())
        if self.accept("++"):
            return B("PreIncrementExpression", self.unary())
        if self.accept("--"):
            return B("PreDecrementExpression", self.unary())
        return self.postfix()

    def postfix(self) -> AstNode:
        e = self.primary()
        while True:
            if self.accept("("):
                args = []
                if not self.accept(")"):
                    while True:
                        args.append(self.expr())
                        if self.accept(")"):
                            break
                        self.expect(",")
                e = B("CallExpression", e, args)
            elif self.accept("."):
                e = B("MemberAccessExpression", e, self.ident())
            elif self.accept("["):
                idx = self.expr()
                self.expect("]")
                e = B("ArrayAccessExpression", e, idx)
            elif self.accept("++"):
                e = B("PostIncrementExpression", e)
            elif self.accept("--"):
                e = B("PostDecrementExpression", e)
            else:
                return e

    def primary(self) -> AstNode:
        t = self.t
        if t.kind == "num":
            self.next()
            if re.search(r"[.eE]", t.text):
                return B("FloatValueExpression", B("FloatLiteralType"), float(t.text))
            return B("IntegerValueExpression", B("IntegerLiteralType"), int(t.text))
        if t.text == "(":
            self.next()
            e = self.expr()
            self.expect(")")
            return e
        if t.text in ("true", "false"):
            self.next()
            return B("BooleanValueExpression", t.text == "true")
        if t.text == "cast":
            self.next()
            self.expect("(")
            e = self.expr()
            self.expect(",")
            ty = self.type_()
            self.expect(")")
            return B("CastExpression", e, ty, False)
        if t.kind == "id":
            self.next()
            return B("IdentifierExpression", t.text)
        self.fail(f"unexpected {t.text!r}")


def parse(src: str) -> AstNode:
    p = Parser(src)
    return p.file()


# ---------------------------------------------------------------- emitter

_BIN = {v: k for level in BINOPS for k, v in level.items()}
_TYPE_NAME = {v: k for k, v in SCALARS.items()}
_PORT_NAME = {v: k for k, v in PORTS.items()}


class Emitter:
    def __init__(self):
        self.lines: list[str] = []

    def out(self, depth, text):
        self.lines.append("    " * depth + text)

    def type_(self, n) -> str:
        if n.kind == "VectorType":
            base, width = n.children[0], n.children[1]
            return f"{self.type_(base)}<{'' if width is None else width.children[1]}>"
        if n.kind == "ArrayType":
            return f"{self.type_(n.children[0])}[{n.children[1].children[1]}]"
        if n.kind in _TYPE_NAME:
            return _TYPE_NAME[n.kind]
        raise NotImplementedError(n.kind)

    def expr(self, n) -> str:
        k = n.kind
        if k == "FloatValueExpression":
            return repr(float(n.children[1]))
        if k == "IntegerValueExpression":
            return str(n.children[1])
        if k == "BooleanValueExpression":
            return "true" if n.children[0] else "false"
        if k == "IdentifierExpression":
            return n.children[0]
        if k in _BIN:
            return f"({self.expr(n.children[0])} {_BIN[k]} {self.expr(n.children[1])})"
        if k == "UnaryMinusExpression":
            return f"(-{self.expr(n.children[0])})"
        if k == "BangExpression":
            return f"(!{self.expr(n.children[0])})"
        if k == "PreIncrementExpression":
            return f"++{self.expr(n.children[0])}"
        if k == "PreDecrementExpression":
            return f"--{self.expr(n.children[0])}"
        if k == "PostIncrementExpression":
            return f"{self.expr(n.children[0])}++"
        if k == "PostDecrementExpression":
            return f"{self.expr(n.children[0])}--"
        if k == "CallExpression":
            return f"{self.expr(n.children[0])}({', '.join(self.expr(a) for a in n.children[1])})"
        if k == "MemberAccessExpression":
            return f"{self.expr(n.children[0])}.{n.children[1]}"
        if k == "ArrayAccessExpression":
            return f"{self.expr(n.children[0])}[{self.expr(n.children[1])}]"
        if k == "CastExpression":
            if n.children[2]:
                raise NotImplementedError("CastExpression flag")
            return f"cast({self.expr(n.children[0])}, {self.type_(n.children[1])})"
        raise NotImplementedError(k)

    def file(self, n) -> str:
        for comp in n.children[0]:
            self.component(comp)
        return "\n".join(self.lines) + "\n"

    def component(self, n):
        self.out(0, f"struct {n.children[0]}")
        self.out(0, "{")
        for m in n.children[1]:
            self.member(m, 1)
        self.out(0, "}")

    def member(self, n, d):
        k = n.kind
        if k == "PortDeclaration":
            ptype, name, c2, c3, c4, default = n.children[:6]
            if c2 is not None or c3 != "" or c4 is not None:
                raise NotImplementedError(f"port {name}: annotations not supported yet")
            inner = ptype.children[0]
            s = _PORT_NAME[ptype.kind]
            if inner is not None:
                s += f"<{self.type_(inner)}>"
            s += f" {name}"
            if default is not None:
                s += f" = {self.expr(default)}"
            self.out(d, s + ";")
        elif k == "VariableDeclaration":
            self.var_decl(n, d)
        elif k == "FunctionDeclaration":
            name, ret, args, body, flag = n.children[:5]
            if flag:
                raise NotImplementedError("function flag")
            a = ", ".join(f"{self.type_(x.children[0])} {x.children[1]}" for x in args)
            self.out(d, f"{self.type_(ret)} {name}({a})")
            self.block(body, d)
        elif k in ("ResetBlockDeclaration", "ProcessBlockDeclaration"):
            self.out(d, "reset" if k.startswith("Reset") else "process")
            self.block(n.children[0], d)
        else:
            raise NotImplementedError(k)

    def var_decl(self, n, d):
        mods, ty, name, init = n.children[:4]
        pre = "".join("const " for m in mods if m.kind == "ConstSpecifier")
        if any(m.kind != "ConstSpecifier" for m in mods):
            raise NotImplementedError("modifier")
        s = f"{pre}{self.type_(ty)} {name}"
        if init is not None:
            s += f" = {self.expr(init)}"
        self.out(d, s + ";")

    def block(self, n, d):
        l0, decls, stmts = n.children
        if l0:
            raise NotImplementedError("block list0")
        self.out(d, "{")
        for x in decls:
            self.var_decl(x, d + 1)
        for s in stmts:
            self.stmt(s, d + 1)
        self.out(d, "}")

    def stmt(self, n, d):
        k = n.kind
        if k == "AssignStatement":
            if n.children[2]:
                raise NotImplementedError("assign flag")
            self.out(d, f"{self.expr(n.children[0])} = {self.expr(n.children[1])};")
        elif k == "BlockStatement":
            self.block(n, d)
        elif k == "ForStatement":
            init, _none, cond, upd, body = n.children
            mods, ty, name, ival = init.children[:4]
            self.out(d, f"for ({self.type_(ty)} {name} = {self.expr(ival)}; {self.expr(cond)}; {self.expr(upd)})")
            self.block(body, d)
        elif k in ("CallExpression", "PreIncrementExpression", "PostIncrementExpression",
                   "PreDecrementExpression", "PostDecrementExpression"):
            self.out(d, self.expr(n) + ";")
        elif k == "ReturnStatement":
            v = n.children[0]
            self.out(d, "return;" if v is None else f"return {self.expr(v)};")
        elif k == "IfStatement":
            cond, then, other = n.children
            self.out(d, f"if ({self.expr(cond)})")
            self.branch(then, d)
            while other is not None:
                if other.kind == "IfStatement":
                    c2, t2, other2 = other.children
                    self.out(d, f"else if ({self.expr(c2)})")
                    self.branch(t2, d)
                    other = other2
                else:
                    self.out(d, "else")
                    self.branch(other, d)
                    other = None
        else:
            raise NotImplementedError(k)

    def branch(self, n, d):
        if n.kind == "BlockStatement":
            self.block(n, d)
        else:
            self.stmt(n, d + 1)


def emit(ast: AstNode) -> str:
    return Emitter().file(ast)


if __name__ == "__main__":
    from bitwig_nitro import serialize_nitrobin
    src = Path(sys.argv[1]).read_text()
    data = serialize_nitrobin(parse(src))
    Path(sys.argv[2]).write_bytes(data)
    print(f"{sys.argv[2]}: {len(data)} bytes")
