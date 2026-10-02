"""Синтез отрывков книги 4 голосами Piper -> exp/audio/<voice>_<passage>.npz (16 кГц, float32 + тайминги строк)."""
import sys, io, wave, os
sys.path.insert(0, 'exp')
from common import *
from piper import PiperVoice
from multiprocessing import Pool

PASSAGE_LINES = [(200, 10), (1500, 10), (3200, 10), (5200, 10), (7600, 10), (9800, 10)]
VOICES = ['irina', 'denis', 'dmitri', 'ruslan']
os.makedirs('exp/audio', exist_ok=True)

def job(voice_name):
    lines, words = load_book()
    voice = PiperVoice.load(f'voices/ru_RU-{voice_name}-medium.onnx')
    for pi, (start, count) in enumerate(PASSAGE_LINES):
        out = f'exp/audio/{voice_name}_{pi}.npz'
        if os.path.exists(out):
            continue
        chunks, timing, t = [], [], 0
        gap = np.zeros(int(0.35 * 16000), dtype=np.float32)
        for text, first, last in lines[start:start + count]:
            b = io.BytesIO()
            with wave.open(b, 'wb') as w:
                voice.synthesize_wav(text, w)
            b.seek(0); w = wave.open(b)
            x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
            x = to16k(x, w.getframerate())
            timing.append((t, t + len(x), first, last))
            chunks += [x, gap]
            t += len(x) + len(gap)
        np.savez(out, audio=np.concatenate(chunks), timing=np.array(timing, dtype=np.int64))
    return voice_name

if __name__ == '__main__':
    with Pool(4) as p:
        print(p.map(job, VOICES))
