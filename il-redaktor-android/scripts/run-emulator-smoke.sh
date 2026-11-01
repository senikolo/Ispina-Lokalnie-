#!/usr/bin/env bash
set -euo pipefail
api_level="$1"
if [ -z "$api_level" ]; then echo 'Pass Android API level for the smoke test'; exit 2; fi
            adb install -r /tmp/il-previous-debug.apk
            adb install -r il-redaktor-android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
            adb shell am instrument -w -e mode seed com.ispinalokalnie.redaktorsmart.stable.instrumentation/com.ispinalokalnie.redaktor.ConfirmationInstrumentation | tee "/tmp/il-update-seed-$api_level.txt"
            grep -F 'IL_UPDATE_SEED=PASS' "/tmp/il-update-seed-$api_level.txt" || { adb logcat -d -s chromium AndroidRuntime ILEditorProbe ActivityManager; exit 1; }
            adb install -r il-redaktor-android/app/build/outputs/apk/debug/app-debug.apk
            adb shell am force-stop com.ispinalokalnie.redaktorsmart.stable
            adb shell am instrument -w -e mode verify com.ispinalokalnie.redaktorsmart.stable.instrumentation/com.ispinalokalnie.redaktor.ConfirmationInstrumentation | tee "/tmp/il-update-data-$api_level.txt"
            grep -F 'IL_UPDATE_DATA_TEST=PASS' "/tmp/il-update-data-$api_level.txt"
            adb shell am force-stop com.ispinalokalnie.redaktorsmart.stable
            adb shell am instrument -w com.ispinalokalnie.redaktorsmart.stable.instrumentation/com.ispinalokalnie.redaktor.ConfirmationInstrumentation | tee "/tmp/il-confirmation-test-$api_level.txt"
            grep -F 'IL_WEBVIEW_CONFIRM_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt" || { adb logcat -d -s chromium AndroidRuntime ILEditorProbe ActivityManager; exit 1; }
            grep -F 'IL_EDITOR_FEATURES_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt"
            grep -F 'IL_WORKSPACE_FEATURES_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt"
            grep -F 'IL_HAPTICS_TEST=PASS' "/tmp/il-confirmation-test-$api_level.txt"
            mkdir -p "/tmp/il-editor-ui-api-$api_level"
            if adb exec-out run-as com.ispinalokalnie.redaktorsmart.stable tar -C files/qa -cf - . > "/tmp/il-editor-ui-api-$api_level.tar"; then
              tar -xf "/tmp/il-editor-ui-api-$api_level.tar" -C "/tmp/il-editor-ui-api-$api_level" || echo "Warning: could not unpack optional emulator screenshots."
            else
              echo "Warning: emulator disconnected after successful instrumentation."
            fi
