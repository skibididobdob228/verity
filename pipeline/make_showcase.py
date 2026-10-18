"""Cuts the showcase clip from two raw lab recordings:

  VERITY_LAB_SEED=7 VERITY_LAB_RECORD=showcase_raw   pipeline/lab.sh pipeline/tests/showcase.txt normal
  VERITY_LAB_SEED=7 VERITY_LAB_RECORD=showcase_broll pipeline/lab.sh pipeline/tests/broll.txt normal
  python3 pipeline/make_showcase.py run/showcase_raw.mp4 run/showcase_broll.mp4 showcase.mp4 [font.ttf]

The recordings are of the whole (virtual) monitor, so the game area is cropped out first; then the chosen
moments are joined, each with a one-line caption in the letterbox bar, and the sound is levelled.
"""
import subprocess
import sys

main, broll, out = sys.argv[1], sys.argv[2], sys.argv[3]
font = sys.argv[4] if len(sys.argv) > 4 else '/usr/share/fonts/noto/NotoSans-Bold.ttf'
# the game window inside the 1280x720 virtual monitor, minus a 24 px strip where a desktop widget overlaps it
CROP = 'crop=1264:606:8:82'
# source (0 = story take, 1 = daylight b-roll), start, end (seconds), caption
CUTS = [
    (0, 8.0, 12.0, 'Посылка, которую никто не заказывал'),
    (0, 14.2, 17.6, 'Внутри — Верити'),
    (0, 28.6, 33.4, 'Он не летает: он живёт у тебя в инвентаре'),
    (0, 38.0, 44.2, 'Отвечает сам — локальный ИИ, голос и липсинк'),
    (0, 46.2, 50.6, 'Сюжет по мотивам сериала «Something»'),
    (1, 9.3, 13.6, 'Истинный облик: рваная, ломаная анимация'),
    (0, 57.8, 60.6, 'Ночь третьего дня'),
    (0, 62.5, 73.5, 'Что-то пришло'),
    (0, 79.0, 84.5, '…и больше никуда не уйдёт'),
]
parts, labels = [], []
for i, (src, a, b, text) in enumerate(CUTS):
    d = b - a
    esc = text.replace(':', r'\:').replace("'", r"\'").replace(',', r'\,')
    parts.append(
        f"[{src}:v]trim={a}:{b},setpts=PTS-STARTPTS,{CROP},pad=1280:720:8:57:black,"
        f"drawtext=fontfile={font}:text='{esc}':fontsize=25:fontcolor=0xEBCC05:x=(w-text_w)/2:y=720-57+13:"
        f"alpha='min(1,min(t/0.35,({d}-t)/0.35))',fps=30,format=yuv420p[v{i}];"
        f"[{src}:a]atrim={a}:{b},asetpts=PTS-STARTPTS,afade=t=in:d=0.08,afade=t=out:st={d - 0.12:.2f}:d=0.12[a{i}]")
    labels.append(f'[v{i}][a{i}]')
total = sum(b - a for _, a, b, _ in CUTS)
graph = ';'.join(parts) + ';' + ''.join(labels) + f'concat=n={len(CUTS)}:v=1:a=1[v][a];' \
    f'[v]fade=t=out:st={total - 0.8:.2f}:d=0.8[vo];[a]loudnorm=I=-17:TP=-2,alimiter=limit=0.85,volume=0.68,afade=t=out:st={total - 0.8:.2f}:d=0.8[ao]'
subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', main, '-i', broll, '-filter_complex', graph, '-map', '[vo]', '-map', '[ao]',
                '-c:v', 'libx264', '-crf', '20', '-preset', 'medium', '-c:a', 'aac', '-b:a', '160k', '-movflags', '+faststart', out], check=True)
print(f'{out}: {total:.1f} s, {len(CUTS)} cuts')
