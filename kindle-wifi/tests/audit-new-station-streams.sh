#!/usr/bin/env bash
set -uo pipefail
# This is an informational network audit. It must not block the regular app build.
# Avoid contacting any third-party transcoding proxy; verify publisher-origin streams.
audit() {
    local name="$1" url="$2"
    echo "=== CANDIDATE: $name ==="
    echo "URL: $url"
    local output
    output=$(timeout 17s ffprobe -hide_banner -loglevel error \
        -rw_timeout 6500000 -analyzeduration 1800000 -probesize 131072 \
        -show_entries stream=codec_name,bit_rate,sample_rate:format=format_name,bit_rate \
        -of default=noprint_wrappers=1 "$url" 2>&1)
    local code=$?
    echo "ffprobe_exit=$code"
    printf '%s\n' "$output" | head -c 1800
    echo
}
audit "Jedynka HLS 48 candidate" "https://stream11.polskieradio.pl/pr1/pr1.sdp/playlist.m3u8"
audit "Jedynka MP3 publisher control (currently 192)" "http://mp3.polskieradio.pl:8900/"
audit "Chillizet MP3 128" "https://ch.cdn.eurozet.pl/chi-net.mp3"
audit "Chillout Radio FluxFM MP3 128" "https://streams.fluxfm.de/chillout/mp3-128/streams.fluxfm.de/"
audit "RMF Chillout AAC 64 existing" "http://31.192.216.8/CHILLOUT"
audit "OpenFM Chillout redirect candidate" "https://getradio.reconv.pl/openfm?s=chillout"
