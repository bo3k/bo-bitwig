"""BO LUFS checks: EBU Tech 3341 cases 1-5 (integrated), Tech 3342 cases 1-4 (LRA), and random
programme material against the independent reference, at 44.1, 48 and 96 kHz."""
import numpy as np
from scipy import signal
from ref_loudness import measure, tone
import sim_bo_lufs as sim

EBU_I = {'3341-1': ([(-23, 20)], -23), '3341-2': ([(-33, 20)], -33),
         '3341-3': ([(-36, 10), (-23, 60), (-36, 10)], -23),
         '3341-4': ([(-72, 10), (-36, 10), (-23, 20), (-36, 10), (-72, 10)], -23),
         '3341-5': ([(-26, 20), (-20, 20.1), (-26, 20)], -23)}
EBU_LRA = {'3342-1': ([(-20, 20), (-30, 20)], 10), '3342-2': ([(-20, 20), (-15, 20)], 5),
           '3342-3': ([(-40, 20), (-20, 20)], 20),
           '3342-4': ([(-50, 20), (-35, 20), (-20, 20), (-35, 20), (-50, 20)], 15)}

def programme(fs, seconds, seed):
    """Music-like test material: pinkish noise, level changes every 0.5-4 s, gaps of silence,
    a few hard transients (true-peak overs)."""
    rng = np.random.default_rng(seed)
    n = int(seconds * fs)
    w = rng.standard_normal((n, 2))
    b, a = signal.butter(1, 800 / (fs / 2))
    x = signal.lfilter(b, a, w, axis=0) * 3 + 0.3 * w
    env = np.zeros(n); i = 0
    while i < n:
        d = int(rng.uniform(0.5, 4) * fs)
        lvl = 0.0 if rng.random() < 0.12 else 10 ** (rng.uniform(-40, -6) / 20)
        env[i:i + d] = lvl; i += d
    env = signal.lfilter([0.002], [1, -0.998], env)
    x = x * env[:, None] * 0.25
    for _ in range(5):
        k = rng.integers(0, n - 10)
        x[k:k + 3] += rng.uniform(0.5, 1.0) * np.array([1, -1, 1])[:, None]
    return x

def main():
    ok = True
    import sys
    for fs in [int(a) for a in sys.argv[1:]] or (44100, 48000, 96000):
        print(f'--- {fs} Hz')
        for k, (seg, exp) in EBU_I.items():
            x = tone(fs, seg); v, _ = sim.run(x, fs)
            good = abs(v['I'] - exp) <= 0.1; ok &= good
            print(f'  {k}: I = {v["I"]:7.3f}  (expected {exp} +-0.1)  {"ok" if good else "FAIL"}')
        for k, (seg, exp) in EBU_LRA.items():
            x = tone(fs, seg); v, _ = sim.run(x, fs)
            good = abs(v['LRA'] - exp) <= 1.0; ok &= good
            print(f'  {k}: LRA = {v["LRA"]:6.2f} (expected {exp} +-1)  {"ok" if good else "FAIL"}')
        for seed in range(3):
            x = programme(fs, 90, seed + 10 * (fs // 1000))
            r = measure(x, fs); v, _ = sim.run(x, fs)
            d = {key: v[key] - r[key] for key in ('I', 'LRA', 'Mmax', 'Smax', 'TP')}
            good = abs(d['I']) <= 0.05 and abs(d['LRA']) <= 0.3 and abs(d['Mmax']) < 1e-6 and abs(d['Smax']) < 1e-6 and -0.4 <= d['TP'] <= 0.2
            ok &= good
            print(f'  programme {seed}: ref I {r["I"]:.2f} LRA {r["LRA"]:.2f} TP {r["TP"]:.2f} | diff ' +
                  ' '.join(f'{k} {val:+.3f}' for k, val in d.items()) + ('  ok' if good else '  FAIL'))
    # clear: restart after 30 s of a louder section, then measure only the rest
    fs = 48000
    x = tone(fs, [(-10, 30), (-23, 30)])
    v, _ = sim.run(x, fs, clear_at=[30.5])
    good = abs(v['I'] + 23) <= 0.1 and v['TP'] < -22.5; ok &= good
    print(f'clear at 30.5 s: I = {v["I"]:.3f} TP = {v["TP"]:.2f} (expected -23 / about -23)  {"ok" if good else "FAIL"}')
    print('ALL OK' if ok else 'SOME FAILED')


if __name__ == "__main__":
    main()
