#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/mock-tests
# Radio and Mail installers must not depend on the removed weather feature.
test ! -e assets/bialoleka.koplugin
test ! -e src/pl/ispinalokalnie/kindlewifi/WeatherJob.java
test ! -e src/pl/ispinalokalnie/kindlewifi/BialolekaForecast.java
if grep -R "BialolekaForecast\\|WeatherJob.kick" src/pl/ispinalokalnie/kindlewifi --include='*.java'; then
  echo "Weather dependency still present in phone code" >&2
  exit 1
fi
javac -encoding UTF-8 -d build/mock-tests \\
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
