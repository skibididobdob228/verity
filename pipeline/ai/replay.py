"""Replays prompts saved by the mod (run/verity/prompts/*.json) against other models, to compare answers side by side.
  python3 pipeline/ai/replay.py run/verity/prompts gemma3:12b qwen3:14b ...
"""
import json, os, re, sys, time, urllib.request

def ask(body, model, temperature=0.6):
    b = dict(body); b['model'] = model; b['temperature'] = temperature; b['max_tokens'] = 400
    msgs = [dict(m) for m in b['messages']]
    if model.startswith('qwen3'):
        msgs[0]['content'] += '\n/no_think'
    b['messages'] = msgs
    req = urllib.request.Request('http://127.0.0.1:11434/v1/chat/completions', json.dumps(b).encode(), {'Content-Type': 'application/json'})
    t = time.time()
    out = json.load(urllib.request.urlopen(req, timeout=300))
    text = out['choices'][0]['message']['content']
    text = re.sub(r'<think>.*?</think>', '', text, flags=re.S).strip()
    return text, time.time() - t, out.get('usage', {}).get('prompt_tokens')

folder, models = sys.argv[1], sys.argv[2:]
files = sorted(os.listdir(folder))
for f in files:
    body = json.load(open(os.path.join(folder, f)))
    q = body['messages'][-1]['content']
    print('\n=== ' + q)
    for m in models:
        try:
            text, dt, pt = ask(body, m)
            try: say = json.loads(text[text.index('{'):text.rindex('}') + 1]).get('say', text)
            except Exception: say = text
            print(f'  [{m} {dt:.1f}s p{pt}] {say}')
        except Exception as e:
            print(f'  [{m}] ERROR {e}')
