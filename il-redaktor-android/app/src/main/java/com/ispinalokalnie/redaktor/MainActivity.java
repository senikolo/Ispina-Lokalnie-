package com.ispinalokalnie.redaktor;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
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
 * OAuth runs in a SEPARATE WebView with no bridge. Tokens are encrypted with
 * Android Keystore and are NEVER committed to source control or logged.
 */
public class MainActivity extends Activity {
    private static final String API = "https://public-api.wordpress.com/wp/v2/sites/252442125/";
    private static final String REDIRECT = "https://ispinalokalnie.wordpress.com/il-redaktor-auth/";
    private static final String SITE = "252442125";
    private static final String PREF = "il_redaktor_settings";
    private static final String KEY = "il_redaktor_oauth_aes_v1";
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private WebView editor;
    private AlertDialog loginDialog;
    private String oauthState = "";
    private volatile boolean verified = false;
    private boolean loginInProgress = false;
    private boolean mcpMode(){ return "mcp21".equals(prefs().getString("auth_mode", "legacy")); }

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
        editor.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return guard(r.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return guard(url);
            }
            private boolean guard(String url) {
                if (url.startsWith("file:///android_asset/")) return false;
                return true;
            }
        });
        editor.addJavascriptInterface(new Bridge(), "IL");
        setContentView(editor);
        editor.loadUrl("file:///android_asset/index.html");
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREF, MODE_PRIVATE); }
    private String clientId() { return prefs().getString("oauth_client_id", ""); }
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
        try {
            String v = prefs().getString("token", "");
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

    private void putToken(String token, long expiresIn) throws Exception {
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
        String val = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        long exp = System.currentTimeMillis() + Math.max(60L, expiresIn - 120L) * 1000;
        prefs().edit().putString("token", val).putLong("expiry", exp).apply();
    }

    private boolean connected() {
        return expiry() > System.currentTimeMillis() && !getToken().isEmpty();
    }

    private void verifyConnection() {
        if (!connected()) { callback("verify", false, "Brak aktywnej sesji WordPressa."); return; }
        if (mcpMode()) {
            work.execute(() -> {
                try {
                    Object data = WpMcp.unwrap(WpMcp.request(getToken(), "posts.list",
                        new JSONObject().put("per_page",1).put("status",new org.json.JSONArray().put("publish"))));
                    if (!(data instanceof org.json.JSONArray)) throw new Exception("WordPress nie zwrócił listy artykułów.");
                    verified=true;
                    callback("verify",true,"{\"connected\":true}");
                } catch(Exception ex) {
                    verified=false;
                    if (ex.getMessage()!=null && ex.getMessage().contains("HTTP 401"))
                        prefs().edit().remove("token").remove("expiry").apply();
                    callback("verify",false,ex.getMessage());
                }
            });
            return;
        }
        work.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(API + "posts?context=edit&per_page=1").openConnection();
                c.setRequestProperty("Authorization", "Bearer " + getToken());
                c.setConnectTimeout(15000); c.setReadTimeout(18000);
                int code = c.getResponseCode();
                String reply = readReply(c, code);
                if (code >= 200 && code < 300 && reply.trim().startsWith("[")) {
                    verified = true;
                    callback("verify", true, "{\"connected\":true}");
                } else {
                    verified = false;
                    if (code == 401 || code == 403) {
                        prefs().edit().remove("token").remove("expiry").apply();
                    }
                    callback("verify", false, "WordPress nie potwierdził dostępu: HTTP " + code + ". " + friendlyError(reply));
                }
            } catch (Exception ex) { verified=false; callback("verify",false,"Nie udało się sprawdzić konta: "+ex.getMessage()); }
            finally { if (c != null) c.disconnect(); }
        });
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
                obj.put("clientId", clientId());
                obj.put("siteId", SITE);
            } catch (Exception ignored) {}
            callback("status", true, obj.toString());
        }

        @JavascriptInterface public void signInGoogle() {
            ui.post(() -> {
                if(loginInProgress) { callback("auth-progress",true,"Logowanie jest już w toku.");return; }
                loginInProgress=true;
                new OAuthBrowserLogin(MainActivity.this,new OAuthBrowserLogin.Listener(){
                    @Override public void status(String text){callback("auth-progress",true,text);}
                    @Override public void failure(String text){
                        loginInProgress=false;
                        callback("auth-progress",false,text);
                    }
                    @Override public void success(String access,long expires,String refresh,String clientId){
                        loginInProgress=false;
                        try{
                            putToken(access,expires);
                            prefs().edit().putString("auth_mode","mcp21").apply();
                            verified=false;
                            callback("auth",true,"{\"connected\":true}");
                            verifyConnection();
                        } catch(Exception ex){
                            callback("auth-progress",false,"Nie udało się bezpiecznie zapisać logowania.");
                        }
                    }
                }).start();
            });
        }

        @JavascriptInterface public void connect(String suppliedClientId) {
            ui.post(() -> {
                String id = suppliedClientId.trim();
                if (!id.matches("[0-9]{2,20}")) {
                    callback("auth", false, "Wpisz numeryczny Client ID z WordPress.com Applications.");
                    return;
                }
                prefs().edit().putString("oauth_client_id", id).apply();
                verified = false;
                startOAuth(id);
            });
        }

        @JavascriptInterface public void verify() { verifyConnection(); }

        @JavascriptInterface public void disconnect() {
            verified = false;
            prefs().edit().remove("token").remove("expiry").remove("auth_mode").apply();
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

    private void startOAuth(String id) {
        oauthState = nonce();
        String auth = "https://public-api.wordpress.com/oauth2/authorize" +
            "?client_id=" + Uri.encode(id) +
            "&redirect_uri=" + Uri.encode(REDIRECT) +
            "&response_type=token&scope=" + Uri.encode("posts media taxonomy") +
            "&blog=" + Uri.encode("ispinalokalnie.wordpress.com") +
            "&state=" + Uri.encode(oauthState);
        WebView login = new WebView(this);
        login.getSettings().setJavaScriptEnabled(true);
        login.getSettings().setDomStorageEnabled(true);
        login.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(login, true);
        login.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest r) {
                return receive(r.getUrl().toString());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView w, String u) {
                return receive(u);
            }
            @Override public void onPageFinished(WebView w, String u) { receive(u); }
        });
        loginDialog = new AlertDialog.Builder(this)
            .setTitle("Połącz z WordPress.com")
            .setView(login)
            .setNegativeButton("Anuluj", (dialog, which) -> {
                callback("auth", false, "Logowanie anulowane.");
                login.destroy();
            }).create();
        loginDialog.setOnDismissListener(dialog -> login.destroy());
        loginDialog.show();
        if (loginDialog.getWindow() != null) {
            loginDialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                (int)(getResources().getDisplayMetrics().heightPixels * .85f));
        }
        login.loadUrl(auth);
    }

    private boolean receive(String url) {
        Uri u = Uri.parse(url);
        if (!REDIRECT.equals(u.getScheme() + "://" + u.getAuthority() + u.getPath())) return false;
        String fragment = u.getFragment();
        String error = u.getQueryParameter("error");
        if (fragment == null || fragment.isEmpty()) {
            if (error != null) {
                callback("auth", false, "WordPress odmówił autoryzacji: " + error);
                ui.post(() -> { if (loginDialog != null) loginDialog.dismiss(); });
            }
            return true;
        }
        Uri p = Uri.parse("https://local.invalid/cb?" + fragment);
        String state = p.getQueryParameter("state");
        String access = p.getQueryParameter("access_token");
        String siteId = p.getQueryParameter("site_id");
        if (!oauthState.equals(state) || access == null || access.isEmpty()
            || (siteId != null && !SITE.equals(siteId))) {
            callback("auth", false, "Nieudana weryfikacja sesji lub witryny. Spróbuj ponownie.");
        } else {
            try {
                long seconds = Long.parseLong(p.getQueryParameter("expires_in") == null
                    ? "1209600" : p.getQueryParameter("expires_in"));
                putToken(access, seconds);
                callback("auth", true, "{\"connected\":true}");
            } catch (Exception ex) {
                callback("auth", false, "Nie udało się bezpiecznie zapisać tokenu.");
            }
        }
        oauthState = "";
        ui.post(() -> { if (loginDialog != null) loginDialog.dismiss(); });
        return true;
    }

    private String nonce() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private void doRequest(String rid, String method, String path, String body) {
        if(mcpMode()){
            try {
                Object value=WpMcp.mapRestCall(getToken(),method,path,body);
                callback(rid,true,value.toString());
            }catch(Exception ex){
                if(ex.getMessage()!=null && ex.getMessage().contains("HTTP 401")) verified=false;
                callback(rid,false,"WordPress MCP: "+ex.getMessage());
            }
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API + path).openConnection();
            conn.setConnectTimeout(17000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + getToken());
            conn.setRequestProperty("Accept", "application/json");
            if ("POST".equals(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 2_000_000) throw new Exception("Treść przekracza 2 MB.");
                try (OutputStream o = conn.getOutputStream()) { o.write(bytes); }
            }
            int status = conn.getResponseCode();
            String result = readReply(conn, status);
            if (status >= 200 && status < 300) {
                callback(rid, true, result);
            } else {
                if (status == 401 || status == 403) {
                    verified = false;
                    if (status == 401) prefs().edit().remove("token").remove("expiry").apply();
                }
                callback(rid, false, "WordPress HTTP " + status + ": " + friendlyError(result));
            }
        } catch (Exception ex) {
            callback(rid, false, "Błąd komunikacji: " + ex.getMessage());
        } finally { if (conn != null) conn.disconnect(); }
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
            c = (HttpURLConnection) new URL(API + "media").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(17000);
            c.setReadTimeout(35000);
            c.setRequestProperty("Authorization", "Bearer " + getToken());
            c.setRequestProperty("Content-Type", "image/jpeg");
            c.setRequestProperty("Content-Disposition", "attachment; filename=\"il-redaktor.jpg\"");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
            int code = c.getResponseCode();
            String body = readReply(c, code);
            if (code >= 200 && code < 300) callback("upload", true, body);
            else callback("upload", false, "Przesyłanie: HTTP " + code + ": " + friendlyError(body));
        } catch (Exception ex) { callback("upload", false, ex.getMessage()); }
        finally { if (c != null) c.disconnect(); }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getData() != null &&
            "ilredaktor".equals(intent.getData().getScheme()) &&
            "resume".equals(intent.getData().getHost())) {
            callback("auth-progress",true,"Wrócono do Redaktora. Sprawdzam wynik autoryzacji WordPress…");
        }
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
