package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
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
    private static final String API = "https://public-api.wordpress.com/wp/v2/sites/252442125/";
    private static final String SITE = "252442125";
    private static final String PREF = "il_redaktor_settings";
    private static final String KEY = "il_redaktor_oauth_aes_v1";
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView editor;
    private volatile boolean verified = false;
    private boolean loginInProgress = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        editor = new WebView(this);
        editor.setBackgroundColor(0xfff3f7fc);
        WebSettings settings = editor.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        editor.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        editor.addJavascriptInterface(new Bridge(), "IL");
        setContentView(editor);
        editor.loadUrl("file:///android_asset/index.html");
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
                editor.evaluateJavascript(js, null);
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

    private void verifyConnection() {
        if (!connected()) { callback("verify", false, "Brak aktywnej sesji WordPressa."); return; }
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
                if (!(data instanceof org.json.JSONArray) || ((org.json.JSONArray)data).length() == 0)
                    throw new Exception("WordPress przyjął logowanie, ale nie zwrócił żadnego istniejącego opublikowanego artykułu dla tej witryny.");
                verified=true;
                callback("verify",true,"{\"connected\":true,\"articles\":"+((org.json.JSONArray)data).length()+"}");
            } catch(Exception ex) {
                verified=false;
                if (isAuthenticationFailure(ex)) clearSession();
                callback("verify",false,ex.getMessage()==null?"Nie udało się zweryfikować dostępu do wpisów.":ex.getMessage());
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
        loginInProgress = true;
        newOAuthLogin().handleRedirect(intent.getData());
    }

    public class Bridge {
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
                if(loginInProgress) { callback("auth-progress",true,"Logowanie jest już w toku.");return; }
                loginInProgress=true;
                newOAuthLogin().start();
            });
        }

        @JavascriptInterface public void assistantStatus() {
            callback("assistant-ready", true,
                BuildConfig.ASSISTANT_ENDPOINT != null && !BuildConfig.ASSISTANT_ENDPOINT.trim().isEmpty()
                    ? "{\"enabled\":true}" : "{\"enabled\":false}");
        }

        @JavascriptInterface public void verify() { verifyConnection(); }

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
            if (rid.length() > 60 || !rid.matches("[A-Za-z0-9_-]+")) return;
            if (prompt == null || prompt.trim().isEmpty() || prompt.length() > 6000) {
                callback(rid, false, "Opisz zadanie krócej niż 6000 znaków."); return;
            }
            if (!connected()) { callback(rid, false, "Połącz konto WordPress, aby użyć Asystenta IL."); return; }
            if (BuildConfig.ASSISTANT_ENDPOINT == null || BuildConfig.ASSISTANT_ENDPOINT.trim().isEmpty()) {
                callback(rid, false, "Tryb AI nie jest jeszcze skonfigurowany. Backend Gemini wymaga osobnego wdrożenia i klucza API po stronie serwera. Możesz nadal korzystać z szablonów, korekty i wskazówek SEO w aplikacji."); return;
            }
            work.execute(() -> askAssistantOnServer(rid, prompt, contextJson));
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
            callback(rid,false,"WordPress MCP: "+(ex.getMessage()==null?"Nieznany błąd.":ex.getMessage()));
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
        if (request == 1005 && result == RESULT_OK && intent != null) {
            java.util.ArrayList<String> matches =
                intent.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (matches != null && !matches.isEmpty()) callback("dictate", true, matches.get(0));
        }
        if (request == 1004 && result == RESULT_OK && intent != null && intent.getData() != null) {
            if (!connected()) { callback("upload", false, "Najpierw połącz konto."); return; }
            Uri uri = intent.getData();
            work.execute(() -> upload(uri));
        }
    }

    private void upload(Uri uri) {
        HttpURLConnection c = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                throw new Exception("Nie rozpoznano zdjęcia.");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth / options.inSampleSize,
                    bounds.outHeight / options.inSampleSize) > 1800) options.inSampleSize *= 2;
            Bitmap bitmap;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) throw new Exception("Nie można odczytać obrazu.");
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, jpeg);
            bitmap.recycle();
            byte[] bytes = jpeg.toByteArray();
            if (bytes.length > 8_000_000) throw new Exception("Zdjęcie jest za duże.");
            for (int attempt = 0; attempt < 2; attempt++) {
                c = (HttpURLConnection) new URL(API + "media").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(17000);
                c.setReadTimeout(35000);
                c.setRequestProperty("Authorization", "Bearer " + accessTokenForRequest(attempt > 0));
                c.setRequestProperty("Content-Type", "image/jpeg");
                c.setRequestProperty("Content-Disposition", "attachment; filename=\"il-redaktor.jpg\"");
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
                int code = c.getResponseCode();
                String body = readReply(c, code);
                if (code == 401 && attempt == 0) {
                    c.disconnect();
                    c = null;
                    continue;
                }
                if (code >= 200 && code < 300) callback("upload", true, body);
                else {
                    if (code == 401) { clearSession(); callback("upload", false, "Sesja WordPress.com wygasła. Zaloguj konto ponownie."); }
                    else callback("upload", false, "Przesyłanie: HTTP " + code + ": " + friendlyError(body));
                }
                break;
            }
        } catch (Exception ex) { callback("upload", false, ex.getMessage()); }
        finally { if (c != null) c.disconnect(); }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOAuthCallback(intent);
    }

    @Override public void onBackPressed() {
        if (editor.canGoBack()) editor.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        work.shutdownNow();
        if (editor != null) editor.destroy();
        super.onDestroy();
    }
}
