import sys, struct
sys.path.insert(0, '/home/claude/bwdev')
from bwdevice import BwDevice
import bwobj
def fmt(f):
    t,v=f.type,f.value
    if isinstance(v,bwobj.Ref): return f'->#{v.target.oid+1} ({v.target.cls:#x})'
    if t==8: return '"'+bwobj.decode_str(v)[:200].replace('\n','\\n')+'"'
    if t in (1,2,3): return str(int.from_bytes(v,'big'))
    if t==5: return 'T' if v[0] else 'F'
    if t==6: return f'{struct.unpack(">f",v)[0]:g}f'
    if t==7: return f'{struct.unpack(">d",v)[0]:g}'
    if t==0x16: return 'rgba'+str(tuple(round(x,3) for x in struct.unpack(">4f",v)))
    if t==0x0a: return 'null'
    if t==0x0d: return f'<{len(v)}B>'
    if t==0x15: return 'uuid'
    return f't{t:#x}'
def dump(o,d,out):
    out.append(f'{"  "*d}#{o.oid+1} cls {o.cls:#x}')
    for f in o.fields:
        v=f.value
        if isinstance(v,bwobj.Obj): out.append(f'{"  "*(d+1)}{f.fid:#x}:'); dump(v,d+2,out)
        elif isinstance(v,list):
            out.append(f'{"  "*(d+1)}{f.fid:#x}: [{len(v)}]')
            for x in v:
                if isinstance(x,bwobj.Obj): dump(x,d+2,out)
                else: out.append(f'{"  "*(d+2)}->#{x.target.oid+1} ({x.target.cls:#x})')
        elif v is None: out.append(f'{"  "*(d+1)}{f.fid:#x}: none')
        else: out.append(f'{"  "*(d+1)}{f.fid:#x} = {fmt(f)}')
if __name__=='__main__':
    root=bwobj.parse_body(bytes(BwDevice(open(sys.argv[1],'rb').read()).body))
    bwobj.number(root); out=[]; dump(root,0,out); print('\n'.join(out))
