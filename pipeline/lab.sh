#!/usr/bin/env bash
# Runs the dev client out of sight (on a Hyprland headless monitor) and plays a selftest script in it.
#   pipeline/lab.sh <script.txt> [flat|normal] [timeoutSeconds]
# Screenshots land in run/screenshots, the log in run/logs/latest.log. Build first: ./gradlew classes processResources
set -euo pipefail
R=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT=$(realpath "$1"); WORLD=${2:-flat}; TIMEOUT=${3:-240}
MON=VERITYLAB
mkdir -p "$R/run"

if ! hyprctl monitors all -j | grep -q "\"name\": \"$MON\""; then
    hyprctl output create headless $MON >/dev/null
    # explicit positions for BOTH: a catch-all "position = auto" rule would otherwise shove the real monitor around
    REAL=$(hyprctl monitors -j | python3 -I -c "import sys,json; m=[x for x in json.load(sys.stdin) if x['name']!='$MON'][0]; print(m['name'], '%dx%d@%d' % (m['width'], m['height'], round(m['refreshRate'])))")
    hyprctl eval "hl.monitor({ output = '${REAL%% *}', mode = '${REAL##* }', position = '0x0', scale = 1.0 })" >/dev/null
    hyprctl eval "hl.monitor({ output = '$MON', mode = '1280x720@60', position = '0x-9000', scale = 1.0 })" >/dev/null
    hyprctl eval "hl.dispatch(hl.dsp.focus({ monitor = '${REAL%% *}' }))" >/dev/null 2>&1 || true
    sleep 0.5
fi
WS=$(hyprctl monitors all -j | python3 -I -c "import sys,json; print([m for m in json.load(sys.stdin) if m['name']=='$MON'][0]['activeWorkspace']['id'])")

[ -f "$R/run/options.txt" ] || cat > "$R/run/options.txt" <<OPT
pauseOnLostFocus:false
enableVsync:false
maxFps:60
renderDistance:8
simulationDistance:6
guiScale:2
soundCategory_master:${VERITY_LAB_VOLUME:-0.0}
tutorialStep:none
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
autosaveIndicator:false
OPT

# VERITY_LAB_RECORD=name: route the game's sound into a silent virtual sink and record screen + sound to run/<name>.mp4
AUDIO_ENV=""
if [ -n "${VERITY_LAB_RECORD:-}" ]; then
    pactl list short sinks | grep -q verity_lab || pactl load-module module-null-sink sink_name=verity_lab sink_properties=device.description=VerityLab >/dev/null
    AUDIO_ENV="export PULSE_SINK=verity_lab PIPEWIRE_NODE=verity_lab;"
fi
# always start muted: a recording script raises the volume itself ("volume 1") once the sound is safely rerouted
sed -i 's/^soundCategory_master:.*/soundCategory_master:0.0/' "$R/run/options.txt"

# VERITY_LAB_JAR=1: load the built jar from run/mods (as a player would) instead of the class folders
MODSRC="'-Dfml.modFolders=verity%%$R/build/classes/java/main:verity%%$R/build/resources/main'"
rm -f "$R"/run/mods/verity-*.jar
if [ -n "${VERITY_LAB_JAR:-}" ]; then mkdir -p "$R/run/mods"; cp "$(ls -t "$R"/build/libs/verity-*.jar | head -1)" "$R/run/mods/"; MODSRC=""; fi

cat > "$R/run/lab-launch.sh" <<LAUNCH
#!/bin/sh
$AUDIO_ENV
cd '$R/run' && echo \$\$ > lab.pid && exec '$R/.tools/jdk21/bin/java' '@$R/build/moddev/clientRunClasspath.txt' '@$R/build/moddev/clientRunVmArgs.txt' \
  $MODSRC \
  '-Dverity.selftest=$SCRIPT' '-Dverity.selftest.world=$WORLD' '-Dverity.selftest.seed=${VERITY_LAB_SEED:-20260530}' ${VERITY_LAB_JVM:-} \
  net.neoforged.devlaunch.Main '@$R/build/moddev/clientRunProgramArgs.txt' --width 1280 --height 720 > lab.out 2>&1
LAUNCH
chmod +x "$R/run/lab-launch.sh"
rm -f "$R/run/lab.pid"
hyprctl eval "hl.exec_cmd('$R/run/lab-launch.sh', { workspace = '$WS silent', no_initial_focus = true, fullscreen = true })" >/dev/null

for _ in $(seq 50); do [ -f "$R/run/lab.pid" ] && break; sleep 0.2; done
PID=$(cat "$R/run/lab.pid")

# VERITY_LAB_GUEST=script: a second client (player "Guest", own game dir run-guest) that joins the first one's LAN world.
# The host script must contain "lan 25599"; screenshots of the guest land in run-guest/screenshots.
GPID=""
if [ -n "${VERITY_LAB_GUEST:-}" ]; then
    G="$R/run-guest"; mkdir -p "$G"; rm -rf "$G/screenshots"
    sed 's/^soundCategory_master:.*/soundCategory_master:0.0/' "$R/run/options.txt" > "$G/options.txt"
    cat > "$G/lab-launch.sh" <<LAUNCH
#!/bin/sh
cd '$G' && echo \$\$ > lab.pid && exec '$R/.tools/jdk21/bin/java' '@$R/build/moddev/clientRunClasspath.txt' '@$R/build/moddev/clientRunVmArgs.txt' \
  $MODSRC \
  '-Dverity.selftest=$(realpath "$VERITY_LAB_GUEST")' '-Dverity.selftest.world=join:25599' \
  net.neoforged.devlaunch.Main '@$R/build/moddev/clientRunProgramArgs.txt' --username Guest --width 1280 --height 720 > lab.out 2>&1
LAUNCH
    chmod +x "$G/lab-launch.sh"; rm -f "$G/lab.pid"
    sleep "${VERITY_LAB_GUEST_DELAY:-16}"
    hyprctl eval "hl.exec_cmd('$G/lab-launch.sh', { workspace = '$WS silent', no_initial_focus = true })" >/dev/null
    for _ in $(seq 50); do [ -f "$G/lab.pid" ] && break; sleep 0.2; done
    GPID=$(cat "$G/lab.pid")
fi
START=$(date +%s)
REC=""
if [ -n "${VERITY_LAB_RECORD:-}" ]; then
    # make sure the game's audio stream really sits on the silent sink, whatever backend OpenAL picked
    for _ in $(seq 8); do
        IDX=$(pactl -f json list sink-inputs 2>/dev/null | python3 -I -c "import sys,json; print(next((str(i['index']) for i in json.load(sys.stdin) if str(i.get('properties',{}).get('application.process.id'))=='$PID'), ''))" 2>/dev/null || true)
        if [ -n "$IDX" ]; then pactl move-sink-input "$IDX" verity_lab && echo "lab: audio stream $IDX -> verity_lab"; break; fi
        sleep 0.5
    done
    sleep "${VERITY_LAB_RECORD_DELAY:-14}"         # skip the loading screens
    rm -f "$R/run/$VERITY_LAB_RECORD.mp4"
    wf-recorder -o $MON --audio=verity_lab.monitor -f "$R/run/$VERITY_LAB_RECORD.mp4" >/dev/null 2>&1 &
    REC=$!
fi
while kill -0 "$PID" 2>/dev/null; do
    if [ $(( $(date +%s) - START )) -gt "$TIMEOUT" ]; then echo "lab: timeout, killing $PID"; kill "$PID"; sleep 2; kill -9 "$PID" 2>/dev/null || true; break; fi
    sleep 1
done
if [ -n "$GPID" ] && kill -0 "$GPID" 2>/dev/null; then sleep 3; kill "$GPID" 2>/dev/null || true; fi
if [ -n "$REC" ]; then kill -INT "$REC" 2>/dev/null || true; wait "$REC" 2>/dev/null || true; fi
echo "lab: finished in $(( $(date +%s) - START ))s"
grep -E 'selftest|\[verity|ERROR|Exception|Caused by' "$R/run/logs/latest.log" | grep -vE 'Narrator|realms|authlib|Failed to retrieve profile|UUID of added entity' | tail -${LAB_LOG_LINES:-25} || true
