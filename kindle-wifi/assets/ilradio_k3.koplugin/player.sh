#!/bin/sh
# The firmware or an installed player provides decoding and audio output.
umask 077
LC_ALL=C
export LC_ALL
R="${TMPDIR:-/tmp}/ilradio-k3"
PID="$R/pid"
TOKEN="$R/token"
PAUSED="$R/paused"
TIMER_PID="$R/timer.pid"
TIMER_TOKEN="$R/timer.token"
TIMER_DEADLINE="$R/timer.deadline"
LOG="$R/player.log"
case "${ILRADIO_K3_SESSION:-}" in
    *[!0-9-]*|"") last_token=$(cat "$R/last" 2>/dev/null);;
    *) last_token=$ILRADIO_K3_SESSION;;
esac
case "$last_token" in *[!0-9-]*|"") :;; *) LOG="$R/log-$last_token.txt";; esac
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
[ ! -L "$R" ] && mkdir -p "$R" 2>/dev/null || exit 1

has() { command -v "$1" >/dev/null 2>&1; }

usable_curl() {
    has curl && curl --proto '=http,https' --proto-redir '=http,https' --version >/dev/null 2>&1
}

gst_legacy() {
    if has gst-launch-0.10; then echo gst-launch-0.10
    elif has gst-launch; then echo gst-launch
    fi
}

mplayer_path() {
    if [ -x /mnt/us/koreader/ilradio-bin/mplayer ]; then
        echo /mnt/us/koreader/ilradio-bin/mplayer
    elif has mplayer; then
        command -v mplayer
    elif [ -x /mnt/us/mplayer/mplayer ]; then
        echo /mnt/us/mplayer/mplayer
    elif [ -x /mnt/us/system/mplayer ]; then
        echo /mnt/us/system/mplayer
    fi
}

backend() {
    case "$1" in
        gst-legacy|gst-mixer) if [ -n "$(gst_legacy)" ]; then echo "$1"; fi;;
        gst-modern) if has gst-launch-1.0; then echo "$1"; fi;;
        mplayer) if [ -n "$(mplayer_path)" ]; then echo mplayer; fi;;
        mpg123|mpv) if has "$1"; then echo "$1"; fi;;
        auto|"")
            if [ -n "$(gst_legacy)" ]; then echo gst-legacy
            elif has gst-launch-1.0; then echo gst-modern
            elif [ -n "$(mplayer_path)" ]; then echo mplayer
            elif has mpg123; then echo mpg123
            elif has mpv; then echo mpv
            fi;;
    esac
}

belongs() {
    case "$1" in ''|*[!0-9]*) return 1;; esac
    [ -r "/proc/$1/environ" ] || return 1
    tr '\000' '\n' 2>/dev/null <"/proc/$1/environ" | awk -v key="$2" '$0 == key {found=1; exit} END {exit !found}'
}

is_ours() {
    [ -r "$TOKEN" ] || return 1
    p=$(cat "$PID" 2>/dev/null)
    token=$(cat "$TOKEN" 2>/dev/null)
    case "$token" in ''|*[!0-9-]*) return 1;; esac
    belongs "$p" "ILRADIO_K3_SESSION=$token" && return 0
    child=$(cat "$R/worker-$token" 2>/dev/null)
    belongs "$child" "ILRADIO_K3_SESSION=$token"
}

mark_children() {
    printf '%s\n' "$@" >> "$R/children-${ILRADIO_K3_SESSION:-$ILRADIO_K3_PROBE}"
}

timer_cancel() {
    timer_pid=$(cat "$TIMER_PID" 2>/dev/null)
    timer_token=$(cat "$TIMER_TOKEN" 2>/dev/null)
    case "$timer_token" in
        ''|*[!0-9-]*) :;;
        *) if belongs "$timer_pid" "ILRADIO_K3_TIMER=$timer_token"; then
               kill -TERM "$timer_pid" 2>/dev/null || :
           fi;;
    esac
    rm -f "$TIMER_PID" "$TIMER_TOKEN" "$TIMER_DEADLINE"
}
short_wait() {
    if has usleep; then usleep 100000
    elif ! sleep 0.1 2>/dev/null; then sleep 1; fi
}

process_group() {
    IFS= read -r stat < "/proc/$1/stat" || return
    set -- ${stat##*) }
    printf '%s\n' "$3"
}

launch() {
    if has setsid; then exec setsid sh "$0" "$@"; fi
    exec sh "$0" "$@"
}

session_members() {
    # The session token remains valid if the launcher exits before a child.
    for member in $2 $(cat "$R/children-$3" 2>/dev/null); do
        if belongs "$member" "$1"; then printf '%s\n' "$member"; fi
    done
}

all_members() {
    for environ in /proc/[0-9]*/environ; do
        member=${environ%/environ}
        member=${member#/proc/}
        if belongs "$member" "$1"; then printf '%s\n' "$member"; fi
    done
}

stop_family() {
    case "$3" in ''|*[!0-9-]*) return 0;; esac
    key="$1=$3"
    members=$(session_members "$key" "$2" "$3")
    if [ -z "$members" ]; then members=$(all_members "$key"); fi
    if [ -z "$members" ]; then return 0; fi
    group=
    for member in $members; do
        if [ "$(process_group "$member" 2>/dev/null)" = "$2" ]; then group="$2"; break; fi
    done
    # Continue paused children so they can handle TERM. A hung decoder must
    # still release the audio device before the next session starts.
    if [ -n "$group" ]; then
        kill -CONT "-$group" 2>/dev/null || :
        kill -TERM "-$group" 2>/dev/null || :
    fi
    kill -CONT $members 2>/dev/null || :
    kill -TERM $members 2>/dev/null || :
    members=$(all_members "$key")
    if [ -n "$members" ]; then
        sleep 1
        members=$(all_members "$key")
        if [ -n "$members" ]; then
            kill -KILL $members 2>/dev/null || :
            for attempt in 1 2 3; do
                members=$(all_members "$key")
                [ -n "$members" ] || break
            done
        fi
    fi
    [ -z "$members" ]
}

stop_audio() {
    audio_pid=$(cat "$PID" 2>/dev/null)
    audio_token=$(cat "$TOKEN" 2>/dev/null)
    stop_family ILRADIO_K3_SESSION "$audio_pid" "$audio_token" || return 1
    case "$audio_token" in
        ''|*[!0-9-]*) :;;
        *) rm -f "$R/control-$audio_token" "$R/stream-$audio_token" "$R/log-$audio_token" \
            "$R/worker-$audio_token" "$R/children-$audio_token" "$R/engine-$audio_token";;
    esac
    rm -f "$PID" "$TOKEN" "$PAUSED"
}

gst_has() {
    if has gst-inspect-0.10; then gst-inspect-0.10 "$1" >/dev/null 2>&1
    elif has gst-inspect; then gst-inspect "$1" >/dev/null 2>&1
    else return 1
    fi
}

stream_legacy() {
    gst=$1
    uri=$2
    volume=$3
    kind=$4
    # Prefer a native HTTP handler when available. On older firmware, feed the
    # decoder via fdsrc. Only the downloader touches the network in this mode.
    if gst_has souphttpsrc || gst_has gnomevfssrc || gst_has giosrc; then
        if [ "$kind" = gst-mixer ]; then
            exec "$gst" -m uridecodebin "uri=$uri" ! audioconvert ! audioresample ! mixersink
        fi
        exec "$gst" -m playbin "uri=$uri" "volume=$volume"
    fi
    if usable_curl; then
        set -- curl -fLsS --max-redirs 5 --connect-timeout 8 --speed-time 10 --speed-limit 128 --proto '=http,https' --proto-redir '=http,https' "$uri"
    elif has wget; then
        set -- wget -T 12 -O - "$uri"
    else
        echo 'Brak programu do otwarcia tego adresu radia.'
        return 2
    fi
    sink=alsasink
    if gst_has mixersink; then sink=mixersink; fi
    fifo="$R/stream-$ILRADIO_K3_SESSION"
    mkfifo "$fifo" || return 2
    downloader=
    player=
    cleanup() {
        if [ -n "$downloader" ]; then kill "$downloader" 2>/dev/null || :; fi
        if [ -n "$player" ]; then kill "$player" 2>/dev/null || :; fi
        rm -f "$fifo"
    }
    trap 'cleanup; exit 0' TERM INT HUP
    trap cleanup EXIT
    "$@" > "$fifo" &
    downloader=$!
    "$gst" -m fdsrc ! decodebin ! audioconvert ! audioresample ! volume "volume=$volume" ! "$sink" < "$fifo" &
    player=$!
    mark_children "$downloader" "$player"
    wait "$player"
}

play_backend() {
    engine=$1
    uri=$2
    pct=$3
    local_path=$4
    offset=$5
    cache=$6
    codec=$7
    boost=${8:-110}
    case "$boost" in 110|140|170) :;; *) boost=110;; esac
    volume=$(awk -v percent="$pct" 'BEGIN {printf "%.2f", percent / 100}')
    case "$engine" in
        gst-legacy|gst-mixer)
            gst=$(gst_legacy)
            if [ -z "$local_path" ]; then stream_legacy "$gst" "$uri" "$volume" "$engine"; return; fi
            if [ "$engine" = gst-mixer ]; then
                exec "$gst" -m uridecodebin "uri=$uri" ! audioconvert ! audioresample ! mixersink
            fi
            exec "$gst" -m playbin "uri=$uri" "volume=$volume";;
        gst-modern)
            if [ -n "$local_path" ]; then exec gst-launch-1.0 -m playbin "uri=$uri" "volume=$volume"; fi
            exec gst-launch-1.0 -m playbin "uri=$uri" "volume=$volume" "buffer-size=$((cache * 1024))";;
        mplayer)
            mplayer=$(mplayer_path)
            [ -n "$mplayer" ] || return 2
            export MPLAYER_HOME="$R"
            fifo="$R/control-$ILRADIO_K3_SESSION"
            mkfifo "$fifo" || exit 2
            set -- -noconfig all -ao alsa -novideo -quiet -softvol -softvol-max "$boost" -slave -input "file=$fifo" -volume "$pct"
            if [ -n "$local_path" ]; then
                exec "$mplayer" "$@" -ss "$offset" "$local_path"
            fi
            # Honour the configured buffer for HTTP MP3 to reduce underruns.
            # The older K3 ARM port may hang when caching unseekable AAC,
            # so AAC retains uncached playback for compatibility.
            if [ "$codec" = MP3 ]; then
                set -- "$@" -cache "$cache"
            else
                set -- "$@" -nocache
            fi
            if [ "$codec" = AAC ]; then set -- "$@" -demuxer aac; fi
            case "$uri" in
                https://*)
                    if usable_curl; then transport "$uri" "$mplayer" "$@" -
                    elif has wget; then transport_wget "$uri" "$mplayer" "$@" -
                    else echo 'ILRADIO_ERROR missing-transport'; return 2; fi;;
                *) exec "$mplayer" "$@" "$uri";;
            esac;;
        mpg123) exec mpg123 -f "$((32768 * pct / 100))" -- "${local_path:-$uri}";;
        mpv) exec mpv --no-video --no-input-terminal --no-config --msg-level=all=warn \
            "--term-playing-msg=ILRADIO_OUTPUT" "--volume=$pct" -- "${local_path:-$uri}";;
    esac
    return 2
}

curl_stream() {
    exec curl -fLsS --max-redirs 5 --connect-timeout 6 --speed-time 14 --speed-limit 128 \
        --proto '=http,https' --proto-redir '=http,https' "$@"
}

transport() {
    uri=$1
    shift
    if ! has curl; then echo 'ILRADIO_ERROR missing-transport'; return 2; fi
    fifo="$R/stream-$ILRADIO_K3_SESSION"
    mkfifo "$fifo" || return 2
    curl_stream "$uri" > "$fifo" &
    downloader=$!
    "$@" < "$fifo" &
    decoder=$!
    mark_children "$downloader" "$decoder"
    trap 'kill "$downloader" "$decoder" 2>/dev/null; rm -f "$fifo"; exit 0' TERM INT HUP
    wait "$decoder"
    result=$?
    kill "$downloader" 2>/dev/null || :
    rm -f "$fifo"
    return "$result"
}

transport_wget() {
    uri=$1
    shift
    fifo="$R/stream-$ILRADIO_K3_SESSION"
    mkfifo "$fifo" || return 2
    wget -T 12 -O - "$uri" > "$fifo" &
    downloader=$!
    "$@" < "$fifo" &
    decoder=$!
    mark_children "$downloader" "$decoder"
    trap 'kill "$downloader" "$decoder" 2>/dev/null; rm -f "$fifo"; exit 0' TERM INT HUP
    wait "$decoder"
    result=$?
    kill "$downloader" 2>/dev/null || :
    rm -f "$fifo"
    return "$result"
}

run_logged() {
    fifo="$R/log-$ILRADIO_K3_SESSION"
    mkfifo "$fifo" || exit 2
    # Shell read handles evidence as each line arrives, even when the decoder keeps its pipe open.
    (
        count=0
        while IFS= read -r line; do
            # Keep one copy of each startup fact when the rolling log later fills with titles.
            key=
            case "$line" in
                ANS_TIME_POSITION=*) printf '%s\n' "${line#*=}" > "$R/position-$ILRADIO_K3_SESSION";;
                AUDIO:*) if [ -z "$pcm_seen" ]; then key=pcm; pcm_seen=1; fi;;
                AO:*|*audio-codec*|MPEG*"L III"*) if [ -z "$audio_seen" ]; then key=audio; audio_seen=1; fi;;
                "Selected audio codec:"*) if [ -z "$decoder_seen" ]; then key=decoder; decoder_seen=1; fi;;
                *"Starting playback"*|*"new-state=(GstState)playing"*|*ILRADIO_OUTPUT*)
                    if [ -z "$play_seen" ]; then key=play; play_seen=1; fi;;
            esac
            if [ -n "$key" ]; then printf '%.384s\n' "$line" >> "$LOG.audio"; fi
            case "$line" in
                *AO:*|*AUDIO:*|*ILRADIO_*|*"Starting playback"*|*ANS_TIME_POSITION*|*"Selected audio codec"*|*audio-codec*|*StreamTitle*|*ICY-META*|*title=*|*playing*|*PLAYING*|*[Ee]rror*|*[Ff]ail*|*[Cc]"ould not"*|*[Cc]annot*|*[Tt]"imed out"*|*[Bb]uffer*|*[Cc]ertificate*|*[Dd]ecoder*|*SSL*|*TLS*|*Exiting*)
                    printf '%.384s\n' "$line" >> "$LOG"
                    count=$((count + 1))
                    if [ "$count" -ge 36 ]; then
                        tail -n 18 "$LOG" > "$LOG.trim" && mv "$LOG.trim" "$LOG"
                        count=18
                    fi;;
            esac
        done
    ) < "$fifo" &
    logger=$!
    sh "$0" backend-run "$@" > "$fifo" 2>&1 &
    worker=$!
    mark_children "$logger" "$worker"
    printf '%s\n' "$worker" > "$R/worker-$ILRADIO_K3_SESSION"
    trap 'kill -CONT "$worker" 2>/dev/null; kill -TERM "$worker" 2>/dev/null; kill "$logger" 2>/dev/null; wait "$worker" 2>/dev/null; wait "$logger" 2>/dev/null; rm -f "$fifo" "$R/worker-$ILRADIO_K3_SESSION"; exit 0' TERM INT HUP
    : > "$R/ready-$ILRADIO_K3_SESSION"
    wait "$worker"
    result=$?
    wait "$logger"
    # Save the decoder exit status for radio as well as local MP3.
    # Otherwise a stream disconnect appears as an unexplained or misleading URL error.
    printf '%s\n' "$result" > "$R/end-$ILRADIO_K3_SESSION"
    rm -f "$fifo" "$R/worker-$ILRADIO_K3_SESSION"
}

probe_stop() {
    probe_pid=$(cat "$R/probe.pid" 2>/dev/null)
    probe_token=$(cat "$R/probe.token" 2>/dev/null)
    stop_family ILRADIO_K3_PROBE "$probe_pid" "$probe_token" || return 1
    case "$probe_token" in
        ''|*[!0-9-]*) :;;
        *) rm -f "$R/probe-$probe_token.pipe" "$R/probe-$probe_token.raw" "$R/children-$probe_token";;
    esac
    rm -f "$R/probe.pid" "$R/probe.token"
}

probe_run() {
    prefix="$R/probe-$ILRADIO_K3_PROBE"
    mkfifo "$prefix.pipe" || exit 2
    head -c 16384 < "$prefix.pipe" > "$prefix.sample" &
    reader=$!
    # Open the FIFO before curl. The reader sees EOF even if TLS fails before a response.
    (
        exec 3> "$prefix.pipe"
        curl_stream --max-time 9 -D "$prefix.headers" -o /proc/self/fd/3 \
            -w '%{http_code}\n%{url_effective}\n' "$1"
    ) > "$prefix.meta" 2> "$prefix.error" &
    downloader=$!
    mark_children "$downloader" "$reader"
    trap 'kill "$downloader" "$reader" 2>/dev/null; rm -f "$prefix".*; exit 0' TERM INT HUP
    wait "$downloader"
    result=$?
    wait "$reader" 2>/dev/null || :
    rm -f "$prefix.pipe"
    printf '%s\n' "$result" > "$prefix.done"
}

probe_run_wget() {
    prefix="$R/probe-$ILRADIO_K3_PROBE"
    mkfifo "$prefix.pipe" || exit 2
    rm -f "$prefix.sample" "$prefix.meta" "$prefix.error" "$prefix.done"
    wget_sample() {
        wget -T 9 -O - "$@" > "$prefix.pipe" 2> "$prefix.error" &
        downloader=$!
        head -c 16384 < "$prefix.pipe" > "$prefix.sample" &
        reader=$!
        mark_children "$downloader" "$reader"
        trap 'kill "$downloader" "$reader" 2>/dev/null; rm -f "$prefix".*; exit 0' TERM INT HUP
        wait "$reader" 2>/dev/null || :
        kill "$downloader" 2>/dev/null || :
        wait "$downloader" 2>/dev/null || :
    }
    wget_sample -S "$1"
    if [ ! -s "$prefix.sample" ] && grep -Eiq 'unrecognized option|invalid option|illegal option' "$prefix.error"; then
        wget_sample "$1"
    fi
    rm -f "$prefix.pipe"
    # Both BusyBox and GNU wget print response headers on stderr with -S.
    # Keep the last response, including ICY fields. A bounded sample can
    # close the pipe before wget exits successfully.
    awk '
        /^[ \t]*(HTTP\/|ICY[ \t])/ { block=$0 "\n"; active=1; next }
        active && /^[ \t]+[A-Za-z0-9_-]+:/ { block=block $0 "\n"; next }
        { active=0 }
        END { if (block != "") printf "%s\n", block }
    ' "$prefix.error" | sed 's/^[[:blank:]]*//' > "$prefix.headers"
    code=$(awk 'NR==1 {print $2}' "$prefix.headers")
    responses=$(grep -Ec '^[[:blank:]]*(HTTP/|ICY[[:blank:]])' "$prefix.error")
    method=wget
    if [ "$responses" = 1 ] && { [ "$code" = 200 ] || [ "$code" = 206 ]; }; then method=wget-direct; fi
    if [ -s "$prefix.sample" ] && { [ "$code" = 200 ] || [ "$code" = 206 ] || [ -z "$code" ]; }; then
        printf '%s\n%s\n%s\n' "${code:-200}" "$1" "$method" > "$prefix.meta"
        echo 0 > "$prefix.done"
    else
        printf '%s\n%s\nwget\n' "${code:-000}" "$1" > "$prefix.meta"
        reason=$(stream_reason "$(tail -n 20 "$prefix.error")")
        case "$reason" in
            tls) echo 60;; transport) echo 127;; address) echo 22;; server) echo 69;;
            network) echo 7;; buffer) echo 28;; *) echo 1;;
        esac > "$prefix.done"
    fi
}

probe_run_mplayer() {
    prefix="$R/probe-$ILRADIO_K3_PROBE"
    mplayer=$(mplayer_path)
    if [ -z "$mplayer" ]; then echo 127 > "$prefix.done"; return; fi
    # Cap the sample at the reader, without waiting for a file-size signal
    # from the old firmware or for a live stream to end.
    mkfifo "$prefix.pipe" || { echo 127 > "$prefix.done"; return; }
    head -c 16384 < "$prefix.pipe" > "$prefix.sample" &
    reader=$!
    (
        export MPLAYER_HOME="$R"
        exec "$mplayer" -noconfig all -novideo -nocache -dumpstream -dumpfile "$prefix.pipe" "$1"
    ) > "$prefix.error" 2>&1 &
    downloader=$!
    (sleep 12; kill -KILL "$downloader" "$reader" 2>/dev/null || :) </dev/null >/dev/null 2>&1 &
    watchdog=$!
    mark_children "$downloader" "$reader" "$watchdog"
    trap 'kill "$downloader" "$reader" "$watchdog" 2>/dev/null; rm -f "$prefix".*; exit 0' TERM INT HUP
    wait "$reader" 2>/dev/null
    result=$?
    kill -KILL "$downloader" 2>/dev/null || :
    wait "$downloader" 2>/dev/null || :
    kill "$watchdog" 2>/dev/null || :
    wait "$watchdog" 2>/dev/null || :
    if [ -s "$prefix.sample" ]; then
        # The native reader does not expose the final HTTP status or address.
        printf '000\n%s\nmplayer\n' "$1" > "$prefix.meta"
        result=0
    else
        case "$(stream_reason "$(tail -n 20 "$prefix.error")")" in
            network) result=7;;
            address) result=22;;
            server) result=69;;
            transport) result=127;;
            *) if [ "$result" = 137 ]; then result=28; else result=1; fi;;
        esac
    fi
    rm -f "$prefix.pipe"
    printf '%s\n' "$result" > "$prefix.done"
}

stream_reason() {
    pattern="$1"
    if printf '%s' "$pattern" | grep -Eiq 'missing-transport|unrecognized option|invalid option|illegal option|not an http or ftp url|https.*not supported'; then echo transport
    elif printf '%s' "$pattern" | grep -Eiq 'certificate|SSL|TLS|curl: \((35|51|58|59|60|77|83|90|91)\)'; then echo tls
    elif printf '%s' "$pattern" | grep -Eiq 'No suitable plugins|No decoder|Cannot find codec|Cannot find audio codec|missing (a )?plug-?in|unrecognized format'; then echo codec
    elif printf '%s' "$pattern" | grep -Eiq 'HTTP/[0-9.]+ (5[0-9][0-9]|429)|5[0-9][0-9] (Internal Server Error|Bad Gateway|Service Unavailable)|429 Too Many Requests'; then echo server
    elif printf '%s' "$pattern" | grep -Eiq 'HTTP/[0-9.]+ 4[0-9][0-9]|4[0-9][0-9] (Not Found|Forbidden|Unauthorized)|curl: \(22\)'; then echo address
    elif printf '%s' "$pattern" | grep -Eiq 'Could not (connect|resolve)|Connection refused|Network is unreachable|curl: \((5|6|7)\)'; then echo network
    elif printf '%s' "$pattern" | grep -Eiq 'timeout|timed out|too slow|curl: \(28\)'; then echo buffer
    elif printf '%s' "$pattern" | grep -Eiq 'audio device|audio driver|alsa.*(error|fail)|Failed to initialize audio'; then echo output
    else echo unconfirmed; fi
}

play_evidence() {
    if [ -e "$PAUSED" ] && is_ours; then echo paused; return; fi
    details=$(tail -n 36 "$LOG" 2>/dev/null | grep -vE 'StreamTitle|ICY-META|title=')
    reason=$(stream_reason "$details")
    if [ "$reason" != unconfirmed ]; then echo "failed:$reason"; return; fi
    if ! is_ours; then
        # An audio pipeline may reach ALSA and start before its stream disconnects.
        # Preserve this fact in diagnostics; a stopped process is not a codec failure.
        result=$(cat "$R/end-$last_token" 2>/dev/null)
        if [ -s "$LOG.audio" ] && grep -Eq 'Starting playback|ILRADIO_OUTPUT|new-state=\(GstState\)playing' "$LOG.audio"; then
            case "$result" in
                0) echo failed:ended; return;;
                ''|*[!0-9]*) :;;
                *) echo failed:playback; return;;
            esac
        fi
        echo failed:unconfirmed
        return
    fi
    startup=$(cat "$LOG.audio" 2>/dev/null)
    if printf '%s' "$startup" | grep -Eq '^AO: |ILRADIO_OUTPUT|audio-codec=|^MPEG.*L III' &&
       printf '%s' "$startup" | grep -Eq 'Starting playback|ILRADIO_OUTPUT|new-state=\(GstState\)playing|^MPEG.*L III'; then
        echo decoded
    else
        echo pending
    fi
}


decode_sample() {
    engine=$1
    name=$2
    input="$HERE/$name"
    pcm="$R/diagnostic.pcm"
    errors="$R/diagnostic.log"
    [ -s "$input" ] || return 2
    rm -f "$pcm" "$errors"
    case "$engine" in
        mplayer)
            exe=$(mplayer_path)
            set -- "$exe" -noconfig all -really-quiet -novideo -ao "pcm:fast:file=$pcm" "$input";;
        gst-legacy|gst-mixer)
            exe=$(gst_legacy)
            if gst_has wavenc; then
                set -- "$exe" -q filesrc "location=$input" ! decodebin ! audioconvert ! wavenc ! filesink "location=$pcm"
            else
                set -- "$exe" -q filesrc "location=$input" ! decodebin ! audioconvert ! filesink "location=$pcm"
            fi;;
        gst-modern)
            set -- gst-launch-1.0 -q filesrc "location=$input" ! decodebin ! audioconvert ! wavenc ! filesink "location=$pcm";;
        mpg123)
            if [ "$name" != test-mp364.mp3 ]; then echo codec; return; fi
            set -- mpg123 -q -w "$pcm" "$input";;
        mpv)
            set -- mpv --no-config --no-video --ao=pcm "--ao-pcm-file=$pcm" -- "$input";;
        *) echo engine; return;;
    esac
    (ulimit -f 2048; exec "$@") > "$errors" 2>&1 &
    decoder=$!
    (sleep 12; kill "$decoder" 2>/dev/null || exit; sleep 1; kill -KILL "$decoder" 2>/dev/null || :) >/dev/null 2>&1 &
    timeout_pid=$!
    trap 'kill "$decoder" "$timeout_pid" 2>/dev/null; rm -f "$pcm" "$errors"; exit 0' TERM INT HUP
    wait "$decoder"
    result=$?
    kill "$timeout_pid" 2>/dev/null || :
    size=0
    if [ -s "$pcm" ]; then size=$(wc -c < "$pcm"); fi
    if [ "$result" = 0 ] && [ "${size:-0}" -gt 4096 ]; then
        case "$name" in
            test-real-he32.aac|test-real-hev224.aac)
                # WAV fmt header: little-endian on Kindle ARM and the host tests.
                rate=$(od -An -tu4 -j24 -N4 "$pcm" 2>/dev/null | tr -d ' ')
                channels=$(od -An -tu2 -j22 -N2 "$pcm" 2>/dev/null | tr -d ' ')
                wave=$(dd if="$pcm" bs=1 skip=8 count=4 2>/dev/null)
                if [ "$wave" = WAVE ] && [ "$rate" = 44100 ] && [ "$channels" = 2 ] && [ "$size" -ge 400000 ]; then
                    echo "pcm:$size"
                else
                    echo unconfirmed
                fi;;
            *) echo "pcm:$size";;
        esac
    else
        stream_reason "$(tail -n 12 "$errors" 2>/dev/null)"
    fi
    rm -f "$pcm" "$errors"
}


control() {
    is_ours || return 1
    fifo="$R/control-$token"
    [ -p "$fifo" ] || return 1
    [ ! -e "$PAUSED" ] || return 1
    # Opening read/write cannot block, but a full FIFO can block the write.
    (exec 3<>"$fifo" && printf '%s\n' "$1" >&3) &
    writer=$!
    (sleep 1; kill -KILL "$writer" 2>/dev/null || :) </dev/null >/dev/null 2>&1 &
    watchdog=$!
    wait "$writer"
    written=$?
    kill "$watchdog" 2>/dev/null || :
    wait "$watchdog" 2>/dev/null || :
    return "$written"
}

case "${1:-}" in
    probe) backend "${2:-auto}";;
    radio-transport)
        if usable_curl || has wget; then echo external
        elif [ -n "$(mplayer_path)" ]; then echo http-only
        fi;;
    start)
        uri="${2:-}"; wanted="${3:-auto}"; pct="${4:-70}"; local_path="${5:-}"; offset="${6:-0}"; cache="${7:-128}"; codec="${8:-}"
        case "$uri" in http://*|https://*|file://*) :;; *) echo invalid_target; exit 2;; esac
        case "$local_path" in
            "") :;;
            /mnt/us/music/*|/mnt/us/audiobooks/*|/mnt/us/koreader/audio/Muzyka/*|/mnt/us/koreader/audio/Audiobooki/*)
                case "$local_path" in *'/../'*|*'/./'*) echo invalid_local_path; exit 2;; esac
                [ -f "$local_path" ] && [ ! -L "$local_path" ] || { echo missing_file; exit 2; };;
            *) echo invalid_local_path; exit 2;;
        esac
        case "$pct" in ''|*[!0-9]*|????*) pct=70;; esac
        case "$offset" in ''|*[!0-9]*|????????*) offset=0;; esac
        [ "$pct" -le 100 ] || pct=100
        case "$cache" in 64|128|256) :;; *) cache=128;; esac
        boost=${11:-110}
        case "$boost" in 110|140|170) :;; *) boost=110;; esac
        engine=$(backend "$wanted")
        [ -n "$engine" ] || { echo no_engine; exit 3; }
        stop_audio || { echo stop_failed; exit 4; }
        # Only filenames generated by this script live in this private directory.
        rm -f "$R"/control-* "$R"/stream-* "$R"/log-* "$R"/worker-* "$R"/end-* "$R"/report-* "$R"/position-* "$R"/ready-* "$R"/local-*
        token="$$-$(date +%s)"
        printf '%s\n' "$token" > "$R/last"
        printf '%s\n' "$token" > "$TOKEN"
        printf '%s\n' "$engine" > "$R/engine-$token"
        if [ -n "${9:-}" ]; then
            if [ -z "$local_path" ]; then
                printf '%s\n' "$9" "$uri" pending > "$R/report-$token"
            else
                case "${10:-}" in
                    music|books) printf '%s\n%s' "${10}" "$local_path" > "$R/local-$token";;
                esac
            fi
        fi
        (
            export ILRADIO_K3_SESSION="$token"
            launch run "$engine" "$uri" "$pct" "$local_path" "$offset" "$cache" "$codec" "$boost"
        ) </dev/null >/dev/null 2>&1 &
        echo "$!" > "$PID"
        for attempt in 1 2 3 4 5 6 7 8 9 10; do
            [ -e "$R/ready-$token" ] && break
            short_wait
        done
        if [ ! -e "$R/ready-$token" ]; then
            stop_audio
            echo start_failed
            exit 4
        fi
        echo "starting:$engine"
        ;;
    run) shift; run_logged "$@";;
    backend-run) shift; play_backend "$@";;
    probe-start)
        case "${2:-}" in http://*|https://*) :;; *) echo address; exit 0;; esac
        probe_stop || { echo busy; exit 4; }
        rm -f "$R"/probe-*.sample "$R"/probe-*.meta "$R"/probe-*.headers "$R"/probe-*.done "$R"/probe-*.error "$R"/probe-*.raw
        token="$$-$(date +%s)"
        printf '%s\n' "$token" > "$R/probe.token"
        if usable_curl; then
            (export ILRADIO_K3_PROBE="$token"; launch probe-run "$2") </dev/null >/dev/null 2>&1 &
        elif has wget; then
            (export ILRADIO_K3_PROBE="$token"; launch probe-run-wget "$2") </dev/null >/dev/null 2>&1 &
        elif [ "${2#http://}" != "$2" ] && [ -n "$(mplayer_path)" ]; then
            (export ILRADIO_K3_PROBE="$token"; launch probe-run-mplayer "$2") </dev/null >/dev/null 2>&1 &
        else
            echo unavailable; exit 0
        fi
        echo "$!" > "$R/probe.pid"
        echo "$R/probe-$token";;
    probe-run) probe_run "$2";;
    probe-run-wget) probe_run_wget "$2";;
    probe-run-mplayer) probe_run_mplayer "$2";;
    probe-stop) probe_stop;;
    evidence) play_evidence;;
    facts)
        { cat "$LOG.audio" 2>/dev/null; tail -n 36 "$LOG" 2>/dev/null | grep '^ANS_TIME_POSITION='; } | sed -n \
            -e 's/^AUDIO: \([0-9][0-9]*\) Hz, \([0-9][0-9]*\) ch,.*/pcm=\1 \2/p' \
            -e 's/^AUDIO: .* \([0-9.]*\) kbit.*/bitrate=\1/p' \
            -e 's/^Selected audio codec: /decoder=/p' \
            -e 's/^ANS_TIME_POSITION=/position=/p';;
    health)
        if ! is_ours; then echo failed
        elif [ -s "$LOG" ] && tail -n 25 "$LOG" | grep -Eiq '(^| )ERROR(:| from)|Could not (open|connect|resolve)|No suitable plugins|Failed to (open|connect)'; then echo failed
        else echo active
        fi;;
    local-result)
        case "$last_token" in ''|*[!0-9-]*) echo failed; exit;; esac
        if is_ours; then
            case "$(play_evidence)" in
                paused) echo paused; exit;;
                decoded) echo decoded; exit;;
                pending) echo pending; exit;;
            esac
        fi
        # Read completion after checking the process. The wrapper writes the
        # exit code before it exits, including for very short files.
        if [ "$(cat "$R/end-$last_token" 2>/dev/null)" = 0 ]; then echo ended
        else echo failed; fi;;
    status)
        if is_ours; then
            if [ -e "$PAUSED" ]; then echo paused; else echo active; fi
        else
            echo stopped
        fi;;
    pause)
        # Local direct engines are children of the logging wrapper.
        if is_ours && child=$(cat "$R/worker-$token" 2>/dev/null) &&
            belongs "$child" "ILRADIO_K3_SESSION=$token" && kill -STOP "$child" 2>/dev/null; then : > "$PAUSED"; echo paused
        else echo stopped
        fi;;
    resume)
        if is_ours && child=$(cat "$R/worker-$token" 2>/dev/null) &&
            belongs "$child" "ILRADIO_K3_SESSION=$token" && kill -CONT "$child" 2>/dev/null; then rm -f "$PAUSED"; echo active
        else echo stopped
        fi;;
    timer-arm)
        case "${2:-}" in 30|60|90|120) :;; *) echo invalid_timer; exit 2;; esac
        timer_cancel
        timer_token="$-$(date +%s)"
        timer_seconds=$(($2 * 60))
        timer_deadline=$(($(date +%s) + timer_seconds))
        printf '%s\n' "$timer_token" > "$TIMER_TOKEN"
        printf '%s\n' "$timer_deadline" > "$TIMER_DEADLINE"
        (export ILRADIO_K3_TIMER="$timer_token"; exec sh "$0" timer-run "$timer_deadline" "$timer_token") </dev/null >/dev/null 2>&1 &
        echo "$!" > "$TIMER_PID"
        echo "armed:$timer_seconds";;
    timer-run)
        deadline="${2:-0}"; original_token="${3:-}"
        case "$deadline" in ''|*[!0-9]*) exit 2;; esac
        while [ "$(cat "$TIMER_TOKEN" 2>/dev/null)" = "$original_token" ]; do
            now=$(date +%s)
            [ "$now" -ge "$deadline" ] && break
            remaining=$((deadline - now))
            [ "$remaining" -le 30 ] || remaining=30
            sleep "$remaining" || exit 0
        done
        if [ "$(cat "$TIMER_TOKEN" 2>/dev/null)" = "$original_token" ]; then
            sh "$0" stop >/dev/null 2>&1
            rm -f "$TIMER_PID" "$TIMER_TOKEN" "$TIMER_DEADLINE"
        fi;;
    timer-left)
        deadline=$(cat "$TIMER_DEADLINE" 2>/dev/null)
        case "$deadline" in ''|*[!0-9]*) echo off;;
            *) now=$(date +%s); remaining=$((deadline - now))
               [ "$remaining" -gt 0 ] && echo "$remaining" || echo 0;;
        esac;;
    timer-cancel)
        timer_cancel
        echo cancelled;;
    stop)
        probe_stop; probe_result=$?
        stop_audio; audio_result=$?
        if [ "$probe_result" = 0 ] && [ "$audio_result" = 0 ]; then echo stopped
        else echo stop_failed; fi;;
    report)
        printf '%s\n' "$2" "$3" "$4" > "$R/report.new" && mv "$R/report.new" "$R/report"
        token=$(cat "$TOKEN" 2>/dev/null)
        case "$token" in ''|*[!0-9-]*) :;; *) cp "$R/report" "$R/report-$token";; esac;;
    report-current)
        if is_ours && [ -r "$R/report-$token" ]; then cat "$R/report-$token"; fi;;
    local-current)
        if is_ours && [ -r "$R/local-$token" ]; then cat "$R/local-$token"; fi;;
    engine-current)
        if is_ours; then cat "$R/engine-$token" 2>/dev/null || echo Nieustalony; fi;;
    decode-test)
        name="$3"
        case "$name" in test-lc48.aac|test-he32.aac|test-hev224.aac|test-lc48.m4a|test-mp364.mp3|test-real-he32.aac|test-real-hev224.aac) :;; *) exit 2;; esac
        case "$2" in auto|gst-legacy|gst-mixer|mplayer|mpg123|gst-modern|mpv) :;; *) exit 2;; esac
        engine=$(backend "$2")
        if [ -z "$engine" ]; then echo engine; exit 0; fi
        if ! mkdir "$R/test-running" 2>/dev/null; then echo busy; exit 0; fi
        rm -f "$R/test-done"
        sh "$0" decode-run "$engine" "$name" </dev/null >/dev/null 2>&1 &
        echo testing;;
    decode-run)
        result=$(decode_sample "$2" "$3")
        case "$3" in
            test-real-he32.aac) label="HE-AAC v1 32 kb/s";;
            test-real-hev224.aac) label="HE-AAC v2 24 kb/s";;
            test-he32.aac) label="AAC 32 kb/s · próbka starsza";;
            test-hev224.aac) label="AAC 24 kb/s · próbka starsza";;
            *) label=$3;;
        esac
        printf '%s\n' "$label · $2 · ${result:-unconfirmed}" > "$R/test-$2-$3"
        printf '%s\n' "${result:-unconfirmed}" > "$R/test-done"
        rmdir "$R/test-running";;
    decode-result)
        if [ -r "$R/test-done" ]; then cat "$R/test-done"; fi;;
    position)
        is_ours || exit 0
        position="$R/position-$token"
        if [ ! -e "$PAUSED" ]; then
            rm -f "$position"
            control get_time_pos || exit 0
            for attempt in 1 2 3 4 5; do
                [ -s "$position" ] && break
                short_wait
            done
        fi
        if [ -r "$position" ]; then sed -n 's/^\([0-9][0-9]*\).*/\1/p' "$position"; fi;;
    title)
        if [ -r "$LOG" ]; then
            tail -n 120 "$LOG" | sed -n \
                -e "s/.*StreamTitle='\([^']*\)'.*/\1/p" \
                -e 's/.*ICY-META:.*title="\([^"]*\)".*/\1/p' \
                -e 's/.*[, ]title=(string)"\([^"]*\)".*/\1/p' | tail -n 1 | cut -c 1-150
        fi;;
    diagnose)
        engine=$(backend auto)
        echo "engine=${engine:-none}"
        echo "model=$(uname -m) kernel=$(uname -r)"
        if is_ours; then
            echo "Pamięć procesów sesji (kB):"
            for member in $(all_members "ILRADIO_K3_SESSION=$token"); do
                awk -v pid="$member" '/^VmRSS:/ {print "PID " pid ": " $2}' "/proc/$member/status" 2>/dev/null
            done
            echo "Zużycie baterii wymaga porównania na Kindle; bitrate określa transfer."
        fi
        # Observe the K3 WM8960 hardware mixer without changing any level.
        # Software volume 100% is not proof of full hardware output.
        if has amixer; then
            echo "Mikser ALSA (odczyt):"
            for control_name in Master PCM Speaker Headphone; do
                mix_line=$(amixer sget "$control_name" 2>/dev/null | grep -m 1 -E '\\[[0-9]+%\\]')
                if [ -n "$mix_line" ]; then
                    printf '%s: %s\n' "$control_name" "$(printf '%s' "$mix_line" | cut -c 1-120)"
                fi
            done
        fi
        for program in gst-launch-0.10 gst-launch gst-launch-1.0 mpg123 mpv amixer curl wget; do
            if has "$program"; then echo "+ $program"; fi
        done
        path=$(mplayer_path)
        if [ -n "$path" ]; then
            echo "+ MPlayer ($path)"
            "$path" -noconfig all -ac help 2>/dev/null | grep -Ei 'aac|faad|mp3' | head -n 10 | cut -c 1-135
        fi
        for inspector in gst-inspect-0.10 gst-inspect gst-inspect-1.0; do
            if has "$inspector"; then
                for element in faad ffdec_aac avdec_aac aacparse qtdemux souphttpsrc; do
                    if "$inspector" "$element" >/dev/null 2>&1; then echo "+ $inspector: $element"; fi
                done
            fi
        done
        echo "evidence=$(play_evidence)"
        if [ -s "$R/end-$last_token" ]; then echo "last-decoder-exit=$(cat "$R/end-$last_token")"; fi
        if [ -r "$R/report" ]; then echo "Ostatnia stacja / adres / wynik:"; head -n 3 "$R/report" | cut -c 1-180; fi
        for file in "$R"/test-*.aac "$R"/test-*.m4a "$R"/test-*.mp3; do
            if [ -f "$file" ]; then head -n 1 "$file"; fi
        done
        for device in /dev/dsp /dev/snd; do if [ -e "$device" ]; then echo "+ $device"; fi; done
        echo "status=$(sh "$0" status)" 
        echo "health=$(sh "$0" health)"
        if [ -r /proc/bus/input/devices ]; then
            echo "wejścia:"
            grep -E '^N: Name=|^H: Handlers=' /proc/bus/input/devices | head -n 14 | cut -c 1-100
        fi
        if [ -s "$LOG" ]; then tail -n 5 "$LOG" | cut -c 1-140; fi;;
    volume)
        pct="${2:-}"
        case "$pct" in ''|*[!0-9]*|????*) exit 2;; esac
        [ "$pct" -le 100 ] || exit 2
        if control "volume $pct 1"; then echo adjusted; fi;;
    volume-node)
        if [ -r /proc/bus/input/devices ]; then
            # Kindle 3 reports key capabilities as 32-bit words. Key codes
            # 114 and 115 are bits 18 and 19 of the fourth word from the end.
            node=$(awk '
                /^N: Name=/ { named = tolower($0) ~ /volume|vol[ _-]?keys/; node = "" }
                /^H: Handlers=/ {
                    for (i=1; i<=NF; i++) if ($i ~ /^event[0-9]+$/) node = "/dev/input/" $i
                    if (named && node != "") { print node; found=1; exit }
                }
                /^B: KEY=/ && node != "" && NF >= 5 {
                    word = $(NF-3)
                    sub(/^KEY=/, "", word)
                    if (length(word) >= 5 && tolower(substr(word, length(word)-4, 1)) ~ /[c-f]/)
                        detected = node
                }
                END { if (!found && detected != "") print detected }
            ' /proc/bus/input/devices)
            if [ -n "$node" ]; then printf '%s\n' "$node"; exit; fi
        fi
        for keys in /sys/class/input/event*/device/capabilities/key; do
            [ -r "$keys" ] || continue
            if awk 'NF >= 4 {
                word = $(NF-3)
                if (length(word) >= 5 && tolower(substr(word, length(word)-4, 1)) ~ /[c-f]/) found = 1
            } END { exit !found }' "$keys"; then
                node=${keys%/device/capabilities/key}
                printf '/dev/input/%s\n' "${node##*/}"
                break
            fi
        done;;
    system-volume)
        case "${2:-}" in
            up) adj='5%+';; down) adj='5%-';;
            *[!0-9]*|""|????*) echo unsupported; exit 2;;
            *) if [ "$2" -gt 100 ]; then exit 2; fi; adj="$2%";;
        esac
        if has amixer && { amixer -q sset Master "$adj" >/dev/null 2>&1 ||
            amixer -q sset PCM "$adj" >/dev/null 2>&1; }; then echo adjusted
        else echo unsupported
        fi;;
    *) exit 2;;
esac
