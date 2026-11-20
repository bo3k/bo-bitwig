# Tools

| Folder | What |
|---|---|
| [`mod-build/`](mod-build) | JDK 21 stand-ins for the BML mod kit: `CompileJar` (compile stubs from your own `bitwig.jar`), `Finish` (the `opens` a mod needs), `build-mod.sh` (assembles a mod folder). |
| [`device-gen/`](device-gen) | The device generator: `bwgen.py` builds a `.bwdevice` from a description, `nitro_text.py` writes its Nitro code in Bitwig's syntax, `bwobj.py` / `bwdevice.py` read and write device files, and `nitroc.py` parses Nitro. |

The device build scripts in `devices/*/source/` find `device-gen` by its place in the repo. See [BUILDING.md](../BUILDING.md).
