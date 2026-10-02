import sys, glob, os, json, difflib, collections
sys.path.insert(0, 'exp')
from common import *
lines, words = load_book()

def word_acc(path):
    ev = [json.loads(l) for l in open(path, encoding='utf-8')]
    start = next(e['pos'] for e in ev if e['k'] == 'start')
    end = next((e['le'] for e in ev if e['k'] == 'end'), None)
    if end is None: return None
    ref = words[start:end + 1]
    hyp = ' '.join(e['text'] for e in ev if e['k'] == 'f' and e['text']).split()
    hyp = [w.replace('ё', 'е') for w in hyp if w != '[unk]']
    sm = difflib.SequenceMatcher(None, ref, hyp, autojunk=False)
    return sum(b.size for b in sm.get_matching_blocks()) / max(1, len(ref))

variants = sys.argv[1].split(','); conds = sys.argv[2].split(',')
print(f'DS={DS}')
print(f'{"вариант":16s}' + ''.join(f'{c:>16s}' for c in conds) + f'{"среднее":>10s}')
for v in variants:
    vals = []
    for c in conds:
        accs = []
        for p in glob.glob(f'{EV}/{v}__{c}__*.jsonl'):
            clip = os.path.basename(p)[:-6].split('__')[2]
            if 'foreign' in clip: continue
            a = word_acc(p)
            if a is not None: accs.append(a)
        vals.append(sum(accs) / len(accs) if accs else float('nan'))
    print(f'{v:16s}' + ''.join(f'{x:16.3f}' for x in vals) + f'{sum(vals)/len(vals):10.3f}')
