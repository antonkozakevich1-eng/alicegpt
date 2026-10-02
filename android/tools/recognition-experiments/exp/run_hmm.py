import sys, os, json, glob, time
sys.path.insert(0, 'exp')
from common import *
import importlib, os
Hmm = importlib.import_module(os.environ.get("HMM_MODULE", "hmm")).Hmm
from multiprocessing import Pool
import re

lines, words = load_book()
_h = None
def words_of(text):
    t = text.replace('[unk]', ' ').replace('ё', 'е')
    return re.findall(r'[^\W_]+', t.lower())

def run_file(args):
    path, outdir, kw = args
    global _h
    if _h is None or kw != getattr(run_file, 'kw', None):
        _h = Hmm(words, **kw); run_file.kw = kw
    ev = [json.loads(l) for l in open(path, encoding='utf-8')]
    start = next(e['pos'] for e in ev if e['k'] == 'start')
    _h.seek(start)
    trace = []
    moves = 0
    for e in ev:
        if e['k'] in ('p', 'f'):
            w = words_of(e['text'])
            m = _h.on_words(w, final=(e['k'] == 'f'))
            if m: moves += 1
            trace.append(_h.cursor)
    name = os.path.basename(path)[:-6]
    open(f'{outdir}/{name}.txt', 'w').write('\n'.join(map(str, trace)) + '\n')
    return name, moves

if __name__ == '__main__':
    variants = sys.argv[1].split(','); conds = sys.argv[2].split(','); outdir = sys.argv[3]
    kw = json.loads(sys.argv[4]) if len(sys.argv) > 4 else {}
    os.makedirs(outdir, exist_ok=True)
    jobs = []
    for v in variants:
        for c in conds:
            for p in glob.glob(f'{EV}/{v}__{c}__*.jsonl'):
                jobs.append((p, outdir, kw))
    t0 = time.time()
    with Pool(4) as pool:
        res = pool.map(run_file, jobs, chunksize=4)
    print('files', len(res), f'{time.time()-t0:.0f}s')
