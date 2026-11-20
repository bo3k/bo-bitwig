# bo-bitwig

Bo's mods and native devices for Bitwig Studio, run with the Patchbay mod loader (BML). They work in Bitwig 6.1.4 and 6.1.7 (Windows x64).

| | What | Where |
|---|---|---|
| **Save Incrementally** | File > Save Incrementally: saves the project as its next numbered version (`Mix_009` → `Mix_010`, `Mix` → `Mix_001`) | `mods/save-incrementally` |
| **Insert Device via Shortcut** | Shortcuts, with no keys of their own, that insert Tool, Chain, Pro-Q 4 (VST3 or CLAP) and every Bitwig audio effect and instrument on the current track | `mods/insert-device-via-shortcut` |
| **bo Master** | A panel with the master track's strip: VU, LUFS, fader, pan, peak/RMS meters, inserts, spectrum and goniometer | `mods/master-bo` |
| **bo LUFS** | Loudness meter (TP, M, S, I) whose readings bo Master shows | `devices/`, sources in `device-tools/lufs` |
| **bo Spectrum** | Spectrum analyser that feeds bo Master's spectrum | `devices/`, sources in `device-tools/spectrum` |
| **bo Scope** | Goniometer that feeds bo Master's scope | `devices/`, sources in `device-tools/scope` |

## Requirements

- Bitwig Studio 6.1.4 or 6.1.7 (Windows x64) with BML 0.36.0-dev.13. BML translates the mods, which are built against 6.1.4, to 6.1.7 itself.
- Save Incrementally and Insert Device via Shortcut: **Framework: Actions and Menus** (`action-menu-composition-framework`) 0.7.0-dev.1 or later.
- bo Master: **Framework: Panels** (`panel-management-framework`) 0.2.0-dev.2.bo.5, Bo's build of shaboogen's framework. It is not in this repo.
- The devices: **Framework: Nitro Devices** (`custom-device-registrar`), with the folder holding them listed in BML's Device Folders.

## Install

- **Mods:** build them (below), copy each built mod folder into `BML-…\mods\`, and tick it in BML.
- **Devices:** copy the `.bwdevice` files from `devices/` into a folder listed in BML's Device Folders.

Save Incrementally and Insert Device via Shortcut replace the earlier BO Tools mod and keep its action IDs, so keys assigned there still work. Don't tick BO Tools together with them.

## Build

**Mods** (`mods/_build-tools`): JDK 21 stand-ins for the BML mod kit.

- `CompileJar` makes compile-only stubs from your installed `bitwig.jar`. The stubs and the jar stay on your machine; `.gitignore` keeps them out.
- `Finish` rewrites the stub package and lists the `opens` a mod needs.
- `build-mod.sh` assembles the mod folder.

Save Incrementally's File-menu hook (`REPLACE_INVOKE` on the window's File menu) is written into its `mod.json` by hand.

**Devices** (`device-tools`): Python 3.10+ and [bitwig-nitro-tools](https://github.com/blakebratcher/bitwig-nitro-tools), installed with `pip install -e .` or checked out next to `device-tools`.

- `bwgen.py` is the device generator. `device-tools/README.md` covers the format.
- A device stores its DSP as **Nitro source** in Bitwig's own syntax, and Bitwig compiles it when it loads the device.
  - `nitro_text.py` converts our `.nitro` files to that syntax.
  - Bitwig 6.1.7 changed the precompiled (binary) form, so a binary built for 6.1.4 doesn't load in 6.1.7, and a 6.1.7 binary doesn't load in 6.1.4. Source loads in both.
- `build_bo_lufs.py`, `build_bo_spectrum.py` and `build_bo_scope.py` need a newer `bwgen.py` than the one here, one with trigger buttons, readouts and Bitwig's scope and spectrum displays. It hasn't been added yet. The `.bwdevice` files in `devices/` are the current builds.

## Not in this repo

- Bitwig's files: `bitwig.jar`, stubs or classes made from it, and Bitwig's own resources.
- BML, its frameworks and other people's mods.
- bo Curve (formerly BO Sat) and bo Magnetic. They will follow.
