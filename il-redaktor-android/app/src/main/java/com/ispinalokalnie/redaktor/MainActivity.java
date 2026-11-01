package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.webkit.WebChromeClient;
import android.webkit.JsResult;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.webkit.WebViewClient;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

/**
 * IL Redaktor Smart. Only the local UI WebView has a JavaScript bridge.
 * OAuth runs in the system browser. Access and refresh tokens are encrypted
 * with Android Keystore and are NEVER committed to source control or logged.
 */
public class MainActivity extends Activity {
    private static final String SITE = "252442125";
    private static final String PREF = "il_redaktor_settings";
    private static final String KEY = "il_redaktor_oauth_aes_v1";
    private static final String TEXT_ZOOM_KEY = "ui_text_zoom";
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService aiWork = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ConcurrentHashMap<String, ChatGptPlanClient.SearchControl> searches = new java.util.concurrent.ConcurrentHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView editor;
    private Dialog publishedDialog;
    private AlertDialog editorConfirmation;
    private volatile boolean verified = false;
    private boolean loginInProgress = false;
    private boolean oauthBrowserWasOpened = false;
    private boolean oauthCallbackArrived = false;
    private boolean webPageReady = false;
    private final java.util.ArrayList<String> waitingJs = new java.util.ArrayList<>();
    private static final String HAPTICS_KEY = "haptics_intensity";
    private static final int RADAR_NOTIFICATION_PERMISSION = 1010;
    private String pendingRadarRequestId = "";
    private long lastOutcomeHapticAt = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        editor = new WebView(this);
        editor.setBackgroundColor(0xfff3f7fc);
        WebSettings settings = editor.getSettings();
        settings.setTextZoom(Math.max(80, Math.min(160, prefs().getInt(TEXT_ZOOM_KEY, 100))));
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        editor.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webPageReady = true;
                for (String code : waitingJs) view.evaluateJavascript(code, null);
                waitingJs.clear();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        // Without a WebChromeClient Android silently cancels JavaScript confirm().
        // Every confirmed write must wait for the user's real native response.
        editor.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (view != editor || !"file:///android_asset/index-android.html".equals(url) || isFinishing()) {
                    result.cancel();
                    return true;
                }
                if (editorConfirmation != null) editorConfirmation.dismiss();
                final boolean[] answered = {false};
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Potwierdź w Redaktorze IL")
                    .setMessage(message)
                    .setPositiveButton("Potwierdzam", (d, which) -> {
                        answered[0] = true;
                        result.confirm();
                    })
                    .setNegativeButton("Anuluj", (d, which) -> {
                        answered[0] = true;
                        result.cancel();
                    }).create();
                editorConfirmation = dialog;
                dialog.setOnDismissListener(d -> {
                    if (!answered[0]) { answered[0] = true; result.cancel(); }
                    if (editorConfirmation == dialog) editorConfirmation = null;
                });
                dialog.show();
                return true;
            }
        });
        editor.addJavascriptInterface(new Bridge(), "IL");
        setContentView(editor);
        editor.loadUrl("file:///android_asset/index-android.html");
        // Deep link may cold-start a new Activity after Android kills the app
        // while the system browser is in the foreground.
        handleOAuthCallback(getIntent());
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREF, MODE_PRIVATE); }
    private String clientId() { return prefs().getString("oauth21_client_id", ""); }
    private long expiry() { return prefs().getLong("expiry", 0L); }

    private void callback(String id, boolean success, String payload) {
        ui.post(() -> {
            if (editor != null) {
                String js = "window.onNativeResult(" + JSONObject.quote(id) + "," + success + ","
                    + JSONObject.quote(payload) + ")";
                if (webPageReady) editor.evaluateJavascript(js, null);
                else waitingJs.add(js);
            }
        });
    }

    private String getToken() {
        return readSecret("token");
    }

    private String readSecret(String preferenceKey) {
        try {
            String v = prefs().getString(preferenceKey, "");
            if (v.isEmpty()) return "";
            String[] parts = v.split(":");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEY, null);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) { return ""; }
    }

    private String encryptSecret(String value) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
            gen.generateKey();
        }
        SecretKey key = (SecretKey) ks.getKey(KEY, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private void putTokens(String accessToken, long expiresIn, String refreshToken, boolean clearMissingRefresh) throws Exception {
        if (accessToken == null || accessToken.trim().isEmpty()) throw new Exception("WordPress nie zwrócił tokenu dostępu.");
        long lifetime = Math.max(60L, expiresIn);
        long safetyMargin = Math.min(120L, Math.max(10L, lifetime / 10L));
        long expiryAt = System.currentTimeMillis() + Math.max(1L, lifetime - safetyMargin) * 1000L;
        SharedPreferences.Editor edit = prefs().edit()
            .putString("token", encryptSecret(accessToken))
            .putLong("expiry", expiryAt);
        if (refreshToken != null && !refreshToken.trim().isEmpty()) {
            edit.putString("refresh_token", encryptSecret(refreshToken));
        } else if (clearMissingRefresh) {
            edit.remove("refresh_token");
        }
        if (!edit.commit()) throw new Exception("Android nie zapisał bezpiecznie sesji WordPress.");
    }

    private void clearSession() {
        prefs().edit().remove("token").remove("refresh_token").remove("expiry").apply();
        verified = false;
        WpMcp.resetSession();
    }

    private boolean connected() {
        if (getToken().isEmpty()) return false;
        return expiry() > System.currentTimeMillis() || !readSecret("refresh_token").isEmpty();
    }

    private boolean isAuthenticationFailure(Exception error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("http 401") || message.contains("invalid_token") ||
            message.contains("token expired") || message.contains("unauthorized");
    }

    /** Returns a usable access token and rotates refresh credentials when WordPress supplies them. */
    private synchronized String accessTokenForRequest(boolean forceRefresh) throws Exception {
        String access = getToken();
        if (access.isEmpty()) throw new Exception("Brak zapisanej sesji WordPress.com. Zaloguj się ponownie.");
        if (!forceRefresh && expiry() > System.currentTimeMillis() + 15000L) return access;
        String refresh = readSecret("refresh_token");
        String registeredClient = clientId();
        if (refresh.isEmpty() || registeredClient.isEmpty()) {
            if (expiry() > System.currentTimeMillis()) return access;
            clearSession();
            throw new Exception("Sesja WordPress.com wygasła, a ta autoryzacja nie udostępniła tokenu odświeżającego. Zaloguj się ponownie.");
        }
        try {
            JSONObject refreshed = WpMcp.refresh(registeredClient, refresh);
            String nextAccess = refreshed.optString("access_token", "");
            if (nextAccess.isEmpty()) throw new Exception("WordPress nie zwrócił nowego tokenu podczas odświeżania sesji.");
            putTokens(nextAccess, refreshed.optLong("expires_in", 3600L), refreshed.optString("refresh_token", ""), false);
            return nextAccess;
        } catch (Exception ex) {
            String detail = ex.getMessage() == null ? "Nieznany błąd." : ex.getMessage();
            String lower = detail.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("invalid_grant") || lower.contains("http 400") || lower.contains("http 401")) {
                clearSession();
                throw new Exception("WordPress odrzucił token odświeżający. Połącz konto ponownie. Szczegóły: " + detail);
            }
            throw new Exception("Nie udało się odświeżyć sesji WordPress.com: " + detail);
        }
    }

    private static String explainVerificationFailure(Exception e) {
        String detail = e.getMessage() == null ? "Brak szczegółów." : e.getMessage();
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("required scope") && lower.contains("global") && lower.contains("auth"))
            return "AUTH_SCOPE: WordPress przyznał tylko podstawowy zakres auth. Dostęp redakcyjny MCP wymaga global. Otwórz zakładkę Połączenie i wybierz Zaloguj do WordPress.com, aby ponownie zatwierdzić szerszy zakres. Nie podawaj hasła w aplikacji.";
        if (lower.contains("http 401") || lower.contains("invalid_token") || lower.contains("unauthorized"))
            return "AUTH_401: Sesja WordPress.com jest nieważna lub wygasła. Rozłącz konto i zaloguj się ponownie. Szczegóły: " + detail;
        if (lower.contains("http 403") || lower.contains("forbidden") || lower.contains("permission"))
            return "AUTH_403: Konto nie ma dostępu do odczytu MCP. Sprawdź WordPress.com > AI i MCP > Odczyt > Content Authoring. Szczegóły: " + detail;
        if (lower.contains("http 404"))
            return "MCP_404: Serwer nie rozpoznał żądania MCP lub sesja wygasła. Spróbuj ponownie. Szczegóły: " + detail;
        if (lower.contains("timed out") || lower.contains("timeout") || lower.contains("unknownhost") ||
            lower.contains("failed to connect") || lower.contains("unable to resolve"))
            return "NET: Błąd sieci. Sprawdź dostęp do Internetu i ponów sprawdzenie. Szczegóły: " + detail;
        return "MCP: Nie można potwierdzić uprawnień. Szczegóły: " + detail;
    }

    private void verifyConnection() {
        if (!connected()) {
            callback("verify", false,
                "NO_SESSION: Brak zapisanej sesji WordPress.com. Zaloguj się w zakładce Połączenie. Publiczne artykuły możesz przeglądać bez logowania.");
            return;
        }
        work.execute(() -> {
            try {
                Object data;
                String token = accessTokenForRequest(false);
                try {
                    data = WpMcp.unwrap(WpMcp.request(token, "posts.list",
                        new JSONObject().put("per_page", 5).put("status", new org.json.JSONArray().put("publish"))));
                } catch (Exception first) {
                    if (!isAuthenticationFailure(first)) throw first;
                    token = accessTokenForRequest(true);
                    data = WpMcp.unwrap(WpMcp.request(token, "posts.list",
                        new JSONObject().put("per_page", 5).put("status", new org.json.JSONArray().put("publish"))));
                }
                if (!(data instanceof org.json.JSONArray))
                    throw new Exception("Serwer WordPress zwrócił nieprawidłowy format listy wpisów.");
                verified=true;
                callback("verify",true,"{\"connected\":true,\"articles\":"+((org.json.JSONArray)data).length()+"}");
            } catch(Exception ex) {
                verified=false;
                if (isAuthenticationFailure(ex)) clearSession();
                callback("verify",false,explainVerificationFailure(ex));
            }
        });
    }

    private OAuthBrowserLogin newOAuthLogin() {
        return new OAuthBrowserLogin(MainActivity.this, new OAuthBrowserLogin.Listener() {
            @Override public void status(String text) {
                callback("auth-progress", true, text);
            }
            @Override public void failure(String text) {
                loginInProgress = false;
                callback("auth-progress", false, text);
            }
            @Override public void success(String access, long expires, String refresh, String clientId) {
                loginInProgress = false;
                try {
                    putTokens(access, expires, refresh, true);
                    WpMcp.resetSession();
                    if (!prefs().edit().putString("oauth21_client_id", clientId).commit())
                        throw new Exception("Android nie zapisał identyfikatora połączenia.");
                    verified = false;
                    callback("auth", true, "{\"connected\":true}");
                    verifyConnection();
                } catch (Exception ex) {
                    callback("auth-progress", false,
                        "Nie udało się zapisać sesji WordPress.com: " +
                        (ex.getMessage() == null ? "błąd zabezpieczeń Android." : ex.getMessage()));
                }
            }
        });
    }

    private void handleOAuthCallback(Intent intent) {
        if (intent == null || !OAuthBrowserLogin.isCallback(intent.getData())) return;
        oauthCallbackArrived = true;
        loginInProgress = true;
        newOAuthLogin().handleRedirect(intent.getData());
    }

    private int hapticMode() {
        return Math.max(0, Math.min(3, prefs().getInt(HAPTICS_KEY, 1)));
    }

    private void vibrateFeedback(String kind) {
        int mode = hapticMode();
        if (mode == 0) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!"tap".equals(kind)) {
            if (now - lastOutcomeHapticAt < 600L) return;
            lastOutcomeHapticAt = now;
        }
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        int duration = mode == 1 ? 22 : mode == 2 ? 32 : 45;
        int amplitude = mode == 1 ? 100 : mode == 2 ? 180 : 255;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if ("success".equals(kind)) {
                    vibrator.vibrate(VibrationEffect.createWaveform(
                        new long[]{0,duration,75,duration * 2L},
                        new int[]{0,amplitude,0,amplitude}, -1));
                } else if ("error".equals(kind)) {
                    vibrator.vibrate(VibrationEffect.createWaveform(
                        new long[]{0,duration * 2L,85,duration * 2L},
                        new int[]{0,amplitude,0,amplitude}, -1));
                } else vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude));
            }
        } catch (RuntimeException ignored) { /* Vibration may be disabled by the device. */ }
    }

    private boolean hasVibrationMotor() {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        return vibrator != null && vibrator.hasVibrator();
    }

    private boolean backupPickerBusy=false;
    private PhotoEditor photoEditor;
    private DurableDraftStore durableDraftStore;
    private synchronized DurableDraftStore draftFiles(){if(durableDraftStore==null)durableDraftStore=new DurableDraftStore(new File(getFilesDir(),"il-drafts-v3.json"));return durableDraftStore;}
    private static final int BACKUP_MAX_BYTES=8_000_000;
    private EditorialMemory editorialDb;
    private synchronized EditorialMemory editorialDatabase() {
        if (editorialDb == null) editorialDb = new EditorialMemory(this);
        return editorialDb;
    }
    private boolean editorialBackupBusy = false;
    private static final int EDITORIAL_BACKUP_MAX = 6_000_000;

    public class Bridge {
        @JavascriptInterface public void chooseSourcePhoto(){ui.post(()->{try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,1008);}catch(Exception ex){callback("source-photo",false,"Nie można otworzyć wyboru zdjęcia dokumentu.");}});}
        @JavascriptInterface public void openResearchLink(String value){try{Uri u=Uri.parse(value);if(!("https".equals(u.getScheme())||"http".equals(u.getScheme()))||u.getHost()==null||u.getUserInfo()!=null)return;ui.post(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception ex){Toast.makeText(MainActivity.this,"Nie można otworzyć źródła w przeglądarce.",Toast.LENGTH_LONG).show();}});}catch(Exception ignored){}}
        @JavascriptInterface public void viewSourcePhoto(String encoded){if(encoded==null||encoded.length()>800000)return;work.execute(()->{try{byte[] bytes=Base64.decode(encoded,Base64.DEFAULT);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new Exception();BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth/options.inSampleSize,bounds.outHeight/options.inSampleSize)>1600)options.inSampleSize*=2;Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw new Exception();ui.post(()->{if(isFinishing()){image.recycle();return;}android.widget.ImageView view=new android.widget.ImageView(MainActivity.this);view.setImageBitmap(image);view.setAdjustViewBounds(true);view.setContentDescription("Zdjęcie dokumentu źródłowego");new AlertDialog.Builder(MainActivity.this).setTitle("Dokument źródłowy").setView(view).setPositiveButton("Zamknij",null).setOnDismissListener(d->{view.setImageDrawable(null);image.recycle();}).show();});}catch(Exception ex){ui.post(()->Toast.makeText(MainActivity.this,"Nie można odczytać zdjęcia dokumentu.",Toast.LENGTH_LONG).show());}});}

        /**
         * All SQL calls run on a worker, never on the WebView/UI thread.
         * There is no network, AI service or WordPress access here.
         */
        @JavascriptInterface public void editorialMemory(String rid, String operation, String data) {
            if (rid == null || !rid.matches("[A-Za-z0-9_-]{1,60}") ||
                data == null || data.length() > 100_000) return;
            if (!("save".equals(operation) || "browse".equals(operation) ||
                  "review".equals(operation) || "delete".equals(operation) ||
                  "history".equals(operation))) {
                callback(rid, false, "Nieobsługiwana funkcja pamięci."); return;
            }
            work.execute(() -> {
                try {
                    JSONObject result = editorialDatabase().execute(operation, new JSONObject(data));
                    callback(rid, true, result.toString());
                } catch (Exception ex) {
                    callback(rid, false, "Pamięć redakcyjna: " +
                        (ex.getMessage() == null ? "nie udało się odczytać danych." : ex.getMessage()));
                }
            });
        }

        @JavascriptInterface public void exportEditorialMemory() {
            ui.post(() -> {
                if (editorialBackupBusy) {
                    callback("editorial-export", false, "Kopia pamięci jest już przygotowywana."); return;
                }
                editorialBackupBusy = true;
                work.execute(() -> {
                    try {
                        byte[] json = editorialDatabase().execute("export", new JSONObject())
                            .toString(2).getBytes(StandardCharsets.UTF_8);
                        if (json.length > EDITORIAL_BACKUP_MAX)
                            throw new Exception("Archiwum przekracza 6 MB. Usuń zbędne stare wpisy.");
                        try (FileOutputStream output = new FileOutputStream(
                                new File(getCacheDir(), "il-editorial-memory-pending.json"))) {
                            output.write(json);
                        }
                        ui.post(() -> {
                            try {
                                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("application/json");
                                intent.putExtra(Intent.EXTRA_TITLE, "IL-pamiec-redakcyjna-" +
                                    new java.text.SimpleDateFormat("yyyy-MM-dd",
                                        java.util.Locale.ROOT).format(new java.util.Date()) + ".json");
                                startActivityForResult(intent, 1011);
                            } catch (Exception e) {
                                editorialBackupBusy = false;
                                callback("editorial-export", false,
                                    "Nie można otworzyć wyboru pliku kopii.");
                            }
                        });
                    } catch (Exception e) {
                        editorialBackupBusy = false;
                        callback("editorial-export", false, "Nie udało się przygotować archiwum: " +
                            (e.getMessage() == null ? "błąd zapisu" : e.getMessage()));
                    }
                });
            });
        }

        @JavascriptInterface public void importEditorialMemory() {
            ui.post(() -> {
                if (editorialBackupBusy) {
                    callback("editorial-import", false, "Trwa już eksport lub import pamięci."); return;
                }
                editorialBackupBusy = true;
                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    startActivityForResult(intent, 1012);
                } catch (Exception ex) {
                    editorialBackupBusy = false;
                    callback("editorial-import", false, "Nie można otworzyć pliku pamięci.");
                }
            });
        }

        @JavascriptInterface public String readDraftLibrary(){return draftFiles().read();}
        @JavascriptInterface public String writeDraftLibrary(String payload){return draftFiles().write(payload);}
        @JavascriptInterface public void exportDraftBackup(String payload) {
            if(payload==null||payload.getBytes(StandardCharsets.UTF_8).length>BACKUP_MAX_BYTES){callback("draft-backup",false,"Kopia przekracza limit 8 MB.");return;}
            try{JSONObject data=new JSONObject(payload);if(!"IL_REDAKTOR_BACKUP".equals(data.optString("format"))||!"ispinalokalnie.wordpress.com".equals(data.optString("site")))throw new Exception();}catch(Exception ex){callback("draft-backup",false,"Nieprawidłowy plik kopii.");return;}
            ui.post(()->{if(backupPickerBusy)return;backupPickerBusy=true;work.execute(()->{
                try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),"il-drafts-export-pending.json"))){out.write(payload.getBytes(StandardCharsets.UTF_8));}
                catch(Exception ex){ui.post(()->backupPickerBusy=false);callback("draft-backup",false,"Nie udało się przygotować kopii.");return;}
                ui.post(()->{try{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"IL-szkice-"+new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(new java.util.Date())+".json");startActivityForResult(i,1006);}catch(Exception ex){backupPickerBusy=false;new File(getCacheDir(),"il-drafts-export-pending.json").delete();callback("draft-backup",false,"Nie można otworzyć wyboru pliku.");}});
            });});
        }
        @JavascriptInterface public void importDraftBackup(){ui.post(()->{if(backupPickerBusy)return;backupPickerBusy=true;try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});startActivityForResult(i,1007);}catch(Exception ex){backupPickerBusy=false;callback("draft-import",false,"Nie można otworzyć wyboru pliku.");}});}
        @JavascriptInterface public void shareText(String text){if(text==null||text.length()>12000)return;ui.post(()->{try{Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,text);startActivity(Intent.createChooser(i,"Udostępnij zapowiedź artykułu"));}catch(Exception ex){callback("copy",false,"Nie znaleziono aplikacji do udostępnienia. Użyj Kopiuj.");}});}
        @JavascriptInterface public void shareChatGptFreePrompt(String text){
            if(text==null||text.trim().isEmpty()||text.length()>18000){callback("assistant-handoff",false,"Polecenie jest puste albo za długie.");return;}
            ui.post(()->{
                try{
                    android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(clipboard==null)throw new IllegalStateException("Brak schowka");
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Polecenie IL",text));
                    Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage("com.openai.chatgpt").putExtra(Intent.EXTRA_TEXT,text);
                    try{startActivity(send);}
                    catch(android.content.ActivityNotFoundException missing){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://chatgpt.com/")));}
                    callback("assistant-handoff",true,"Otwarto ChatGPT. Polecenie jest w schowku — wklej je i wyślij. Po otrzymaniu odpowiedzi skopiuj ją i wróć do Redaktora. Obowiązują limity Twojego konta.");
                }catch(RuntimeException ex){callback("assistant-handoff",false,"Nie udało się otworzyć ChatGPT. Użyj przycisku Kopiuj polecenie i otwórz chatgpt.com samodzielnie.");}
            });
        }

        @JavascriptInterface public void shareAssistantPrompt(String text){
            if(text==null||text.trim().isEmpty()||text.length()>18000){callback("assistant-handoff",false,"Polecenie jest puste albo za długie. Skróć je i spróbuj ponownie.");return;}
            ui.post(()->{
                Intent base=new Intent(Intent.ACTION_SEND);base.setType("text/plain");base.putExtra(Intent.EXTRA_TEXT,text);
                try{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Polecenie IL",text));}catch(RuntimeException ignored){/* The direct ACTION_SEND still carries the text. */}
                String[][] apps={{"com.openai.chatgpt","ChatGPT jest otwarty. Wklej polecenie, jeśli aplikacja o to poprosi; po uzyskaniu odpowiedzi wróć do Redaktora."},{"com.google.android.apps.bard","ChatGPT nie jest dostępny. Otworzyłem Gemini; wklej polecenie, jeśli aplikacja o to poprosi."}};
                for(String[] app:apps){
                    Intent target=new Intent(base);target.setPackage(app[0]);
                    try{if(target.resolveActivity(getPackageManager())!=null){startActivity(target);callback("assistant-handoff",true,app[1]);return;}}
                    catch(RuntimeException ignored){/* Try the next provider, then the system share sheet. */}
                }
                try{
                    android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Polecenie IL",text));
                    startActivity(Intent.createChooser(base,"ChatGPT lub Gemini — wybierz aplikację"));
                    callback("assistant-handoff",true,"Nie wykryłem odbiorcy ChatGPT/Gemini. Skopiowałem polecenie i otworzyłem pozostałe aplikacje do udostępniania; możesz też wkleić tekst ręcznie w ChatGPT lub Gemini.");
                }catch(RuntimeException ex){callback("assistant-handoff",false,"Nie można otworzyć aplikacji AI. Polecenie nie zostało wysłane.");}
            });
        }
        @JavascriptInterface public void readAssistantClipboard(){
            ui.post(()->{
                try{
                    android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    if(clipboard==null||!clipboard.hasPrimaryClip()||clipboard.getPrimaryClip()==null||clipboard.getPrimaryClip().getItemCount()==0){callback("assistant-clipboard",true,"{\"text\":\"\"}");return;}
                    CharSequence value=clipboard.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                    String text=value==null?"":value.toString();
                    if(text.length()>12000){callback("assistant-clipboard",false,"Odpowiedź ze schowka przekracza 12 000 znaków. Skopiuj krótszą propozycję.");return;}
                    callback("assistant-clipboard",true,new JSONObject().put("text",text).toString());
                }catch(Exception ex){callback("assistant-clipboard",false,"Nie udało się odczytać schowka. Skopiuj odpowiedź ponownie i spróbuj jeszcze raz.");}
            });
        }

        @JavascriptInterface public void getHaptics() {
            callback("haptics", true, "{\"mode\":" + hapticMode()
                + ",\"available\":" + hasVibrationMotor() + "}");
        }

        @JavascriptInterface public void testHaptics() {
            int mode = hapticMode();
            if (mode == 0) { callback("haptics-test", false, "Wibracje są wyłączone. Wybierz Delikatne, Normalne lub Mocniejsze."); return; }
            if (!hasVibrationMotor()) { callback("haptics-test", false, "Ten telefon nie zgłasza silnika wibracyjnego."); return; }
            ui.post(() -> {
                vibrateFeedback("success");
                callback("haptics-test", true, "Wysłano sygnał testowy. Jeśli go nie czujesz, sprawdź systemowe ustawienia wibracji telefonu.");
            });
        }

        @JavascriptInterface public void getTextZoom() {
            callback("text-zoom", true,
                "{\"value\":" + Math.max(80, Math.min(160, prefs().getInt(TEXT_ZOOM_KEY, 100))) + "}");
        }

        @JavascriptInterface public void setTextZoom(int percent) {
            if (percent < 80 || percent > 160 || percent % 5 != 0) {
                callback("text-zoom", false, "Wybierz rozmiar tekstu od 80% do 160%.");
                return;
            }
            if (!prefs().edit().putInt(TEXT_ZOOM_KEY, percent).commit()) {
                callback("text-zoom", false, "Nie udało się zapisać rozmiaru tekstu.");
                return;
            }
            ui.post(() -> {
                if (editor != null) editor.getSettings().setTextZoom(percent);
                callback("text-zoom", true, "{\"value\":" + percent + "}");
            });
        }

        @JavascriptInterface public void setHaptics(int mode) {
            if (mode < 0 || mode > 3) { callback("haptics", false, "Nieprawidłowe ustawienie."); return; }
            if (!prefs().edit().putInt(HAPTICS_KEY, mode).commit()) {
                callback("haptics", false, "Nie udało się zapisać ustawienia."); return;
            }
            callback("haptics", true, "{\"mode\":" + mode + "}");
            ui.post(() -> vibrateFeedback("tap"));
        }

        @JavascriptInterface public void haptic(String kind) {
            if (!("tap".equals(kind) || "success".equals(kind) || "error".equals(kind))) return;
            ui.post(() -> vibrateFeedback(kind));
        }

        @JavascriptInterface public void publicationSuccess() {
            ui.post(() -> {
                try {
                    AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
                    ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65);
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 350);
                    ui.postDelayed(tone::release, 700);
                } catch (RuntimeException ignored) { /* Visual confirmation remains available. */ }
            });
        }

        @JavascriptInterface public void loginState() {
            boolean pending = newOAuthLogin().hasPendingLogin();
            callback("login-state", true, "{\"pending\":" + pending + "}");
        }

        @JavascriptInterface public void finishLoginWithUrl(String raw) {
            if (raw == null || raw.length() > 4096) {
                callback("auth-progress", false, "Nieprawidłowy adres powrotu."); return;
            }
            try {
                Uri uri = Uri.parse(raw.trim());
                if (!OAuthBrowserLogin.isCallback(uri)) {
                    callback("auth-progress", false,
                        "Ten adres nie jest adresem powrotu Redaktora IL. Nie wklejaj tu hasła ani zwykłego linku WordPress.");
                    return;
                }
                if (!newOAuthLogin().hasPendingLogin()) {
                    callback("auth-progress", false, "Sesja logowania wygasła. Uruchom logowanie od nowa."); return;
                }
                ui.post(() -> newOAuthLogin().handleRedirect(uri));
            } catch (Exception ex) {
                callback("auth-progress", false, "Nie można odczytać adresu powrotu."); 
            }
        }

        @JavascriptInterface public void resumeLogin() {
            ui.post(() -> {
                if (!newOAuthLogin().hasPendingLogin()) {
                    callback("auth-progress", false,
                        "Poprzednia próba logowania wygasła. Rozpocznij nową.");
                    return;
                }
                newOAuthLogin().resumePendingLogin();
            });
        }

        @JavascriptInterface public void cancelLogin() {
            newOAuthLogin().cancelLogin();
            loginInProgress = false;
            callback("auth-cancel", true, "Anulowano oczekiwanie. Możesz rozpocząć logowanie ponownie.");
        }

        @JavascriptInterface public void copyText(String value) {
            ui.post(() -> {
                android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IL Redaktor Smart", value));
                callback("copy", true, "Skopiowano tekst.");
            });
        }

        @JavascriptInterface public void status() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("connected", connected());
                obj.put("verified", verified);
                obj.put("siteId", SITE);
            } catch (Exception ignored) {}
            callback("status", true, obj.toString());
        }

        @JavascriptInterface public void signInGoogle() {
            ui.post(() -> {
                if (newOAuthLogin().hasPendingLogin()) {
                    callback("auth-progress", true, "Oczekuję na zakończenie poprzedniego logowania. Użyj przycisku Anuluj i spróbuj ponownie, jeśli poprzednia próba utknęła.");
                    return;
                }
                if(loginInProgress) { callback("auth-progress",true,"Logowanie jest już w toku.");return; }
                loginInProgress=true;
                oauthCallbackArrived=false;
                newOAuthLogin().start();
            });
        }

        @JavascriptInterface public void assistantStatus() {
            callback("assistant-ready", true, new org.json.JSONObject().toString());
        }

        @JavascriptInterface public void verify() { verifyConnection(); }

        /** Public WordPress REST endpoint: no password, OAuth bearer, or write operation. */
        /**
         * Read-only connectivity diagnostics; never sends credentials and never writes
         * to WordPress. A reachable endpoint is not proof of authoring permissions.
         */
        @JavascriptInterface public void runDiagnostics(String rid) {
            if (rid == null || !rid.matches("[A-Za-z0-9_-]{1,60}")) return;
            work.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    result.put("wordpressToken", connected());
                    result.put("wordpressVerified", verified);
                    result.put("assistantConfigured", false);
                    result.put("publicWordPress", probePublicEndpoint(
                        "https://public-api.wordpress.com/wp/v2/sites/252442125/posts?per_page=1&_fields=id"));
                    result.put("googleNews", probePublicEndpoint(
                        "https://news.google.com/rss/search?q=Ispina&hl=pl&gl=PL&ceid=PL:pl"));
                    result.put("bing", probePublicEndpoint(
                        "https://www.bing.com/search?format=rss&q=Ispina"));
                    result.put("editorialMemory", editorialDatabase().execute("history", new JSONObject()).has("items"));
                    callback(rid, true, result.toString());
                } catch (Exception ex) {
                    callback(rid, false, "Nie udało się ukończyć diagnostyki: " +
                        (ex.getMessage() == null ? "błąd lokalny" : ex.getMessage()));
                }
            });
        }

        @JavascriptInterface public void publicPosts() { publicPostsPage(1); }
        @JavascriptInterface public void publicPostsPage(int page) {
            if (page < 1 || page > 100) {
                callback("public-posts-more", false, "Nieprawidłowa strona listy artykułów.");
                return;
            }
            final String event = page == 1 ? "public-posts" : "public-posts-more";
            work.execute(() -> {
                java.net.HttpURLConnection connection = null;
                try {
                    java.net.URL url = new java.net.URL(
                        "https://public-api.wordpress.com/wp/v2/sites/252442125/posts" +
                        "?per_page=30&page=" + page + "&orderby=modified&order=desc" +
                        "&_fields=id,title,excerpt,content,modified,status,link,featured_media");
                    connection = (java.net.HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(18000);
                    connection.setRequestProperty("Accept", "application/json");
                    int status = connection.getResponseCode();
                    if (page > 1 && status == 400) {
                        callback(event, true, "[]"); return;
                    }
                    if (status < 200 || status >= 300)
                        throw new Exception("Odczyt publicznych wpisów: HTTP " + status);
                    String reply = WpMcp.readReply(connection);
                    org.json.JSONArray posts = new org.json.JSONArray(reply);
                    if (posts.length() > 30) throw new Exception("Nieprawidłowa liczba wpisów.");
                    callback(event, true, posts.toString());
                } catch (Exception ex) {
                    String detail = ex.getMessage() == null ? "Błąd sieci." : ex.getMessage();
                    callback(event, false, "Nie udało się pobrać publicznych artykułów. " +
                        detail + ". Spróbuj ponownie albo otwórz portal w przeglądarce.");
                } finally {
                    if (connection != null) connection.disconnect();
                }
            });
        }

        @JavascriptInterface public void disconnect() {
            verified = false;
            clearSession();
            callback("auth", true, "{\"connected\":false}");
        }

        @JavascriptInterface public void request(String rid, String method, String path, String json) {
            if (rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            if (!("GET".equals(method) || "POST".equals(method))) {
                callback(rid, false, "Niedozwolona metoda."); return;
            }
            if (!path.matches("[A-Za-z0-9_?&=,./%+\\-]+") || path.contains("..")
                || path.startsWith("/") || path.contains("//")) {
                callback(rid, false, "Niedozwolona ścieżka API."); return;
            }
            if (!connected()) {
                callback(rid, false, "Sesja WordPress wygasła. Połącz konto ponownie."); return;
            }
            work.execute(() -> doRequest(rid, method, path, json));
        }

        @JavascriptInterface public void askAssistant(String rid, String prompt, String contextJson) {
            if (rid == null || !rid.matches("[A-Za-z0-9_-]{1,60}")) return;
            callback(rid, false, "Ta wersja działa bez płatnego AI. Użyj lokalnego formatowania lub ręcznie otwórz bezpłatny ChatGPT.");
        }

        @JavascriptInterface public void askResearch(String rid, String prompt) {
            if (rid == null || rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            if (prompt == null || prompt.trim().isEmpty() || prompt.length() > 1800) {
                callback(rid, false, "Wpisz temat wyszukiwania, maksymalnie 1800 znaków."); return;
            }
            ChatGptPlanClient.SearchControl control = new ChatGptPlanClient.SearchControl(MainActivity.this, 60000);
            searches.put(rid, control);
            aiWork.execute(() -> {
                try {
                    JSONObject result = FreeIspinaSearchClient.ask(MainActivity.this, prompt, control);
                    callback(rid, true, result.toString());
                } catch (Exception ex) {
                    callback(rid, false, ex.getMessage() == null ? "Nie udało się pobrać publicznych źródeł." : ex.getMessage());
                } finally { searches.remove(rid, control); }
            });
        }

        // User-selected lookback, not an unverified "last 30 days" label.
        @JavascriptInterface public void askResearchWithDays(String rid, String prompt, int days) {
            if (rid == null || !rid.matches("[A-Za-z0-9_-]{1,60}")) return;
            if (prompt == null || prompt.trim().isEmpty() || prompt.length() > 1800) {
                callback(rid, false, "Wpisz temat wyszukiwania, maksymalnie 1800 znaków."); return;
            }
            if (days != 0 && days != 7 && days != 30 && days != 90 && days != 365) {
                callback(rid, false, "Nieprawidłowy zakres dat."); return;
            }
            final long since = days == 0 ? 0L :
                System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(days);
            ChatGptPlanClient.SearchControl control = new ChatGptPlanClient.SearchControl(MainActivity.this, 60000);
            searches.put(rid, control);
            aiWork.execute(() -> {
                try {
                    JSONObject result = FreeIspinaSearchClient.search(MainActivity.this, prompt,
                        control, since, days != 0);
                    callback(rid, true, result.toString());
                } catch (Exception ex) {
                    callback(rid, false, ex.getMessage() == null ?
                        "Nie udało się pobrać publicznych źródeł." : ex.getMessage());
                } finally { searches.remove(rid, control); }
            });
        }

        @JavascriptInterface public void askIspinaChat(String rid, String prompt, String historyJson) {
            if (rid == null || rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            if (prompt == null || prompt.trim().isEmpty() || prompt.length() > 2000) {
                callback(rid, false, "Wpisz pytanie o Ispinę, maksymalnie 2000 znaków."); return;
            }
            org.json.JSONArray history;
            try {
                history = new org.json.JSONArray(historyJson == null ? "[]" : historyJson);
                if (history.length() > 8) {
                    callback(rid, false, "Historia rozmowy jest za długa. Wyczyść czat i spróbuj ponownie."); return;
                }
            } catch (Exception ex) {
                callback(rid, false, "Nieprawidłowa historia rozmowy."); return;
            }
            org.json.JSONArray safeHistory = history;
            ChatGptPlanClient.SearchControl control = new ChatGptPlanClient.SearchControl(MainActivity.this, 60000);
            searches.put(rid, control);
            aiWork.execute(() -> {
                try {
                    JSONObject result = FreeIspinaSearchClient.askConversation(MainActivity.this, prompt,
                        safeHistory, control);
                    callback(rid, true, result.toString());
                } catch (Exception ex) {
                    callback(rid, false, ex.getMessage() == null ? "Nie udało się pobrać publicznych źródeł." : ex.getMessage());
                } finally { searches.remove(rid, control); }
            });
        }

        @JavascriptInterface public void cancelIspinaSearch(String rid) {
            if (rid == null || !rid.matches("[A-Za-z0-9_-]{1,60}")) return;
            ChatGptPlanClient.SearchControl control = searches.remove(rid);
            if (control != null) control.cancel();
        }

        @JavascriptInterface public void getChatGptState(String rid) {
            if (rid == null || rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            callback(rid, true, ChatGptPlanClient.state(MainActivity.this).toString());
        }

        @JavascriptInterface public void connectChatGpt(String rid) {
            callback(rid, false, "Bezpłatny Radar nie wymaga logowania do ChatGPT.");
        }

        @JavascriptInterface public void disconnectChatGpt(String rid) {
            if (rid == null || rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            ui.post(() -> {
                prefs().edit().putBoolean(IspinaRadarWorker.PREF_ENABLED, false).apply();
                IspinaRadarWorker.cancel(MainActivity.this);
                new Thread(() -> {
                    try {
                    boolean revoked = ChatGptPlanClient.disconnect(MainActivity.this);
                    JSONObject state = ChatGptPlanClient.state(MainActivity.this);
                    try { state.put("revocationConfirmed", revoked); } catch (Exception ignored) {}
                    callback(rid, true, state.toString());
                    } catch (Exception ex) { callback(rid, false, ex.getMessage()); }
                }, "il-chatgpt-disconnect").start();
            });
        }

        @JavascriptInterface public void openChatGptUsage() {
            ui.post(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"));
                    intent.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(intent);
                } catch (Exception ex) {
                    Toast.makeText(MainActivity.this, "Nie udało się otworzyć ustawień użycia ChatGPT.", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface public void getIspinaRadarState(String rid) {
            if (rid == null || rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            callback(rid, true, ispinaRadarState().toString());
        }

        @JavascriptInterface public void setIspinaRadarEnabled(String rid, boolean enabled) {
            if (rid == null || rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            ui.post(() -> {
                if (!enabled) {
                    prefs().edit().putBoolean(IspinaRadarWorker.PREF_ENABLED, false).apply();
                    IspinaRadarWorker.cancel(MainActivity.this);
                    callback(rid, true, ispinaRadarState().toString());
                    return;
                }
                if (!notificationsAllowed()) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingRadarRequestId = rid;
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, RADAR_NOTIFICATION_PERMISSION);
                        return;
                    }
                    callback(rid, false, "Powiadomienia IL Redaktora są zablokowane w ustawieniach Androida. Włącz je dla aplikacji i spróbuj ponownie.");
                    return;
                }
                enableIspinaRadar(rid);
            });
        }

        @JavascriptInterface public void dictate() {
            ui.post(() -> {
                try {
                    Intent voice = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pl-PL");
                    voice.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT,
                        "Podyktuj treść dla Ispina Lokalnie");
                    startActivityForResult(voice, 1005);
                } catch (Exception ex) {
                    callback("dictate", false, "Na telefonie nie ma dostępnego rozpoznawania mowy.");
                }
            });
        }

        @JavascriptInterface public void chooseImage() {
            ui.post(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, 1004);
            });
        }

        @JavascriptInterface public void openSite(String target) {
            Uri safe = Uri.parse(target);
            String host = safe.getHost();
            if (!"https".equals(safe.getScheme()) || host == null || !(
                    host.equals("ispinalokalnie.wordpress.com") || host.equals("wordpress.com")
                    || host.endsWith(".wordpress.com") || host.equals("developer.wordpress.com"))) return;
            ui.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target))); }
                catch (Exception e) { callback("external", false, "Nie można otworzyć przeglądarki."); }
            });
        }

        @JavascriptInterface public void showPublishedPage(String target) {
            showPublishedPageInApp(target);
        }
    }

    private void doRequest(String rid, String method, String path, String body) {
        try {
            String token = accessTokenForRequest(false);
            Object value;
            try {
                value = WpMcp.mapRestCall(token,method,path,body);
            } catch (Exception first) {
                if (!isAuthenticationFailure(first)) throw first;
                token = accessTokenForRequest(true);
                value = WpMcp.mapRestCall(token,method,path,body);
            }
            callback(rid,true,value.toString());
        } catch(Exception ex) {
            if (isAuthenticationFailure(ex)) { verified=false; clearSession(); }
            callback(rid,false,"WordPress: "+(ex.getMessage()==null?"Nieznany błąd.":ex.getMessage()));
        }
    }

    private void askAssistantOnServer(String rid, String prompt, String contextJson) {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("prompt", prompt);
            try { payload.put("context", new JSONObject(contextJson == null ? "{}" : contextJson)); }
            catch (Exception ex) { throw new Exception("Nieprawidłowe dane kontekstu artykułów."); }
            String token = accessTokenForRequest(false);
            for (int attempt = 0; attempt < 2; attempt++) {
                connection = (HttpURLConnection) new URL(BuildConfig.ASSISTANT_ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                String response = readReply(connection, status);
                if (status == 401 && attempt == 0) {
                    connection.disconnect(); connection = null;
                    token = accessTokenForRequest(true);
                    continue;
                }
                if (status < 200 || status >= 300)
                    throw new Exception("Backend asystenta: HTTP " + status + ": " + friendlyError(response));
                JSONObject result = new JSONObject(response);
                if (!result.has("intent") || !result.has("message"))
                    throw new Exception("Backend nie zwrócił kompletnej propozycji.");
                callback(rid, true, result.toString());
                return;
            }
            throw new Exception("Nie udało się odświeżyć sesji WordPress dla Asystenta.");
        } catch (Exception ex) {
            if (isAuthenticationFailure(ex)) { verified = false; clearSession(); }
            callback(rid, false, ex.getMessage() == null ? "Nie udało się skontaktować z Asystentem." : ex.getMessage());
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void askResearchOnServer(String rid, String prompt) {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject().put("mode", "research").put("prompt", prompt);
            String token = accessTokenForRequest(false);
            for (int attempt = 0; attempt < 2; attempt++) {
                connection = (HttpURLConnection) new URL(BuildConfig.ASSISTANT_ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                String response = readReply(connection, status);
                if (status == 401 && attempt == 0) {
                    connection.disconnect(); connection = null;
                    token = accessTokenForRequest(true);
                    continue;
                }
                if (status < 200 || status >= 300)
                    throw new Exception("Wyszukiwanie internetowe: HTTP " + status + ": " + friendlyError(response));
                JSONObject result = new JSONObject(response);
                if (result.optString("text").trim().isEmpty() || result.optJSONArray("sources") == null
                    || result.optJSONArray("sources").length() == 0)
                    throw new Exception("Backend nie zwrócił raportu ze źródłami.");
                callback(rid, true, result.toString());
                return;
            }
            throw new Exception("Nie udało się odświeżyć sesji WordPress dla wyszukiwania.");
        } catch (Exception ex) {
            if (isAuthenticationFailure(ex)) { verified = false; clearSession(); }
            callback(rid, false, ex.getMessage() == null ? "Nie udało się wykonać wyszukiwania." : ex.getMessage());
        } finally { if (connection != null) connection.disconnect(); }
    }

    private void askIspinaChatOnServer(String rid, String prompt, String historyJson) {
        HttpURLConnection connection = null;
        try {
            org.json.JSONArray history = new org.json.JSONArray(historyJson == null ? "[]" : historyJson);
            if (history.length() > 8) throw new Exception("Historia rozmowy przekracza bezpieczny limit.");
            JSONObject payload = new JSONObject().put("mode", "chat").put("prompt", prompt).put("history", history);
            String token = accessTokenForRequest(false);
            for (int attempt = 0; attempt < 2; attempt++) {
                connection = (HttpURLConnection) new URL(BuildConfig.ASSISTANT_ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(60000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                String response = readReply(connection, status);
                if (status == 401 && attempt == 0) {
                    connection.disconnect(); connection = null;
                    token = accessTokenForRequest(true);
                    continue;
                }
                if (status < 200 || status >= 300)
                    throw new Exception("IL Chat AI: HTTP " + status + ": " + friendlyError(response));
                JSONObject result = new JSONObject(response);
                if (!result.has("verified") || result.optJSONArray("sources") == null)
                    throw new Exception("Radar nie zwrócił prawidłowego statusu i źródeł.");
                callback(rid, true, result.toString());
                return;
            }
            throw new Exception("Nie udało się odświeżyć sesji WordPress dla IL Chat AI.");
        } catch (Exception ex) {
            if (isAuthenticationFailure(ex)) { verified = false; clearSession(); }
            callback(rid, false, ex.getMessage() == null ? "Nie udało się połączyć z IL Chat AI." : ex.getMessage());
        } finally { if (connection != null) connection.disconnect(); }
    }

    private String friendlyError(String result) {
        try {
            JSONObject j = new JSONObject(result);
            return j.optString("message", j.optString("error", result)).replaceAll("<[^>]*>", " ");
        } catch (Exception ex) {
            return result.substring(0, Math.min(380, result.length()));
        }
    }

    private String readReply(HttpURLConnection c, int status) throws Exception {
        InputStream source = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (source == null) return "{}";
        try (InputStream in = source; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int n;
            while ((n = in.read(block)) != -1) {
                out.write(block, 0, n);
                if (out.size() > 2_000_000) throw new Exception("Zbyt duża odpowiedź API.");
            }
            return out.toString("UTF-8");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == 1012) {
            if (result != RESULT_OK || intent == null || intent.getData() == null) {
                editorialBackupBusy = false;
                callback("editorial-import", false, "Anulowano import pamięci. Dane pozostały bez zmian.");
                return;
            }
            Uri selected = intent.getData();
            work.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(selected);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    if (in == null) throw new Exception("Nie można otworzyć kopii.");
                    byte[] buf = new byte[8192]; int count;
                    while ((count = in.read(buf)) != -1) {
                        if (out.size() + count > EDITORIAL_BACKUP_MAX)
                            throw new Exception("Archiwum przekracza limit 6 MB.");
                        out.write(buf, 0, count);
                    }
                    JSONObject report = editorialDatabase().execute("import",
                        new JSONObject(out.toString("UTF-8")));
                    callback("editorial-import", true, report.toString());
                } catch (Exception ex) {
                    callback("editorial-import", false,
                        "Import nie powiódł się, istniejące dane zachowano: " +
                        (ex.getMessage() == null ? "błąd pliku" : ex.getMessage()));
                } finally {editorialBackupBusy = false;}
            });
            return;
        }
        if (request == 1011) {
            editorialBackupBusy = false;
            final File pending = new File(getCacheDir(), "il-editorial-memory-pending.json");
            if (result != RESULT_OK || intent == null || intent.getData() == null) {
                pending.delete();
                callback("editorial-export", false, "Anulowano zapis kopii pamięci.");
                return;
            }
            final Uri selected = intent.getData();
            work.execute(() -> {
                try (InputStream in = new FileInputStream(pending);
                     OutputStream out = getContentResolver().openOutputStream(selected, "wt")) {
                    if (out == null) throw new Exception("Nie można zapisać wybranego pliku.");
                    byte[] buffer = new byte[8192]; int bytes;
                    while ((bytes = in.read(buffer)) != -1) out.write(buffer, 0, bytes);
                    out.flush();
                    callback("editorial-export", true,
                        "Kopia pamięci zapisana. Zawiera historię wyszukiwania i źródła, bez tokenów.");
                } catch (Exception ex) {
                    callback("editorial-export", false, "Nie zapisano kopii: " +
                        (ex.getMessage() == null ? "sprawdź lokalizację" : ex.getMessage()));
                } finally { pending.delete(); }
            });
            return;
        }
        if(request==1008){
            if(result!=RESULT_OK||intent==null||intent.getData()==null){callback("source-photo",false,"Anulowano dodawanie zdjęcia. Źródła pozostają bez zmian.");return;}
            Uri uri=intent.getData();work.execute(()->{Bitmap original=null,scaled=null;try{original=PhotoEditor.decode(MainActivity.this,uri);int max=Math.max(original.getWidth(),original.getHeight());scaled=max>1024?Bitmap.createScaledBitmap(original,Math.max(1,Math.round(original.getWidth()*1024f/max)),Math.max(1,Math.round(original.getHeight()*1024f/max)),true):original;ByteArrayOutputStream out=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,75,out);if(out.size()>600000)throw new Exception("Zdjęcie dokumentu przekracza 600 KB. Wybierz mniejszy fragment.");callback("source-photo",true,new JSONObject().put("mime","image/jpeg").put("name","Zdjęcie dokumentu").put("base64",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)).toString());}catch(Exception ex){callback("source-photo",false,"Nie udało się zachować zdjęcia dokumentu. "+(ex.getMessage()==null?"Wybierz inny plik.":ex.getMessage()));}finally{if(scaled!=null&&scaled!=original&&!scaled.isRecycled())scaled.recycle();if(original!=null&&!original.isRecycled())original.recycle();}});return;
        }
        if(request==1006||request==1007){
            backupPickerBusy=false;
            if(result!=RESULT_OK||intent==null||intent.getData()==null){if(request==1006)new File(getCacheDir(),"il-drafts-export-pending.json").delete();callback(request==1006?"draft-backup":"draft-import",false,"Anulowano wybór pliku. Szkice pozostają bez zmian.");return;}
            Uri selected=intent.getData();work.execute(()->{
                File pending=new File(getCacheDir(),"il-drafts-export-pending.json");
                try{
                    if(request==1006){try(InputStream in=new FileInputStream(pending);OutputStream out=getContentResolver().openOutputStream(selected,"wt")){if(out==null)throw new Exception();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);out.flush();}callback("draft-backup",true,"Zapisano plik kopii szkiców. Możesz przenieść go na drugi telefon.");}
                    else{try(InputStream in=getContentResolver().openInputStream(selected);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new Exception();byte[] buffer=new byte[8192];int count,total=0;while((count=in.read(buffer))!=-1){total+=count;if(total>BACKUP_MAX_BYTES)throw new Exception("Plik przekracza limit 8 MB.");out.write(buffer,0,count);}callback("draft-import",true,out.toString("UTF-8"));}}
                }catch(Exception ex){callback(request==1006?"draft-backup":"draft-import",false,"Nie udało się "+(request==1006?"zapisać":"odczytać")+" pliku kopii. "+(ex.getMessage()==null?"Sprawdź dostępne miejsce i wybór pliku.":ex.getMessage()));}
                finally{if(request==1006)pending.delete();}
            });return;
        }
        if (request == 1005 && result == RESULT_OK && intent != null) {
            java.util.ArrayList<String> matches =
                intent.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) callback("dictate", true, matches.get(0));
        }
        if (request == 1004 && result == RESULT_OK && intent != null && intent.getData() != null) {
            if (!connected()) { callback("upload", false, "Najpierw połącz konto."); return; }
            Uri uri = intent.getData();
            work.execute(() -> {
                try{Bitmap bitmap=PhotoEditor.decode(MainActivity.this,uri);ui.post(()->{if(isFinishing()){bitmap.recycle();return;}photoEditor=new PhotoEditor(MainActivity.this,bitmap,(edited,caption,author,alt)->work.execute(()->upload(edited,caption,author,alt)));photoEditor.show();if(photoEditor.getWindow()!=null)photoEditor.getWindow().setLayout(-1,-1);});}
                catch(Exception ex){callback("upload",false,"Nie udało się przygotować zdjęcia: "+ex.getMessage());}
            });
        }
    }

    /**
     * Uploads through the SAME authenticated MCP connection as posts.
     * No implicit REST fallback and no retry of an uncertain creation.
     */
    private void upload(Bitmap bitmap,String caption,String author,String alt) {
        try {
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
            bitmap.recycle();
            byte[] bytes = jpeg.toByteArray();
            if (bytes.length == 0 || bytes.length > 6_000_000)
                throw new Exception("Zdjęcie jest zbyt duże. Wybierz mniejsze.");
            JSONObject args = new JSONObject()
                .put("wpcom_site", WpMcp.SITE)
                .put("filename", "il-redaktor-" + System.currentTimeMillis() + ".jpg")
                .put("mime_type", "image/jpeg")
                .put("file_content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("user_confirmed", true);
            String fullCaption=caption+(author.isEmpty()?"":(caption.isEmpty()?"":" · ")+"Fot. "+author);
            args.put("caption",android.text.TextUtils.htmlEncode(fullCaption));
            args.put("alt_text",alt);
            args.put("description",android.text.TextUtils.htmlEncode(author.isEmpty()?"":"Autor / źródło zdjęcia: "+author));
            String token = accessTokenForRequest(false);
            Object result = WpMcp.unwrap(WpMcp.request(token, "media.create", args));
            if (!(result instanceof JSONObject))
                throw new Exception("WordPress nie zwrócił potwierdzenia zdjęcia.");
            JSONObject image = (JSONObject) result;
            int id = image.optInt("id", 0);
            if (id <= 0)
                throw new Exception("Zdjęcie mogło zostać przesłane, ale nie ma ID. Sprawdź bibliotekę przed ponowieniem.");
            try {
                Object check = WpMcp.unwrap(WpMcp.request(token, "media.get",
                    new JSONObject().put("id", id)));
                if (!(check instanceof JSONObject) || ((JSONObject) check).optInt("id", 0) != id)
                    throw new Exception("Nie znaleziono przesłanego zdjęcia.");
            } catch (Exception verification) {
                callback("upload", false, "Zdjęcie ma ID " + id +
                    ", ale nie udało się sprawdzić jego zapisu. Odszukaj je w galerii przed ponownym wysłaniem. " +
                    verification.getMessage());
                return;
            }
            callback("upload", true, image.toString());
        } catch (Exception ex) {
            callback("upload", false, "Przesyłanie zdjęcia przez WordPress MCP: " +
                (ex.getMessage() == null ? "Nieznany błąd." : ex.getMessage()));
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isPublishedSiteUrl(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equals(uri.getScheme()) &&
                "ispinalokalnie.wordpress.com".equals(uri.getHost()) &&
                uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception ignored) { return false; }
    }

    private static String probePublicEndpoint(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new java.net.URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, application/rss+xml, application/xml");
            int status = connection.getResponseCode();
            return status >= 200 && status < 300 ? "DOSTĘPNE" : "HTTP " + status;
        } catch (Exception ex) {
            return "NIEDOSTĘPNE (sieć lub serwer)";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void openExternalPage(String value) {
        try {
            Uri uri = Uri.parse(value);
            if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null)
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) { }
    }

    private void showPublishedPageInApp(String target) {
        if (!isPublishedSiteUrl(target)) {
            ui.post(() -> Toast.makeText(this, "Adres wpisu nie należy do portalu Ispina Lokalnie.", Toast.LENGTH_LONG).show());
            return;
        }
        ui.post(() -> {
            if (isFinishing()) return;
            if (publishedDialog != null && publishedDialog.isShowing()) publishedDialog.dismiss();

            Dialog dialog = new Dialog(this);
            publishedDialog = dialog;
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(12), dp(14), dp(10));
            root.setBackgroundColor(0xfff3f7fc);

            TextView heading = new TextView(this);
            heading.setText("Opublikowany wpis · Ispina Lokalnie");
            heading.setTextColor(0xff174875);
            heading.setTextSize(19);
            heading.setTypeface(null, Typeface.BOLD);
            heading.setPadding(dp(2), dp(3), dp(2), dp(8));
            root.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView status = new TextView(this);
            status.setText("Łączę się ze stroną i wczytuję opublikowaną treść…");
            status.setTextColor(0xff38546d);
            status.setTextSize(14);
            status.setGravity(Gravity.CENTER_VERTICAL);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

            Button refresh = new Button(this);
            refresh.setText("Odśwież");
            refresh.setAllCaps(false);
            LinearLayout statusRow = new LinearLayout(this);
            statusRow.setGravity(Gravity.CENTER_VERTICAL);
            statusRow.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            statusRow.addView(refresh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            WebView page = new WebView(this);
            page.setBackgroundColor(Color.WHITE);
            WebSettings settings = page.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            page.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    status.setText("Wczytuję opublikowaną stronę. Za chwilę zobaczysz jej rzeczywisty wygląd.");
                }

                @Override public void onPageFinished(WebView view, String url) {
                    status.setText("Wpis wyświetlony z portalu IL. Jeśli widzisz starszą wersję, wybierz „Odśwież”.");
                }

                @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if (isPublishedSiteUrl(uri.toString())) return false;
                    if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null)
                        openExternalPage(uri.toString());
                    return true;
                }

                @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                                      android.webkit.WebResourceError error) {
                    if (request.isForMainFrame())
                        status.setText("Nie udało się wczytać wpisu. Sprawdź połączenie z Internetem i wybierz „Odśwież”.");
                }

                @Override public void onReceivedHttpError(WebView view, android.webkit.WebResourceRequest request,
                                                          android.webkit.WebResourceResponse response) {
                    if (request.isForMainFrame() && response.getStatusCode() >= 400)
                        status.setText("Strona zwróciła błąd HTTP " + response.getStatusCode() + ". Możesz spróbować odświeżyć wpis.");
                }
            });
            root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            Button browser = new Button(this);
            browser.setText("Otwórz w przeglądarce");
            browser.setAllCaps(false);
            Button close = new Button(this);
            close.setText("Wróć do Redaktora");
            close.setAllCaps(false);
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.addView(browser, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            actions.addView(close, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            root.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            refresh.setOnClickListener(v -> {
                status.setText("Odświeżam opublikowaną stronę…");
                page.reload();
            });
            browser.setOnClickListener(v -> openExternalPage(target));
            close.setOnClickListener(v -> dialog.dismiss());
            dialog.setContentView(root);
            dialog.setCancelable(true);
            dialog.setOnDismissListener(ignored -> {
                page.stopLoading();
                page.destroy();
                if (publishedDialog == dialog) publishedDialog = null;
            });
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(0xfff3f7fc));
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            }
            page.loadUrl(target);
        });
    }

    private boolean notificationsAllowed() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return false;
        android.app.NotificationManager manager =
            (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    private JSONObject ispinaRadarState() {
        JSONObject state = new JSONObject();
        try {
            state.put("enabled", prefs().getBoolean(IspinaRadarWorker.PREF_ENABLED, false));
            state.put("intervalHours", 8);
            state.put("lastChecked", prefs().getLong(IspinaRadarWorker.PREF_LAST_CHECKED, 0L));
            state.put("lastStatus", prefs().getString(IspinaRadarWorker.PREF_LAST_STATUS, "idle"));
            state.put("lastMessage", prefs().getString(IspinaRadarWorker.PREF_LAST_MESSAGE, ""));
        } catch (Exception ignored) { }
        return state;
    }

    private void enableIspinaRadar(String rid) {
        if (!notificationsAllowed()) { callback(rid, false, "Android nie zezwala na powiadomienia IL Redaktora."); return; }
        try {
            IspinaRadarWorker.ensureNotificationChannel(this);
            if (!prefs().edit().putBoolean(IspinaRadarWorker.PREF_ENABLED, true).commit())
                throw new Exception("Nie udało się zapisać ustawienia radaru.");
            IspinaRadarWorker.schedule(this);
            callback(rid, true, ispinaRadarState().toString());
        } catch (Exception ex) {
            prefs().edit().putBoolean(IspinaRadarWorker.PREF_ENABLED, false).commit();
            IspinaRadarWorker.cancel(this);
            callback(rid, false, ex.getMessage() == null ? "Nie udało się zaplanować radaru." : ex.getMessage());
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != RADAR_NOTIFICATION_PERMISSION) return;
        String rid = pendingRadarRequestId;
        pendingRadarRequestId = "";
        if (rid.isEmpty()) return;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            enableIspinaRadar(rid);
        else callback(rid, false, "Nie włączono radaru, bo Android nie przyznał zgody na powiadomienia.");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOAuthCallback(intent);
    }

    @Override public void onBackPressed() {
        if (publishedDialog != null && publishedDialog.isShowing()) {
            publishedDialog.dismiss();
            return;
        }
        if (editor == null || !webPageReady) { super.onBackPressed(); return; }
        // Do not destroy the editor until the native AtomicFile store has answered.
        editor.evaluateJavascript("typeof flushDraftOnExit==='function' ? flushDraftOnExit() : true", value -> {
            if (!"true".equals(value)) {
                Toast.makeText(this, "Nie zapisano szkicu. Skopiuj tekst przed wyjściem.", Toast.LENGTH_LONG).show();
                return;
            }
            if (editor != null && editor.canGoBack()) editor.goBack();
            else MainActivity.super.onBackPressed();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (oauthBrowserWasOpened) {
            oauthBrowserWasOpened = false;
            ui.postDelayed(() -> {
                if (!oauthCallbackArrived && newOAuthLogin().hasPendingLogin()) {
                    callback("auth-return", true,
                        "Wrócono z przeglądarki bez potwierdzenia WordPress.com. " +
                        "Otwórz ponownie logowanie i zaakceptuj dostęp albo anuluj tę próbę.");
                }
            }, 1000);
        }
    }

    @Override protected void onPause() {
        if (newOAuthLogin().hasPendingLogin()) oauthBrowserWasOpened = true;
        if (editor != null && webPageReady) editor.evaluateJavascript("if(typeof flushDraftOnExit==='function')flushDraftOnExit()", null);
        super.onPause();
    }

    @Override protected void onStop() {
        if (editor != null && webPageReady) editor.evaluateJavascript("if(typeof flushDraftOnExit==='function')flushDraftOnExit()", null);
        super.onStop();
    }

    @Override protected void onDestroy() {
        if(photoEditor!=null&&photoEditor.isShowing())photoEditor.dismiss();
        if (editorConfirmation != null) editorConfirmation.dismiss();
        for (ChatGptPlanClient.SearchControl control : searches.values()) control.cancel();
        searches.clear();
        if (editorialDb != null) editorialDb.close();
        aiWork.shutdownNow();
        work.shutdownNow();
        if (publishedDialog != null && publishedDialog.isShowing()) publishedDialog.dismiss();
        if (editor != null) editor.destroy();
        super.onDestroy();
    }
}
