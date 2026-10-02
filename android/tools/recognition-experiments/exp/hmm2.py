"""Прототип «байесовского» трекера: распределение вероятностей по позициям + состояние паузы.

Состояние: (i, режим) — i: индекс последнего произнесённого слова; режим ON — читает, OFF — пауза/мусор/чужая речь.
Наблюдение — распознанное слово r. ON: слово совпадает с текстом с вероятностью rho (нечётко), иначе — «фоновое».
Фон для слова r ~ доля позиций текста, похожих на r: редкое слово — сильное доказательство, частое — слабое.
"""
import math, collections, bisect, sys
sys.path.insert(0, 'exp')

def lev(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(cur[-1] + 1, prev[j] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]

def similarity(a, b):
    if a == b: return 1.0
    mn, mx = min(len(a), len(b)), max(len(a), len(b))
    if mn < 3: return 0.0
    if mx - mn > max(2, mx * 2 // 5): return 0.0
    p = 0
    while p < mn and a[p] == b[p]: p += 1
    sim = 1.0 - lev(a, b) / mx
    if p >= 4 and p * 5 >= mx * 3: sim = max(sim, 0.8)
    elif p >= 3 and mn >= 4 and p * 5 >= mx * 3: sim = max(sim, 0.75)
    return sim if sim >= 0.7 else 0.0

DEFAULT = dict(
    rho=0.6,          # P(распознано слово, близкое к настоящему | читает)
    p_adv=(0.0, 0.80, 0.10, 0.03),   # ON: сдвиг на 0 (повтор), 1, 2, 3 слова
    p_back=0.01,      # ON: небольшой откат (повтор слов), равномерно на 1..8 слов назад
    p_skip=0.01,      # ON: пропуск вперёд на 4..40 слов
    p_stop=0.04,      # ON -> OFF
    p_start=0.05,     # OFF -> ON
    p_reread=0.05,    # доля p_start, уходящая на перечитывание (назад до 40 слов)
    p_jump=3e-4,      # прыжок в любое место текста
    b_floor=0.0,      # нижняя граница «фоновой» вероятности слова
    prune=1e-7, beam=150,
    tau_move=0.5, tau_mid=0.8, tau_far=0.97, jitter=2,
    need_near=2, need_mid=2, need_far=3, need_jump=4, need_huge=6,  # сколько совпавших слов из последних 8
    far_after=60, huge_after=200,
    seek_on=0.5, wide_cap=600,
)

class Hmm:
    def __init__(self, norm, **kw):
        self.p = dict(DEFAULT); self.p.update(kw)
        self.t = list(norm); self.n = len(self.t)
        self.pos_of = collections.defaultdict(list)
        for i, w in enumerate(self.t): self.pos_of[w].append(i)
        self.vocab = sorted(self.pos_of)
        self.bucket = collections.defaultdict(list)
        for w in self.vocab: self.bucket[w[:3]].append(w)
        self._sim = {}
        self.cursor = 0
        self.committed = {}      # i -> [on, off], распределение в начале текущей фразы
        self._cache_words = []; self._cache_states = []
        self.seek(0)

    # ---- похожие слова ----
    def similar(self, r, prefix=False):
        key = (r, prefix)
        got = self._sim.get(key)
        if got is not None: return got
        sims = {}
        for w in self.bucket.get(r[:3], ()):
            s = similarity(r, w)
            if s > 0: sims[w] = s
        if r in self.pos_of: sims[r] = 1.0
        if prefix and len(r) >= 3:
            lo = bisect.bisect_left(self.vocab, r)
            k = lo
            while k < len(self.vocab) and self.vocab[k].startswith(r):
                sims[self.vocab[k]] = max(sims.get(self.vocab[k], 0), 0.6); k += 1
        cnt = sum(len(self.pos_of[w]) for w in sims)
        got = (sims, cnt)
        self._sim[key] = got
        return got

    def seek(self, i):
        i = max(0, min(self.n, i))
        self.cursor = i
        j = max(0, i - 1)
        self.committed = {j: [self.p['seek_on'], 1 - self.p['seek_on'], 0]} if self.n else {}
        self._cache_words = []; self._cache_states = [self.committed]

    # ---- один шаг фильтра ----
    def step(self, bel, r, last_partial):
        p = self.p; n = self.n
        sims, cnt = self.similar(r, prefix=last_partial)
        new = {}
        def add(i, on, off, hist=0):
            if i < 0 or i >= n: return
            e = new.get(i)
            if e is None:
                new[i] = [on, off, on, hist]
            else:
                e[0] += on; e[1] += off
                if on > e[2]: e[2] = on; e[3] = hist
        p0, p1, p2, p3 = p['p_adv']
        ps_ = p['p_start']; keep = 1 - p['p_reread']
        for j, (on, off, hist) in bel.items():
            if on > 0:
                add(j, on * p0, 0, hist); add(j + 1, on * p1, 0, hist); add(j + 2, on * p2, 0, hist); add(j + 3, on * p3, 0, hist)
                add(j, 0, on * p['p_stop'])
            if off > 0:
                add(j, 0, off * (1 - ps_))
                st = off * ps_ * keep
                add(j + 1, st * 0.78, 0, 0); add(j + 2, st * 0.15, 0, 0); add(j + 3, st * 0.07, 0, 0)
        # широкие переходы (пропуск вперёд, откат, перечитывание) — только туда, где совпало слово: остальное всё равно
        # получило бы лишь «мусорный» множитель и сгинуло; hist=0 — на новом месте нужно набрать подтверждения заново
        cap = p['wide_cap']
        if sims and cnt <= cap and bel:
            order = sorted(bel)
            cum_on = [0.0]; cum_off = [0.0]
            for j in order:
                cum_on.append(cum_on[-1] + bel[j][0]); cum_off.append(cum_off[-1] + bel[j][1])
            def rng(lo, hi, cum):   # сумма по позициям j из [lo, hi]
                l = bisect.bisect_left(order, lo); h = bisect.bisect_right(order, hi)
                return cum[h] - cum[l]
            J = p['p_jump'] / n
            for w in sims:
                for i in self.pos_of[w]:
                    m = J
                    m += p['p_skip'] / 37 * rng(i - 40, i - 4, cum_on)
                    m += p['p_back'] / 8 * rng(i + 1, i + 8, cum_on)
                    m += ps_ * p['p_reread'] / 40 * rng(i + 1, i + 40, cum_off)
                    add(i, m, 0, 0)
        rho = p['rho']
        b = max(max(cnt, 1) / n, p['b_floor'])
        out = {}
        tot = 0.0
        for i, (on, off, _, hist) in new.items():
            s_ = sims.get(self.t[i], 0.0)
            e_on = (1 - rho) + rho * s_ / b
            on2 = on * e_on
            t = on2 + off
            if t > 0:
                out[i] = [on2, off, ((hist << 1) | (1 if s_ > 0 else 0)) & 0xFF]
                tot += t
        if tot <= 0: return bel
        inv = 1.0 / tot
        thr = p['prune']
        res = {}
        for i, (on, off, hist) in out.items():
            on *= inv; off *= inv
            if on + off >= thr: res[i] = [on, off, hist]
        if len(res) > p['beam']:
            top = sorted(res.items(), key=lambda kv: -(kv[1][0] + kv[1][1]))[:p['beam']]
            res = dict(top)
            z = sum(v[0] + v[1] for v in res.values())
            for v in res.values(): v[0] /= z; v[1] /= z
        return res

    # ---- обработка фразы ----
    def _states_for(self, words, partial):
        # общий префикс с прошлой гипотезой (последнее слово partial может «достраиваться» — его не кэшируем)
        k = 0
        cw = self._cache_words
        while k < len(words) and k < len(cw) and cw[k] == words[k]: k += 1
        k = min(k, len(self._cache_states) - 1)
        if partial and k >= len(words): k = max(0, len(words) - 1)
        states = self._cache_states[:k + 1]
        bel = states[-1]
        for idx in range(k, len(words)):
            bel = self.step(bel, words[idx], last_partial=(partial and idx == len(words) - 1))
            states.append(bel)
        # кэш: слова и состояния для всех, кроме «недостроенного» последнего слова partial
        self._cache_words = list(words); self._cache_states = states
        if partial:
            self._cache_words = list(words[:-1]); self._cache_states = states[:len(words)]
        return bel

    def _decide(self, bel):
        p = self.p
        if not bel: return None
        marg = {i: v[0] + v[1] for i, v in bel.items()}
        x = max(marg, key=marg.get)
        local = marg.get(x, 0) + marg.get(x - 1, 0) + marg.get(x + 1, 0)
        c = x + 1
        d = c - self.cursor
        if d == 0: return None
        hist = bel[x][2]
        if not (hist & 1): return None                 # последнее слово должно совпасть именно здесь
        matched = bin(hist).count('1')
        ad = abs(d)
        if d > 0 and d <= 2: need_m, need_p = p['need_near'], p['tau_move']
        elif ad <= 10 and d > 0: need_m, need_p = p['need_mid'], p['tau_mid']
        elif ad <= p['far_after']: need_m, need_p = p['need_far'], p['tau_mid']
        elif ad <= p['huge_after']: need_m, need_p = p['need_jump'], p['tau_far']
        else: need_m, need_p = p['need_huge'], p['tau_far']
        if d < 0:
            if ad <= p['jitter']: return None
            need_m = max(need_m, p['need_far'])
        if matched < need_m or local < need_p: return None
        return c

    def on_words(self, words, final):
        if not words: return None
        bel = self._states_for(words, partial=not final)
        c = self._decide(bel)
        moved = None
        if c is not None:
            moved = (self.cursor, c); self.cursor = c
        if final:
            self.committed = bel
            self._cache_words = []; self._cache_states = [bel]
        return moved
