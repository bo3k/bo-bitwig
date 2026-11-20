# bo-bitwig

Bo's mods and native devices for Bitwig Studio, run with the Patchbay mod loader (BML). For Bitwig 6.1.4 and 6.1.7, Windows x64, with BML 0.36.0-dev.13.

| Folder | What |
|---|---|
| [`bo-master/`](bo-master) | **bo Master**: a panel with the master track's strip, VU, LUFS, spectrum and goniometer. Its meter devices (bo LUFS, bo Spectrum, bo Scope), and the build of Framework: Panels it needs. |
| [`workflow-mods/`](workflow-mods) | **Save Incrementally** (File menu) and **Insert Device via Shortcut**. |
| [`devices/`](devices) | Stand-alone devices: **bo Curve** (saturator) and **bo Safety** (speaker and hearing protection limiter). |
| [`tools/`](tools) | Shared build tools: `mod-build` for the mods, `device-gen` for the devices. |

## Install

Ready-made builds are on the [Releases](https://github.com/bo3k/bo-bitwig/releases) page.

- **Mods:** each mod is a Patchbay-ready ZIP. In BML, open the Mods page and use **Add Mod…** (Ctrl+O), or drop the ZIPs on the window, then tick the mods. Several ZIPs can be added at once. Needs BML 0.36.0-dev.13 or later.
- **Devices:** unpack the device ZIP into a folder of your choice and add that folder in **Tools > Device Folders**.

| Release | Contents |
|---|---|
| bo Master | `master-bo` and `panel-management-framework` (Bo's build, replaces the stock one) mod ZIPs, and `bo-master-devices.zip` (bo LUFS, bo Spectrum, bo Scope). Install all three. |
| Workflow mods | `save-incrementally` and `insert-device-via-shortcut` mod ZIPs. |
| Devices | `bo-curve-and-safety.zip` (bo Curve, bo Safety). |

## Mods

Mods are built from source with `tools/mod-build`: JDK 21 stand-ins for the BML mod kit.

- `CompileJar` makes compile-only stubs from your installed `bitwig.jar`. The stubs and the jar stay on your machine; `.gitignore` keeps them out.
- `Finish` rewrites the stub package and lists the `opens` a mod needs.
- `build-mod.sh` assembles the mod folder.

Copy each built mod folder into `BML-…\mods\` (or zip it with the folder at the top and use Add Mod…) and tick it in BML. BML translates mods built against 6.1.4 to 6.1.7 itself.

## Devices

The `.bwdevice` files are ready to use: put them in a folder listed in BML's **Device Folders** (Framework: Nitro Devices).

Each device stores its DSP as Nitro source in Bitwig's own syntax, and Bitwig compiles it when it loads the device. Bitwig 6.1.7 changed the precompiled form, so a precompiled device from one version doesn't load in the other; source loads in both.

To rebuild a device, use the `build_*.py` script next to it: Python 3.10+, and [bitwig-nitro-tools](https://github.com/blakebratcher/bitwig-nitro-tools) installed with `pip install -e .` or named in `BITWIG_NITRO_TOOLS`. Some scripts need a newer generator than the one in `tools/device-gen`; each folder's README says which.

## Not in this repo

- Bitwig's files: `bitwig.jar`, stubs or classes made from it, and Bitwig's own resources.
- BML and its frameworks, apart from the Panels build bo Master needs, and other people's mods.
- bo Magnetic.
