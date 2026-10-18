#!/usr/bin/env bash
# Puts your own music into the game for Verity's world: converts every audio file in a folder to .ogg (the only format
# Minecraft can play) and copies it into the Prism instance "Verity". Folder names are kept, so an album laid out as
# ".../Stage 1/...", ".../Stage 2/..." plays stage by stage as your sanity goes.
#   ./import-music.sh ~/Музыка/"The Caretaker"
# The mod does not come with anybody's music: use files you own.
set -euo pipefail
SRC=${1:?"Укажи папку с музыкой: ./import-music.sh <папка>"}
PRISM="${PRISM_DIR:-$HOME/.local/share/PrismLauncher}"
DST="${VERITY_MUSIC_DIR:-$PRISM/instances/Verity/minecraft/verity/music}"
[ -d "$SRC" ] || { echo "Нет такой папки: $SRC"; exit 1; }
command -v ffmpeg >/dev/null || { echo "Нужен ffmpeg."; exit 1; }
mkdir -p "$DST"
count=0
while IFS= read -r -d '' f; do
    rel=${f#"$SRC"/}
    out="$DST/${rel%.*}.ogg"
    mkdir -p "$(dirname "$out")"
    if [ -f "$out" ] && [ "$out" -nt "$f" ]; then continue; fi
    echo "→ ${rel%.*}.ogg"
    ffmpeg -nostdin -y -loglevel error -i "$f" -vn -c:a libvorbis -q:a 4 "$out"
    count=$((count + 1))
done < <(find "$SRC" -type f \( -iname '*.mp3' -o -iname '*.flac' -o -iname '*.wav' -o -iname '*.m4a' -o -iname '*.opus' -o -iname '*.ogg' -o -iname '*.aac' -o -iname '*.wma' \) -print0 | sort -z)
echo "Готово: добавлено файлов: $count. Папка: $DST"
