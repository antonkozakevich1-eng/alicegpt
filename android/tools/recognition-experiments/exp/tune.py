"""Подбор параметров: прогон набора записей с заданными параметрами, вывод ключевых метрик одной строкой."""
import sys, os, json, glob, time, itertools
sys.path.insert(0, 'exp')
from common import *
import analyze
from run_hmm import run_file
from multiprocessing import Pool

SETS = {
    'quick': dict(variants=['zip_hot20@300', 'words_yo@300'], conds=['clean', 'noisy', 'foreign', 'noise', 'elsewhere']),
    'full': dict(variants=['zip_hot20@300', 'zip_small', 'words_yo@300', 'nogram'], conds=['clean', 'quiet_gain', 'noisy', 'quietnoisy_gain', 'foreign', 'noise', 'elsewhere']),
}

def evaluate(kw, setname='quick', out=None):
    out = out or (TR + '_tune')
    s = SETS[setname]
    os.makedirs(out, exist_ok=True)
    jobs = []
    for v in s['variants']:
        for c in s['conds']:
            for p in glob.glob(f'{EV}/{v}__{c}__*.jsonl'):
                jobs.append((p, out, kw))
    with Pool(4) as pool:
        pool.map(run_file, jobs, chunksize=4)
    rows = {}
    for v in s['variants']:
        cov = []; lagm = []; lagp90 = []; line = []; early = []
        for c in [c for c in s['conds'] if c in ('clean', 'quiet_gain', 'noisy', 'quietnoisy_gain')]:
            tr = analyze.load_traces(out, v, c)
            if not tr: continue
            sm = analyze.summarize(tr)
            cov.append(sm['coverage']); lagm.append(sm['lag_mean']); lagp90.append(sm['lag_p90']); early.append(sm['early'])
            la = []
            for name, (t, clip) in tr.items():
                ev = [json.loads(l) for l in open(f'{EV}/{name[:-4]}.jsonl')]
                la.append(analyze.line_end_acc(t, clip, ev))
            line.append(sum(la) / len(la))
        fs = [analyze.false_shift_runs(out, v, k) for k in ('foreign', 'noise', 'elsewhere')]
        rows[v] = dict(cov=sum(cov)/len(cov), lag=sum(lagm)/len(lagm), p90=sum(lagp90)/len(lagp90), line=sum(line)/len(line), early=sum(early)/len(early),
                       fs=[f'{b}/{t}' for b, t in fs], fsn=sum(b for b, t in fs))
    return rows

def fmt(rows):
    return ' | '.join(f'{v.split("@")[0]}: cov {r["cov"]:.2f} lag {r["lag"]:.2f}/{r["p90"]:.2f} line {r["line"]:.2f} early {r["early"]:.3f} FALSE {"+".join(r["fs"])}' for v, r in rows.items())

if __name__ == '__main__':
    base = json.loads(sys.argv[1]) if len(sys.argv) > 1 else {}
    t0 = time.time()
    rows = evaluate(base, sys.argv[2] if len(sys.argv) > 2 else 'quick')
    print(fmt(rows), f'[{time.time()-t0:.0f}s]')
