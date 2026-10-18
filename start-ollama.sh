#!/usr/bin/env bash
# Starts the local AI server Verity talks to (http://127.0.0.1:11434, model gemma3:12b). Leave it running while you play.
R=$(cd "$(dirname "$0")" && pwd)
# 8192 tokens of context: Verity's briefing (surroundings, inventory, recent events, memory) no longer fits the default 4096
export OLLAMA_MODELS="$R/.tools/ollama/models" OLLAMA_HOST=127.0.0.1:11434 OLLAMA_CONTEXT_LENGTH=8192
if curl -s -o /dev/null http://127.0.0.1:11434/api/version; then echo "Ollama is already running."; exit 0; fi
exec "$R/.tools/ollama/bin/ollama" serve
