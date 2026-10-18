"""Writes a few lines in the current voice to voice-demo/, to be listened to without starting the game.

  python3 pipeline/voice_demo.py <name> [intonation multiplier]     e.g.  python3 pipeline/voice_demo.py 3-сильнее 1.6
"""
import copy
import json
import os
import subprocess
import sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voice_dsp as dsp  # noqa: E402
import voice_prosody as prosody  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
recipe = json.load(open(os.path.join(ROOT, 'src/main/resources/verity_voice.json'), encoding='utf-8'))
lines = json.load(open(os.path.join(ROOT, 'src/main/resources/verity_lines.json'), encoding='utf-8'))['lines']
SR = recipe['sampleRate']
name = sys.argv[1]; k = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
recipe = copy.deepcopy(recipe)
for p in recipe['profiles'].values():
    p['intonation'] = p.get('intonation', 1.0) * k


def piper(text, length_scale, noise=0.667, noise_w=0.8):
    out = subprocess.run([os.path.join(ROOT, '.tools/piper/piper'), '--model', os.path.join(ROOT, '.tools/piper/voices', recipe['model'] + '.onnx'), '--output_raw',
                          '--length_scale', f'{length_scale:.4f}', '--noise_scale', f'{noise:.3f}', '--noise_w', f'{noise_w:.3f}', '--sentence_silence', '0', '-q'],
                         input=text.encode(), capture_output=True, check=True).stdout
    return np.frombuffer(out, '<i2').astype(np.float32) / 32768.0


parts = []
for lid in ('intro_hello', 'q_alone', 'think_lonely', 'lava_rage', 'rule_3'):
    line = lines[lid]; p = recipe['profiles'][line['profile']]
    y = dsp.fx(prosody.speak(line['text'], p, line['emotion'], recipe, SR, piper), p, SR)
    if 'layer' in p:
        q = dict(recipe['profiles'][p['layer']]); q['tempo'] = p['tempo']
        z = dsp.fx(prosody.speak(line['text'], q, line['emotion'], recipe, SR, piper), q, SR)
        n = max(len(y), len(z)); y = np.pad(y, (0, n - len(y))); z = np.pad(z, (0, n - len(z)))
        y = dsp.finish(y, 1.0) + dsp.finish(z, 1.0) * p['layerMix']
    parts += [dsp.finish(y, p['gain']), np.zeros(SR // 2, np.float32)]
os.makedirs(os.path.join(ROOT, 'voice-demo'), exist_ok=True)
pcm = (np.clip(np.concatenate(parts), -1, 1) * 32767).astype('<i2').tobytes()
out = os.path.join(ROOT, 'voice-demo', name + '.ogg')
subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', 'pipe:0', '-c:a', 'libvorbis', '-q:a', '5', out], input=pcm, check=True)
print(out)
