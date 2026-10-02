"""Реалистичный тест на живой речи (FLEURS, русский, CC-BY): «книга» из предложений, «чтение» дикторами, чужая речь."""
import csv, collections, re, random, os, sys
sys.path.insert(0, 'exp')
from common import *
from scipy.io import wavfile

rows = list(csv.reader(open('fleurs/dev.tsv', encoding='utf-8'), delimiter='\t'))
by = collections.OrderedDict()
for r in rows: by.setdefault(r[0], []).append(r)
sents = [k for k, v in by.items() if not re.search(r'\d', v[0][2])]
book = sents[:100]
foreign = sents[100:]
os.makedirs('exp/docs', exist_ok=True); os.makedirs('exp/audio', exist_ok=True)
open('exp/docs/fleurs.txt', 'w', encoding='utf-8').write('\n'.join(by[k][0][2] for k in book) + '\n')

def load(path):
    sr, x = wavfile.read(path)
    x = x.astype(np.float32) / 32768.0 if x.dtype == np.int16 else x.astype(np.float32)
    if x.ndim > 1: x = x[:, 0]
    return to16k(x, sr) if sr != 16000 else x

rnd = random.Random(7)
words_before = []
total_words = 0
doc_words = []
for k in book:
    doc_words.append(norm_words(by[k][0][2]))
starts = []; acc = 0
for w in doc_words: starts.append(acc); acc += len(w)

gap = np.zeros(int(0.5 * 16000), dtype=np.float32)
P = 6
n_pass = 0
for p0 in range(0, len(book) - P + 1, P):
    chunks = []; timing = []; t = 0
    for k in range(p0, p0 + P):
        clip = rnd.choice(by[book[k]])
        x = load(f'fleurs/dev/{clip[1]}')
        timing.append((t, t + len(x), starts[k], starts[k] + len(doc_words[k]) - 1))
        chunks += [x, gap]; t += len(x) + len(gap)
    np.savez(f'exp/audio/fl_{n_pass}.npz', audio=np.concatenate(chunks), timing=np.array(timing, dtype=np.int64), doc='fleurs')
    n_pass += 1
# чужая речь: предложения, которых нет в книге
f_chunks = []; fi = 0
for i, k in enumerate(foreign):
    clip = rnd.choice(by[k]); f_chunks += [load(f'fleurs/dev/{clip[1]}'), gap]
    if len(f_chunks) >= 10 or i == len(foreign) - 1:
        np.savez(f'exp/audio/flforeign_{fi}.npz', audio=np.concatenate(f_chunks), doc='fleurs'); f_chunks = []; fi += 1
print('book sentences', len(book), 'words', acc, 'passages', n_pass, 'foreign clips', fi)
