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
        try(java.io.InputStream in=getContext().getAssets().open("photo-exif-orientation-6.jpg");java.io.FileOutputStream out=new java.io.FileOutputStream(sample)){
            byte[] buffer=new byte[4096];int read;while((read=in.read(buffer))!=-1)out.write(buffer,0,read);
        }
        int fixtureOrientation=new androidx.exifinterface.media.ExifInterface(sample.getAbsolutePath()).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,0);
        if(fixtureOrientation!=6)throw new AssertionError("JPEG fixture orientation tag unreadable: "+fixtureOrientation);
        android.graphics.Bitmap oriented=PhotoEditor.decode(getTargetContext(),android.net.Uri.fromFile(sample));
        if(oriented.getWidth()!=80||oriented.getHeight()!=120)throw new AssertionError("EXIF orientation lost; dimensions="+oriented.getWidth()+"x"+oriented.getHeight());
        oriented.recycle();sample.delete();
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
        diagnosticStage="professional formatting and exit save";
        String formatAndSave=evaluate("(()=>{resetEditor();$('post-mode').value='text';const text='To jest ważna informacja';$('post-body').value=text;const field=$('post-body');field.setSelectionRange(text.indexOf('ważna'),text.length);formatEditor('bold');const formatted=field.value==='To jest **ważna informacja**'&&editorHtml().includes('<strong>ważna informacja</strong>');$('post-title').value='Szkic zachowany przy wyjściu';field.value='Ostatni tekst przed zamknięciem.';scheduleLocalSave();showScreen('home');const store=JSON.parse(JSON.parse(IL.readDraftLibrary()).data);const saved=store.drafts.find(d=>d.title==='Szkic zachowany przy wyjściu');showScreen('editor');return formatted&&!!saved&&saved.body==='Ostatni tekst przed zamknięciem.'})()");
        if(!"true".equals(formatAndSave))throw new AssertionError("Professional formatting / exit save failed: "+formatAndSave);captureUi("editor-formatting");
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

    private void testFreeRadar() throws Exception {
        long now=System.currentTimeMillis();
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z",java.util.Locale.US);
        String xml="<rss><channel><item><title>Most w Ispinie</title><link>https://example.org/most</link><description>&lt;b&gt;Nowa informacja&lt;/b&gt;</description><pubDate>"+format.format(new java.util.Date(now))+"</pubDate></item>"
            +"<item><title>Bochnia bez Ispiny w tekście</title><link>javascript:alert(1)</link></item>"
            +"<item><title>Ispina bez daty</title><link>https://example.org/bez-daty</link></item>"
            +"<item><title>Ispina dawniej</title><link>https://example.org/archiwum</link><pubDate>Sat, 01 Jan 2000 00:00:00 +0000</pubDate></item>"
            +"<item><title>Inna miejscowość</title><link>https://example.org/inna</link></item></channel></rss>";
        // Atom entries and arbitrary topics must work on Android 8 too.
        String atom="<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>Kapliczka w Ispinie</title>"
            +"<link rel=\"alternate\" href=\"https://example.org/kapliczka\"/>"
            +"<summary>Odnowiona kapliczka</summary><published>2026-10-30T10:20:00Z</published></entry></feed>";
        org.json.JSONArray atomItems=FreeIspinaSearchClient.parseFeed(atom,0,false);
        if(atomItems.length()!=1 || !atomItems.getJSONObject(0).getString("url").contains("kapliczka"))
            throw new AssertionError("Atom feed links were not parsed");
        if(!"kapliczki".equals(FreeIspinaSearchClient.topic("Wyszukaj informacje o kapliczki w Ispinie")))
            throw new AssertionError("Unlisted topic was discarded");
        if(!FreeIspinaSearchClient.matchesTopic(
            new org.json.JSONObject().put("title","Kapliczka w Ispinie"),"kapliczki"))
            throw new AssertionError("Inflected Polish subject not matched");
        if(FreeIspinaSearchClient.matchesTopic(
            new org.json.JSONObject().put("title","Most w Ispinie"),"most kapliczki"))
            throw new AssertionError("Multiword topic matched only one term");
        if(!FreeIspinaSearchClient.matchesTopic(
            new org.json.JSONObject().put("title","Most i kapliczka w Ispinie"),"most kapliczki"))
            throw new AssertionError("Multiword topic failed to match all terms");
        if(!"".equals(FreeIspinaSearchClient.topic(
            "Co nowego o Ispinie w ostatnich 30 dniach? Sprawdź źródła urzędowe, BIP, lokalne media i przetargi.")))
            throw new AssertionError("Broad multi-source brief was treated as a narrow topic");
        org.json.JSONArray history=new org.json.JSONArray().put(new org.json.JSONObject()
            .put("role","user").put("content","Co wiadomo o moście w Ispinie?"));
        String inherited=FreeIspinaSearchClient.resolveConversationPrompt("A kiedy?",history);
        if(!"Ispina most".equals(inherited))
            throw new AssertionError("A short follow-up lost the previous subject: "+inherited);
        if(!"Ispina".equals(FreeIspinaSearchClient.resolveConversationPrompt("Ispina",history)))
            throw new AssertionError("An explicit broad query incorrectly inherited a previous subject");
        if(!"Co z kapliczki w Ispinie?".equals(FreeIspinaSearchClient.resolveConversationPrompt(
            "Co z kapliczki w Ispinie?",history)))
            throw new AssertionError("Explicit new topic was overwritten with previous context");
        org.json.JSONArray manual=FreeIspinaSearchClient.parseFeed(xml,0,false);
        org.json.JSONArray background=FreeIspinaSearchClient.parseFeed(xml,now-86400000L,true);
        if(manual.length()!=3 || background.length()!=1)throw new AssertionError("Free RSS location/date/link filtering failed");
        if(background.getJSONObject(0).getString("snippet").contains("<b>"))throw new AssertionError("RSS HTML was not converted to plain text");
        try { FreeIspinaSearchClient.parseFeed("<!DOCTYPE rss><rss/>",0,false);throw new AssertionError("RSS DTD accepted"); }
        catch(java.io.IOException expected) {}
        ChatGptPlanClient.SearchControl cancelled=new ChatGptPlanClient.SearchControl(getTargetContext(),10000);
        cancelled.cancel();
        try { FreeIspinaSearchClient.ask(getTargetContext(),"Ispina",cancelled);throw new AssertionError("Cancelled search was allowed"); }
        catch(Exception expected) { /* Expected cancellation/deadline, no network call. */ }
        ChatGptPlanClient.SearchControl expired=new ChatGptPlanClient.SearchControl(getTargetContext(),1);
        try { Thread.sleep(20);expired.check();throw new AssertionError("Search deadline did not expire"); }
        catch(Exception expected) { /* Expected timeout. AssertionError is not caught by Exception. */ }
        finally { expired.close(); }
    }

    private void testChatGptLogoutSecurity() throws Exception {
        android.content.Context ctx = getTargetContext();
        java.lang.reflect.Method prefMethod = ChatGptPlanClient.class.getDeclaredMethod("prefs", android.content.Context.class);
        prefMethod.setAccessible(true);
        android.content.SharedPreferences p = (android.content.SharedPreferences) prefMethod.invoke(null, ctx);
        java.lang.reflect.Method encrypt = ChatGptPlanClient.class.getDeclaredMethod("encryptSecret", android.content.Context.class, String.class);
        java.lang.reflect.Method read = ChatGptPlanClient.class.getDeclaredMethod("readSecret", android.content.Context.class, String.class);
        java.lang.reflect.Method guard = ChatGptPlanClient.class.getDeclaredMethod("ensureCurrentGeneration", android.content.SharedPreferences.class, long.class);
        encrypt.setAccessible(true); read.setAccessible(true); guard.setAccessible(true);
        String ciphertext = (String) encrypt.invoke(null, ctx, "local-test-secret");
        if (ciphertext.contains("local-test-secret")) throw new AssertionError("Token was stored in plain text");
        long generation = p.getLong("chatgpt_auth_generation", 0L) + 1L;
        p.edit().putString("chatgpt_access_token", ciphertext)
            .putString("chatgpt_id_token", ciphertext).putString("chatgpt_email", "test@example.invalid")
            .putBoolean("chatgpt_email_verified", true).putLong("chatgpt_auth_generation", generation).commit();
        if (!"local-test-secret".equals(read.invoke(null, ctx, "chatgpt_access_token")))
            throw new AssertionError("Android Keystore token roundtrip failed");
        guard.invoke(null, p, generation);
        ChatGptPlanClient.disconnect(ctx); // No refresh token: never sends a network request.
        for (String key : new String[]{"chatgpt_access_token","chatgpt_id_token","chatgpt_email","chatgpt_email_verified"})
            if (p.contains(key)) throw new AssertionError("Logout retained " + key);
        if (ChatGptPlanClient.isConnected(ctx)) throw new AssertionError("Logout retained connection");
        try { guard.invoke(null, p, generation); throw new AssertionError("Pending OAuth survived logout"); }
        catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof Exception)) throw expected;
        }
        java.lang.reflect.Method configure = ChatGptPlanClient.class.getDeclaredMethod("configureWebSearch", org.json.JSONObject.class, boolean.class);
        configure.setAccessible(true);
        org.json.JSONObject request = new org.json.JSONObject();
        configure.invoke(null, request, true);
        if (!"required".equals(request.getString("tool_choice")) ||
            !"web_search_call.action.sources".equals(request.getJSONArray("include").getString(0)))
            throw new AssertionError("Radar did not require web search with sources");
        java.lang.reflect.Method parse = ChatGptPlanClient.class.getDeclaredMethod("responseResult", String.class, org.json.JSONObject.class);
        parse.setAccessible(true);
        org.json.JSONObject source = new org.json.JSONObject().put("url", "https://example.org/ispina").put("title", "Ispina");
        org.json.JSONObject call = new org.json.JSONObject().put("type", "web_search_call")
            .put("action", new org.json.JSONObject().put("sources", new org.json.JSONArray().put(source)));
        org.json.JSONObject parsed = (org.json.JSONObject) parse.invoke(null, "Raport testowy",
            new org.json.JSONObject().put("output", new org.json.JSONArray().put(call)));
        if (!parsed.getBoolean("verified") || parsed.getJSONArray("sources").length() != 1)
            throw new AssertionError("Radar discarded returned search sources");
        java.lang.reflect.Method error = ChatGptPlanClient.class.getDeclaredMethod("formatApiError", int.class, String.class);
        error.setAccessible(true);
        String transientError = (String) error.invoke(null, 429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        String quota = (String) error.invoke(null, 429, "{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}");
        if (transientError.contains("Limit użycia ChatGPT") || !quota.contains("Limit użycia ChatGPT"))
            throw new AssertionError("Temporary throttling was confused with exhausted plan usage");
    }


    private void testEditorialMemory() throws Exception {
        diagnosticStage="sqlite editorial memory persistence";
        String url="https://example.org/il-redaktor-qa-only";
        EditorialMemory memory=new EditorialMemory(getTargetContext());
        org.json.JSONObject hit=new org.json.JSONObject()
            .put("url",url).put("title","Most w Ispinie")
            .put("snippet","Pierwsza wersja komunikatu").put("publishedAt","2026-11-01");
        org.json.JSONObject scan=new org.json.JSONObject().put("question","Most w Ispinie")
            .put("answer","Znaleziono link do sprawdzenia, nie potwierdzono faktów.")
            .put("sources",new org.json.JSONArray().put(hit));
        try {
            memory.execute("delete",new org.json.JSONObject().put("url",url).put("confirmed",true));
            if(memory.execute("save",scan).getInt("new")!=1)
                throw new AssertionError("First source not marked as new");
            if(memory.execute("save",scan).getInt("known")!=1)
                throw new AssertionError("Identical source not deduplicated");
            if(!"link_found".equals(memory.execute("browse",new org.json.JSONObject()
                .put("topic","Most")).getJSONArray("items").getJSONObject(0).getString("status")))
                throw new AssertionError("Found link was falsely marked verified");
            try {
                memory.execute("review",new org.json.JSONObject().put("url",url));
                throw new AssertionError("Manual source approval accepted without consent");
            } catch(IllegalArgumentException expected) {}
            memory.execute("review",new org.json.JSONObject()
                .put("url",url).put("confirmed",true));
            if(!"reviewed".equals(memory.execute("browse",new org.json.JSONObject()
                .put("topic","Most")).getJSONArray("items").getJSONObject(0).getString("status")))
                throw new AssertionError("Review status was not saved");
            hit.put("snippet","Zmieniono metadane tego linku");
            if(memory.execute("save",scan).getInt("changed")!=1)
                throw new AssertionError("Source change not detected");
            if(!"link_found".equals(memory.execute("browse",new org.json.JSONObject()
                .put("topic","Most")).getJSONArray("items").getJSONObject(0).getString("status")))
                throw new AssertionError("Updated source retained stale review approval");
            memory.close();
            EditorialMemory reopened=new EditorialMemory(getTargetContext());
            try {
                if(reopened.execute("browse",new org.json.JSONObject().put("topic","Most"))
                    .getJSONArray("items").length()<1)throw new AssertionError("SQLite lost persisted source");
                if(reopened.execute("history",new org.json.JSONObject())
                    .getJSONArray("items").length()<1)throw new AssertionError("SQLite lost conversation");
                if(!"IL_EDITORIAL_MEMORY_V1".equals(reopened.execute("export",new org.json.JSONObject())
                    .getString("format")))throw new AssertionError("Portable backup not generated");
                if(reopened.execute("delete",new org.json.JSONObject().put("url",url).put("confirmed",true))
                    .getInt("deleted")!=1)throw new AssertionError("Selected deletion did not work");
            } finally { reopened.close(); }
        } finally { memory.close(); }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Thread trace=new Thread(()->{for(int n=0;n<35&&!diagnosticDone;n++){SystemClock.sleep(2000);if(!diagnosticDone)android.util.Log.i("ILEditorProbe",diagnosticStage+" "+java.util.Arrays.toString(android.os.Looper.getMainLooper().getThread().getStackTrace()));}},"il-test-diagnostics");trace.setDaemon(true);trace.start();
        try {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (MainActivity) startActivitySync(launch);
            editor = (WebView) field("editor");
            long deadline = SystemClock.uptimeMillis() + 45000;
            String expectedAsset = "seed".equals(mode) ? "index.html" : "index-android.html";
            String pageReadyScript = "location.href==='file:///android_asset/" + expectedAsset + "' && document.readyState==='complete'";
            while (!"true".equals(evaluate(pageReadyScript))) {
                if (SystemClock.uptimeMillis() >= deadline) throw new AssertionError("Editor did not finish loading");
                SystemClock.sleep(100);
            }
            diagnosticStage="editor ready";
            runOnMainSync(() -> editor.requestFocus());
            waitForIdleSync();
            // Complete the first rendered frame before opening another native window.
            // document.readyState alone only confirms DOM loading on older WebView.
            captureUi("startup");
            if ("seed".equals(mode) || "seed-current".equals(mode)) {
                int expectedVersion = "seed-current".equals(mode) ? 39 : 21;
                if(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionCode!=expectedVersion)throw new AssertionError("Seed baseline versionCode mismatch: expected "+expectedVersion);
                String seeded=evaluate("localStorage.removeItem(draftKey);draftStore=null;activeDraftId=null;$('post-title').value='Pierwszy szkic';$('post-body').value='Pierwsza wersja';persistDraft(true);$('post-body').value='Druga wersja';persistDraft(true);resetEditor();$('post-title').value='Drugi szkic';$('post-body').value='Drugi tekst';persistDraft(true);String(readDraftStore().drafts.length)");
                if(!"\"2\"".equals(seeded))throw new AssertionError("Draft seeding failed: "+seeded);
                if(!getTargetContext().getSharedPreferences("il_update_probe",0).edit().putString("marker","preserved").commit())throw new AssertionError("Preferences seeding failed");
                result.putString("stream","IL_UPDATE_SEED=PASS\n");
            } else if ("verify".equals(mode)) {
                if(getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),0).versionCode!=39)throw new AssertionError("Verification must run after upgrade to versionCode 39");
                String restored=evaluate("JSON.stringify(readDraftStore().drafts.map(d=>[d.title,d.body,d.revisions.length]))");
                if(!restored.contains("Pierwszy szkic")||!restored.contains("Druga wersja")||!restored.contains("Drugi szkic"))throw new AssertionError("Drafts lost after reinstall: "+restored);
                if(!"preserved".equals(getTargetContext().getSharedPreferences("il_update_probe",0).getString("marker","")))throw new AssertionError("Preferences lost after reinstall");
                if(!"true".equals(evaluate("readDraftStore().drafts.some(d=>d.revisions.some(v=>v.snapshot.body==='Pierwsza wersja'))")))throw new AssertionError("Revision history lost");
                result.putString("stream","IL_UPDATE_DATA_TEST=PASS (drafts, history and preferences preserved across install)\n");
            } else {
            diagnosticStage="photo crop";verifyEditorFeatures();
            verifyWorkspaceFeatures();
            diagnosticStage="vibration feedback";
            if(!"true".equals(evaluate("(()=>{const targets=Array.from(document.querySelectorAll('button.tile,nav button,button.calendar-day'));return targets.length>=13&&targets.every(target=>hapticTileTap({target})===true)})()")))throw new AssertionError("Every dashboard and navigation tile must request haptic feedback");
            if(!"true".equals(evaluate("$('haptics-select').value='2';setHapticsMode('2');true")))throw new AssertionError("Could not select normal vibration mode");
            waitForScript("$('haptics-select').value==='2'");
            evaluate("testHaptics()");waitForScript("$('haptics-status').textContent.includes('Wysłano sygnał testowy') || $('haptics-status').textContent.includes('nie zgłasza silnika')");
            if(!"true".equals(evaluate("IL.setHaptics(0);true")))throw new AssertionError("Could not disable vibration");
            waitForScript("$('haptics-select').value==='0'");evaluate("testHaptics()");waitForScript("$('haptics-status').textContent.includes('wyłączone')");
            evaluate("IL.setHaptics(1)");waitForScript("$('haptics-select').value==='1'");
            result.putString("haptics", "IL_HAPTICS_TEST=PASS (every tile tap, motor detection, adjustable intensity, test signal, disabled-mode message)\n");
            diagnosticStage="publication review";
            String review=evaluate("$('post-title').value='Podgląd testowy';$('post-body').value='Treść bez publikacji';$('post-category').value='10543';reviewPublication();!$('publish-review').classList.contains('hidden') && !$('review-confirm').disabled && $('review-preview').getAttribute('sandbox')===''");
            if(!"true".equals(review))throw new AssertionError("Publication review failed: "+review);
            evaluate("closeReview();resetEditor()");
            diagnosticStage="native confirmations";
            // A real Android WebView must pause until native confirmation is selected.
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", false);
            probe(AlertDialog.BUTTON_POSITIVE, "\"true:1\"", false);
            probe(AlertDialog.BUTTON_NEGATIVE, "\"false:0\"", true);
            testFreeRadar();
            testEditorialMemory();
            testChatGptLogoutSecurity();
            result.putString("memory", "IL_EDITORIAL_MEMORY_TEST=PASS (dedup, explicit review, change invalidation, persistence, export, delete)\n");
            result.putString("stream", "IL_FREE_RADAR_TEST=PASS\nIL_CHATGPT_AUTH_SECURITY_TEST=PASS\nIL_WEBVIEW_CONFIRM_TEST=PASS (cancel, confirm once, dismiss)\nIL_EDITOR_FEATURES_TEST=PASS (comparison, undo, backup file streams, photo crop, EXIF, cancel)\nIL_WORKSPACE_FEATURES_TEST=PASS (sources/photos backup, literal replacement, viewport, calendar, remote conflict)\nIL_HAPTICS_TEST=PASS (every tile tap, motor detection, adjustable intensity, test signal, disabled-mode message)\n");
            }
            runOnMainSync(() -> activity.finish());waitForIdleSync();
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "IL_WEBVIEW_CONFIRM_TEST=FAIL " + error + "\n" + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally { diagnosticDone=true; }
    }
}
