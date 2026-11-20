#!/bin/bash
# jp.sh <jar> <internal/Class/name> [javap args...] : javap -c on a class from a Java-25 jar via a version-patched copy
J=$1; C=$2; shift 2
D=$(mktemp -d); python3 - "$J" "$C" "$D" <<'PY'
import zipfile,sys,os
j,c,d=sys.argv[1:4]; b=bytearray(zipfile.ZipFile(j).read(c+'.class')); b[6]=0;b[7]=65
p=os.path.join(d,c+'.class'); os.makedirs(os.path.dirname(p),exist_ok=True); open(p,'wb').write(b)
PY
javap -cp "$D" "$@" "$(echo $C | tr / .)" 2>&1 | grep -v JAVA_TOOL; rm -rf $D
