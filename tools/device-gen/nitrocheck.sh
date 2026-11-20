#!/bin/bash
# nitrocheck.sh file.nitro [samplerate] : compile with Bitwig 6.1.4's own Nitro front end (offline)
set -e
export PYTHONPATH=/tmp/bitwig-nitro-tools/src
python3 -c "
import sys; sys.path.insert(0,'/root/bwdev')
import nitroc
from bitwig_nitro import serialize_nitrobin
open('/tmp/nc_field.bin','wb').write(b'\x00'+serialize_nitrobin(nitroc.parse(open(sys.argv[1]).read())))
" "$1"
cd /root/bml/nitrocheck
java -DinstallationRoot=/tmp/bwroot -cp .:../jars/bitwig-indy21.jar:../jars/bitwig-rt65.jar:../jars/libs.jar bo.NitroCheck /tmp/nc_field.bin ${2:-48000} 2>&1 | grep -v "JAVA_TOOL\|nitro warning" | head -${LINES_OUT:-6}
