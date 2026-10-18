#!/usr/bin/env bash
# Builds the speech recognition Verity uses when you hold V and ask him out loud (whisper.cpp, runs offline on the CPU)
# and downloads its Russian-capable model (ggml-small-q5_1, 190 MB). Afterwards run ./install-to-prism.sh.
#   ./setup-whisper.sh
set -euo pipefail
R=$(cd "$(dirname "$0")" && pwd)
V=v1.7.6
mkdir -p "$R/.tools/whisper/models" "$R/.tools/whisper-src"
if [ ! -x "$R/.tools/whisper/whisper-cli" ]; then
    cd "$R/.tools/whisper-src"
    [ -d src ] || { curl -L -o src.tar.gz "https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/refs/tags/$V"; mkdir -p src; tar xzf src.tar.gz -C src --strip-components=1; }
    cmake -S src -B src/build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DWHISPER_BUILD_TESTS=OFF -DGGML_NATIVE=OFF -DGGML_AVX2=ON -DGGML_FMA=ON -DGGML_F16C=ON -DGGML_OPENMP=OFF
    cmake --build src/build -j "$(nproc)" --target whisper-cli
    cp src/build/bin/whisper-cli "$R/.tools/whisper/whisper-cli"
fi
M="$R/.tools/whisper/models/ggml-small-q5_1.bin"
[ -s "$M" ] || curl -L -o "$M" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
echo "whisper ready: $R/.tools/whisper"
