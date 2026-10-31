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
    private volatile String diagnosticStage="startup";
    private volatile boolean diagnosticDone=false;

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

    private void waitForScript(String script) throws Exception {
        long deadline=SystemClock.uptimeMillis()+25000;
        while(!"true".equals(evaluate(script))){if(SystemClock.uptimeMillis()>deadline)throw new AssertionError("Timed out waiting for "+script);SystemClock.sleep(100);}
    }
    private void verifyEditorFeatures() throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        runOnMainSync(()->{try{
            android.graphics.Bitmap source=android.graphics.Bitmap.createBitmap(800,600,android.graphics.Bitmap.Config.ARGB_8888);
            source.setPixel(0,0,android.graphics.Color.BLUE);source.setPixel(0,150,android.graphics.Color.GREEN);
            android.graphics.Bitmap top=PhotoEditor.crop(source,16f/9f,50,0),bottom=PhotoEditor.crop(source,16f/9f,50,100);
            if(top.getWidth()!=800||top.getHeight()!=450||top.getPixel(0,0)!=android.graphics.Color.BLUE||bottom.getPixel(0,0)!=android.graphics.Color.GREEN)throw new AssertionError("Crop dimensions / position failed");
            top.recycle();bottom.recycle();source.recycle();
        }catch(Throwable t){failure.set(t);}});
        if(failure.get()!=null)throw new AssertionError("Native photo tools",failure.get());
        diagnosticStage="photo dialog";
        AtomicReference<PhotoEditor> displayed=new AtomicReference<>();java.util.concurrent.atomic.AtomicBoolean uploaded=new java.util.concurrent.atomic.AtomicBoolean(false);
        runOnMainSync(()->{try{android.graphics.Bitmap preview=android.graphics.Bitmap.createBitmap(320,240,android.graphics.Bitmap.Config.ARGB_8888);PhotoEditor dialog=new PhotoEditor(activity,preview,(image,c,a,t)->{uploaded.set(true);image.recycle();});displayed.set(dialog);dialog.show();}catch(Throwable t){failure.set(t);}});
        waitForIdleSync();
        if(failure.get()!=null)throw new AssertionError("Photo dialog creation",failure.get());
        runOnMainSync(()->{if(displayed.get()==null||!displayed.get().isShowing())failure.set(new AssertionError("Photo editor not visible"));else displayed.get().dismiss();});
        waitForIdleSync();if(failure.get()!=null)throw new AssertionError("Photo dialog",failure.get());if(uploaded.get())throw new AssertionError("Photo cancellation uploaded data");
        diagnosticStage="photo EXIF";
        java.io.File sample=new java.io.File(getTargetContext().getCacheDir(),"photo-exif-test.jpg");
        android.graphics.Bitmap raw=android.graphics.Bitmap.createBitmap(120,80,android.graphics.Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(sample)){raw.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out);}raw.recycle();
        android.media.ExifInterface exif=new android.media.ExifInterface(sample.getAbsolutePath());exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
        android.graphics.Bitmap oriented=PhotoEditor.decode(getTargetContext(),android.net.Uri.fromFile(sample));if(oriented.getWidth()!=80||oriented.getHeight()!=120)throw new AssertionError("EXIF orientation lost");oriented.recycle();sample.delete();
        diagnosticStage="correction comparison";
        if(!"true".equals(evaluate("$('post-body').value='W Ispini.';offerCorrection({body:'W Ispinie.'},'Test');$('accept-body').checked && $('post-body').value==='W Ispini.'")))throw new AssertionError("Correction comparison changed text before consent");
        if(!"true".equals(evaluate("acceptCorrection();$('post-body').value==='W Ispinie.' && undoEditorialChange() && $('post-body').value==='W Ispini.'")))throw new AssertionError("Correction acceptance / undo failed");
        diagnosticStage="backup export";
        String payload=(String)new org.json.JSONTokener(evaluate("makeDraftBackup()")).nextValue();
        java.io.File pending=new java.io.File(getTargetContext().getCacheDir(),"il-drafts-export-pending.json"),saved=new java.io.File(getTargetContext().getCacheDir(),"backup-stream-test.json");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(pending)){out.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        runOnMainSync(()->activity.onActivityResult(1006,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(saved))));
        waitForScript("$('backup-output').textContent.includes('Zapisano plik kopii')");
        String written=new String(java.nio.file.Files.readAllBytes(saved.toPath()),java.nio.charset.StandardCharsets.UTF_8);if(!payload.equals(written))throw new AssertionError("Backup file differs from draft payload");
        diagnosticStage="backup import";
        runOnMainSync(()->activity.onActivityResult(1007,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(saved))));
        waitForScript("pendingBackupImport!==null && !$('backup-dialog').classList.contains('hidden')");evaluate("cancelBackupImport()");saved.delete();
        runOnMainSync(()->editor.requestFocus());waitForIdleSync();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Thread trace=new Thread(()->{for(int n=0;n<35&&!diagnosticDone;n++){SystemClock.sleep(2000);if(!diagnosticDone)android.util.Log.i("ILEditorProbe",diagnosticStage+" "+java.util.Arrays.toString(android.os.Looper.getMainLooper().getThread().getStackTrace()));}},"il-test-diagnostics");trace.setDaemon(true);trace.start();
        try {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            editor = (WebView) field("editor");
            long deadline = SystemClock.uptimeMillis() + 45000;
            while (!"true".equals(evaluate("location.href==='file:///android_asset/index.html' && document.readyState==='complete'"))) {
                if (SystemClock.uptimeMillis() >= deadline) throw new AssertionError("Editor did not finish loading");
                SystemClock.sleep(100);
            }
            diagnosticStage="editor ready";
            runOnMainSync(() -> editor.requestFocus());
            waitForIdleSync();
            if ("seed".equals(mode)) {
                if(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionCode!=20)throw new AssertionError("Seed must run on previous 1.14 versionCode 20");
                String seeded=evaluate("localStorage.removeItem(draftKey);draftStore=null;activeDraftId=null;$('post-title').value='Pierwszy szkic';$('post-body').value='Pierwsza wersja';persistDraft(true);$('post-body').value='Druga wersja';persistDraft(true);resetEditor();$('post-title').value='Drugi szkic';$('post-body').value='Drugi tekst';persistDraft(true);String(readDraftStore().drafts.length)");
                if(!"\"2\"".equals(seeded))throw new AssertionError("Draft seeding failed: "+seeded);
                if(!getTargetContext().getSharedPreferences("il_update_probe",0).edit().putString("marker","preserved").commit())throw new AssertionError("Preferences seeding failed");
                result.putString("stream","IL_UPDATE_SEED=PASS\n");
            } else if ("verify".equals(mode)) {
                if(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionCode!=21)throw new AssertionError("Verification must run after upgrade to versionCode 21");
                String restored=evaluate("JSON.stringify(readDraftStore().drafts.map(d=>[d.title,d.body,d.revisions.length]))");
                if(!restored.contains("Pierwszy szkic")||!restored.contains("Druga wersja")||!restored.contains("Drugi szkic"))throw new AssertionError("Drafts lost after reinstall: "+restored);
                if(!"preserved".equals(getTargetContext().getSharedPreferences("il_update_probe",0).getString("marker","")))throw new AssertionError("Preferences lost after reinstall");
                if(!"true".equals(evaluate("readDraftStore().drafts.some(d=>d.revisions.some(v=>v.snapshot.body==='Pierwsza wersja'))")))throw new AssertionError("Revision history lost");
                result.putString("stream","IL_UPDATE_DATA_TEST=PASS (1.14 to 1.15 upgrade: drafts, history, preferences)\n");
            } else {
            diagnosticStage="photo crop";verifyEditorFeatures();
            diagnosticStage="publication review";
            String review=evaluate("$('post-title').value='Podgląd testowy';$('post-body').value='Treść bez publikacji';$('post-category').value='10543';reviewPublication();!$('publish-review').classList.contains('hidden') && !$('review-confirm').disabled && $('review-preview').getAttribute('sandbox')===''");
            if(!"true".equals(review))throw new AssertionError("Publication review failed: "+review);
            evaluate("closeReview();resetEditor()");
            diagnosticStage="native confirmations";
            // A real Android WebView must pause until native confirmation is selected.
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", false);
            probe(AlertDialog.BUTTON_POSITIVE, "\"true:1\"", false);
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", true);
            result.putString("stream", "IL_WEBVIEW_CONFIRM_TEST=PASS (cancel, confirm once, dismiss)\nIL_EDITOR_FEATURES_TEST=PASS (comparison, undo, backup file streams, photo crop, EXIF, cancel)\n");
            }
            runOnMainSync(() -> activity.finish());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "IL_WEBVIEW_CONFIRM_TEST=FAIL " + error + "\n" + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally { diagnosticDone=true; }
    }
}
