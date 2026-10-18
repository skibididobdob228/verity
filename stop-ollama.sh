#!/usr/bin/env bash
# Stops the local AI server that start-ollama.sh started (and only that one). Verity keeps working without it:
# his scripted lines stay, but he can no longer answer what you write.
R=$(cd "$(dirname "$0")" && pwd)
BIN="$R/.tools/ollama/bin/ollama"
PIDS=$(pgrep -f "^$BIN serve" || true)
if [ -z "$PIDS" ]; then echo "ИИ и так выключен (Ollama из этой папки не запущена)."; exit 0; fi
kill $PIDS
for _ in $(seq 50); do
    pgrep -f "^$BIN serve" >/dev/null || { echo "ИИ выключен."; exit 0; }
    sleep 0.2
done
kill -9 $(pgrep -f "^$BIN serve") 2>/dev/null
echo "ИИ выключен (пришлось остановить принудительно)."
