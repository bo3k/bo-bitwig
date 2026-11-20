# Devices

| Device | What |
|---|---|
| [`bo-curve/`](bo-curve) | **bo Curve** (formerly BO Sat): a saturator with Curve, Asym, Drive, Tone, Mix, Output, Link, three modes (smooth, triode, bitcrush) and three skins (Bitwig, Modern, Vintage). |
| [`bo-safety/`](bo-safety) | **bo Safety**: a speaker and hearing protection limiter with Limit (-18 to +10 dB) and TP Margin. |

The `.bwdevice` files are ready to use: put them in a folder listed in BML's **Device Folders** (Framework: Nitro Devices).

## Rebuilding

- **bo Curve:** `python build_bo_sat.py` writes `out/bo Curve.bwdevice`, the same file as the one here. The Vintage skin's images are made from the renders in `satv5/` with `python make_sat_background.py satv5`.
- **bo Safety:** `build_bo_safety_limiter.py` needs a newer device generator than `tools/device-gen/bwgen.py`, as bo Master's devices do. The `.bwdevice` here is the current build.
