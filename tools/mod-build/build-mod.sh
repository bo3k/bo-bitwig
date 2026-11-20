#!/bin/bash
# Builds a BML mod (no @Hook mods only) with JDK 21, standing in for bml-modkit (which needs JDK 25).
#   build-mod.sh <mod source folder> [framework-id ...]
# Output: out/<mod-id>/ (installable folder, with src/, build.json, uses.tsv, SHA256SUMS.txt)
#         out/<mod-id>-<version>.zip
set -euo pipefail
cd "$(dirname "$0")"
MOD=$(realpath "$1"); shift
ID=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['mod']['id'])" "$MOD/mod.json")
VER=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['mod']['version'])" "$MOD/mod.json")
X="--add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED"
WORK=build/$ID; OUT=out/$ID
rm -rf "$WORK" "$OUT"; mkdir -p "$WORK/classes" "$OUT"
DEPS=""; for fw in "$@"; do DEPS="$DEPS:fw/$fw/helper.jar"; done
CP="jars/bitwig614-compile.jar:jars/libs-compile.jar:kit/bml-api.jar$DEPS"
javac --release 21 -proc:none -encoding UTF-8 -implicit:none -Xlint:-options -sourcepath "$MOD/src" -cp "$CP" -d "$WORK/classes" $(find "$MOD/src" -name '*.java') 2>&1 | grep -v JAVA_TOOL || true
[ -n "$(find "$WORK/classes" -name '*.class')" ] || { echo "COMPILE_FAILED"; exit 1; }
java $X -cp tools Finish "$WORK/classes" "$WORK/hooks.json" "$WORK/uses.tsv" jars/bitwig.jar jars/libs.jar 2>&1 | grep -v JAVA_TOOL | tee "$WORK/finish.log"
grep -q '^FINISH_OK' "$WORK/finish.log" || { echo "FINISH_FAILED"; exit 1; }
(cd "$WORK/classes" && find . -type f | sort | zip -q -X -D "../helper.jar" -@)
cp "$WORK/helper.jar" "$OUT/helper.jar"
# finished mod.json: targets + hooks + opens from hooks.json (building.md, "The finished mod.json")
python3 - "$MOD/mod.json" "$WORK/hooks.json" "$OUT/mod.json" <<'PY'
import json, re, sys
src, hooks, out = sys.argv[1:4]
m = json.load(open(src)); h = json.load(open(hooks))
targets = {}; opens = []
def tid(t):
    for k, v in targets.items():
        if v == t: return k
    base = t["class"] if t["kind"] == "class" else t["owner"] + "." + t["name"]
    base = re.sub(r"[^A-Za-z0-9_.-]", "", base.replace("/", ".").replace("$", "_"))
    k, n = base, 2
    while k in targets: k = f"{base}-{n}"; n += 1
    targets[k] = t; return k
for a in h["access"]: opens.append(tid(a))
res = {"manifest_version": m["manifest_version"], "mod": m["mod"], "built_against": m["built_against"],
       "targets": targets, "hooks": []}
if opens: res["opens"] = sorted(opens)
for k, v in m.items():
    if k not in res: res[k] = v
json.dump(res, open(out, "w"), indent=2); open(out, "a").write("\n")
PY
cp "$MOD/README.md" "$OUT/"
[ -d "$MOD/images" ] && [ -n "$(ls -A "$MOD/images")" ] && cp -r "$MOD/images" "$OUT/"
cp -r "$MOD/package/." "$OUT/"
cp -r "$MOD/src" "$OUT/"; cp "$MOD/build.json" "$OUT/"; cp "$WORK/uses.tsv" "$OUT/"
(cd "$OUT" && for f in README.md helper.jar mod.json $(cd "$MOD/package" && find . -type f | sed 's#^\./##' | sort) uses.tsv; do sha256sum "$f"; done > SHA256SUMS.txt)
rm -f "out/$ID-$VER.zip"; (cd out && zip -q -r "$ID-$VER.zip" "$ID")
echo "BUILD_OK $ID $VER -> $OUT"
