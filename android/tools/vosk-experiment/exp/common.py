import re, io, wave, json, os
import numpy as np
from scipy.signal import resample_poly

ASSET = os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../../app/src/main/assets/odyssey_zhukovsky.txt')
HEAD = re.compile(r'^(ПЕСНЬ [А-ЯЁ ]+|ПРИМЕЧАНИЯ)$')

def norm_words(s):
    s = re.sub(r'\[\d+\]', ' ', s).lower().replace('ё', 'е')
    return re.findall(r'[^\W_]+', s)

def load_book():
    """-> (lines, words): lines = [(text_without_markers, first_word_idx, last_word_idx)], words = все слова книги."""
    lines, words = [], []
    for ln in open(ASSET, encoding='utf-8').read().split('\n'):
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

def to16k(x, sr):
    return resample_poly(x, 16000, sr).astype(np.float32)

def to_int16(x):
    return np.clip(x * 32767, -32768, 32767).astype(np.int16)
