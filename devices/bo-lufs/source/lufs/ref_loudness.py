"""Independent float64 reference for BO LUFS (not a model of the Nitro code).

Straight from the documents, no histograms, no shortcuts:
  ITU-R BS.1770-4  K-weighting, 400 ms gating blocks (75 % overlap), absolute gate -70 LUFS,
                   relative gate -10 LU, integrated loudness.
  EBU Tech 3341    momentary (400 ms), short-term (3 s).
  EBU Tech 3342    loudness range: short-term values, abs gate -70, relative gate -20 LU,
                   LRA = P95 - P10.
  True peak        32x oversampling with a long linear-phase filter (close to the analog peak).

K-weighting at any rate from the analog prototypes (the same derivation libebur128 uses);
at 48 kHz these reproduce BS.1770's Table 1 / Table 2 coefficients.
"""
import numpy as np
from scipy import signal


def kweight_coeffs(fs):
    # stage 1: high shelf
    f0, G, Q = 1681.974450955533, 3.999843853973347, 0.7071752369554196
    K = np.tan(np.pi * f0 / fs)
    Vh = 10 ** (G / 20)
    Vb = Vh ** 0.4996667741545416
    a0 = 1 + K / Q + K * K
    b1 = np.array([(Vh + Vb * K / Q + K * K) / a0, 2 * (K * K - Vh) / a0, (Vh - Vb * K / Q + K * K) / a0])
    a1 = np.array([1.0, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0])
    # stage 2: RLB high-pass
    f0, Q = 38.13547087602444, 0.5003270373238773
    K = np.tan(np.pi * f0 / fs)
    a0 = 1 + K / Q + K * K
    b2 = np.array([1.0, -2.0, 1.0])
    a2 = np.array([1.0, 2 * (K * K - 1) / a0, (1 - K / Q + K * K) / a0])
    return (b1, a1), (b2, a2)


def kweight(x, fs):
    (b1, a1), (b2, a2) = kweight_coeffs(fs)
    return signal.lfilter(b2, a2, signal.lfilter(b1, a1, x, axis=0), axis=0)


def lufs(e):
    e = np.asarray(e, float)
    with np.errstate(divide="ignore"):
        return -0.691 + 10 * np.log10(e)


def windows(z, fs, length_s, step_s=0.1):
    """Mean square of z (summed over channels) over sliding windows ending at every 100 ms step."""
    n = int(round(length_s * fs))
    st = int(round(step_s * fs))
    c = np.concatenate([[0.0], np.cumsum(z)])
    ends = np.arange(n, len(z) + 1, st)
    return (c[ends] - c[ends - n]) / n


def measure(x, fs):
    """x: (samples, 2). Returns dict with integrated, LRA, max M, max S, last M/S, TP."""
    y = kweight(x, fs)
    z = (y * y).sum(axis=1)
    m = windows(z, fs, 0.4)
    s = windows(z, fs, 3.0)
    lm = lufs(m)
    # integrated (BS.1770-4)
    g = m[lm > -70]
    I = -np.inf
    if len(g):
        rel = lufs(g.mean()) - 10
        g2 = g[lufs(g) > rel]
        I = lufs(g2.mean()) if len(g2) else -np.inf
    # LRA (Tech 3342)
    ls_all = lufs(s)
    sg = s[ls_all > -70]
    LRA = 0.0
    if len(sg):
        rel = lufs(sg.mean()) - 20
        v = np.sort(lufs(sg[lufs(sg) > rel]))
        if len(v):
            LRA = np.percentile(v, 95) - np.percentile(v, 10)
    # true peak, 32x
    pk, hop, pad = 0.0, 1 << 16, 512
    for i in range(0, len(x), hop):
        seg = x[max(0, i - pad):i + hop + pad]
        up = signal.resample_poly(seg, 32, 1, axis=0, window=("kaiser", 12.0))
        pk = max(pk, float(np.abs(up).max()))
    tp = 20 * np.log10(pk + 1e-30)
    return dict(I=I, LRA=LRA, Mmax=lm.max() if len(lm) else -np.inf,
                Smax=ls_all.max() if len(ls_all) else -np.inf,
                M=lm[-1] if len(lm) else -np.inf, S=ls_all[-1] if len(ls_all) else -np.inf, TP=tp)


# ---------------------------------------------------------------- test signals
def tone(fs, segments, f=1000.0, phase=0.0):
    """segments: [(dBFS, seconds)], stereo, same sine in both channels (EBU Tech 3341 style)."""
    parts, t0 = [], 0
    for db, sec in segments:
        n = int(round(sec * fs))
        t = (np.arange(n) + t0) / fs
        parts.append(10 ** (db / 20) * np.sin(2 * np.pi * f * t + phase))
        t0 += n
    s = np.concatenate(parts)
    return np.stack([s, s], axis=1)
