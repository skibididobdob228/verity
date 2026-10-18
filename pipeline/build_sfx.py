"""Effects and music -> assets/verity/sounds/{sfx,music}/*.ogg.

Seven sounds were generated with fal.ai (pipeline/fal/manifest.json; the mp3s are kept next to it; the monster's
own noises are then mangled by monsterize() below), the small
ones are synthesised right here. Positional sounds are mono (Minecraft only attenuates mono); the two loops
that play "in your head" stay stereo.   python3 pipeline/build_sfx.py   then   python3 pipeline/build_voice.py
"""
import json
import os
import subprocess
import urllib.request
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'src/main/resources/assets/verity/sounds')
FAL = os.path.join(ROOT, 'pipeline/fal')
SR = 44100
rng = np.random.default_rng(3)
os.makedirs(os.path.join(OUT, 'sfx'), exist_ok=True); os.makedirs(os.path.join(OUT, 'music'), exist_ok=True)


def t(sec): return np.arange(int(SR * sec)) / SR
def env(sec, decay): return np.exp(-t(sec) * decay)
def lp(x, hz):
    a = 1 - np.exp(-2 * np.pi * hz / SR); y = np.empty_like(x); s = 0.0
    for i, v in enumerate(x):
        s += a * (v - s); y[i] = s
    return y
def place(total, *parts):
    y = np.zeros(int(SR * total))
    for at, p in parts:
        i = int(SR * at); y[i:i + len(p)] += p[:len(y) - i]
    return y
def save(name, y, kind='sfx'):
    y = y / max(np.abs(y).max(), 1e-9) * 0.9
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'f32le', '-ar', str(SR), '-ac', '1', '-i', 'pipe:0', '-c:a', 'libvorbis', '-q:a', '5',
                    os.path.join(OUT, kind, name + '.ogg')], input=y.astype('<f4').tobytes(), check=True)


def thump(freq, sec, decay, noise=0.0, noise_hz=900):
    y = np.sin(2 * np.pi * freq * t(sec) * (1 + 0.6 * env(sec, 30))) * env(sec, decay)
    if noise:
        y += lp(rng.normal(0, 1, len(y)), noise_hz) * env(sec, decay * 2.5) * noise
    return y


# knocking from inside a cardboard box
k = thump(130, 0.16, 26, 0.9, 700)
save('knock', place(0.75, (0.0, k), (0.19, k * 0.85), (0.38, k * 1.0)))
# the box bursts open: a ripping hiss and a pop
rip = rng.normal(0, 1, int(SR * 0.35)) * np.linspace(0.2, 1, int(SR * 0.35)) ** 2
save('box_open', place(0.9, (0.0, lp(rip, 5000) * 0.5), (0.33, thump(180, 0.3, 14, 0.8, 2500)), (0.36, lp(rng.normal(0, 1, int(SR * 0.4)), 1800) * env(0.4, 9) * 0.5)))
# a giant's footstep
save('step', place(0.6, (0.0, thump(48, 0.55, 9, 0.6, 300)), (0.02, thump(90, 0.2, 22, 0.3, 500) * 0.5)))
# the ball bouncing
save('bounce', thump(240, 0.16, 20, 0.15, 3000))
# heartbeat: lub-dub
save('heartbeat', place(0.5, (0.0, thump(52, 0.22, 16)), (0.17, thump(44, 0.26, 14) * 0.75)))
# digital glitch
g = np.zeros(int(SR * 0.4))
for _ in range(14):
    i = rng.integers(0, len(g) - 2000); n = rng.integers(300, 2000); f = rng.choice([220, 440, 880, 1760, 3520]) * rng.uniform(0.9, 1.1)
    g[i:i + n] += np.sign(np.sin(2 * np.pi * f * np.arange(n) / SR)) * rng.uniform(0.3, 1)
save('glitch', np.round(g * 4) / 4 + rng.normal(0, 0.05, len(g)))
# "the night": a lo-fi two-tone alert, wrong enough to be nobody's real alarm
tt = t(4.5)
sir = (np.sin(2 * np.pi * 760 * tt) + np.sin(2 * np.pi * 912 * tt)) * (np.sin(2 * np.pi * 1.1 * tt) > -0.2)
sir = np.tanh(sir * 2.5) * (1 + 0.15 * np.sin(2 * np.pi * 0.7 * tt)) + rng.normal(0, 0.06, len(tt))
save('siren', lp(sir, 2600) * np.minimum(1, np.minimum(tt * 8, (4.5 - tt) * 3)))

def load(path):
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'], capture_output=True, check=True).stdout
    x = np.frombuffer(raw, '<f4').astype(np.float64)
    return x / max(np.abs(x).max(), 1e-9)


def repitch(x, factor):
    pos = np.arange(int(len(x) / factor)) * factor
    i = np.minimum(pos.astype(np.int64), len(x) - 2); f = pos - i
    return x[i] * (1 - f) + x[i + 1] * f


def monsterize(x, strength=1.0, stutters=7):
    """Turns a plain creature sound into something that should not have a throat: the take is stacked with copies of itself
    an octave and a fifth lower, a reversed gasp is glued to the front, the whole thing is ring-modulated with a wandering
    low sine, clipped hard, torn into repeating slices like a skipping recording and finally sample-rate crushed."""
    n = int(len(x) * 1.4)
    fit = lambda a: np.pad(a, (0, max(0, n - len(a))))[:n]  # noqa: E731
    low, mid = repitch(x, 0.5), repitch(x, 0.7)
    y = 0.5 * fit(x) + 0.9 * fit(mid) + 1.0 * fit(low) + 0.25 * fit(repitch(x, 1.6))
    m = int(SR * 0.45)
    y = np.concatenate([fit(mid)[:m][::-1] * np.linspace(0, 1, m) ** 2 * 0.7, y])
    tt = np.arange(len(y)) / SR
    wob = 36 + 9 * np.sin(2 * np.pi * 0.8 * tt) + 5 * np.sin(2 * np.pi * 3.1 * tt)
    depth = 0.5 * strength
    y = y * (1 - depth + depth * np.sin(2 * np.pi * np.cumsum(wob) / SR))
    y = np.tanh(y * (3 + 5 * strength))
    for _ in range(stutters):
        i = int(rng.integers(m, max(m + 1, len(y) - int(SR * 0.5)))); L = int(rng.integers(int(SR * 0.04), int(SR * 0.1)))
        seg = y[i:i + L].copy()
        for r in range(1, int(rng.integers(2, 6))):
            j = i + r * L
            if j + L < len(y):
                y[j:j + L] = seg * (1 - 0.1 * r) * (-1 if r % 2 else 1)
    k = 2 + int(round(2 * strength)); y = np.repeat(y[::k], k)[:len(y)]
    y = lp(y, 6000)
    d = int(SR * 0.085)
    y[d:] += 0.34 * y[:-d]; y[2 * d:] += 0.16 * y[:-2 * d]
    f = int(SR * 0.35); y[-f:] *= np.linspace(1, 0, f)
    return y


# fal-generated sounds
MONSTER = {'scream': (1.0, 8), 'growl': (0.7, 3), 'jumpscare': (0.6, 5), 'transform': (0.35, 2)}
manifest = json.load(open(os.path.join(FAL, 'manifest.json')))
for name, item in manifest.items():
    if name.startswith('_'):
        continue
    mp3 = os.path.join(FAL, name + '.mp3')
    if not os.path.exists(mp3):
        urllib.request.urlretrieve(item['url'], mp3)
    if name in MONSTER:
        save(name, monsterize(load(mp3), *MONSTER[name]))
        continue
    stereo_loop = item.get('loop', False)
    kind = 'music' if name == 'old_tune' else 'sfx'
    af = 'loudnorm=I=-16:TP=-1.5' + ('' if stereo_loop else ',afade=t=out:st=%.2f:d=0.25' % (item['seconds'] - 0.25))
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', mp3, '-ac', '2' if stereo_loop else '1', '-ar', '44100', '-af', af,
                    '-c:a', 'libvorbis', '-q:a', '5', os.path.join(OUT, kind, name + '.ogg')], check=True)
print('[sfx]', sorted(os.listdir(os.path.join(OUT, 'sfx'))), sorted(os.listdir(os.path.join(OUT, 'music'))))
