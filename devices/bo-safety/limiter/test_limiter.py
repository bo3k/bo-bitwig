"""Runs the Nitro text itself (transpiled, float32) and checks the safety limiter's guarantees."""
import math, random, sys
sys.path.insert(0, '/root/bwdev'); sys.path.insert(0, '/root/bwdev/lufs')
from nitro2py import load

FS = 8000
Comp, code = load(open('bo_safety_limiter.nitro').read())
open('/tmp/limiter_transpiled.py', 'w').write(code)

def make(limit=0.0, margin=True):
    c = Comp(FS); c.limit_ = limit; c.tp_margin_ = margin
    return c

def run(c, l, r=None):
    c.in_ = [l, l if r is None else r]
    c.process()
    return c.out_[0], c.out_[1]

def db(x): return 10 ** (x / 20)
fails = 0
def check(cond, what):
    global fails
    print(('ok   ' if cond else 'FAIL ') + what)
    fails += not cond

# 1. passes unchanged below the trip level (0 dB, margin on: T = -1 dB)
c = make()
outs = [run(c, 0.5 * math.sin(i * 0.3)) for i in range(5000)]
check(all(abs(o[0] - 0.5 * math.sin(i * 0.3)) < 1e-6 for i, o in enumerate(outs)), "passes unchanged at gain 1")

# 2. one sample over: muted on that sample, 4 s hold, 2 s ramp, then exactly 1
c = make()
check(run(c, db(-0.9))[0] == 0.0, "the over sample itself is muted (-0.9 dBFS > T = -1 dBFS)")
held = [run(c, 0.5)[0] for _ in range(4 * FS)]
check(all(v == 0.0 for v in held), "silent through the 4 s hold")
ramp = [run(c, 0.5)[0] / 0.5 for _ in range(2 * FS)]
check(0 < ramp[0] < 0.001 and all(ramp[i] <= ramp[i + 1] for i in range(len(ramp) - 1)), "the ramp rises steadily from 0 (never falls)")
check(abs(ramp[FS - 1] - 0.5) < 0.002, "half way after 1 s (half-cosine)")
check(ramp[-1] == 1.0 and run(c, 0.5)[0] == 0.5, "exactly 1 after 2 s")

# 3. an over during the ramp mutes again and restarts the 4 s
c = make(); run(c, 1.0)
for _ in range(4 * FS + FS // 2): run(c, 0.3)
check(run(c, 0.95)[0] == 0.0, "over during the ramp mutes")
held = [run(c, 0.3)[0] for _ in range(4 * FS)]
check(all(v == 0.0 for v in held), "and the full 4 s start again")

# 4. the TP margin
c = make(margin=False); check(run(c, db(-0.5))[0] != 0.0, "margin off: -0.5 dBFS passes at 0 dB")
c = make(margin=True); check(run(c, db(-0.5))[0] == 0.0, "margin on: -0.5 dBFS trips at 0 dB")
c = make(limit=10.0, margin=False); check(run(c, db(9.9))[0] != 0.0, "+10 dB limit passes +9.9 dBFS")
c = make(limit=-18.0, margin=False); check(run(c, db(-17.9))[0] == 0.0, "-18 dB limit trips at -17.9 dBFS")

# 5. NaN and Inf in the audio: mute, never output
for bad in (float('nan'), float('inf'), -float('inf')):
    c = make(); o = run(c, 0.1, bad)
    check(o == (0.0, 0.0), f"{bad} on one channel mutes both")

# 6. a bad limit parameter falls back to -18 dB (the safest)
for bad in (float('nan'), -1e9):
    c = make(limit=bad, margin=False)
    check(run(c, db(-17.9))[0] == 0.0 and make(limit=bad, margin=False) and run(make(limit=bad, margin=False), db(-18.5))[0] != 0.0,
          f"limit {bad} -> -18 dB")
c = make(limit=1e9, margin=False); check(run(c, db(10.1))[0] == 0.0, "limit above the range -> +10 dB")

# 7. randomized: every output finite and within +-T(t), with the limit automated every sample;
#    mostly in-range audio (so it passes and recovers), rare overs, NaN and Inf
random.seed(1)
c = make(); worst = 0.0; finite = True; passed = 0
for n in range(400000):
    if n % 5000 == 0:
        base = random.uniform(-18, 10)
    lim = base + random.uniform(-0.2, 0.2); c.limit_ = lim; c.tp_margin_ = (n // 20000) % 2 == 0
    t = db(max(-18, min(10, lim)) - (1 if c.tp_margin_ else 0))
    k = random.random()
    x = float('nan') if k < 0.000002 else float('inf') if k < 0.000004 else \
        random.uniform(-2 * t, 2 * t) if k < 0.00002 else random.uniform(-0.9 * t, 0.9 * t)
    o = run(c, x, -x)
    for v in o:
        finite &= math.isfinite(v)
        worst = max(worst, abs(v) / t)
    passed += o[0] != 0.0
check(finite and worst <= 1.0 + 1e-6 and passed > 100000, f"400k random samples: all finite, max |y|/T = {worst:.6f}, {passed} passed through")

# 8. a reset while muted keeps it muted and restarts the hold
c = make(); run(c, 1.0)
for _ in range(3 * FS): run(c, 0.1)
c.reset()
check(all(run(c, 0.1)[0] == 0.0 for _ in range(4 * FS)), "reset while muted: stays muted for a full 4 s more")
print("FAILED" if fails else "ALL PASSED")
