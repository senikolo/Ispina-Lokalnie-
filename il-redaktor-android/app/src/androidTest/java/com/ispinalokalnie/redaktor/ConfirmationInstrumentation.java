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
        diagnosticStage="durable draft storage";
        java.io.File durablePath=new java.io.File(getTargetContext().getCacheDir(),"durable-draft-test.json");DurableDraftStore durable=new DurableDraftStore(durablePath);
        String valid="{\"version\":1,\"drafts\":[{\"title\":\"Trwały szkic\"}]}";
        if(!new org.json.JSONObject(durable.write(valid)).optBoolean("ok"))throw new AssertionError("Durable write failed");
        if(new org.json.JSONObject(durable.write("broken")).optBoolean("ok"))throw new AssertionError("Invalid draft data accepted");
        android.util.AtomicFile interrupted=new android.util.AtomicFile(durablePath);java.io.FileOutputStream partial=interrupted.startWrite();partial.write("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));interrupted.failWrite(partial);
        if(!valid.equals(new org.json.JSONObject(durable.read()).getString("data")))throw new AssertionError("Atomic rollback lost previous drafts");durablePath.delete();
        if(!"true".equals(evaluate("JSON.parse(IL.readDraftLibrary()).exists && JSON.parse(JSON.parse(IL.readDraftLibrary()).data).drafts.length===readDraftStore().drafts.length")))throw new AssertionError("Editor did not durably migrate/save drafts");
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

    private void captureUi(String name)throws Exception{
        CountDownLatch rendered=new CountDownLatch(1);
        runOnMainSync(()->editor.postVisualStateCallback(SystemClock.uptimeMillis(),new WebView.VisualStateCallback(){@Override public void onComplete(long requestId){editor.invalidate();editor.postOnAnimation(()->editor.postOnAnimation(rendered::countDown));}}));
        if(!rendered.await(15,TimeUnit.SECONDS))throw new AssertionError("WebView did not present "+name);
        getUiAutomation().waitForIdle(600,5000);android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();if(shot==null)throw new AssertionError("No screenshot for "+name);
        java.io.File dir=new java.io.File(getTargetContext().getFilesDir(),"qa");dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();
    }
    private void verifyWorkspaceFeatures()throws Exception{
        diagnosticStage="sources and portable photos";
        if(!"true".equals(evaluate("resetEditor();$('post-title').value='Historia Ispiny';$('post-body').value='W Ispini.';openSources();$('source-name').value='Gazeta Krakowska';$('source-notes').value='Sprawdzić datę w skanie';saveSource();currentSources().length===1 && !currentSources()[0].checked")))throw new AssertionError("Source note workflow failed");
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(1280,960,android.graphics.Bitmap.Config.ARGB_8888);int[] pixels=new int[1280*960];java.util.Random random=new java.util.Random(17);for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0x1000000);image.setPixels(pixels,0,1280,0,0,1280,960);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();image.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,out);image.recycle();
        java.io.File sourceImage=new java.io.File(getTargetContext().getCacheDir(),"source-photo-test.jpg");try(java.io.FileOutputStream file=new java.io.FileOutputStream(sourceImage)){file.write(out.toByteArray());}
        evaluate("sourcePhotoTarget={id:activeDraftId,source:null}");runOnMainSync(()->activity.onActivityResult(1008,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(sourceImage))));waitForScript("currentSources().length===2 && Object.keys(readDraftStore().sourceFiles||{}).length===1");sourceImage.delete();
        if(!"true".equals(evaluate("Object.keys(parseDraftBackup(makeDraftBackup()).sourceFiles).length===1")))throw new AssertionError("Portable source photo failed");if(!"true".equals(evaluate("!$('sources-dialog').classList.contains('hidden') && getComputedStyle($('sources-dialog')).display!=='none' && $('sources-dialog').getBoundingClientRect().top===0 && $('sources-dialog').getBoundingClientRect().height>=innerHeight-1")))throw new AssertionError("Source dialog not visible");captureUi("sources");evaluate("closeSources()");
        diagnosticStage="literal replacement and responsive preview";
        if(!"true".equals(evaluate("$('find-text').value='Ispini';$('replace-text').value='Ispinie';$('find-field').value='body';proposeReplacement();$('post-body').value==='W Ispini.' && acceptCorrection() && $('post-body').value==='W Ispinie.'")))throw new AssertionError("Find/replace confirmation failed");
        if(!"true".equals(evaluate("preview();setPreviewDevice('desktop');$('post-preview').style.width==='1024px'")))throw new AssertionError("Desktop preview width failed");evaluate("setPreviewDevice('phone')");
        diagnosticStage="editorial calendar";
        if(!"true".equals(evaluate("$('post-schedule').value='2026-11-05T09:30';persistDraft();showCalendar();$('calendar-month').value='2026-11';renderCalendar();$('calendar-entries').textContent.includes('Plan roboczy')")))throw new AssertionError("Local calendar failed");if(!"true".equals(evaluate("$('screen-calendar').classList.contains('active')")))throw new AssertionError("Calendar not active");captureUi("calendar");evaluate("showScreen('editor')");
        diagnosticStage="remote conflict guard";
        evaluate("window.__oldRpc=rpc;window.__oldConnected=connected;window.__fakeRemote={id:98765,status:'publish',title:{raw:'Historia Ispiny'},content:{raw:'Pierwotna wersja na stronie'},excerpt:{raw:''},categories:[10543],tags:[],modified:'old'};postId=98765;postStatus='publish';remoteBaseline=remoteVersion(window.__fakeRemote);persistDraft();window.__fakeRemote={...window.__fakeRemote,modified:'new',content:{raw:'Nowsza wersja z WordPressa'}};rpc=async(method)=>{if(method!=='GET')throw Error('Unexpected write');return window.__fakeRemote};window.__conflictProbe=false;preflightRemote(draftSignature(captureDraft())).then(ok=>{window.__conflictProbe=!ok&&pendingRemoteConflict!==null;showConflictComparison()})");
        waitForScript("window.__conflictProbe===true");captureUi("conflict");evaluate("closeRemoteConflict();rpc=window.__oldRpc;connected=window.__oldConnected;postId=null;postStatus='';remoteBaseline=null;resetEditor();showScreen('home')");if(!"true".equals(evaluate("$('screen-home').classList.contains('active') && !$('screen-editor').classList.contains('active')")))throw new AssertionError("Home not active");captureUi("home");
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
                if(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionCode!=21)throw new AssertionError("Seed must run on previous 1.15 versionCode 21");
                String seeded=evaluate("localStorage.removeItem(draftKey);draftStore=null;activeDraftId=null;$('post-title').value='Pierwszy szkic';$('post-body').value='Pierwsza wersja';persistDraft(true);$('post-body').value='Druga wersja';persistDraft(true);resetEditor();$('post-title').value='Drugi szkic';$('post-body').value='Drugi tekst';persistDraft(true);String(readDraftStore().drafts.length)");
                if(!"\"2\"".equals(seeded))throw new AssertionError("Draft seeding failed: "+seeded);
                if(!getTargetContext().getSharedPreferences("il_update_probe",0).edit().putString("marker","preserved").commit())throw new AssertionError("Preferences seeding failed");
                result.putString("stream","IL_UPDATE_SEED=PASS\n");
            } else if ("verify".equals(mode)) {
                if(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionCode!=22)throw new AssertionError("Verification must run after upgrade to versionCode 22");
                String restored=evaluate("JSON.stringify(readDraftStore().drafts.map(d=>[d.title,d.body,d.revisions.length]))");
                if(!restored.contains("Pierwszy szkic")||!restored.contains("Druga wersja")||!restored.contains("Drugi szkic"))throw new AssertionError("Drafts lost after reinstall: "+restored);
                if(!"preserved".equals(getTargetContext().getSharedPreferences("il_update_probe",0).getString("marker","")))throw new AssertionError("Preferences lost after reinstall");
                if(!"true".equals(evaluate("readDraftStore().drafts.some(d=>d.revisions.some(v=>v.snapshot.body==='Pierwsza wersja'))")))throw new AssertionError("Revision history lost");
                result.putString("stream","IL_UPDATE_DATA_TEST=PASS (1.15 to 1.16 upgrade: drafts, history, preferences)\n");
            } else {
            diagnosticStage="photo crop";verifyEditorFeatures();
            verifyWorkspaceFeatures();
            diagnosticStage="publication review";
            String review=evaluate("$('post-title').value='Podgląd testowy';$('post-body').value='Treść bez publikacji';$('post-category').value='10543';reviewPublication();!$('publish-review').classList.contains('hidden') && !$('review-confirm').disabled && $('review-preview').getAttribute('sandbox')===''");
            if(!"true".equals(review))throw new AssertionError("Publication review failed: "+review);
            evaluate("closeReview();resetEditor()");
            diagnosticStage="native confirmations";
            // A real Android WebView must pause until native confirmation is selected.
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", false);
            probe(AlertDialog.BUTTON_POSITIVE, "\"true:1\"", false);
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", true);
            result.putString("stream", "IL_WEBVIEW_CONFIRM_TEST=PASS (cancel, confirm once, dismiss)\nIL_EDITOR_FEATURES_TEST=PASS (comparison, undo, backup file streams, photo crop, EXIF, cancel)\nIL_WORKSPACE_FEATURES_TEST=PASS (sources/photos backup, literal replacement, viewport, calendar, remote conflict)\n");
            }
            runOnMainSync(() -> activity.finish());waitForIdleSync();
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "IL_WEBVIEW_CONFIRM_TEST=FAIL " + error + "\n" + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally { diagnosticDone=true; }
    }
}
