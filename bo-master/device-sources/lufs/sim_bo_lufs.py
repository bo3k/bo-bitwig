"""Python model of bo_lufs.nitro: same blocks, ring, histograms, gates and true-peak filter,
vectorised where the Nitro code works sample by sample. Used to check the Nitro algorithm
against the independent reference (ref_loudness.py)."""
import math
import numpy as np
from scipy import signal

from ref_loudness import kweight_coeffs
from tp_design import design

TPC = design(12, 1.0, 5).astype(np.float32)


def lufs(e):
    return -0.691 + 10.0 * math.log10(e + 1e-30)


def bin_of(l):
    return int(min(799.0, max(0.0, math.floor((l + 70.0) * 10.0))))


def integrated(ih_n, ih_e, i_n, i_e):
    if i_n <= 0:
        return -200.0
    gate = (lufs(i_e / i_n) - 10.0 + 70.0) * 10.0
    sel = (np.arange(800) + 0.5) > gate
    n, e = ih_n[sel].sum(), ih_e[sel].sum()
    return lufs(e / n) if n > 0 else -200.0


def lra(sh_n, sh_e, s_n, s_e):
    if s_n <= 0:
        return -200.0
    gate = (lufs(s_e / s_n) - 20.0 + 70.0) * 10.0
    bc = np.arange(800) + 0.5
    sel = bc > gate
    n = sh_n[sel].sum()
    if n <= 0:
        return -200.0
    c = np.cumsum(np.where(sel, sh_n, 0.0))
    lo = bc[np.argmax(sel & (c >= 0.1 * n))]
    hi = bc[np.argmax(sel & (c >= 0.95 * n))]
    return (hi - lo) * 0.1


def run(x, fs, clear_at=()):
    """x: (samples, 2) float. Returns the final display values and per-block history."""
    x32 = np.asarray(x, np.float32)
    (b1, a1), (b2, a2) = kweight_coeffs(fs)
    y = signal.lfilter(b2, a2, signal.lfilter(b1, a1, x32.astype(np.float64), axis=0), axis=0)
    z = (y * y).sum(axis=1)
    L = int(math.floor(fs * 0.1 + 0.5))

    # true peak per sample (float32 like the Nitro code)
    tp = np.abs(x32).max(axis=1)
    for p in range(4):
        for ch in range(2):
            v = np.convolve(x32[:, ch], TPC[p], mode="full")[:len(x32)].astype(np.float32)
            tp = np.maximum(tp, np.abs(v))

    ring = np.zeros(30); rp = 0; nb = 0
    ih_n = np.zeros(800); ih_e = np.zeros(800); i_n = i_e = 0.0
    sh_n = np.zeros(800); sh_e = np.zeros(800); s_n = s_e = 0.0
    v = dict(M=-200.0, S=-200.0, I=-200.0, LRA=-200.0, Mmax=-200.0, Smax=-200.0)
    tp_max = 0.0
    hist = []
    nblk = len(z) // L
    clear_blocks = {int(c * fs) // L for c in clear_at}
    for k in range(nblk):
        if k in clear_blocks:
            ring[:] = 0; rp = 0; nb = 0
            ih_n[:] = 0; ih_e[:] = 0; i_n = i_e = 0.0
            sh_n[:] = 0; sh_e[:] = 0; s_n = s_e = 0.0
            v = dict(M=-200.0, S=-200.0, I=-200.0, LRA=-200.0, Mmax=-200.0, Smax=-200.0)
            tp_max = 0.0
        acc = z[k * L:(k + 1) * L].sum()
        tp_max = max(tp_max, float(tp[k * L:(k + 1) * L].max()))
        ring[rp] = acc; rp = (rp + 1) % 30; nb += 1
        idx = [(rp - 1 - j) % 30 for j in range(30)]
        e4 = ring[idx[:4]].sum(); e30 = ring[idx].sum()
        em = e4 / (4 * L); es = e30 / (30 * L)
        lm, ls = lufs(em), lufs(es)
        v["M"], v["S"] = max(lm, -200.0), max(ls, -200.0)
        v["Mmax"], v["Smax"] = max(v["Mmax"], v["M"]), max(v["Smax"], v["S"])
        if nb >= 4 and lm > -70:
            b = bin_of(lm); ih_n[b] += 1; ih_e[b] += em; i_n += 1; i_e += em
            v["I"] = integrated(ih_n, ih_e, i_n, i_e)
        if nb >= 30 and ls > -70:
            b = bin_of(ls); sh_n[b] += 1; sh_e[b] += es; s_n += 1; s_e += es
            v["LRA"] = lra(sh_n, sh_e, s_n, s_e)
        hist.append(dict(v))
    v["TP"] = 20 * math.log10(tp_max) if tp_max > 1e-10 else -200.0
    return v, hist
