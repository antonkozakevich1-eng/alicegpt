"""Режим «поиск места»: читатель в другом месте книги. Нужно найти место быстро и не сдвигаться на чужой речи/шуме."""
import sys, os, json, glob, time
sys.path.insert(0, 'exp')
from common import *
import importlib
Hmm = importlib.import_module('hmm2').Hmm
import re
from multiprocessing import Pool

lines, words = load_book()
def wo(t): return re.findall(r'[^\W_]+', t.replace('[unk]', ' ').replace('ё', 'е').lower())
_h = {}
def run(args):
    path, kw = args
    key = json.dumps(kw, sort_keys=True)
    if key not in _h: _h[key] = Hmm(words, **kw)
    h = _h[key]
    ev = [json.loads(l) for l in open(path, encoding='utf-8')]
    start = next(e['pos'] for e in ev if e['k'] == 'start')
    h.seek(start)
    pe = [e for e in ev if e['k'] in ('p', 'f')]
    first_ok = None; moved = False; final_ok = False
    for k, e in enumerate(pe):
        h.on_words(wo(e['text']), final=(e['k'] == 'f'))
        if h.cursor != start: moved = True
        if first_ok is None and abs(h.cursor - (e['le'] + 1)) <= 15 and e['le'] >= 0 and h.cursor != start:
            first_ok = k
    last = pe[-1]
    final_ok = abs(h.cursor - (next(x for x in reversed(ev) if x['k'] == 'end')['le'] + 1)) <= 15
    return moved, first_ok, final_ok

def evaluate(kw, variants, conds):
    out = {}
    with Pool(4) as pool:
        for v in variants:
            for c in conds:
                paths = [p for p in glob.glob(f'exp/events/{v}__{c if c != "foreign" else "clean"}__*.jsonl') if (('foreign' in p) == (c == 'foreign'))]
                res = pool.map(run, [(p, kw) for p in paths], chunksize=2)
                out[(v, c)] = res
    return out

if __name__ == '__main__':
    kw = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {}
    variants = ['zip_small', 'nogram']
    out = evaluate(kw, variants, ['elsewhere', 'foreign', 'noise'])
    for v in variants:
        el = out[(v, 'elsewhere')]
        found = sum(1 for m, f, ok in el if ok)
        times = sorted(f * 0.1 for m, f, ok in el if f is not None)
        med = times[len(times) // 2] if times else float('nan')
        fo = out[(v, 'foreign')]; no = out[(v, 'noise')]
        print(f'{v:10s} читают другое место: нашли {found}/{len(el)} (медиана {med:.1f} с); '
              f'ложных: чужая речь {sum(1 for m,_,_ in fo if m)}/{len(fo)}, шум {sum(1 for m,_,_ in no if m)}/{len(no)}')
