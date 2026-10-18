#!/usr/bin/env bash
# One long recording with all six stages in a row -> six folders "Stage 1" ... "Stage 6" of .ogg pieces in the Prism
# instance "Verity", so that each stage plays at its own sanity level.
#   ./split-music.sh <file>                                   stage boundaries guessed from the album's proportions
#   ./split-music.sh <file> 0:41:23 1:23:15 2:08:42 3:35:46 5:03:25      or given exactly: where stages 2..6 begin
# Each stage is cut into pieces of about eight minutes so that it does not always start from the same place.
set -euo pipefail
SRC=${1:?"Укажи файл: ./split-music.sh <файл> [начало стадий 2..6]"}
PRISM="${PRISM_DIR:-$HOME/.local/share/PrismLauncher}"
DST="${VERITY_MUSIC_DIR:-$PRISM/instances/Verity/minecraft/verity/music}"
[ -f "$SRC" ] || { echo "Нет такого файла: $SRC"; exit 1; }
secs() { awk -F: '{ s = 0; for (i = 1; i <= NF; i++) s = s * 60 + $i; print s }' <<<"$1"; }
TOTAL=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$SRC"); TOTAL=${TOTAL%.*}
if [ $# -ge 6 ]; then
    B=(0 "$(secs "$2")" "$(secs "$3")" "$(secs "$4")" "$(secs "$5")" "$(secs "$6")" "$TOTAL")
else
    # the stages' shares of the whole album (about 41, 42, 45, 87, 88 and 86 minutes), scaled to this file's length
    B=(0); for share in 1063 2139 3307 5544 7796; do B+=($((TOTAL * share / 10000))); done; B+=("$TOTAL")
    echo "Границы стадий взяты по пропорциям альбома. Если не совпадут, укажи их сам (см. начало скрипта)."
fi
for n in 1 2 3 4 5 6; do
    from=${B[$((n - 1))]}; to=${B[$n]}
    mkdir -p "$DST/Stage $n"; rm -f "$DST/Stage $n"/part-*.ogg
    printf 'Стадия %d: %d:%02d:%02d – %d:%02d:%02d\n' "$n" $((from / 3600)) $((from % 3600 / 60)) $((from % 60)) $((to / 3600)) $((to % 3600 / 60)) $((to % 60))
    ffmpeg -nostdin -y -loglevel error -ss "$from" -to "$to" -i "$SRC" -vn -c:a libvorbis -q:a 4 -f segment -segment_time 480 "$DST/Stage $n/part-%02d.ogg"
done
echo "Готово. Папка: $DST"
