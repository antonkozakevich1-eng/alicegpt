"""Нейросетевая модель с «горячими словами» из окна текста (contextual biasing) — потолок выигрыша от подсказок."""
import sys, json, os, glob, time
sys.path.insert(0, 'exp')
from common import *
from run_vosk import make_condition, CHUNK
import sherpa_onnx
from multiprocessing import Pool

OUT = EV
SCORE = float(os.environ.get('HOT_SCORE', '2.0'))
WINDOW = int(os.environ.get('HOT_WINDOW', '300'))
VARIANT = os.environ.get('HOT_VARIANT', f'zip_hot{int(SCORE*10)}@{WINDOW}')
lines, words = load_book()
_rec = None
def recognizer():
    global _rec
    if _rec is None:
        _rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens='zip/tokens.txt', encoder='zip/encoder.int8.onnx', decoder='zip/decoder.onnx', joiner='zip/joiner.int8.onnx',
            num_threads=1, sample_rate=16000, feature_dim=80, decoding_method='modified_beam_search', max_active_paths=4,
            hotwords_score=SCORE, modeling_unit='bpe', bpe_vocab='zip/unigram_500.vocab',
            enable_endpoint_detection=True, rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=30)
    return _rec

def hot_string(center):
    back = WINDOW // 6
    win = words[max(0, center - back): center + WINDOW - back]
    uniq = sorted(set(w for w in win if len(w) >= 3))
    return '/'.join(uniq)

def job(args):
    cond, clip, seed = args
    name = f'{VARIANT}__{cond}__{clip}'
    out = f'{OUT}/{name}.jsonl'
    if os.path.exists(out): return name
    r = recognizer()
    d = np.load(f'exp/audio/{clip}.npz')
    audio = d['audio']; timing = d['timing'] if 'timing' in d.files else None
    x = make_condition(audio, cond, seed)
    x = np.concatenate([x, np.zeros(16000, dtype=np.float32)]).astype(np.float32)
    center = int(timing[0][2]) if timing is not None else FOREIGN_CENTER
    if cond == 'elsewhere':
        center = int(np.load(f'exp/audio/{other_clip(clip)}.npz')['timing'][0][2])
    ev = [{'k': 'start', 'pos': center, 'doc': DOC}]
    hot = hot_string(center)
    s = r.create_stream(hot)
    li = 0
    for i in range(0, len(x), CHUNK):
        if timing is not None:
            while li + 1 < len(timing) and i >= timing[li + 1][0]: li += 1
            ls, le = int(timing[li][2]), int(timing[li][3])
        else:
            ls = le = center - 1
        s.accept_waveform(16000, x[i:i + CHUNK])
        while r.is_ready(s): r.decode_stream(s)
        text = r.get_result(s); text = text if isinstance(text, str) else text.text
        if r.is_endpoint(s):
            ev.append({'k': 'f', 'text': text.lower(), 'ls': ls, 'le': le}); r.reset(s)
            s = r.create_stream(hot) if False else s
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
    return name

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
    with Pool(4) as p:
        for i, res in enumerate(p.imap_unordered(job, jobs, chunksize=2)):
            if (i + 1) % 25 == 0: print(f'{i+1}/{len(jobs)} {time.time()-t0:.0f}s', flush=True)
    print('done', len(jobs), f'{time.time()-t0:.0f}s')
