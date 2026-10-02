import sys, json, os, glob, time
sys.path.insert(0, 'exp')
from common import *
from vosk import Model, KaldiRecognizer, SetLogLevel
from multiprocessing import Pool
SetLogLevel(-1)

lines, words = load_book()
OUT = 'exp/events'
os.makedirs(OUT, exist_ok=True)
CHUNK = 1600  # 100 мс, как в приложении

def pink(n, rng):
    x = rng.normal(size=n)
    X = np.fft.rfft(x)
    f = np.arange(len(X)); f[0] = 1
    X = X / np.sqrt(f)
    y = np.fft.irfft(X, n)
    return y / (np.std(y) + 1e-9)

def active_rms(x):
    fr = x[: len(x) // 320 * 320].reshape(-1, 320)
    r = np.sqrt((fr ** 2).mean(axis=1))
    floor = np.percentile(r, 10)
    act = r[r > max(2 * floor, 1e-5)]
    return float(np.sqrt((act ** 2).mean())) if len(act) else float(r.mean())

def make_condition(audio, cond, seed):
    rng = np.random.default_rng(seed)
    a = audio / (active_rms(audio) + 1e-9)          # активная речь -> RMS 1
    n = len(a)
    if cond == 'clean':   x = a * 0.10
    elif cond in ('quiet', 'quiet_gain'):  x = a * 0.006 + rng.normal(size=n) * 0.0012
    elif cond == 'noisy': x = a * 0.08 + pink(n, rng) * 0.03
    elif cond in ('quietnoisy', 'quietnoisy_gain'): x = a * 0.006 + pink(n, rng) * 0.002
    elif cond == 'noise': x = pink(n, rng) * 0.03 + rng.normal(size=n) * 0.002
    elif cond == 'elsewhere': x = a * 0.05 + pink(n, rng) * 0.004
    else: raise ValueError(cond)
    if cond.endswith('_gain'):
        g = min(0.1 / (active_rms(x) + 1e-9), 30.0)   # то, что делает программное усиление в приложении
        x = x * g
    return np.clip(x, -1, 1)

def yo_variants(w):
    idx = [i for i, c in enumerate(w) if c == 'е']
    if not idx: return [w]
    out = {w}
    if len(idx) <= 3:
        for mask in range(1, 1 << len(idx)):
            s = list(w)
            for b, i in enumerate(idx):
                if mask >> b & 1: s[i] = 'ё'
            out.add(''.join(s))
    else:
        for i in idx:
            s = list(w); s[i] = 'ё'; out.add(''.join(s))
    return sorted(out)

def grammar(variant, center):
    kind, _, size = variant.partition('@')
    size = int(size) if size else 300
    back = size // 6
    win = words[max(0, center - back): center + size - back]
    if kind == 'nogram': return None
    if kind in ('words', 'words_yo'):
        u = sorted(set(win))
        if kind == 'words_yo': u = sorted({v for w in u for v in yo_variants(w)})
        return json.dumps(u + ['[unk]'], ensure_ascii=False)
    if kind in ('bi', 'tri', 'quad'):
        n = {'bi': 2, 'tri': 3, 'quad': 4}[kind]
        phrases = set()
        for i in range(len(win) - n + 1):
            combos = [[]]
            for w in win[i:i + n]:
                vs = yo_variants(w)
                combos = [c + [v] for c in combos for v in vs]
                if len(combos) > 12:
                    combos = [win[i:i + n]]
                    break
            for c in combos: phrases.add(' '.join(c))
        return json.dumps(sorted(phrases) + ['[unk]'], ensure_ascii=False)
    if kind == 'lines':
        ph = [' '.join(norm_words(l[0])) for l in lines if l[2] >= center - back and l[1] <= center + size - back]
        return json.dumps(ph + ['[unk]'], ensure_ascii=False)
    raise ValueError(variant)

_model = None
def job(args):
    global _model
    variant, cond, clip, seed = args
    name = f'{variant}__{cond}__{clip}'
    out = f'{OUT}/{name}.jsonl'
    if os.path.exists(out): return name
    if _model is None: _model = Model('models/vosk-model-small-ru-0.22')
    d = np.load(f'exp/audio/{clip}.npz')
    audio = d['audio']
    timing = d['timing'] if 'timing' in d.files else None
    x = make_condition(audio, cond, seed)
    pcm = to_int16(np.concatenate([x, np.zeros(16000, dtype=np.float32)]))
    center = int(timing[0][2]) if timing is not None else 50000
    if cond == 'elsewhere':
        voice, pi = clip.rsplit('_', 1)
        other = np.load(f'exp/audio/{voice}_{(int(pi) + 3) % 6}.npz')['timing']
        center = int(other[0][2])
    g = grammar(variant, center)
    rec = KaldiRecognizer(_model, 16000, g) if g else KaldiRecognizer(_model, 16000)
    ev = [{'k': 'start', 'pos': center}]
    li = 0
    for i in range(0, len(pcm), CHUNK):
        t = i
        if timing is not None:
            while li + 1 < len(timing) and t >= timing[li + 1][0]: li += 1
            ls, le = int(timing[li][2]), int(timing[li][3])
        else:
            ls = le = center - 1
        if rec.AcceptWaveform(pcm[i:i + CHUNK].tobytes()):
            ev.append({'k': 'f', 'text': json.loads(rec.Result()).get('text', ''), 'ls': ls, 'le': le})
        else:
            ev.append({'k': 'p', 'text': json.loads(rec.PartialResult()).get('partial', ''), 'ls': ls, 'le': le})
        if timing is not None:
            for j in range(len(timing)):
                if timing[j][1] + 8000 <= t + CHUNK and timing[j][1] + 8000 > t:   # через 0,5 с после конца строки
                    ev.append({'k': 'mark', 'ls': int(timing[j][2]), 'le': int(timing[j][3])})
    ev.append({'k': 'f', 'text': json.loads(rec.FinalResult()).get('text', ''), 'ls': ls, 'le': le})
    ev.append({'k': 'end', 'le': int(timing[-1][3]) if timing is not None else center - 1})
    with open(out, 'w', encoding='utf-8') as f:
        for e in ev: f.write(json.dumps(e, ensure_ascii=False) + '\n')
    return name

if __name__ == '__main__':
    variants = sys.argv[1].split(',')
    conds = sys.argv[2].split(',')
    clips = sorted(os.path.basename(p)[:-4] for p in glob.glob('exp/audio/*.npz'))
    book = [c for c in clips if not c.startswith('foreign')]
    foreign = [c for c in clips if c.startswith('foreign')]
    jobs = []
    for v in variants:
        for c in conds:
            use = foreign if c in ('foreign',) else book
            for k, clip in enumerate(use):
                if c == 'foreign':
                    jobs.append((v, 'clean', clip, 1000 + k))
                elif c == 'noise':
                    jobs.append((v, 'noise', clip, 2000 + k))   # шум по длительности отрывков
                else:
                    jobs.append((v, c, clip, 3000 + k))
    t0 = time.time()
    with Pool(4) as p:
        done = 0
        for _ in p.imap_unordered(job, jobs, chunksize=2):
            done += 1
            if done % 50 == 0: print(f'{done}/{len(jobs)} {time.time()-t0:.0f}s', flush=True)
    print('done', len(jobs), f'{time.time()-t0:.0f}s')
