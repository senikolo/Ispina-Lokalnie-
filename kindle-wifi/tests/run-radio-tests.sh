#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
player="$PWD/assets/ilradio_k3.koplugin/player.sh"
sh -n "$player"
fixture="$(mktemp -d)"
trap 'sh "$player" stop >/dev/null; if [ -n "${server:-}" ]; then kill "$server" 2>/dev/null || true; fi; rm -rf "$fixture"' EXIT
export TMPDIR="$fixture"
export PCM_OUTPUT="$fixture/output.pcm"
mkdir -p "$fixture/bin"
export PATH="$fixture/bin:$PATH"
cat > "$fixture/bin/gst-launch-0.10" <<'PLAYER'
#!/bin/sh
case " $* " in
    *' fdsrc '*)
        ffmpeg -nostdin -v error -f mp3 -i pipe:0 -f s16le -y "$PCM_OUTPUT"
        ;;
    *) exit 1;;
esac
PLAYER
cat > "$fixture/bin/gst-inspect-0.10" <<'INSPECT'
#!/bin/sh
exit 1
INSPECT
chmod +x "$fixture/bin/"*
ffmpeg -nostdin -v error -f lavfi -i 'sine=frequency=1000:duration=1' -codec:a libmp3lame "$fixture/sample.mp3"
python3 - "$fixture" <<'PY' &
import functools, http.server, pathlib, sys
fixture = pathlib.Path(sys.argv[1])
handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(fixture))
with http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler) as server:
    (fixture / 'port').write_text(str(server.server_port))
    server.serve_forever()
PY
server=$!
for delay in {1..50}; do
    if [ -s "$fixture/port" ]; then break; fi
    sleep .1
done
port="$(cat "$fixture/port")"
[ "$(sh "$player" probe gst-legacy)" = gst-legacy ]
[ "$(sh "$player" start "http://127.0.0.1:$port/sample.mp3" gst-legacy 65)" = 'starting:gst-legacy' ]
for delay in {1..50}; do
    if [ -s "$PCM_OUTPUT" ] && [ "$(sh "$player" status)" = stopped ]; then break; fi
    sleep .1
done
[ -s "$PCM_OUTPUT" ] && [ "$(sh "$player" status)" = stopped ]
[ "$(sh "$player" stop)" = stopped ]
echo 'PASS: local HTTP MP3 through the legacy player pipeline, decoded PCM verified via FFmpeg'
cat > "$fixture/bin/mplayer" <<'PLAYER'
#!/bin/sh
printf '%s\n' 'AUDIO: 22050 Hz, 2 ch, s16le, 32.0 kbit/3%' 'AO: [alsa]' 'Starting playback...'
for i in $(seq 1 80); do printf "StreamTitle='Fixture %s'\n" "$i"; done
exec sleep 20
PLAYER
chmod +x "$fixture/bin/mplayer"
[ "$(sh "$player" start "http://127.0.0.1:$port/sample.mp3" mplayer 65)" = 'starting:mplayer' ]
for delay in {1..30}; do
    if [ "$(sh "$player" title)" = "Fixture 80" ]; then break; fi
    sleep .1
done
[ "$(sh "$player" title)" = "Fixture 80" ]
[ "$(sh "$player" status)" = active ]
[ "$(sh "$player" evidence)" = decoded ]
sh "$player" facts | grep -Fx 'pcm=22050 2'
python3 - "$player" <<'PY'
import os
import pathlib
import subprocess
import sys

root = pathlib.Path(os.environ["TMPDIR"]) / "ilradio-k3"
fifo = next(root.glob("control-*"))
fd = os.open(fifo, os.O_RDWR | os.O_NONBLOCK)
try:
    while True:
        os.write(fd, b"x" * 512)
except BlockingIOError:
    pass
try:
    result = subprocess.run(["sh", sys.argv[1], "volume", "70"], capture_output=True, timeout=4)
    assert result.stdout.strip() != b"adjusted"
finally:
    os.close(fd)
print("PASS: full MPlayer control FIFO cannot block the user interface")
PY
[ "$(sh "$player" stop)" = stopped ]
echo 'PASS: playback evidence is visible while the decoder is running'
