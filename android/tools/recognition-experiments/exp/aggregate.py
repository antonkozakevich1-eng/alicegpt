import sys, glob, os, json, csv, difflib, collections
sys.path.insert(0, 'exp')
from common import *

lines, words = load_book()
rows = list(csv.DictReader(open('exp/results.tsv'), delimiter='\t'))

def word_acc(path):
    """Доля слов отрывка, найденных в распознанном тексте по порядку (по final-результатам)."""
    ev = [json.loads(l) for l in open(path, encoding='utf-8')]
    start = next(e['pos'] for e in ev if e['k'] == 'start')
    end = next(e['le'] for e in ev if e['k'] == 'end')
    ref = words[start:end + 1]
    hyp = ' '.join(e['text'] for e in ev if e['k'] == 'f' and e['text']).split()
    hyp = [w.replace('ё', 'е') for w in hyp if w != '[unk]']
    sm = difflib.SequenceMatcher(None, ref, hyp, autojunk=False)
    ok = sum(b.size for b in sm.get_matching_blocks())
    return ok / max(1, len(ref)), len(hyp) / max(1, len(ref))

agg = collections.defaultdict(lambda: collections.defaultdict(list))
for r in rows:
    variant, cond, clip = r['name'].split('__')
    kind = 'foreign' if 'foreign' in clip else cond
    a = agg[(variant, kind)]
    a['moves'].append(int(r['moves']))
    a['bad'].append(int(r['bad']))
    a['falseFwd'].append(int(r['falseFwd']))
    marks, ok = int(r['marks']), int(r['marksOk'])
    if marks:
        a['lineAcc'].append(ok / marks)
        a['endLag'].append(int(r['endTruth']) - int(r['endPos']))
        wa, hr = word_acc(f'{EV}/{r["name"]}.jsonl')
        a['wordAcc'].append(wa)
    else:
        a['moveDist'].append(abs(int(r['endPos']) - 50000))

def mean(x): return sum(x) / len(x) if x else float('nan')
variants = []
for (v, c) in agg:
    if v not in variants: variants.append(v)
conds = ['clean', 'quiet_gain', 'noisy', 'quietnoisy_gain']
order = ['zip_small', 'zip_hot20@300', 'zip_hot40@300', 'nogram', 'words_yo@100', 'words_yo@300', 'bi@300', 'tri@300', 'quad@300', 'lines@300']
variants = [v for v in order if v in variants]
def table(title, key, fmt='{:16.2f}'):
    print('\n== ' + title + ' ==')
    print(f'{"вариант":16s}' + ''.join(f'{c:>16s}' for c in conds) + f'{"среднее":>10s}')
    for v in variants:
        vals = [mean(agg[(v, c)][key]) for c in conds]
        print(f'{v:16s}' + ''.join(fmt.format(x) for x in vals) + f'{mean(vals):10.2f}')
table('Точность распознавания слов (доля слов отрывка, найденных по порядку)', 'wordAcc')
table('Трекер: доля строк, на конце которых подсветка в пределах 3 слов от истины', 'lineAcc')
table('Трекер: ложный сдвиг вперёд (событий за прогон, среднее)', 'falseFwd')
print('\n== Ложные сдвиги подсветки (прогонов, где подсветка ушла не туда) ==')
print(f'{"вариант":16s}{"чужая речь (8)":>18s}{"шум без речи (24)":>20s}{"читают другое место (24)":>26s}')
for v in variants:
    f = agg[(v, 'foreign')]['moves']; n = agg[(v, 'noise')]['moves']
    e = agg[(v, 'elsewhere')]
    print(f'{v:16s}{sum(1 for x in f if x>0):>12d} из {len(f):<2d}{sum(1 for x in n if x>0):>13d} из {len(n):<3d}{sum(1 for x in e["bad"] if x>0):>16d} из {len(e["bad"]):<3d}')
