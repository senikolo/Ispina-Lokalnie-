#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
player="$PWD/assets/ilradio_k3.koplugin/player.sh"
fixture="$(mktemp -d)"
export TMPDIR="$fixture"
trap 'sh "$player" stop >/dev/null; if [ -n "${server:-}" ]; then kill "$server" 2>/dev/null || true; fi; rm -rf "$fixture"' EXIT
cp assets/ilradio_k3.koplugin/test-he32.aac "$fixture/radio.aac"
openssl req -new -x509 -nodes -newkey rsa:2048 -days 1 -subj /CN=localhost \
    -addext subjectAltName=DNS:localhost -keyout "$fixture/key" -out "$fixture/cert" >/dev/null 2>&1
python3 - "$fixture" <<'PY' &
import http.server, pathlib, ssl, sys, threading, time
root = pathlib.Path(sys.argv[1])
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/start':
            self.send_response(302)
            self.send_header('Location','/radio.aac')
            self.end_headers()
            return
        if self.path not in ('/radio.aac','/stall'):
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type','audio/aacp')
        self.send_header('icy-br','32')
        self.end_headers()
        if self.path == '/stall':
            time.sleep(12)
            return
        self.wfile.write((root/'radio.aac').read_bytes() * 4)
    def log_message(self, *args):
        pass
plain = http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
https = http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler)
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(root/'cert',root/'key')
https.socket=ctx.wrap_socket(https.socket,server_side=True)
(root/'ports').write_text(f'{plain.server_port} {https.server_port}\n')
threading.Thread(target=plain.serve_forever,daemon=True).start()
https.serve_forever()
PY
server=$!
for i in {1..50}; do [ ! -s "$fixture/ports" ] || break; sleep .1; done
read -r port secure < "$fixture/ports"
export NO_PROXY=localhost,127.0.0.1
export no_proxy="$NO_PROXY"
probe() {
    prefix=$(sh "$player" probe-start "$1")
    for i in {1..115}; do [ ! -s "$prefix.done" ] || return 0; sleep .1; done
    return 1
}
probe "http://127.0.0.1:$port/start"
grep -Fx "http://127.0.0.1:$port/radio.aac" "$prefix.meta"
[ "$(wc -c < "$prefix.sample")" = 16384 ]
grep -iq '^icy-br: 32' "$prefix.headers"
probe "http://127.0.0.1:$port/404"
[ "$(cat "$prefix.done")" = 22 ]
probe "https://localhost:$secure/radio.aac"
[ "$(cat "$prefix.done")" = 60 ]
export CURL_CA_BUNDLE="$fixture/cert"
probe "https://localhost:$secure/radio.aac"
grep -Fx 200 "$prefix.meta"
[ "$(wc -c < "$prefix.sample")" = 16384 ]
probe "http://127.0.0.1:1/radio.aac"
[ "$(cat "$prefix.done")" = 7 ]
probe "http://127.0.0.1:$port/stall"
[ "$(cat "$prefix.done")" = 28 ]
echo 'PASS real HTTP redirect, HTTPS certificate, timeout, dead server and capped sample'
if command -v wget >/dev/null; then
    mkdir "$fixture/bin"
    cat > "$fixture/bin/curl" <<'CURL'
#!/bin/sh
exit 2
CURL
    chmod +x "$fixture/bin/curl"
    export PATH="$fixture/bin:$PATH"
    probe "http://127.0.0.1:$port/radio.aac"
    grep -Fx wget-direct "$prefix.meta"
    [ "$(wc -c < "$prefix.sample")" = 16384 ]
    echo 'PASS: old curl without protocol flags falls back to wget'
fi
