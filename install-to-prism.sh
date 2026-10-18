#!/usr/bin/env bash
# Installs the freshly built mod into its own Prism Launcher instance "Verity" (your other instances are not touched).
#   ./install-to-prism.sh            (build first: ./gradlew build)
set -euo pipefail
R=$(cd "$(dirname "$0")" && pwd)
PRISM="${PRISM_DIR:-$HOME/.local/share/PrismLauncher}"
INST="$PRISM/instances/Verity"
JAR=$(ls -t "$R"/build/libs/verity-*.jar | head -1)
mkdir -p "$INST/minecraft/mods" "$INST/minecraft/verity/piper/voices"
if [ ! -f "$INST/mmc-pack.json" ]; then
    cat > "$INST/mmc-pack.json" <<JSON
{"components":[{"cachedName":"LWJGL 3","cachedVersion":"3.3.3","dependencyOnly":true,"uid":"org.lwjgl3","version":"3.3.3"},
{"cachedName":"Minecraft","cachedVersion":"1.21.1","important":true,"uid":"net.minecraft","version":"1.21.1"},
{"cachedName":"NeoForge","cachedVersion":"21.1.257","uid":"net.neoforged","version":"21.1.257"}],"formatVersion":1}
JSON
    printf '[General]\nConfigVersion=1.3\nInstanceType=OneSix\nname=Verity\niconKey=default\nnotes=Верити: мод по мотивам сериала «Something». Перед игрой запусти ./start-ollama.sh из папки проекта.\n' > "$INST/instance.cfg"
    printf 'lang:ru_ru\n' > "$INST/minecraft/options.txt"
fi
rm -f "$INST"/minecraft/mods/verity-*.jar
cp "$JAR" "$INST/minecraft/mods/"
# the voice engine for AI answers (scripted lines are already inside the jar)
if [ -d "$R/.tools/piper" ]; then
    MODEL=$(python3 -c "import json;print(json.load(open('$R/src/main/resources/verity_voice.json'))['model'])")
    rsync -a --exclude voices "$R/.tools/piper/" "$INST/minecraft/verity/piper/"
    cp "$R"/.tools/piper/voices/*.onnx "$R"/.tools/piper/voices/*.onnx.json "$INST/minecraft/verity/piper/voices/"     # Verity, plus the voices he borrows
fi
# speech recognition for asking him out loud (hold V): whisper.cpp and its model, built by setup-whisper.sh
if [ -x "$R/.tools/whisper/whisper-cli" ]; then
    mkdir -p "$INST/minecraft/verity/whisper/models"
    cp "$R/.tools/whisper/whisper-cli" "$INST/minecraft/verity/whisper/"
    cp -n "$R"/.tools/whisper/models/*.bin "$INST/minecraft/verity/whisper/models/" 2>/dev/null || true
fi
echo "Installed $(basename "$JAR") into $INST"
