"""Literal Nitro -> Python transpiler for the subset used by bo_lufs.nitro.

Turns the component into a Python class whose process() runs one sample, statement for
statement, so the Nitro text itself (not a hand-written model) can be checked offline.
Numbers: f32 members/locals are rounded to float32 on assignment; integers stay ints.
"""
import math
import numpy as np
import nitroc

BIN = {"AddExpression": "+", "SubExpression": "-", "MulExpression": "*", "DivExpression": "/",
       "LTExpression": "<", "GTExpression": ">", "LEqExpression": "<=", "GEqExpression": ">=",
       "EqExpression": "==", "NEqExpression": "!=", "AndAndExpression": "and", "OrOrExpression": "or"}
SCAL = {"Float32Type": "f32", "Float64Type": "f64", "Int32Type": "i32", "BooleanType": "bool",
        "UInt32Type": "u32"}


def tname(t):
    if t.kind == "ArrayType":
        return ("arr", SCAL[t.children[0].kind], t.children[1].children[1])
    return SCAL.get(t.kind, t.kind)


class T:
    def __init__(self, comp):
        self.members, self.types, self.funcs = set(), {}, set()
        self.lines = []
        for m in comp.children[1]:
            if m.kind == "VariableDeclaration":
                self.members.add(m.children[2]); self.types[m.children[2]] = tname(m.children[1])
            elif m.kind == "PortDeclaration":
                self.members.add(m.children[1])
            elif m.kind == "FunctionDeclaration":
                self.funcs.add(m.children[0])
        self.local_types = {}

    def ex(self, n):
        k = n.kind
        c = n.children
        if k in BIN:
            return f"({self.ex(c[0])} {BIN[k]} {self.ex(c[1])})"
        if k == "FloatValueExpression":
            return repr(float(c[1]))
        if k == "IntegerValueExpression":
            return repr(int(c[1]))
        if k == "BooleanValueExpression":
            return repr(bool(c[0]))
        if k == "UnaryMinusExpression":
            return f"(-{self.ex(c[0])})"
        if k == "BangExpression":
            return f"(not {self.ex(c[0])})"
        if k == "IdentifierExpression":
            name = c[0]
            return f"self.{name}_" if name in self.members else name
        if k == "MemberAccessExpression":
            return f"self.EC_{c[1]}"
        if k == "ArrayAccessExpression":
            return f"{self.ex(c[0])}[{self.ex(c[1])}]"
        if k == "CastExpression":
            t = SCAL[c[1].kind]
            inner = self.ex(c[0])
            return {"i32": f"int({inner})", "f64": f"float({inner})", "f32": f"f32({inner})"}[t]
        if k == "CallExpression":
            fn = c[0].children[0]
            args = ", ".join(self.ex(a) for a in c[1])
            if fn in self.funcs:
                return f"self.{fn}({args})"
            return f"B_{fn}({args})"
        raise NotImplementedError(k)

    def typ_of_target(self, n):
        if n.kind == "IdentifierExpression":
            name = n.children[0]
            t = self.local_types.get(name, self.types.get(name))
        else:
            t = self.types.get(n.children[0].children[0])
            if isinstance(t, tuple):
                t = t[1]
        return t

    def wrap(self, t, e):
        return f"f32({e})" if t == "f32" else (f"int({e})" if t == "i32" else e)

    def st(self, n, ind):
        k = n.kind
        c = n.children
        p = "    " * ind
        if k == "BlockStatement":
            for d in c[1]:
                self.st(d, ind)
            for s in c[2]:
                self.st(s, ind)
            if not c[1] and not c[2]:
                self.lines.append(p + "pass")
        elif k == "VariableDeclaration":
            t = tname(c[1]); self.local_types[c[2]] = t
            if isinstance(t, tuple):
                self.lines.append(f"{p}{c[2]} = [0.0] * {t[2]}")
            else:
                init = self.ex(c[3]) if c[3] is not None else "0"
                if t == "f32" and c[1].kind == "VectorType":
                    pass
                self.lines.append(f"{p}{c[2]} = {self.wrap(t, init)}")
        elif k == "AssignStatement":
            tgt = self.ex(c[0])
            self.lines.append(f"{p}{tgt} = {self.wrap(self.typ_of_target(c[0]), self.ex(c[1]))}")
        elif k == "IfStatement":
            self.lines.append(f"{p}if {self.ex(c[0])}:")
            self.st(c[1], ind + 1)
            if c[2] is not None:
                self.lines.append(f"{p}else:")
                self.st(c[2], ind + 1)
        elif k == "ForStatement":
            d = c[0]
            self.local_types[d.children[2]] = "i32"
            self.st(d, ind)
            self.lines.append(f"{p}while {self.ex(c[2])}:")
            self.st(c[4], ind + 1)
            var = c[3].children[0].children[0]
            self.lines.append(f"{p}    {var} += 1")
        elif k == "ReturnStatement":
            self.lines.append(f"{p}return {self.ex(c[0])}")
        elif k == "PreIncrementExpression":
            self.lines.append(f"{p}{self.ex(c[0])} += 1")
        elif k == "CallExpression":
            self.lines.append(p + self.ex(n))
        elif k == "SendStatement":
            self.lines.append(f"{p}self.sent.setdefault({c[0]!r}, []).append({self.ex(c[2])})")
        else:
            raise NotImplementedError(k)


def transpile(src):
    comp = nitroc.parse(src).children[0][0]
    t = T(comp)
    L = t.lines
    L.append("class Component:")
    L.append("    def __init__(self, samplerate):")
    L.append("        self.EC_samplerate = samplerate")
    for m in comp.children[1]:
        if m.kind == "VariableDeclaration":
            ty = tname(m.children[1]); nm = m.children[2]
            if isinstance(ty, tuple):
                L.append(f"        self.{nm}_ = [0.0] * {ty[2]}")
            else:
                init = t.ex(m.children[3]) if m.children[3] is not None else "0"
                L.append(f"        self.{nm}_ = {t.wrap(ty, init)}")
        elif m.kind == "PortDeclaration":
            L.append(f"        self.{m.children[1]}_ = [0.0, 0.0, 0.0, 0.0]")
    L.append("        self.sent = {}")
    for m in comp.children[1]:
        if m.kind == "PortDeclaration" and m.children[4] is not None:
            t.local_types = {}
            L.append(f"    def on_{m.children[1]}(self):")
            t.st(m.children[4], 2)
        if m.kind == "FunctionDeclaration":
            t.local_types = {}
            args = [a.children[1] for a in m.children[2]]
            for a in m.children[2]:
                t.local_types[a.children[1]] = SCAL[a.children[0].kind]
            L.append(f"    def {m.children[0]}(self{''.join(', ' + a for a in args)}):")
            t.st(m.children[3], 2)
        elif m.kind in ("ResetBlockDeclaration", "ProcessBlockDeclaration"):
            t.local_types = {}
            L.append(f"    def {'reset' if m.kind.startswith('Reset') else 'process'}(self):")
            t.st(m.children[0], 2)
    return "\n".join(L) + "\n"


def f32(v):
    return float(np.float32(v))


BUILTINS = dict(f32=f32, B_log10=lambda v: math.log10(v) if v > 0 else -math.inf,
                B_floor=lambda v: float(math.floor(v)), B_fmax=max, B_fmin=min, B_fabs=abs,
                B_sin=math.sin, B_cos=math.cos, B_exp=math.exp, B_sqrt=math.sqrt)


def load(src):
    code = transpile(src)
    ns = dict(BUILTINS)
    exec(compile(code, "<nitro>", "exec"), ns)
    return ns["Component"], code
