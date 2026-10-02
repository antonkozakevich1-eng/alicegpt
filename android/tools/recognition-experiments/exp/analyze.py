"""Метрики скорости и качества подсветки по траекториям (позиция после каждого события распознавателя).

Для отрывков книги: для каждого слова — когда подсветка его «прошла» (позиция > слово) относительно момента, когда
диктор реально его договорил (время слова оценивается пропорционально числу букв внутри строки).
"""
import sys, os, json, glob, collections, statistics
sys.path.insert(0, 'exp')
from common import *

lines, words = load_book()
_timing = {}
def timing(clip):
    if clip not in _timing:
        _timing[clip] = np.load(f'exp/audio/{clip}.npz')['timing']
    return _timing[clip]

def word_end_times(clip):
    """-> dict word_index -> время окончания слова, секунды."""
    out = {}
    for t0, t1, first, last in timing(clip):
        ws = words[first:last + 1]
        total = sum(len(w) for w in ws)
        acc = 0
        for k, w in enumerate(ws):
            acc += len(w)
            out[int(first) + k] = (t0 + (t1 - t0) * acc / total) / 16000.0
    return out

def analyze_book(trace, clip):
    """trace — позиции после каждого p/f-события (шаг 0,1 с). -> метрики по отрывку."""
    te = word_end_times(clip)
    start = min(te); end = max(te)
    pos = [int(x) for x in trace]
    # время, когда подсветка впервые прошла слово w (позиция >= w+1)
    first_pass = {}
    best = pos[0] if pos else 0
    for k, p in enumerate(pos):
        if p > best:
            for w in range(max(best, start), min(p, end + 1)):
                first_pass.setdefault(w, k * 0.1)
            best = p
    lags = [first_pass[w] - te[w] for w in te if w in first_pass]
    n = len(te)
    return {
        'coverage': len(lags) / n,
        'lags': lags,
        'early': sum(1 for l in lags if l < -0.5),
        'n': n,
    }

def summarize(names_traces):
    agg = collections.defaultdict(list)
    for key, (trace, clip) in names_traces.items():
        a = analyze_book(trace, clip)
        agg['coverage'].append(a['coverage'])
        agg['lags'].extend(a['lags'])
        agg['early'].append(a['early'] / a['n'])
    lags = sorted(agg['lags'])
    def q(p): return lags[int(p * (len(lags) - 1))] if lags else float('nan')
    return {
        'coverage': sum(agg['coverage']) / len(agg['coverage']),
        'lag_mean': sum(lags) / len(lags) if lags else float('nan'),
        'lag_med': q(0.5), 'lag_p90': q(0.9),
        'early': sum(agg['early']) / len(agg['early']),
    }

def load_traces(dirpath, variant, cond):
    out = {}
    for p in glob.glob(f'{dirpath}/{variant}__{cond}__*.txt'):
        clip = os.path.basename(p)[:-4].split('__')[2]
        if 'foreign' in clip or clip.startswith('fl') != (DS == 'fl'): continue
        out[os.path.basename(p)] = ([int(x) for x in open(p).read().split()], clip)
    return out

def false_shift_runs(dirpath, variant, kind, events_dir=EV):
    """kind: foreign | noise | elsewhere. -> (прогонов с ложным сдвигом, всего)."""
    bad = tot = 0
    cond = 'clean' if kind == 'foreign' else kind
    for p in glob.glob(f'{dirpath}/{variant}__{cond}__*.txt'):
        clip = os.path.basename(p)[:-4].split('__')[2]
        if (kind == 'foreign') != ('foreign' in clip) or clip.startswith('fl') != (DS == 'fl'): continue
        tr = [int(x) for x in open(p).read().split()]
        ev = [json.loads(l) for l in open(f'{events_dir}/{os.path.basename(p)[:-4]}.jsonl')]
        start = next(e['pos'] for e in ev if e['k'] == 'start')
        pe = [e for e in ev if e['k'] in ('p', 'f')]
        tot += 1
        if kind in ('foreign', 'noise'):
            wrong = any(c != start for c in tr)
        else:  # elsewhere: сдвиг допустим только туда, где реально читают
            lo = pe[0]['ls']; wrong = any(c != start and not (lo - 3 <= c <= e['le'] + 3) for c, e in zip(tr, pe))
        bad += wrong
    return bad, tot

def line_end_acc(trace, clip, events):
    ev = [e for e in events]
    pe = [e for e in ev if e['k'] in ('p', 'f')]
    # метки «через 0,5 с после конца строки» — позиция в этот момент
    ok = tot = 0
    k = 0
    for e in ev:
        if e['k'] in ('p', 'f'):
            k += 1
        elif e['k'] == 'mark':
            tot += 1
            pos = trace[min(k, len(trace)) - 1]
            if e['le'] + 1 - 3 <= pos <= e['le'] + 2: ok += 1
    return ok / max(1, tot)

if __name__ == '__main__':
    d = sys.argv[1]
    variants = sys.argv[2].split(',')
    conds = sys.argv[3].split(',') if len(sys.argv) > 3 else ['clean', 'quiet_gain', 'noisy', 'quietnoisy_gain']
    print(f'{"вариант":18s}' + ''.join(f'{c:>40s}' for c in conds))
    print(f'{"":18s}' + ''.join(f'{"покрытие  лаг,с (сред/мед/p90)  рано  строки":>40s}' for c in conds))
    for v in variants:
        row = f'{v:18s}'
        for c in conds:
            tr = load_traces(d, v, c)
            if not tr: row += f'{"-":>40s}'; continue
            s_ = summarize(tr)
            la = []
            for name, (t, clip) in tr.items():
                ev = [json.loads(l) for l in open(f'{EV}/{name[:-4]}.jsonl')]
                la.append(line_end_acc(t, clip, ev))
            row += f'{s_["coverage"]:9.2f}  {s_["lag_mean"]:5.2f}/{s_["lag_med"]:5.2f}/{s_["lag_p90"]:5.2f}  {s_["early"]:5.3f}  {sum(la)/len(la):5.2f}'.rjust(40)
        print(row)
    print()
    print(f'{"ложные сдвиги":18s}{"чужая речь":>14s}{"шум":>10s}{"другое место":>16s}')
    for v in variants:
        r = []
        for kind in ('foreign', 'noise', 'elsewhere'):
            b, t = false_shift_runs(d, v, kind)
            r.append(f'{b} из {t}' if t else '-')
        print(f'{v:18s}{r[0]:>14s}{r[1]:>10s}{r[2]:>16s}')
