#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/mock-tests
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
