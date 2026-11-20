# BO LUFS

Loudness and true-peak meter as a native Bitwig device (ITU-R BS.1770-4, EBU R128).
Audio passes through untouched.

Build: `python build_bo_lufs.py` in `bo-device-tools` → `out/BO LUFS.bwdevice`
(needs bitwig-nitro-tools on the Python path, like the other BO devices).

## What it measures

| Value | How |
|---|---|
| M (momentary) | 400 ms, updated every 100 ms |
| S (short-term) | 3 s, updated every 100 ms |
| I (integrated) | BS.1770-4 gating (−70 LUFS absolute, −10 LU relative) since the last Reset; 0.1 LU histogram with exact energy sums, so memory stays fixed |
| LRA | EBU Tech 3342: short-term values, −70 absolute / −20 LU relative gate, P95 − P10 |
| TP | 4× polyphase interpolation, 48 taps; max since Reset (readout), last 100 ms (bar) |

Panel: only Bitwig's own widgets. Native meters (M, S, I, TP; linear −60 … 0 dB like Polarity's
Normalizer), number readouts, labels, LEDs, a Reference knob and a Reset trigger button.
The LEDs next to S and I light when that value is at or above Reference (native readouts have a
fixed colour, so they can't turn orange themselves).

## Nitro outputs

- `out`: the audio, unchanged
- `m_meter`, `s_meter`, `i_meter`, `tp_meter`: steady signals (amplitude 10^(L/20)) for the meters
- `s_over`, `i_over`: 1 when S / I ≥ Reference (LEDs)
- `m_val`, `s_val`, `i_val`, `lra_val`, `tp_val`: readout values, sent every 100 ms (−100 = no value)

## Offline check with Bitwig's own Nitro compiler

`../nitrocheck.sh file.nitro` runs Bitwig 6.1.4's Nitro front end (parse, type check, code
generation) from `bitwig.jar` on Java 21, with the switch call sites patched (`nitrocheck/PatchIndy.java`)
and `Library/nitro-std` / `nitro-image` from the install. The first BO LUFS build failed in Bitwig with
"Type mismatch: 1.58… // f64" (an `exp()` of constants); the checker reproduces that exact error and
also caught "send must be used within the process method".

## Files

| File | What |
|---|---|
| `bo_lufs.nitro.in`, `tp_fill.inc` | DSP source; the build inlines the true-peak coefficients |
| `bo_lufs_font.sksl`, `bo_lufs_bars.sksl`, `bo_lufs_readout.sksl` | First version's shader displays (not used now) |
| `ref_loudness.py` | Independent float64 reference straight from the standards |
| `sim_bo_lufs.py` | Model of the Nitro algorithm (histograms, gates, filter) |
| `test_bo_lufs.py` | EBU Tech 3341 cases 1–5, Tech 3342 cases 1–4, random programme vs reference |
| `nitro2py.py`, `test_literal.py` | Runs the Nitro text itself (transpiled) sample by sample against the model |
| `tp_design.py`, `tp_eval.py` | True-peak filter design and accuracy check |
| `sksl_preview/` | Compiles a shader as C++ to render previews (also catches vector/scalar mixing) |

## Test results (Nov 2026)

- Integrated, Tech 3341 cases 1–5 at 44.1/48/96 kHz: all within ±0.1 LU (worst −23.07 vs −23).
- LRA, Tech 3342 cases 1–4: exact (10, 5, 20, 15 LU).
- Random programme vs reference: I within 0.02 LU, LRA within 0.22 LU, M/S max identical,
  TP −0.13 to −0.23 dB (tolerance +0.2 / −0.4).
- Nitro text vs model: identical values on every 100 ms block, including a Reset mid-way.
