"""True-peak interpolator for BO LUFS: 4x polyphase low-pass (windowed sinc), chosen by the
worst-case reading error on sines up to 20 kHz at 44.1/48 kHz."""
import numpy as np
from scipy import signal

def design(taps_per_phase, cutoff, beta, L=4):
    n = taps_per_phase * L
    h = signal.firwin(n, cutoff, window=("kaiser", beta), fs=2.0 * L)  # cutoff in units of fs/2 (=1.0)
    h = h * L                      # unity gain per phase
    return h.reshape(taps_per_phase, L).T.copy()   # phase p: h[p::L]

def tp_est(x, ph, phases=(0, 1, 2, 3)):
    best = np.abs(x).max()
    for p in phases:
        best = max(best, np.abs(np.convolve(x, ph[p], mode="valid")).max())
    return best

def errors(ph, fs, fmax=20000.0, phases=(0, 1, 2, 3), seed=1):
    rng = np.random.default_rng(seed)
    worst_lo, worst_hi = 0.0, 0.0
    for f in np.concatenate([np.geomspace(20, fmax, 60), [fmax]]):
        for _ in range(12):
            t = np.arange(2048) / fs
            x = 0.5 * np.sin(2 * np.pi * f * t + rng.uniform(0, 2 * np.pi))
            e = 20 * np.log10(tp_est(x, ph, phases) / 0.5)
            worst_lo, worst_hi = min(worst_lo, e), max(worst_hi, e)
    return worst_lo, worst_hi

if __name__ == "__main__":
    for tpp in (12, 16):
        best = None
        for cut in np.linspace(0.85, 1.0, 16):
            for beta in (4, 5, 6, 7, 8):
                ph = design(tpp, cut, beta)
                lo, hi = errors(ph, 44100.0)
                score = max(-lo, hi)
                if best is None or score < best[0]:
                    best = (score, cut, beta, lo, hi)
        print(tpp, "taps/phase: cut %.3f beta %g  44.1k err %.3f..%+.3f dB" % best[1:])
