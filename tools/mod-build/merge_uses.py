import sys
# merge_uses.py <original uses.tsv> <new uses.tsv>: keep the original row where the member (first 4 columns) is the same
orig = {}
for l in open(sys.argv[1]):
    l = l.rstrip('\n'); k = '\t'.join(l.split('\t')[:4]); orig[k] = l
out = []
for l in open(sys.argv[2]):
    l = l.rstrip('\n'); k = '\t'.join(l.split('\t')[:4]); out.append(orig.get(k, l))
open(sys.argv[2], 'w').write('\n'.join(sorted(set(out))) + '\n')
