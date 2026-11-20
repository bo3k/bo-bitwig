"""Runs BO Spectrum's Nitro (transpiled) with sines: the band at the sine reads about 0 dB (full scale on
both channels), its neighbours well below; off: no levels move."""
import math, sys
sys.path.insert(0, '/root/bwdev'); sys.path.insert(0, '/root/bwdev/lufs')
from nitro2py import load
FS = 48000
Comp, code = load(open('bo_spectrum.nitro').read())
def levels(freq, amp=1.0, secs=0.4, show=True, mode=0, right=1.0, hold=False, c=None):
    if c is None:
        c = Comp(FS)
    c.show_ = show; c.slope_ = 0; c.mode_ = mode; c.view_ = 0; c.hold_ = hold; c.hold_time_ = 0
    for i in range(int(FS * secs)):
        v = amp * math.sin(2 * math.pi * freq * i / FS)
        c.in_ = [v, right * v]; c.process()
    return list(c.lev_)[:31], c
fails = 0
for f, b in ((1000, 17), (100, 7), (20, 0), (10000, 27), (19953, 30)):
    lev, c = levels(f, secs=0.6 if f < 50 else 0.3)
    nb = [lev[k] for k in (b - 2, b + 2) if 0 <= k < 31]
    ok = abs(lev[b] - (17 - b)) < 1.0 and all(n < lev[b] - 6 for n in nb)   # Bars: weighted -10 log10(fc / 1 kHz)
    fails += not ok
    print(('ok   ' if ok else 'FAIL ') + f"{f} Hz: band {b} {lev[b]:.2f} dB, +-2 bands {[round(n, 1) for n in nb]}")
lev, c = levels(1000, mode=1, right=-1.0, secs=0.3)
s = list(c.levs_)[:31]
ok = s[17] > -1 and lev[17] < -60; fails += not ok
print(('ok   ' if ok else 'FAIL ') + f"M/S, out of phase: side {s[17]:.2f} dB, mid {lev[17]:.1f} dB")
pk = int(c.pk_[17]); a, b, cc = pk // 65536, (pk // 256) % 256, pk % 256
ok = a == 255 and b <= 2 and cc == 255; fails += not ok
print(('ok   ' if ok else 'FAIL ') + f"packed {pk}: first {a}, side {b}, hold {cc}")
# hold: 1 kHz for 0.3 s, then silence: held for 1 s, then falling
lev, c = levels(1000, secs=0.3, hold=True)
_, c = levels(1000, amp=0.0, secs=0.8, hold=True, c=c)
h1 = c.hv_[17]
_, c = levels(1000, amp=0.0, secs=0.7, hold=True, c=c)
h2 = c.hv_[17]
ok = abs(h1) < 1 and -12 < h2 < -3 and c.lev_[17] < -30; fails += not ok
print(('ok   ' if ok else 'FAIL ') + f"hold: {h1:.1f} dB after 0.8 s, {h2:.1f} dB after 1.5 s (level {c.lev_[17]:.0f})")
lev, c = levels(1000, show=False, secs=0.05)
ok = all(v == -200 for v in lev); fails += not ok
print(('ok   ' if ok else 'FAIL ') + "display off: levels stay at -200, state slot: " + str(c.sp2_[3]))
sys.exit(fails)
