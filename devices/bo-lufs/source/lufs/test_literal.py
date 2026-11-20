"""Run the Nitro text itself (transpiled) sample by sample and compare with sim_bo_lufs."""
import sys, time
import numpy as np
from nitro2py import load
import sim_bo_lufs as sim
from test_bo_lufs import programme

fs = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
sec = float(sys.argv[2]) if len(sys.argv) > 2 else 40
Comp, code = load(open('../bo_lufs.nitro').read())
open('/tmp/bo_lufs_transpiled.py', 'w').write(code)
c = Comp(fs); c.reference_ = -14.0; c.reset()
x = programme(fs, sec, 5).astype(np.float32)
clear_t = sec * 0.6
t0 = time.time()
L = int(np.floor(fs * 0.1 + 0.5))
rows = []
for n in range(len(x)):
    if n == int(round(clear_t * fs)):
        c.on_reset_btn()         # Reset button pressed once
    c.in_ = [float(x[n, 0]), float(x[n, 1])]
    c.process()
    assert c.out_[0] == x[n, 0] and c.out_[1] == x[n, 1]
    if (n + 1) % L == 0:
        rows.append((list(c.ui_a_), list(c.ui_b_)))
print(f'{len(x)} samples in {time.time()-t0:.1f} s')
# the sim clears at the start of the block containing clear_t; the Nitro code clears at the sample
# itself. Align: clear in the sim at that block, and compare blocks before and well after it.
v, hist = sim.run(x, fs, clear_at=[(int(round(clear_t * fs)) // L) * L / fs])
cb = int(round(clear_t * fs)) // L
worst = {}
for k, (a, b) in enumerate(rows):
    if cb <= k < cb + 1:
        continue
    h = hist[k]
    for name, got in (('M', a[0]), ('S', a[1]), ('I', a[2]), ('LRA', a[3])):
        ref = h[name]
        ref = max(ref, -200.0)
        if k >= cb + 1 and name in ('S', 'Smax', 'LRA') and k < cb + 31:
            continue                       # partial block straddling the clear: windows differ by design
        d = abs(got - ref)
        worst[name] = max(worst.get(name, 0.0), d)
        if d > 0.01 and name in ("M","I"): print("  block", k, name, round(got,3), round(ref,3))
print('max |nitro - sim| per value:', {k: round(v_, 4) for k, v_ in worst.items()})
print('final  nitro: M %.2f S %.2f I %.2f LRA %.2f TPmax %.2f' % (*rows[-1][0], rows[-1][1][0]))
print('final  sim  : M %.2f S %.2f I %.2f LRA %.2f TPmax %.2f' % (v['M'], v['S'], v['I'], v['LRA'], v['TP']))
