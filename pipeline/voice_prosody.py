"""Intonation for Verity's voice (numpy). dev.verity.client.voice.Prosody is a port: keep them in sync.

A line is cut into sentences, each one is synthesised on its own with a tempo and base pitch that depend on the emotion,
and then its pitch is moved along a melody: the voice's own rises and falls are made bigger, the phrase drifts down, a
yes/no question rises at the end, a full stop falls, an ellipsis sags.

The pitch is moved with PSOLA (single vocal pulses laid closer together or further apart), which changes neither the
speed nor the timbre. The first version bent the pitch by resampling instead; that also sped the voice up and thinned it
wherever it rose, and slowed and thickened it wherever it fell, and what the player heard was a voice that swam.
The numbers live in verity_voice.json.
"""
import re
import numpy as np

HOP_S = 0.01
PAUSE = {'.': 0.26, '!': 0.20, '?': 0.30, '…': 0.48}
WH = ('кто', 'что', 'чего', 'чем', 'где', 'куда', 'когда', 'зачем', 'почему', 'как', 'какой', 'какая', 'какое', 'какого', 'какие',
      'сколько', 'чей', 'откуда', 'кому', 'кого')


def split(text):
    """[(sentence, end)]: end is one of . ! ? … (how the sentence closes decides its melody)."""
    out = []
    for m in re.finditer(r'[^.!?…]+[.!?…]*', text.strip()):
        part = m.group(0).strip()
        tail = part[len(part.rstrip('.!?…')):]
        end = '?' if '?' in tail else '!' if '!' in tail else '…' if ('…' in tail or '..' in tail) else '.'
        if sum(c.isalnum() for c in part) < 1:
            continue
        out.append((part, end))
    return out or [(text.strip(), '.')]


def is_wh(sentence):
    """A question that opens with a question word falls at the end in Russian; a yes/no question rises."""
    words = re.findall(r'[а-яёa-z]+', sentence.lower())
    while words and words[0] in ('а', 'и', 'ну', 'но', 'так'):
        words = words[1:]
    return bool(words) and words[0] in WH


def brevity(sentence):
    """One-word answers ("Да.", "Что-то.") are the heaviest lines he has; Piper rattles them off, so they are slowed down."""
    vowels = sum(c in 'аеёиоуыэюяaeiouy' for c in sentence.lower())
    return 1.8 if vowels <= 1 else 1.35 if vowels == 2 else 1.15 if vowels == 3 else 1.0


def stretch(x, factor, sr):
    """Makes a sound longer without lowering it (WSOLA: overlapping windows, each slid a little to line up with the last)."""
    win = int(sr * 0.03); hs = win // 2; tol = int(sr * 0.006)
    if factor <= 1.01 or len(x) < win * 3:
        return x
    w = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(win) / win)).astype(np.float32)
    frames = int((len(x) - win - tol) * factor / hs)
    y = np.zeros(frames * hs + win, np.float32)
    y[:win] += x[:win] * w
    prev = 0
    for k in range(1, frames):
        ideal = int(k * hs / factor)
        best = 0
        if prev + hs + win <= len(x):
            target = x[prev + hs:prev + hs + win]; score = -1e30
            for d in range(-tol, tol + 1, 4):
                a = ideal + d
                if a < 0 or a + win > len(x):
                    continue
                c = float(np.dot(x[a:a + win], target))
                if c > score:
                    score = c; best = d
        a = min(max(ideal + best, 0), len(x) - win)
        y[k * hs:k * hs + win] += x[a:a + win] * w
        prev = a
    return y


def lengthen(x, sr, sentence):
    """Piper says a lone open syllable ("Да.", "Я.") in a tenth of a second whatever its tempo: give such words their weight back."""
    vowels = sum(c in 'аеёиоуыэюяaeiouy' for c in sentence.lower())
    seconds = len(x) / sr
    if vowels > 1 or seconds <= 0.02 or seconds >= 0.4:
        return x
    return stretch(x, min(2.6, 0.42 / seconds), sr)


def seed_of(text):
    s = 7
    for i, ch in enumerate(text):
        s = (s * 31 + ord(ch) * (i % 13 + 1)) % 100003
    return s


def hops(x, sr):
    hop = int(sr * HOP_S); n = len(x) // hop
    if n == 0:
        return np.zeros(0, np.float32), hop
    return np.sqrt((x[:n * hop].reshape(n, hop).astype(np.float64) ** 2).mean(1)).astype(np.float32), hop


def smooth(a, k):
    """Moving average over k hops (edges held)."""
    if len(a) == 0 or k <= 1:
        return a
    pad = k // 2
    b = np.concatenate([np.full(pad, a[0]), a, np.full(pad, a[-1])])
    c = np.cumsum(np.concatenate([[0.0], b]))
    return ((c[k:] - c[:-k]) / k)[:len(a)].astype(np.float32)


def ease(v):
    v = np.clip(v, 0.0, 1.0)
    return v * v * (3 - 2 * v)


def trim(x, sr):
    """Cuts the silence Piper leaves around a sentence, so that the pauses are ours to decide."""
    rms, hop = hops(x, sr)
    if len(rms) < 4:
        return x
    loud = np.nonzero(rms > 0.03 * np.sort(rms)[int(len(rms) * 0.95)])[0]
    if len(loud) == 0:
        return x
    return x[max(0, loud[0] - 3) * hop:min(len(rms), loud[-1] + 7) * hop]


def track_f0(x, sr):
    """The voice's pitch in Hz for every 10 ms (0 where there is no voice): normalised autocorrelation, then the usual repairs."""
    hop = int(sr * HOP_S); win = int(sr * 0.04); lo = sr // 400; hi = sr // 70
    n = len(x) // hop
    f0 = np.zeros(n, np.float32)
    if n < 4:
        return f0
    pad = np.concatenate([np.zeros(win // 2, np.float32), x.astype(np.float32), np.zeros(win, np.float32)])
    ref = float(np.sqrt((x.astype(np.float64) ** 2).mean())) + 1e-9
    lags = np.arange(lo, hi + 1)
    for h in range(n):
        w = pad[h * hop:h * hop + win]; w = (w - w.mean()).astype(np.float64)
        if float(np.sqrt((w * w).mean())) < 0.12 * ref:
            continue
        corr = np.correlate(w, w, 'full')[win - 1:]                  # corr[k] = sum of w[i] * w[i + k]
        c2 = np.cumsum(np.concatenate([[0.0], w * w]))
        r = corr[lags] / (np.sqrt(c2[win - lags] * (c2[win] - c2[lags])) + 1e-12)
        top = float(r.max())
        if top <= 0.5:
            continue
        pick = int(np.argmax(r))
        for j in range(1, len(r) - 1):                               # the first peak that is about as good: the shortest period that fits
            if r[j] >= top - 0.05 and r[j] >= r[j - 1] and r[j] >= r[j + 1]:
                pick = j
                break
        f0[h] = sr / lags[pick]
    v = f0 > 0
    if v.sum() < 4:
        return np.zeros(n, np.float32)
    med = float(np.median(f0[v]))
    for h in range(n):                                               # octave slips
        if f0[h] > 0:
            a = max(0, h - 12); near = f0[a:h + 13]; near = near[near > 0]
            m = float(np.median(near)) if len(near) >= 3 else med
            while f0[h] > 1.75 * m:
                f0[h] /= 2
            while f0[h] < 0.57 * m:
                f0[h] *= 2
    out = f0.copy()
    for h in range(n):                                               # single wild frames
        if f0[h] > 0:
            near = f0[max(0, h - 2):h + 3]; near = near[near > 0]
            out[h] = float(np.median(near))
    run = 0
    for h in range(n + 1):                                           # voiced islands too short to be a vowel
        if h < n and out[h] > 0:
            run += 1
        else:
            if 0 < run < 3:
                out[h - run:h] = 0
            run = 0
    return out


def voiced_runs(f0):
    """[(start, end)] of stretches of voice, bridging the two or three frames a consonant takes."""
    runs = []; a = -1; gap = 0
    for i in range(len(f0) + 1):
        on = i < len(f0) and f0[i] > 0
        if on:
            if a < 0:
                a = i
            gap = 0
        elif a >= 0:
            gap += 1
            if gap > 3 or i >= len(f0):
                runs.append((a, i - gap + 1)); a = -1; gap = 0
    return runs


def melody(x, sr, f0, end, wh, emo, amount):
    """
    How far to move the pitch, in semitones, for every 10 ms of one sentence. Mostly it makes the voice's own melody
    bigger (the stresses and turns Piper already put there), raising what is stressed rather than pushing the rest down;
    on top of that the sentence ends the way its punctuation says. Nothing here wavers on its own: every movement
    belongs to a syllable.
    """
    n = len(f0)
    s = np.zeros(n, np.float32)
    idx = np.nonzero(f0 > 0)[0]
    if len(idx) < 6:
        return s
    first, last = int(idx[0]), int(idx[-1])
    med = float(np.median(f0[idx]))
    natural = np.interp(np.arange(n), idx, 12 * np.log2(f0[idx] / med)).astype(np.float32)
    natural = smooth(natural, 5)
    R = emo['range'] * amount
    Rq = min(max(R, 0.9), 1.2)                                  # a question must sound like one even in the flattest voice
    s = np.clip(0.5 * R * natural, -2.0, 4.5)                   # his own melody, exaggerated
    k_all = np.arange(n, dtype=np.float32)
    u = np.clip((k_all - first) / max(last - first, 1), 0, 1)
    s += R * 0.4 * (1 - 2 * u)                                  # the phrase as a whole drifts downwards
    v = ease((u - 0.6) / 0.4)                                   # 0 over most of the phrase, 1 at its end
    if end == '?' and not wh:
        # Piper lets a question fall like a statement. Find the last syllable said at full voice (the stressed one) and
        # carry the pitch up across it; whatever trails after it stays up a little instead of sinking.
        rms, _ = hops(x, sr); env = smooth(rms[:n] if len(rms) >= n else np.pad(rms, (0, n - len(rms))), 3)
        runs = voiced_runs(f0)
        floor = 0.4 * float(np.median(env[idx]))
        strong = [r for r in runs if r[1] - r[0] >= 6 and float(env[r[0]:r[1]].mean()) >= floor] or [r for r in runs if r[1] - r[0] >= 4] or runs
        ra, rb = strong[-1]
        ra = max(ra, rb - 30)                                   # at most the last 0.3 s of it
        w = (np.arange(ra, rb, dtype=np.float32) - ra) / max(rb - ra - 1, 1)
        want = float(natural[ra]) + s[ra] + Rq * 4.5 * ease(w)
        lift = np.clip(want - natural[ra:rb], -1.0, 7.0)
        s[ra:rb] = lift
        if rb < n:
            s[rb:] = min(float(lift[-1]), 4.0) * 0.6
    elif end == '?':
        s += R * (0.8 * ease((u - 0.8) / 0.2) - 0.8 * ease((u - 0.3) / 0.4))
    elif end == '!':
        s += R * (1.0 - 0.9 * v)                                # said from higher up, and it stays up until the last moment
    elif end == '…':
        s -= R * 1.3 * ease((u - 0.4) / 0.6)
    else:
        s -= R * 0.8 * v
    s -= 0.5 * float(s[idx].mean())                             # bend the phrase, do not move the whole voice up or down
    return smooth(np.clip(s, -3.0, 7.0).astype(np.float32), 5)


def _peak(seg, expected, T):
    """The pulse in this stretch: the highest point, but one period on from the last pulse counts for more than a stray spike."""
    top = float(np.max(np.abs(seg))) + 1e-9
    return int(np.argmax(seg / top - 0.6 * np.abs(np.arange(len(seg)) - expected) / T))


def pitch_marks(x, sr, f0):
    """One mark per vocal-fold pulse where there is voice, one every 5 ms where there is not. Returns (positions, voiced flags)."""
    hop = int(sr * HOP_S); n = len(x)
    k = max(1, sr // 1000)                                           # 1 ms box filter twice: the pulses without the hiss
    z = np.convolve(np.convolve(x, np.ones(k) / k, 'same'), np.ones(k) / k, 'same')
    voiced = f0 > 0
    if voiced.any():
        seg = np.repeat(voiced, hop)[:n]; zz = z[:len(seg)][seg]
        if len(zz) and -zz.min() > zz.max():
            z = -z                                                    # mark the stronger side of the wave
    marks = []; flags = []
    h = 0; H = len(f0)
    while h < H:
        if not voiced[h]:
            h += 1; continue
        e = h
        while e < H and voiced[e]:
            e += 1
        lo = h * hop; hi = min(n, e * hop)
        mid = (lo + hi) // 2; T = sr / f0[min(H - 1, mid // hop)]
        a = max(lo, int(mid - T)); c = a + int(np.argmax(z[a:min(hi, int(mid + T))]))
        seg_marks = [c]
        cur = c
        while True:                                                   # forwards
            T = sr / max(f0[min(e - 1, max(h, cur // hop))], 1.0)
            a = int(cur + 0.7 * T); b2 = int(cur + 1.35 * T)
            if b2 >= hi or a >= b2:
                break
            cur = a + _peak(z[a:b2], cur + T - a, T); seg_marks.append(cur)
        cur = c
        while True:                                                   # backwards
            T = sr / max(f0[min(e - 1, max(h, cur // hop))], 1.0)
            a = int(cur - 1.35 * T); b2 = int(cur - 0.7 * T)
            if a < lo or a >= b2:
                break
            cur = a + _peak(z[a:b2], cur - T - a, T); seg_marks.insert(0, cur)
        marks += seg_marks; flags += [True] * len(seg_marks)
        h = e
    order = np.argsort(marks) if marks else []
    marks = [marks[i] for i in order]; flags = [flags[i] for i in order]
    step = int(sr * 0.005)
    full = []; fl = []
    prev = 0
    for m, f in list(zip(marks, flags)) + [(n - 1, False)]:           # fill the voiceless gaps
        p = prev + step
        while p < m - step // 2:
            full.append(p); fl.append(False); p += step
        if m < n - 1 or not full or full[-1] < m:
            full.append(m); fl.append(f)
        prev = m
    return np.array(full, np.int64), np.array(fl, bool)


def psola(x, sr, f0, semis):
    """
    Moves the pitch by 'semis' (per 10 ms) without touching speed or timbre: the voice is cut into single pulses and they
    are laid down again closer together (higher) or further apart (lower). Where there is no voice the sound is copied.
    """
    n = len(x)
    if n < sr // 20 or len(semis) < 2 or not np.any(np.abs(semis) > 0.05):
        return x
    marks, voiced = pitch_marks(x, sr, f0)
    if len(marks) < 4:
        return x
    hop = int(sr * HOP_S)
    out = np.zeros(n + 2048, np.float32); wsum = np.zeros(n + 2048, np.float32)
    t = float(marks[1]); i = 1; M = len(marks)
    while t <= marks[-2] + 0.5:
        while i < M - 2 and abs(marks[i + 1] - t) <= abs(marks[i] - t):
            i += 1
        L = int(marks[i] - marks[i - 1]); Rr = int(marks[i + 1] - marks[i])
        c = int(round(t))
        if L > 0 and Rr > 0 and c - L >= 0:
            w = np.concatenate([0.5 - 0.5 * np.cos(np.pi * np.arange(L) / L), 0.5 + 0.5 * np.cos(np.pi * np.arange(Rr) / Rr)]).astype(np.float32)
            g = x[marks[i] - L:marks[i] + Rr]
            out[c - L:c - L + len(g)] += g * w[:len(g)]; wsum[c - L:c - L + len(g)] += w[:len(g)]
        if voiced[i]:
            st = semis[min(len(semis) - 1, c // hop)]
            t += 0.5 * (L + Rr) / float(2.0 ** (st / 12.0))
        else:
            t += max(Rr, 1)
    y = out[:n] / np.maximum(wsum[:n], 0.55)
    head = int(marks[1]); tail = int(marks[-2])                       # the two ends were never windowed in: keep them as they were
    y[:head] = x[:head]
    y[tail:] = x[tail:]
    return y.astype(np.float32)


def intone(x, sr, end, wh, emo, amount):
    """One sentence with its melody put in. If moving the pitch would roughen the voice it is moved less, or not at all."""
    f0 = track_f0(x, sr)
    had = int((f0 > 0).sum())
    if had < 6:
        return x
    semis = melody(x, sr, f0, end, wh, emo, amount)
    for strength in (1.0, 0.6):
        y = psola(x, sr, f0, semis * strength)
        if int((track_f0(y, sr) > 0).sum()) >= 0.72 * had:      # still a clear voice afterwards
            return y
    return x


def emotion(recipe, name):
    table = recipe.get('prosody', {}).get('emotions', {})
    e = dict(table.get('neutral', {'tempo': 1.0, 'pitch': 1.0, 'range': 0.6, 'pause': 1.0, 'noise': 0.667, 'noiseW': 0.8}))
    e.update(table.get((name or 'neutral').lower(), {}))
    return e


def speak(text, profile, emo_name, recipe, sr, piper):
    """The whole line, dry (before the effect chain). piper(text, length_scale, noise, noise_w) -> float mono."""
    emo = emotion(recipe, emo_name)
    amount = profile.get('intonation', 1.0)
    pitch = profile['pitch'] * (1 + (emo['pitch'] - 1) * min(amount, 1.0))
    parts = []
    for sentence, end in split(text):
        x = trim(piper(sentence, pitch * profile['tempo'] * emo['tempo'] * brevity(sentence), emo['noise'], emo['noiseW']), sr)
        x = lengthen(x, sr, sentence)
        x = intone(x, sr, end, is_wh(sentence), emo, amount)
        if end == '!':
            x = x * 1.12                                         # said with force
        parts.append(resample(x, pitch))                         # the whole voice lower: one steady ratio, so nothing drifts
        parts.append(np.zeros(int(sr * PAUSE[end] * emo['pause']), np.float32))
    return np.concatenate(parts[:-1]) if parts else np.zeros(0, np.float32)


def resample(x, speed):
    n = int(len(x) / speed)
    if n < 2 or len(x) < 2:
        return x
    pos = np.arange(n) * speed
    i = np.minimum(pos.astype(np.int64), len(x) - 2); f = (pos - i).astype(np.float32)
    return (x[i] * (1 - f) + x[i + 1] * f).astype(np.float32)
