import numpy as np
from scipy import signal
from tp_design import design

def true_peak_ref(x):
    return np.abs(signal.resample_poly(x, 64, 1, window=("kaiser", 14.0))).max()

def est(x, ph, phases):
    b = np.abs(x).max()
    for p in phases:
        b = max(b, np.abs(np.convolve(x, ph[p], mode="full")).max())
    return b

def test(ph, fs, fmax=20000.0, phases=(0,1,2,3), n=300, seed=3):
    rng = np.random.default_rng(seed)
    lp = signal.firwin(255, fmax, fs=fs, window=("kaiser", 10))
    errs = []
    for _ in range(n):
        w = rng.standard_normal(600)
        x = np.convolve(w, lp)[300:400]          # 100 samples of band-limited noise
        x = np.pad(x, 200)
        r = true_peak_ref(x); e = est(x, ph, phases)
        errs.append(20*np.log10(e/r))
    # single tone bursts near the band edge, fractional peak positions (iZotope-style)
    for f in (15000, 18000, 19000, 20000):
        for off in np.linspace(0, 1, 9):
            t = (np.arange(-400, 400) - off) / fs
            x = np.sinc(2*fmax*t) * np.cos(2*np.pi*(f-fmax/2)*t*0)   # band-limited pulse
            x = x * np.hanning(len(x))
            r = true_peak_ref(x); e = est(x, ph, phases)
            errs.append(20*np.log10(e/r))
    errs = np.array(errs)
    return errs.min(), errs.max()

if __name__ == "__main__":
    for tpp in (12, 16):
        best=None
        for cut in np.linspace(0.85, 1.0, 7):
            for beta in (5, 7, 9):
                ph = design(tpp, cut, beta)
                lo, hi = test(ph, 48000.0, n=120)
                sc = max(-lo - 0.0, hi * 2)
                if best is None or sc < best[0]: best=(sc,cut,beta,lo,hi)
        print(tpp, 'cut %.3f beta %g  48k err %.3f..%+.3f' % best[1:])
