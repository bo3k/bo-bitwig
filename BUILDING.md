# Building from source

For people who want to change or rebuild the mods and devices. To just use them, take the files from the [Releases](https://github.com/bo3k/bo-bitwig/releases) page; see the [README](README.md).

## Layout

| Folder | What |
|---|---|
| `mods/<mod>/` | A mod: `mod.json`, `build.json`, `src/` (Java), `package/` (files copied into the mod), `media/` (pictures, shipped with the mod so Patchbay's README viewer shows them). |
| `devices/<device>/` | A device: the built `.bwdevice`, `media/`, and `source/` with its build script, Nitro DSP and SkSL drawings. |
| `tools/mod-build/` | JDK 21 stand-ins for the BML mod kit. |
| `tools/device-gen/` | The device generator and helpers. |

## Mods

Built with `tools/mod-build`:

- `CompileJar` makes compile-only stubs from your installed `bitwig.jar`. The stubs and the jar stay on your machine; `.gitignore` keeps them out.
- `Finish` rewrites the stub package and lists the `opens` a mod needs.
- `build-mod.sh <mod folder> [framework …]` assembles the installable mod folder and a Patchbay zip (one top folder named after the mod's ID, as Add Mod… requires).

bo Master compiles against the Panels framework's `helper.jar` (`build-mod.sh mods/bo-master panel-management-framework`); the two workflow mods against Framework: Actions and Menus. Mods are built against Bitwig 6.1.4; BML translates them to 6.1.7 itself.

### Why bo Master needs its own Panels build

bo Master's view is declared with `side("browser")`, `sideFirst(true)` and `sideColumn(...)`. Those calls exist only in this build of shaboogen's framework (`0.2.0-dev.2.bo.5`); the original `0.2.0-dev.2` that ships with BML can only place views in the main area and its stacks. What the build adds is in [`mods/panels-framework/CHANGES.md`](mods/panels-framework/CHANGES.md). The framework is MIT-licensed; its licence is kept in that folder and in its zip.

## Devices

Each device stores its DSP as Nitro source in Bitwig's own syntax, and Bitwig compiles it when it loads the device. Bitwig 6.1.7 changed the precompiled form, so a precompiled device from one version doesn't load in the other; source loads in both.

Build scripts are in each device's `source/` folder: Python 3.10+, and [bitwig-nitro-tools](https://github.com/blakebratcher/bitwig-nitro-tools) installed with `pip install -e .` or named in `BITWIG_NITRO_TOOLS`.

- **bo Curve:** `python build_bo_sat.py` writes `out/bo Curve.bwdevice`; then `python ../../../tools/device-gen/theme_greys.py curve "out/bo Curve.bwdevice" "../bo Curve.bwdevice"` gives the file in the repo. The Vintage skin's images are made from the renders in `satv5/` with `python make_sat_background.py satv5`.
- **bo Safety, bo LUFS, bo Spectrum, bo Scope:** their scripts need a newer generator than `tools/device-gen/bwgen.py` (trigger buttons, value readouts, Bitwig's scope and spectrum displays). It isn't in the repo yet; the `.bwdevice` files are the current builds.

### bo LUFS's values for bo Master

`tools/device-gen/lufs_scopes.py` gives bo LUFS two of Bitwig's oscilloscopes, fed by four audio outputs of its DSP (M and S, I and TP), which bo Master reads. It used to pass them through hidden readout parameters, but a value the DSP sends to a parameter is an undoable change in Bitwig, ten a second. Run it after `theme_greys.py`; the `.bwdevice` in the repo has both.

### Reset buttons

No Bitwig button style draws nothing, and a hidden button takes no clicks (`tools/device-gen` has no test for it; it was checked in Bitwig with a test device). `reset_buttons.py lufs|spectrum <in> <out>` keeps bo LUFS's reset buttons only under its readouts, whose dark boxes cover them, and gives bo Spectrum an ordinary Reset button. `spectrum_layout.py <in> <new nitro> <out>` then lays out bo Spectrum's bottom rows (Hold label with Off, Plot / 1/3 Oct. under Stereo / M/S, a square Reset at the right) and changes its DSP to match.

### Theme greys

`tools/device-gen/theme_greys.py <device> <in> <out>` makes a built device's greys follow Bitwig's theme (`lufs`, `spectrum`, `scope`, `safety`, `curve`, `magnetic`). A drawing can't read the theme, so each grey is drawn as black or white over what Bitwig draws under it; in the default theme it looks exactly as before. bo Curve's Bitwig skin gets its own themed graph. Run it on a fresh build; the `.bwdevice` files in the repo are already patched.

## Releases

One release holds everything, with the same file names every time, so the download links in the READMEs (`releases/latest/download/<file>`) always point at the newest files:

`bo-master.zip`, `panels-framework.zip`, `save-incrementally.zip`, `insert-device-via-shortcut.zip`, `bo-Curve.bwdevice`, `bo-Safety.bwdevice`, `bo-LUFS.bwdevice`, `bo-Spectrum.bwdevice`, `bo-Scope.bwdevice`, `bo-devices.zip` (all five, with their real names) and `bo-master-devices.zip` (bo LUFS, bo Spectrum and bo Scope).

A release must not be marked as a pre-release, or `latest` skips it. The mods' versions are inside the mods (Patchbay shows them), not in the file names.

## Not in this repo

- Bitwig's files: `bitwig.jar`, stubs or classes made from it, and Bitwig's own resources.
- BML and its frameworks, apart from the Panels build bo Master needs, and other people's mods.
- bo Magnetic.
