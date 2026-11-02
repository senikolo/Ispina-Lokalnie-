#!/usr/bin/env bash
set -euo pipefail
api_level="${1:-}"
if [ -z "$api_level" ]; then echo 'Pass Android API level for the smoke test'; exit 2; fi

current_apk="il-redaktor-android/app/build/outputs/apk/debug/app-debug.apk"
test_apk="il-redaktor-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb logcat -v threadtime > "/tmp/il-emulator-logcat-$api_level.txt" 2>&1 &
logcat_pid=$!
preserve_evidence() {
  local test_result=$?
  set +e
  kill "$logcat_pid" 2>/dev/null
  free -m > "/tmp/il-host-memory-$api_level.txt"
  dmesg -T 2>/dev/null | tail -80 > "/tmp/il-host-kernel-$api_level.txt"
  mkdir -p "/tmp/il-editor-ui-api-$api_level"
  if timeout 15 adb exec-out run-as com.ispinalokalnie.redaktorsmart.stable tar -C files/qa -cf - . > "/tmp/il-editor-ui-api-$api_level.tar"; then
    tar -xf "/tmp/il-editor-ui-api-$api_level.tar" -C "/tmp/il-editor-ui-api-$api_level" || echo "Warning: could not unpack emulator evidence."
  else
    echo "Warning: emulator evidence unavailable."
  fi
  exit "$test_result"
}
trap preserve_evidence EXIT

if [ "${2:-full}" = "live-only" ]; then
  adb install --no-streaming -r "$current_apk"
  adb install --no-streaming -r "$test_apk"
  adb shell am instrument -w -e mode live-recall com.ispinalokalnie.redaktorsmart.stable.instrumentation/com.ispinalokalnie.redaktor.ConfirmationInstrumentation | tee "/tmp/il-live-recall-$api_level.txt"
  grep -F 'IL_LIVE_RECALL_TEST=PASS' "/tmp/il-live-recall-$api_level.txt"
  exit 0
fi

# Upgrade from the user's latest transpiled 1.29.4 build on every OS.
# The ancient 1.15 HTML cannot parse in Android 10's stock WebView.
adb install --no-streaming -r /tmp/il-previous-debug.apk
adb install --no-streaming -r "$test_apk"
seed_mode="seed"
adb shell am instrument -w -e mode "$seed_mode" com.ispinalokalnie.redaktorsmart.stable.instrumentation/com.ispinalokalnie.redaktor.ConfirmationInstrumentation | tee "/tmp/il-update-seed-$api_level.txt"
grep -F 'IL_UPDATE_SEED=PASS' "/tmp/il-update-seed-$api_level.txt" || { adb logcat -d -s chromium AndroidRuntime ILEditorProbe ActivityManager; exit 1; }

adb shell am force-stop com.ispinalokalnie.redaktorsmart.stable
adb install --no-streaming -r "$current_apk"
adb shell am force-stop com.ispinalokalnie.redaktorsmart.stable
adb shell am instrument -w -e mode verify com.ispinalokalnie.redaktorsmart.stable.instrumentation/com.ispinalokalnie.redaktor.ConfirmationInstrumentation | tee "/tmp/il-update-data-$api_level.txt"
grep -F 'IL_UPDATE_DATA_TEST=PASS' "/tmp/il-update-data-$api_level.txt"
adb shell am force-stop com.ispinalokalnie.redaktorsmart.stable
adb shell am instrument -w com.ispinalokalnie.redaktorsmart.stable.instrumentation/com.ispinalokalnie.redaktor.ConfirmationInstrumentation | tee "/tmp/il-confirmation-test-$api_level.txt"
grep -F 'IL_WEBVIEW_CONFIRM_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt" || { adb logcat -d -s chromium AndroidRuntime ILEditorProbe ActivityManager; exit 1; }
grep -F 'IL_EDITOR_FEATURES_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt"
grep -F 'IL_WORKSPACE_FEATURES_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt"
grep -F 'IL_HAPTICS_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt"
