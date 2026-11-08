#!/bin/bash
# Installs the simulator build with a spoken sample audiobook and the tiny.en model, runs the app's tour
# (READALONG_TOUR, see Tour.kt), screenshots each step the app announces, and records the screen throughout.
# Small copies of the screenshots and the recording go in the log between TOUR-FILE markers.
set -uo pipefail
app="$1"; out="$2"
mkdir -p "$out/tour"
bundle=io.github.lukeherc-cpu.readalong

# A minute of narration from macOS's own voice, as an M4B-style AAC file.
work=$(mktemp -d)
cat > "$work/text.txt" <<'TEXT'
Chapter One. The lamp turned once every eleven seconds, and in the pause between its sweeps the keeper could hear the sea breathing against the rocks below.
She had counted those seconds for nineteen winters. Tonight, though, the count felt different, as if the light itself were waiting for something.
Down on the shingle a figure was walking, slowly, with a lantern held low. Nobody came to the point after dark.
The keeper set down her pen, closed the logbook on the half-written line, and reached for her coat.
Outside, the wind had dropped to almost nothing. The lantern stopped at the foot of the steps and lifted, once, as if in greeting.
TEXT
say -v Samantha -r 170 -f "$work/text.txt" -o "$work/book.aiff" || say -f "$work/text.txt" -o "$work/book.aiff"
afconvert "$work/book.aiff" -f m4af -d aac -b 64000 "$work/The Lighthouse Keeper.m4a"
curl -sSL -o "$work/ggml-tiny.en.bin" https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin

device=$(xcrun simctl list devices available | grep -m1 -oE 'iPhone [0-9]+ \(([A-F0-9-]+)\)' | grep -oE '[A-F0-9-]{36}')
echo "Simulator: $(xcrun simctl list devices | grep "$device")"
xcrun simctl boot "$device"
xcrun simctl bootstatus "$device" -b > /dev/null
xcrun simctl install "$device" "$app"
data=$(xcrun simctl get_app_container "$device" "$bundle" data)
mkdir -p "$data/Documents/Audiobooks/The Lighthouse Keeper" "$data/Library/Application Support/ReadAlong/models"
cp "$work/The Lighthouse Keeper.m4a" "$data/Documents/Audiobooks/The Lighthouse Keeper/"
cp "$work/ggml-tiny.en.bin" "$data/Library/Application Support/ReadAlong/models/"

xcrun simctl io "$device" recordVideo --codec=h264 --force "$out/tour/tour.mp4" > /dev/null 2>&1 &
recorder=$!
sleep 2
SIMCTL_CHILD_READALONG_TOUR=1 xcrun simctl launch --console-pty "$device" "$bundle" > "$out/tour/console.log" 2>&1 &

taken=""
for second in $(seq 1 600); do
  sleep 1
  for name in $(grep -oE 'TOUR-SHOT [^[:space:]]+' "$out/tour/console.log" | awk '{print $2}'); do
    case " $taken " in *" $name "*) continue ;; esac
    xcrun simctl io "$device" screenshot "$out/tour/$name.png" > /dev/null 2>&1 && echo "Screenshot $name after ${second}s"
    taken="$taken $name"
  done
  grep -q TOUR-DONE "$out/tour/console.log" && break
  if ! xcrun simctl spawn "$device" launchctl list | grep -q "$bundle"; then echo "ReadAlong stopped running after ${second}s"; break; fi
done
sleep 2
kill -INT $recorder; wait $recorder 2> /dev/null

echo "---- app output ----"; grep -E 'TOUR|ReadAlong|rror|xception' "$out/tour/console.log" | tail -80
# Crash reports, if any.
ls ~/Library/Logs/DiagnosticReports 2> /dev/null | grep -i readalong && for f in ~/Library/Logs/DiagnosticReports/*ReadAlong*; do head -80 "$f"; done

for shot in "$out"/tour/*.png; do
  [ -f "$shot" ] || continue
  small="${shot%.png}-small.jpg"
  sips -Z 640 -s format jpeg -s formatOptions 55 "$shot" --out "$small" > /dev/null
  echo "TOUR-FILE-BEGIN $(basename "$small")"; base64 -i "$small"; echo "TOUR-FILE-END"
done
if [ -f "$out/tour/tour.mp4" ]; then
  avconvert --preset Preset640x480 --source "$out/tour/tour.mp4" --output "$out/tour/tour-small.mp4" > /dev/null 2>&1 || cp "$out/tour/tour.mp4" "$out/tour/tour-small.mp4"
  ls -la "$out/tour/tour.mp4" "$out/tour/tour-small.mp4"
  size=$(stat -f %z "$out/tour/tour-small.mp4")
  if [ "$size" -lt 12000000 ]; then echo "TOUR-FILE-BEGIN tour-small.mp4"; base64 -i "$out/tour/tour-small.mp4"; echo "TOUR-FILE-END"; fi
fi
grep -q TOUR-DONE "$out/tour/console.log" && ! grep -q "TOUR FAILED" "$out/tour/console.log"
