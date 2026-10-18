"""Bakes every scripted line from verity_lines.json into assets: voice/<id>.ogg + lipsync.json, and (re)writes sounds.json.

  python3 pipeline/build_voice.py            (uses .tools/piper and ffmpeg)
"""
import base64
import hashlib
import json
import os
import subprocess
import sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import voice_dsp as dsp  # noqa: E402
import voice_prosody as prosody  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'src/main/resources')
ASSETS = os.path.join(RES, 'assets/verity')
PIPER = os.path.join(ROOT, '.tools/piper/piper')
CACHE = os.path.join(ROOT, 'pipeline/out/voice_cache')
recipe = json.load(open(os.path.join(RES, 'verity_voice.json'), encoding='utf-8'))
lines = json.load(open(os.path.join(RES, 'verity_lines.json'), encoding='utf-8'))['lines']
SR = recipe['sampleRate']
MODEL = os.path.join(ROOT, '.tools/piper/voices', recipe['model'] + '.onnx')
PROSODY_SRC = hashlib.sha1(open(prosody.__file__, 'rb').read()).hexdigest()       # a change to the melody rebakes everything


def piper(text, length_scale, noise=0.667, noise_w=0.8, model=MODEL):
    out = subprocess.run([PIPER, '--model', model, '--output_raw', '--length_scale', f'{length_scale:.4f}', '--noise_scale', f'{noise:.3f}',
                          '--noise_w', f'{noise_w:.3f}', '--sentence_silence', '0', '-q'],
                         input=text.encode(), capture_output=True, check=True).stdout
    return np.frombuffer(out, '<i2').astype(np.float32) / 32768.0


def speak(text, name, emotion):
    p = recipe['profiles'][name]
    # a profile may speak with another Piper voice (the friend, the support line); the rest are Verity's own
    own = os.path.join(ROOT, '.tools/piper/voices', p['model'] + '.onnx') if 'model' in p else MODEL
    voice = lambda t, ls, noise=0.667, noise_w=0.8: piper(t, ls, noise, noise_w, own)
    y = dsp.fx(prosody.speak(text, p, emotion, recipe, SR, voice), p, SR)
    if 'layer' in p:                                   # second, deeper voice underneath, same tempo
        q = dict(recipe['profiles'][p['layer']]); q['tempo'] = p['tempo']
        z = dsp.fx(prosody.speak(text, q, emotion, recipe, SR, piper), q, SR)
        n = max(len(y), len(z)); y = np.pad(y, (0, n - len(y))); z = np.pad(z, (0, n - len(z)))
        y = dsp.finish(y, 1.0) + dsp.finish(z, 1.0) * p['layerMix']
    return dsp.finish(y, p['gain'])


def ogg(samples, path):
    pcm = (np.clip(samples, -1, 1) * 32767).astype('<i2').tobytes()
    subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-f', 's16le', '-ar', str(SR), '-ac', '1', '-i', 'pipe:0',
                    '-c:a', 'libvorbis', '-q:a', '5', path], input=pcm, check=True)


os.makedirs(os.path.join(ASSETS, 'sounds/voice'), exist_ok=True); os.makedirs(CACHE, exist_ok=True)
lipsync = {}; made = 0
for lid, line in lines.items():
    key = hashlib.sha1(json.dumps([line['text'], line['profile'], line['emotion'], recipe['prosody'], PROSODY_SRC, recipe['profiles'][line['profile']],
                                   recipe['profiles'].get(recipe['profiles'][line['profile']].get('layer', ''), 0), recipe['model']],
                                  sort_keys=True).encode()).hexdigest()[:16]
    cached = os.path.join(CACHE, f'{lid}.{key}.npy'); target = os.path.join(ASSETS, 'sounds/voice', lid + '.ogg')
    if os.path.exists(cached) and os.path.exists(target):
        y = np.load(cached)
    else:
        y = speak(line['text'], line['profile'], line['emotion']); np.save(cached, y); ogg(y, target); made += 1
    lipsync[lid] = {'d': round(len(y) / SR, 3), 'e': base64.b64encode(dsp.envelope(y, SR).tobytes()).decode()}
ogg(np.zeros(SR // 10, np.float32), os.path.join(ASSETS, 'sounds/voice/silence.ogg'))
json.dump({'hop': 0.02, 'lines': lipsync}, open(os.path.join(ASSETS, 'lipsync.json'), 'w'))

# sounds.json: voice lines + the stream placeholder for runtime TTS + whatever effect/music files exist
sounds = {f'voice.{lid}': {'sounds': [{'name': f'verity:voice/{lid}'}]} for lid in lines}
sounds['voice.dynamic'] = {'sounds': [{'name': 'verity:voice/silence', 'stream': True}]}
for kind, stream in (('sfx', False), ('music', True)):
    d = os.path.join(ASSETS, 'sounds', kind)
    for f in sorted(os.listdir(d)) if os.path.isdir(d) else []:
        if f.endswith('.ogg'):
            entry = {'name': f'verity:{kind}/{f[:-4]}'}
            if stream:
                entry['stream'] = True
            base = f[:-4].rstrip('0123456789').rstrip('_') if f[:-4][-1].isdigit() else f[:-4]
            sounds.setdefault(f'{kind}.{base}', {'sounds': []})['sounds'].append(entry)
json.dump(sounds, open(os.path.join(ASSETS, 'sounds.json'), 'w'), indent=1)
total = sum(v['d'] for v in lipsync.values())
print(f'[voice] {len(lines)} lines ({made} synthesised now), {total:.0f} s of speech, {len(sounds)} sound events')
