#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/mock-tests
# The bundled Radio main.lua exceeds 64 KiB; do not regress to the old 65536-byte limit.
radio_dir="assets/ilradio_k3.koplugin"
test "$(wc -c < "$radio_dir/main.lua")" -gt 65536
grep -Fq 'MAX_FILE = 128 * 1024' src/pl/ispinalokalnie/kindlewifi/RadioInstaller.java
for item in _meta.lua stations.lua player.sh main.lua tuning.lua streams.tsv \
    test-lc48.aac test-he32.aac test-hev224.aac test-lc48.m4a \
    test-mp364.mp3 test-real-he32.aac test-real-hev224.aac; do
    file="$radio_dir/$item"
    test -s "$file"
    bytes=$(wc -c < "$file")
    if [ "$bytes" -gt 131072 ]; then
        echo "Bundled radio file $item exceeds 128 KiB: $bytes bytes" >&2
        exit 1
    fi
done
echo "PASS: all bundled radio files satisfy the 128 KiB installer limit"
# The Mail installer has different, intentional limits.
mail_dir="assets/ilmail_k3.koplugin"
for item in _meta.lua main.lua backend.lua vault.lua imap.lua mail.lua LICENSE-THIRD-PARTY.txt; do
    file="$mail_dir/$item"
    test -s "$file"
    bytes=$(wc -c < "$file")
    if [ "$bytes" -gt 65536 ]; then
        echo "Bundled mail file $item exceeds the 64 KiB installer limit: $bytes bytes" >&2
        exit 1
    fi
done
test -s "$mail_dir/ca-bundle.crt"
test "$(wc -c < "$mail_dir/ca-bundle.crt")" -le 1048576
echo "PASS: bundled Mail files satisfy individual installer limits"
# Radio and Mail installers must not depend on the removed weather feature.
test ! -e assets/bialoleka.koplugin
test ! -e src/pl/ispinalokalnie/kindlewifi/WeatherJob.java
test ! -e src/pl/ispinalokalnie/kindlewifi/BialolekaForecast.java
if grep -R "BialolekaForecast\\|WeatherJob.kick" src/pl/ispinalokalnie/kindlewifi --include='*.java'; then
  echo "Weather dependency still present in phone code" >&2
  exit 1
fi
javac -encoding UTF-8 -d build/mock-tests \
  src/pl/ispinalokalnie/kindlewifi/BoundedInput.java tests/BoundedInputTest.java
java -Dfile.encoding=UTF-8 -cp build/mock-tests pl.ispinalokalnie.kindlewifi.BoundedInputTest
javac -encoding UTF-8 -d build/mock-tests \
  src/pl/ispinalokalnie/kindlewifi/VerifiedTransfer.java \
  src/pl/ispinalokalnie/kindlewifi/AudioFiles.java tests/AudioFilesTest.java \
  $(find tests/mock/src -name '*.java')
java -Dfile.encoding=UTF-8 -cp build/mock-tests VerifyAtomicTest
java -Dfile.encoding=UTF-8 -cp build/mock-tests pl.ispinalokalnie.kindlewifi.AudioFilesTest
javac -encoding UTF-8 -d build/mock-tests \
  src/pl/ispinalokalnie/kindlewifi/TransferQueue.java tests/QueueTest.java
java -Dfile.encoding=UTF-8 -cp build/mock-tests pl.ispinalokalnie.kindlewifi.QueueTest
if command -v ffmpeg >/dev/null && command -v curl >/dev/null; then
    bash tests/run-radio-tests.sh
fi
if [ "${GITHUB_ACTIONS:-}" = true ]; then
    bash tests/run-sftp-tests.sh
fi
if command -v lua5.3 >/dev/null; then
    lua5.3 tests/radio-menu-test.lua assets/ilradio_k3.koplugin
elif command -v texlua >/dev/null; then
    texlua tests/radio-menu-test.lua assets/ilradio_k3.koplugin
fi
