"""Six stages of one old tune coming apart, for the six levels of sanity.

The source is the mod's own "old tune" (pipeline/fal/old_tune.mp3, generated for this mod). Nothing here is anybody
else's recording: the idea of a ballroom record that decays stage by stage is borrowed, the sound is made here.

  python3 pipeline/build_music.py        -> src/main/resources/assets/verity/sounds/music/decay_a.ogg ... decay_f.ogg
  (then python3 pipeline/build_voice.py, which rewrites sounds.json)

Stage 1 is the tune as a worn record. Each stage after it is slower, duller and further away; from the third the needle
sticks, from the fourth only fragments are left, the fifth is their noise, the sixth is what remains when even that is gone.
"""
import os
import subprocess
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'pipeline/fal/old_tune.mp3')
OUT = os.path.join(ROOT, 'src/main/resources/assets/verity/sounds/music')
SR = 32000
rng = np.random.default_rng(1931)


def load():
    raw = subprocess.run(['ffmpeg', '-loglevel', 'error', '-i', SRC, '-f', 'f32le', '-ac', '2', '-ar', str(SR), 'pipe:1'], capture_output=True, check=True).stdout
    return np.frombuffer(raw, '<f4').reshape(-1, 2).astype(np.float32)


def save(x, name, quality=2):
    peak = float(np.abs(x).max()) or 1.0
    x = x / peak * 0.8
    fade = SR * 3                                              # every track ends and begins in silence: they follow one another with gaps
    x[:fade] *= np.linspace(0, 1, fade)[:, None] ** 2
    x[-fade:] *= np.linspace(1, 0, fade)[:, None] ** 2
    pcm = (np.clip(x, -1, 1) * 32767).astype('<i2').tobytes()
    path = os.path.join(OUT, name + '.ogg')
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 's16le', '-ar', str(SR), '-ac', '2', '-i', 'pipe:0', '-c:a', 'libvorbis', '-q:a', str(quality), path],
                   input=pcm, check=True)
    print(f'[music] {name}: {len(x) / SR:.0f} s, {os.path.getsize(path) // 1024} KiB')


def slow(x, speed):
    """A turntable running slow: longer and lower at once."""
    n = int(len(x) / speed)
    pos = np.arange(n) * speed
    i = np.minimum(pos.astype(np.int64), len(x) - 2); f = (pos - i)[:, None].astype(np.float32)
    return x[i] * (1 - f) + x[i + 1] * f


def wow(x, depth, seconds):
    """The record is not flat and the motor is tired: the speed wanders."""
    n = len(x); t = np.arange(n) / SR
    drift = depth * (np.sin(2 * np.pi * t / seconds) + 0.5 * np.sin(2 * np.pi * t / (seconds * 0.37) + 1.3))
    pos = np.clip(np.cumsum(1.0 + drift), 0, n - 2)
    i = pos.astype(np.int64); f = (pos - i)[:, None].astype(np.float32)
    return x[i] * (1 - f) + x[i + 1] * f


def spectral(x, gain):
    """Applies gain(frequencies) to both channels."""
    X = np.fft.rfft(x, axis=0)
    return np.fft.irfft(X * gain(np.fft.rfftfreq(len(x), 1 / SR))[:, None], n=len(x), axis=0).astype(np.float32)


def lowpass(x, hz):
    return spectral(x, lambda f: 1.0 / (1.0 + (f / hz) ** 4))


def bandpass(x, lo, hi):
    return spectral(x, lambda f: (1.0 / (1.0 + (f / hi) ** 4)) * (1.0 - 1.0 / (1.0 + (f / lo) ** 4)))


def reverb(x, seconds, mix):
    """A big empty room: the sound convolved with a burst of dying noise, different for each ear."""
    n = int(SR * seconds * 1.6)
    out = np.zeros((len(x) + n, 2), np.float32)
    size = 1 << int(np.ceil(np.log2(len(x) + n)))
    for c in range(2):
        ir = rng.standard_normal(n).astype(np.float32) * np.exp(-6.9 * np.arange(n) / (SR * seconds))
        ir[:int(SR * 0.012)] = 0
        wet = np.fft.irfft(np.fft.rfft(x[:, c], size) * np.fft.rfft(ir, size), size)[:len(x) + n]
        out[:, c] = wet / (np.abs(wet).max() + 1e-9)
    dry = np.zeros_like(out); dry[:len(x)] = x / (np.abs(x).max() + 1e-9)
    return dry * (1 - mix) + out * mix


def crackle(n, pops_per_second, hiss):
    """Surface noise: pops, a steady hiss, and the thump of a warp once a revolution."""
    y = np.zeros((n, 2), np.float32)
    count = int(n / SR * pops_per_second)
    at = rng.integers(0, n - 64, count); size = rng.random(count) ** 3
    for c in range(2):
        side = rng.random(count) * 0.6 + 0.4
        np.add.at(y[:, c], at, (size * side * rng.choice([-1, 1], count)).astype(np.float32))
        np.add.at(y[:, c], at + 1, (-0.5 * size * side).astype(np.float32))
    y += hiss * bandpass(rng.standard_normal((n, 2)).astype(np.float32), 1500, 7000)
    rev = int(SR * 60 / 78)                                    # 78 rpm
    thump = np.exp(-np.arange(900) / 120.0) * np.sin(2 * np.pi * 55 * np.arange(900) / SR)
    for start in range(0, n - 900, rev):
        y[start:start + 900] += (0.25 * hiss * 20 * thump)[:, None].astype(np.float32)
    return y


def stuck(x, at_s, length_s, times):
    """The needle jumps back: one short stretch plays again and again."""
    a = int(at_s * SR); n = int(length_s * SR)
    groove = x[a:a + n].copy()
    k = int(0.01 * SR)
    groove[:k] *= np.linspace(0, 1, k)[:, None]; groove[-k:] *= np.linspace(1, 0, k)[:, None]
    return np.concatenate([x[:a + n]] + [groove] * times + [x[a + n:]])


def paulstretch(x, stretch, window_s=0.25):
    """Stretches a sound enormously by keeping the loudness of every frequency and throwing its timing away: a held breath of the tune."""
    win = int(window_s * SR) // 2 * 2
    w = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(win) / win)) ** 1.0
    hop_out = win // 4; hop_in = hop_out / stretch
    frames = int((len(x) - win) / hop_in)
    out = np.zeros((frames * hop_out + win, 2), np.float32)
    for c in range(2):
        for k in range(frames):
            a = int(k * hop_in)
            mag = np.abs(np.fft.rfft(x[a:a + win, c] * w))
            seg = np.fft.irfft(mag * np.exp(2j * np.pi * rng.random(len(mag))), win)
            out[k * hop_out:k * hop_out + win, c] += (seg * w).astype(np.float32)
    return out


def level(x, target):
    return x * (target / (float(np.sqrt((x ** 2).mean())) + 1e-9))


def looped(x, seconds):
    reps = int(np.ceil(seconds * SR / len(x)))
    return np.concatenate([x] * reps)[:int(seconds * SR)]


def main():
    os.makedirs(OUT, exist_ok=True)
    tune = load()
    tune = tune / (np.abs(tune).max() + 1e-9)

    # 1: a record somebody loved. Slightly slow, a little far away, the surface just audible.
    a = lowpass(slow(looped(tune, 66), 0.94), 5500)
    a = reverb(a, 1.2, 0.2)
    save(level(a, 0.2) + crackle(len(a), 9, 0.004), 'decay_a', quality=3)

    # 2: the same record, later. Duller, slower, and it has started to wander.
    b = wow(lowpass(slow(looped(tune, 66), 0.88), 4000), 0.004, 5.5)
    b = reverb(b, 2.2, 0.32)
    save(level(b, 0.2) + crackle(len(b), 22, 0.008), 'decay_b')

    # 3: it sticks. A phrase comes round again when it should not, and again.
    c = slow(looped(tune, 60), 0.82)
    c = stuck(c, 17.0, 1.35, 4)
    c = stuck(c, 44.0, 0.7, 7)
    c = wow(lowpass(c, 3000), 0.007, 4.3)
    c = reverb(c, 3.2, 0.45)
    save(level(c, 0.19) + crackle(len(c), 40, 0.013), 'decay_c')

    # 4: after the point of knowing. Pieces of the tune, out of order, on top of one another, mostly room.
    base = lowpass(slow(looped(tune, 44), 0.72), 2300)
    n = int(80 * SR)
    d = np.zeros((n, 2), np.float32)
    for _ in range(26):
        length = int(rng.uniform(1.2, 5.0) * SR); src = rng.integers(0, len(base) - length); dst = rng.integers(0, n - length)
        piece = base[src:src + length] * (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(length) / length))[:, None]
        if rng.random() < 0.3:
            piece = piece[::-1]
        d[dst:dst + length] += piece * rng.uniform(0.4, 1.0)
    d = np.tanh(reverb(d, 4.5, 0.7) * 2.2)
    save(level(d, 0.18) + crackle(len(d), 30, 0.018), 'decay_d')

    # 5: the pieces themselves are going. Smears of them, held too long, with noise where the rest was.
    smear = paulstretch(lowpass(slow(tune, 0.7), 2200), 5.0, 0.18)
    e = level(looped(smear, 85), 0.16)
    bursts = np.zeros_like(e)
    for _ in range(9):
        length = int(rng.uniform(0.6, 2.2) * SR); src = rng.integers(0, len(base) - length); dst = rng.integers(0, len(e) - length)
        bursts[dst:dst + length] += np.tanh(base[src:src + length] * 6) * 0.1 * (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(length) / length))[:, None]
    e = reverb(np.tanh((e + bursts) * 2.5), 5.5, 0.6)
    e = lowpass(e, 1900)
    swell = 0.55 + 0.45 * np.sin(2 * np.pi * np.arange(len(e)) / SR / 23.0)
    save(level(e * swell[:, None], 0.16) + crackle(len(e), 60, 0.03), 'decay_e')

    # 6: nothing is left to forget. A low held breath of what the tune was, and long stretches of nearly nothing.
    drone = paulstretch(lowpass(slow(tune, 0.6), 1100), 14.0, 0.5)
    f = lowpass(looped(drone, 95), 800)
    f = reverb(f, 8.0, 0.75)
    t = np.arange(len(f)) / SR
    swell = np.clip(0.5 + 0.5 * np.sin(2 * np.pi * t / 31.0 - 1.2), 0, 1) ** 2.5
    rumble = np.sin(2 * np.pi * 38 * t) * (0.5 + 0.5 * np.sin(2 * np.pi * t / 17.0))
    f = level(f, 0.15) * (0.12 + 0.88 * swell)[:, None] + 0.035 * rumble[:, None].astype(np.float32)
    save(f + crackle(len(f), 3, 0.006), 'decay_f')


if __name__ == '__main__':
    main()
