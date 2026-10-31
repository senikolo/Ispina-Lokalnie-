package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.webkit.WebView;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual Activity's WebView and native confirmation, with no WordPress writes. */
public final class ConfirmationInstrumentation extends Instrumentation {
    private MainActivity activity;
    private WebView editor;
    private String mode="confirm";

    @Override public void onCreate(Bundle args) { super.onCreate(args); if(args!=null)mode=args.getString("mode","confirm"); start(); }

    private Object field(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(activity);
    }

    private String evaluate(String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        runOnMainSync(() -> editor.evaluateJavascript(script, value -> { result.set(value); done.countDown(); }));
        if (!done.await(20, TimeUnit.SECONDS)) throw new AssertionError("WebView callback timed out");
        return result.get();
    }

    private void probe(int button, String expected, boolean dismiss) throws Exception {
        runOnMainSync(() -> editor.evaluateJavascript(
            "window.__submissionCount=0;window.__confirmationProbe=confirm('Test potwierdzenia: nic nie zostanie wysłane do WordPressa.');if(window.__confirmationProbe)window.__submissionCount++;", null));
        AtomicReference<AlertDialog> found = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (found.get() == null && SystemClock.uptimeMillis() < deadline) {
            runOnMainSync(() -> {
                try { AlertDialog d = (AlertDialog) field("editorConfirmation"); if (d != null && d.isShowing()) found.set(d); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            if (found.get() == null) SystemClock.sleep(50);
        }
        if (found.get() == null) throw new AssertionError("Native confirmation was not displayed: "+evaluate("JSON.stringify({ready:document.readyState,focused:document.hasFocus(),probe:window.__confirmationProbe,count:window.__submissionCount})"));
        runOnMainSync(() -> {
            if (dismiss) found.get().dismiss();
            else found.get().getButton(button).performClick();
        });
        String actual = evaluate("String(window.__confirmationProbe)+':'+window.__submissionCount");
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            editor = (WebView) field("editor");
            long deadline = SystemClock.uptimeMillis() + 45000;
            while (!"true".equals(evaluate("location.href==='file:///android_asset/index.html' && document.readyState==='complete'"))) {
                if (SystemClock.uptimeMillis() >= deadline) throw new AssertionError("Editor did not finish loading");
                SystemClock.sleep(100);
            }
            runOnMainSync(() -> editor.requestFocus());
            waitForIdleSync();
            if ("seed".equals(mode)) {
                String seeded=evaluate("localStorage.removeItem(draftKey);draftStore=null;activeDraftId=null;$('post-title').value='Pierwszy szkic';$('post-body').value='Pierwsza wersja';persistDraft(true);$('post-body').value='Druga wersja';persistDraft(true);resetEditor();$('post-title').value='Drugi szkic';$('post-body').value='Drugi tekst';persistDraft(true);String(readDraftStore().drafts.length)");
                if(!"\"2\"".equals(seeded))throw new AssertionError("Draft seeding failed: "+seeded);
                if(!getTargetContext().getSharedPreferences("il_update_probe",0).edit().putString("marker","preserved").commit())throw new AssertionError("Preferences seeding failed");
                result.putString("stream","IL_UPDATE_SEED=PASS\n");
            } else if ("verify".equals(mode)) {
                String restored=evaluate("JSON.stringify(readDraftStore().drafts.map(d=>[d.title,d.body,d.revisions.length]))");
                if(!restored.contains("Pierwszy szkic")||!restored.contains("Druga wersja")||!restored.contains("Drugi szkic"))throw new AssertionError("Drafts lost after reinstall: "+restored);
                if(!"preserved".equals(getTargetContext().getSharedPreferences("il_update_probe",0).getString("marker","")))throw new AssertionError("Preferences lost after reinstall");
                if(!"true".equals(evaluate("readDraftStore().drafts.some(d=>d.revisions.some(v=>v.snapshot.body==='Pierwsza wersja'))")))throw new AssertionError("Revision history lost");
                result.putString("stream","IL_UPDATE_DATA_TEST=PASS (drafts, history, preferences after adb install -r)\n");
            } else {
            String review=evaluate("$('post-title').value='Podgląd testowy';$('post-body').value='Treść bez publikacji';$('post-category').value='10543';reviewPublication();!$('publish-review').classList.contains('hidden') && !$('review-confirm').disabled && $('review-preview').getAttribute('sandbox')===''");
            if(!"true".equals(review))throw new AssertionError("Publication review failed: "+review);
            evaluate("closeReview();resetEditor()");
            // A real Android WebView must pause until native confirmation is selected.
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", false);
            probe(AlertDialog.BUTTON_POSITIVE, "\"true:1\"", false);
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", true);
            result.putString("stream", "IL_WEBVIEW_CONFIRM_TEST=PASS (cancel, confirm once, dismiss)\n");
            }
            runOnMainSync(() -> activity.finish());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "IL_WEBVIEW_CONFIRM_TEST=FAIL " + error + "\n" + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
