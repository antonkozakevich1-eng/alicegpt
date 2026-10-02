import re, io, wave, json, os
import numpy as np
from scipy.signal import resample_poly

ASSET = '/home/user/alicegpt/android/app/src/main/assets/odyssey_zhukovsky.txt'
DS = os.environ.get('DS', 'od')            # od — «Одиссея» (синтетика), fl — FLEURS (живая речь)
DOC = 'fleurs' if DS == 'fl' else 'odyssey'
EV = 'exp/events_fl' if DS == 'fl' else 'exp/events'
TR = 'exp/traces_fl' if DS == 'fl' else 'exp/traces'
FOREIGN_CENTER = 900 if DS == 'fl' else 50000   # где стоит подсветка, пока звучит чужая речь
HEAD = re.compile(r'^(ПЕСНЬ [А-ЯЁ ]+|ПРИМЕЧАНИЯ)$')

def norm_words(s):
    s = re.sub(r'\[\d+\]', ' ', s).lower().replace('ё', 'е')
    return re.findall(r'[^\W_]+', s)

def load_book(name=None):
    """-> (lines, words): lines = [(text_without_markers, first_word_idx, last_word_idx)], words = все слова документа."""
    name = name or DOC
    path = ASSET if name == 'odyssey' else f'exp/docs/{name}.txt'
    lines, words = [], []
    for ln in open(path, encoding='utf-8').read().split('\n'):
        t = ln.strip()
        if not t or HEAD.match(t):
            continue
        w = norm_words(t)
        if not w:
            continue
        first = len(words)
        words.extend(w)
        lines.append((re.sub(r'^\[\d+\]\s*', '', t), first, len(words) - 1))
    return lines, words

def clip_list():
    """Клипы текущего набора данных: (книжные, чужая речь)."""
    import glob
    clips = sorted(os.path.basename(p)[:-4] for p in glob.glob('exp/audio/*.npz'))
    clips = [c for c in clips if c.startswith('fl') == (DS == 'fl')]
    return [c for c in clips if 'foreign' not in c], [c for c in clips if 'foreign' in c]

def other_clip(clip):
    """Отрывок из другого места книги — для условия «читают другое место»."""
    import glob
    prefix, k = clip.rsplit('_', 1)
    n = len(glob.glob(f'exp/audio/{prefix}_*.npz'))
    return f'{prefix}_{(int(k) + max(3, n // 3)) % n}'

def to16k(x, sr):
    return resample_poly(x, 16000, sr).astype(np.float32)

def to_int16(x):
    return np.clip(x * 32767, -32768, 32767).astype(np.int16)
