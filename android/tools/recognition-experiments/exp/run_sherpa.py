"""Те же записи, те же условия, но распознаёт нейросетевая стриминговая модель (sherpa-onnx). События — в том же формате, что у Vosk."""
import sys, json, os, glob, time
sys.path.insert(0, 'exp')
from common import *
from run_vosk import make_condition, CHUNK
import sherpa_onnx
from multiprocessing import Pool

OUT = EV
MODEL = os.environ.get('ZIP_DIR', 'zip')
VARIANT = os.environ.get('ZIP_VARIANT', 'zip_small')
DECODING = os.environ.get('ZIP_DECODING', 'greedy_search')
THREADS = int(os.environ.get('ZIP_THREADS', '1'))

_rec = None
def recognizer():
    global _rec
    if _rec is None:
        enc = 'encoder.int8.onnx' if os.path.exists(f'{MODEL}/encoder.int8.onnx') else 'encoder.onnx'
        joi = 'joiner.int8.onnx' if os.path.exists(f'{MODEL}/joiner.int8.onnx') else 'joiner.onnx'
        _rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=f'{MODEL}/tokens.txt', encoder=f'{MODEL}/{enc}', decoder=f'{MODEL}/decoder.onnx', joiner=f'{MODEL}/{joi}',
            num_threads=THREADS, sample_rate=16000, feature_dim=80, decoding_method=DECODING,
            enable_endpoint_detection=True, rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=30)
    return _rec

def job(args):
    cond, clip, seed = args
    name = f'{VARIANT}__{cond}__{clip}'
    out = f'{OUT}/{name}.jsonl'
    if os.path.exists(out): return name
    r = recognizer()
    d = np.load(f'exp/audio/{clip}.npz')
    audio = d['audio']
    timing = d['timing'] if 'timing' in d.files else None
    x = make_condition(audio, cond, seed)
    x = np.concatenate([x, np.zeros(16000, dtype=np.float32)]).astype(np.float32)
    center = int(timing[0][2]) if timing is not None else FOREIGN_CENTER
    ev = [{'k': 'start', 'pos': center, 'doc': DOC}]
    s = r.create_stream()
    li = 0
    t_decode = 0.0
    for i in range(0, len(x), CHUNK):
        if timing is not None:
            while li + 1 < len(timing) and i >= timing[li + 1][0]: li += 1
            ls, le = int(timing[li][2]), int(timing[li][3])
        else:
            ls = le = center - 1
        s.accept_waveform(16000, x[i:i + CHUNK])
        t0 = time.time()
        while r.is_ready(s): r.decode_stream(s)
        t_decode += time.time() - t0
        text = r.get_result(s)
        text = text if isinstance(text, str) else text.text
        if r.is_endpoint(s):
            ev.append({'k': 'f', 'text': text.lower(), 'ls': ls, 'le': le})
            r.reset(s)
        else:
            ev.append({'k': 'p', 'text': text.lower(), 'ls': ls, 'le': le})
        if timing is not None:
            for j in range(len(timing)):
                if timing[j][1] + 8000 <= i + CHUNK and timing[j][1] + 8000 > i:
                    ev.append({'k': 'mark', 'ls': int(timing[j][2]), 'le': int(timing[j][3])})
    s.input_finished()
    while r.is_ready(s): r.decode_stream(s)
    text = r.get_result(s); text = text if isinstance(text, str) else text.text
    ev.append({'k': 'f', 'text': text.lower(), 'ls': ls, 'le': le})
    ev.append({'k': 'end', 'le': int(timing[-1][3]) if timing is not None else center - 1})
    with open(out, 'w', encoding='utf-8') as f:
        for e in ev: f.write(json.dumps(e, ensure_ascii=False) + '\n')
    return name + f' rtf={t_decode / (len(x) / 16000):.3f}'

if __name__ == '__main__':
    conds = sys.argv[1].split(',')
    book, foreign = clip_list()
    jobs = []
    for c in conds:
        use = foreign if c == 'foreign' else book
        for k, clip in enumerate(use):
            if c == 'foreign': jobs.append(('clean', clip, 1000 + k))
            elif c == 'noise': jobs.append(('noise', clip, 2000 + k))
            else: jobs.append((c, clip, 3000 + k))
    t0 = time.time()
    rtfs = []
    with Pool(4) as p:
        for i, res in enumerate(p.imap_unordered(job, jobs, chunksize=2)):
            if 'rtf=' in res: rtfs.append(float(res.split('rtf=')[1]))
            if (i + 1) % 25 == 0: print(f'{i+1}/{len(jobs)} {time.time()-t0:.0f}s', flush=True)
    print('done', len(jobs), f'{time.time()-t0:.0f}s', 'mean decode RTF (1 thread):', f'{sum(rtfs)/max(1,len(rtfs)):.3f}' if rtfs else 'n/a')
