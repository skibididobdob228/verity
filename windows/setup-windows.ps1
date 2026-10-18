# Верити: голос и вопросы голосом для Windows.
# Скачивает Piper (голос, которым Верити отвечает), три русских голоса, whisper.cpp и модель распознавания речи
# и раскладывает всё в <папка игры>\verity\. Запуск: двойной клик по setup-windows.bat рядом с этим файлом.
#   powershell -ExecutionPolicy Bypass -File setup-windows.ps1 [-GameDir "C:\путь\к\minecraft"]
param([string]$GameDir = "")

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"          # без полоски прогресса Invoke-WebRequest качает в разы быстрее
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$PiperZip   = "https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_windows_amd64.zip"
$WhisperZip = "https://github.com/ggml-org/whisper.cpp/releases/download/v1.7.6/whisper-bin-x64.zip"
$ModelUrl   = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
$VoiceBase  = "https://huggingface.co/rhasspy/piper-voices/resolve/main/ru/ru_RU"
$Voices     = @("dmitri", "ruslan", "irina")     # Верити; Twixxel и оператор; служба поддержки

function Say($text, $color = "Gray") { Write-Host $text -ForegroundColor $color }

function Download($url, $dest) {
    if ((Test-Path $dest) -and ((Get-Item $dest).Length -gt 0)) { Say "  уже есть: $(Split-Path $dest -Leaf)"; return }
    Say "  качаю $(Split-Path $dest -Leaf)..."
    $tmp = "$dest.part"
    Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing
    Move-Item -Force $tmp $dest
}

# ---------------------------------------------------------------- где игра
function Find-GameDirs {
    $found = @()
    $roots = @("$env:APPDATA\PrismLauncher\instances", "$env:APPDATA\.minecraft\versions", "$env:APPDATA\MultiMC\instances",
               "$env:USERPROFILE\scoop\persist\prismlauncher\instances", "$env:APPDATA\ATLauncher\instances", "$env:APPDATA\.tlauncher",
               "$env:USERPROFILE\curseforge\minecraft\Instances", "$env:APPDATA\ModrinthApp\profiles", "$env:APPDATA\com.modrinth.theseus\profiles",
               "$env:APPDATA\gdlauncher_carbon\data\instances", "$env:APPDATA\.minecraft")
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem -Path $root -Recurse -Depth 3 -Filter "verity-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Directory.Name -eq "mods" } |
            ForEach-Object { $found += $_.Directory.Parent.FullName }
    }
    $official = "$env:APPDATA\.minecraft"
    if (Test-Path "$official\mods\verity-*.jar") { $found += $official }
    return $found | Select-Object -Unique
}

if ($GameDir -eq "") {
    $dirs = @(Find-GameDirs)
    if ($dirs.Count -eq 1) {
        $GameDir = $dirs[0]
    } elseif ($dirs.Count -gt 1) {
        Say "Нашёл несколько папок с модом Верити:" "Yellow"
        for ($i = 0; $i -lt $dirs.Count; $i++) { Say "  [$($i + 1)] $($dirs[$i])" "White" }
        $n = Read-Host "Какую взять (номер)"
        $GameDir = $dirs[[int]$n - 1]
    } else {
        Say "Не нашёл папку игры с модом Верити (verity-*.jar в папке mods)." "Yellow"
        Say "Это папка, в которой лежит папка mods с модом (у обычного лаунчера: %APPDATA%\.minecraft). Скопируй её путь." "Yellow"
        $GameDir = (Read-Host "Вставь путь к папке игры").Trim('"', ' ')
    }
}
if (-not (Test-Path $GameDir)) { Say "Нет такой папки: $GameDir" "Red"; Read-Host "Enter, чтобы закрыть"; exit 1 }
if (-not (Test-Path "$GameDir\mods\verity-*.jar")) { Say "Внимание: в $GameDir\mods нет мода Верити. Положи туда verity-*.jar." "Yellow" }

$V = Join-Path $GameDir "verity"
$Tmp = Join-Path $env:TEMP "verity-setup"
New-Item -ItemType Directory -Force -Path $V, $Tmp | Out-Null
Say "Ставлю в $V" "Cyan"

try {
    # ------------------------------------------------------------ Piper: его голос
    Say "1/3 Piper (голос Верити)" "Cyan"
    if (-not (Test-Path "$V\piper\piper.exe")) {
        Download $PiperZip "$Tmp\piper.zip"
        Expand-Archive -Force -Path "$Tmp\piper.zip" -DestinationPath $V      # в архиве уже есть папка piper
    } else { Say "  уже есть: piper.exe" }

    Say "2/3 Голоса (по ~63 МБ)" "Cyan"
    New-Item -ItemType Directory -Force -Path "$V\piper\voices" | Out-Null
    foreach ($name in $Voices) {
        $file = "ru_RU-$name-medium.onnx"
        Download "$VoiceBase/$name/medium/$file" "$V\piper\voices\$file"
        Download "$VoiceBase/$name/medium/$file.json" "$V\piper\voices\$file.json"
    }

    # ------------------------------------------------------------ Whisper: вопросы голосом по V
    Say "3/3 Распознавание речи (whisper.cpp и модель 190 МБ)" "Cyan"
    New-Item -ItemType Directory -Force -Path "$V\whisper\models" | Out-Null
    if (-not (Test-Path "$V\whisper\whisper-cli.exe")) {
        Download $WhisperZip "$Tmp\whisper.zip"
        $out = "$Tmp\whisper"
        if (Test-Path $out) { Remove-Item -Recurse -Force $out }
        Expand-Archive -Force -Path "$Tmp\whisper.zip" -DestinationPath $out
        Copy-Item -Force -Path "$out\Release\*" -Destination "$V\whisper\"     # exe вместе с его dll
    } else { Say "  уже есть: whisper-cli.exe" }
    Download $ModelUrl "$V\whisper\models\ggml-small-q5_1.bin"
} catch {
    Say "Не получилось: $($_.Exception.Message)" "Red"
    Say "Проверь интернет и запусти ещё раз: уже скачанное повторно качаться не будет." "Yellow"
    Read-Host "Enter, чтобы закрыть"
    exit 1
} finally {
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------- проверка
$ok = (Test-Path "$V\piper\piper.exe") -and (Test-Path "$V\piper\voices\ru_RU-dmitri-medium.onnx") -and
      (Test-Path "$V\whisper\whisper-cli.exe") -and (Test-Path "$V\whisper\models\ggml-small-q5_1.bin")
if (-not (Test-Path "$env:WINDIR\System32\vcruntime140.dll")) {
    Say "Похоже, не хватает Microsoft Visual C++ Redistributable (x64). Без него Piper и Whisper не запустятся." "Yellow"
    Say "Скачай с сайта Microsoft: https://aka.ms/vs/17/release/vc_redist.x64.exe" "Yellow"
}
if ($ok) {
    Say ""
    Say "Готово! Перезапусти игру." "Green"
    Say "Зажми V и говори, отпусти — Верити ответит. Микрофон работает только пока V зажата, запись не сохраняется." "Green"
    Say "Если пишет «Микрофон недоступен»: Параметры -> Конфиденциальность -> Микрофон -> разреши классическим приложениям." "Gray"
} else {
    Say "Что-то не докачалось. Запусти скрипт ещё раз." "Red"
}
Read-Host "Enter, чтобы закрыть"
