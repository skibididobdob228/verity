"""Verity's voice effect chain (numpy). dev.verity.client.voice.VoiceDsp is a line-by-line port: keep them in sync."""
import numpy as np


def resample(x, speed):
    n = int(len(x) / speed)
    pos = np.arange(n) * speed
    i = np.minimum(pos.astype(np.int64), len(x) - 2); f = (pos - i).astype(np.float32)
    return x[i] * (1 - f) + x[i + 1] * f


def one_pole_lp(x, hz, sr):
    a = float(1.0 - np.exp(-2.0 * np.pi * hz / sr)); y = np.empty_like(x); s = 0.0
    for n in range(len(x)):
        s += a * (x[n] - s); y[n] = s
    return y


def process(x, p, sr):
    """x: float mono in [-1, 1] straight from Piper. Returns the finished voice (no intonation: see voice_prosody)."""
    return fx(resample(x.astype(np.float32), p['pitch']), p, sr)


def fx(y, p, sr):
    """The effect chain alone, for audio that has already been brought to its final pitch."""
    y = y.astype(np.float32).copy()
    if p.get('highpass', 0) > 0:
        y = y - one_pole_lp(y, p['highpass'], sr)
    if p.get('drive', 0) > 0:
        y = np.tanh(y * p['drive']) / np.tanh(p['drive'])
    if p.get('ringDepth', 0) > 0:
        n = np.arange(len(y), dtype=np.float32)
        y = y * (1 - p['ringDepth'] + p['ringDepth'] * np.sin(2 * np.pi * p['ringHz'] * n / sr)).astype(np.float32)
    if p.get('crush', 0) > 1:
        k = int(p['crush']); y = np.repeat(y[::k], k)[:len(y)]
    if p.get('combMs', 0) > 0:
        d = int(sr * p['combMs'] / 1000.0); src = y.copy()
        y[d:] += p['combGain'] * src[:-d]
    if p.get('echoMs', 0) > 0:
        d = int(sr * p['echoMs'] / 1000.0); y = np.concatenate([y, np.zeros(d * 3, np.float32)])
        for n in range(d, len(y)):
            y[n] += p['echoFb'] * y[n - d]
    if p.get('lowpass', 0) > 0:
        y = one_pole_lp(one_pole_lp(y, p['lowpass'], sr), p['lowpass'], sr)
    return y


def finish(y, gain):
    peak = float(np.abs(y).max()) or 1.0
    return y * (0.89 * gain / peak)


def envelope(y, sr, hop_s=0.02):
    """Mouth-opening curve: one byte per 20 ms."""
    hop = int(sr * hop_s); n = len(y) // hop
    rms = np.sqrt((y[:n * hop].reshape(n, hop) ** 2).mean(1)) if n else np.zeros(0)
    ref = np.percentile(rms, 95) if n else 1.0
    e = np.clip(rms / max(ref, 1e-6), 0, 1) ** 0.6
    return (e * 255).astype(np.uint8)
