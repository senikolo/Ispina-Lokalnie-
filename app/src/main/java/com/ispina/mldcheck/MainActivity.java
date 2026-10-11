package com.ispina.mldcheck;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native host for the fully bundled IL interface. Remote responses are data only. */
public final class MainActivity extends Activity {
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String APP_URL = "https://" + APP_HOST + "/assets/index.html";
    private static final String NEWS_URL = "https://ispinalokalnie.wordpress.com/wp-json/wp/v2/posts?per_page=8&_fields=id,date,link,title,excerpt,content,categories";
    private static final String HISTORY_URL = "https://ispinalokalnie.wordpress.com/wp-json/wp/v2/pages?slug%5B%5D=historia-ispiny&slug%5B%5D=ciekawostki-historyczne&_fields=id,date,link,slug,title,content";
    private static final String WEATHER_URL = "https://api.open-meteo.com/v1/forecast?latitude=50.1136&longitude=20.3781&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=Europe%2FWarsaw&forecast_days=3";
    private static final String CHANNEL_ID = "ispina_local_updates";
    private static final int NOTIFICATION_REQUEST = 4101;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady;
    private volatile String readerText = "";
    private volatile String activeUtterancePrefix = "";
    private volatile int readerOffset;
    private long readerRunId;
    private volatile String currentScreen = "home";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        createNotificationChannel();
        NotificationJobService.schedule(this);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF5F8FC);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) settings.setSafeBrowsingEnabled(true);
        webView.addJavascriptInterface(new NativeBridge(), "ILNative");
        webView.setWebViewClient(new LocalOnlyWebViewClient());
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (Build.VERSION.SDK_INT >= 35) {
            webView.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        setContentView(webView);
        if (state == null) webView.loadUrl(APP_URL); else webView.restoreState(state);
        initializeSpeech();
    }

    private void initializeSpeech() {
        tts = new TextToSpeech(getApplicationContext(), status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                Locale polish = new Locale("pl", "PL");
                int languageStatus = tts.isLanguageAvailable(polish);
                ttsReady = languageStatus >= TextToSpeech.LANG_AVAILABLE && tts.setLanguage(polish) >= TextToSpeech.LANG_AVAILABLE;
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) {
                        String prefix = activeUtterancePrefix;
                        if (prefix.isEmpty() || !id.startsWith(prefix)) return;
                        int separator = id.indexOf('-', prefix.length());
                        try { readerOffset = Math.max(readerOffset, Integer.parseInt(id.substring(separator + 1))); }
                        catch (Exception ignored) { }
                    }
                    @Override public void onError(String id) { }
                    @Override public void onRangeStart(String id, int start, int end, int frame) {
                        String prefix = activeUtterancePrefix;
                        if (prefix.isEmpty() || !id.startsWith(prefix)) return;
                        int separator = id.indexOf('-', prefix.length());
                        try { readerOffset = Math.max(readerOffset, Integer.parseInt(id.substring(prefix.length(), separator)) + end); }
                        catch (Exception ignored) { }
                    }
                });
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Ispina Lokalnie", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Powiadomienia o nowych wiadomościach i terminach odbioru odpadów");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private final class LocalOnlyWebViewClient extends WebViewClient {
        @Override public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (BuildConfig.DEBUG && APP_URL.equals(url)) {
                view.postDelayed(() -> view.evaluateJavascript(
                        "(function(){var h=document.querySelector('#mainContent h1');return h?h.textContent.trim():''})()",
                        value -> Log.i("IL_SMOKE", "home-title=" + value)), 1000L);
            }
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!APP_HOST.equals(uri.getHost())) return blockedResponse();
            String path = uri.getPath();
            if (path == null || !path.startsWith("/assets/")) return blockedResponse();
            String assetPath = path.substring("/assets/".length());
            try {
                InputStream stream = getAssets().open(assetPath);
                String mime = mimeType(assetPath);
                return new WebResourceResponse(mime, "UTF-8", stream);
            } catch (Exception ignored) {
                return new WebResourceResponse("text/plain", "UTF-8",
                        new ByteArrayInputStream("Not found".getBytes(StandardCharsets.UTF_8)));
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (APP_HOST.equals(uri.getHost())) return false;
            openExternal(uri.toString());
            return true;
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            if (APP_HOST.equals(uri.getHost())) return false;
            openExternal(url);
            return true;
        }
    }

    private WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "UTF-8",
                new ByteArrayInputStream("External executable content is blocked".getBytes(StandardCharsets.UTF_8)));
    }

    private static String mimeType(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private void openExternal(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equals("https") || scheme.equals("mailto") || scheme.equals("tel"))) {
                toast("Ten odnośnik nie jest obsługiwany.");
                return;
            }
            if (scheme.equals("https") && uri.getHost() == null) return;
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            toast("Nie udało się otworzyć odnośnika.");
        }
    }

    private void requestData(String kind, String endpoint) {
        io.execute(() -> {
            String body;
            String error = "";
            try {
                if (!isAllowedApi(endpoint)) throw new IllegalArgumentException("Niedozwolone źródło danych");
                body = readHttpsJson(endpoint);
            } catch (Exception ex) {
                body = "null";
                error = ex.getMessage() == null ? "Brak połączenia" : ex.getMessage();
            }
            deliverJson(kind, body, error);
        });
    }

    static boolean isAllowedApi(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            if (!"https".equals(uri.getScheme())) return false;
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            if ("ispinalokalnie.wordpress.com".equals(host)) return path.startsWith("/wp-json/wp/v2/posts") || path.startsWith("/wp-json/wp/v2/pages");
            return "api.open-meteo.com".equals(host) && path.equals("/v1/forecast");
        } catch (Exception ignored) { return false; }
    }

    static String readHttpsJson(String raw) throws Exception {
        String current = raw;
        for (int redirects = 0; redirects <= 4; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "IspinaLokalnie/2.0 (Android)");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    if (redirects == 4) throw new IllegalStateException("Zbyt wiele przekierowań");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IllegalStateException("Brak adresu przekierowania");
                    current = new URL(new URL(current), location).toString();
                    if (!isAllowedApi(current)) throw new IllegalStateException("Przekierowanie do niedozwolonego źródła");
                    continue;
                }
                if (status < 200 || status >= 300) throw new IllegalStateException("Serwer zwrócił HTTP " + status);
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("Odpowiedź jest zbyt duża");
                        output.write(buffer, 0, count);
                    }
                    String json = output.toString("UTF-8");
                    new JSONObject("{\"value\":" + json + "}");
                    return json;
                }
            } finally { connection.disconnect(); }
        }
        throw new IllegalStateException("Nie udało się pobrać danych");
    }

    private void deliverJson(String kind, String json, String error) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String script = "window.IL&&window.IL.nativeData(" + JSONObject.quote(kind) + ",JSON.parse(" +
                    JSONObject.quote(json) + ")," + JSONObject.quote(error) + ");";
            webView.evaluateJavascript(script, null);
        });
    }

    private void toast(String message) { runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show()); }

    private final class NativeBridge {
        @JavascriptInterface public void requestNews() { requestData("news", NEWS_URL); }
        @JavascriptInterface public void requestHistory() { requestData("history", HISTORY_URL); }
        @JavascriptInterface public void requestWeather() { requestData("weather", WEATHER_URL); }
        @JavascriptInterface public void setCurrentScreen(String screen) {
            if ("home".equals(screen) || "planner".equals(screen) || "news".equals(screen) || "waste".equals(screen) || "more".equals(screen) || "weather".equals(screen) || "radio".equals(screen) || "history".equals(screen) || "services".equals(screen) || "settings".equals(screen) || "about".equals(screen)) currentScreen = screen;
        }
        @JavascriptInterface public int getCachedScheduleSize() { return TransitSync.cachedSize(MainActivity.this); }
        @JavascriptInterface public String readCachedScheduleChunk(int offset, int length) {
            return TransitSync.readCachedChunk(MainActivity.this, offset, length);
        }
        @JavascriptInterface public void requestScheduleSync() {
            io.execute(() -> {
                try {
                    TransitSync.downloadAndSave(MainActivity.this);
                    JSONObject ready = new JSONObject().put("length", TransitSync.cachedSize(MainActivity.this));
                    deliverJson("schedule-ready", ready.toString(), "");
                }
                catch (Exception ex) { deliverJson("schedule", "null", ex.getMessage() == null ? "Nie udało się odświeżyć rozkładu" : ex.getMessage()); }
            });
        }
        @JavascriptInterface public void openExternal(String url) { MainActivity.this.runOnUiThread(() -> MainActivity.this.openExternal(url)); }
        @JavascriptInterface public boolean isOnline() {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || cm.getActiveNetwork() == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        @JavascriptInterface public String getAppInfoJson() {
            return "{\"name\":\"Ispina Lokalnie\",\"version\":\"2.0.0-preview\",\"build\":20000,\"channel\":\"fdroid-candidate\"}";
        }
        @JavascriptInterface public void speak(String text, float rate) {
            MainActivity.this.runOnUiThread(() -> {
                if (!ttsReady || text == null || text.trim().isEmpty()) { toast("Polski głos TTS nie jest dostępny."); return; }
                readerText = text.length() > 80000 ? text.substring(0, 80000) : text;
                readerOffset = 0;
                tts.setSpeechRate(Math.max(0.65f, Math.min(1.35f, rate)));
                queueReaderFrom(0);
            });
        }
        @JavascriptInterface public void pauseReading() {
            MainActivity.this.runOnUiThread(() -> { activeUtterancePrefix = ""; if (ttsReady) tts.stop(); });
        }
        @JavascriptInterface public void resumeReading() {
            MainActivity.this.runOnUiThread(() -> {
                if (!ttsReady || readerText.isEmpty() || readerOffset >= readerText.length()) return;
                queueReaderFrom(readerOffset);
            });
        }
        @JavascriptInterface public void stopReading() {
            MainActivity.this.runOnUiThread(() -> { activeUtterancePrefix = ""; if (ttsReady) tts.stop(); readerText = ""; readerOffset = 0; });
        }
        @JavascriptInterface public void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST));
            }
        }
        @JavascriptInterface public void showTestNotification() { postNotification("Ispina Lokalnie", "Powiadomienia działają na tym telefonie."); }
        @JavascriptInterface public void vibrate() {
            android.os.Vibrator vibrator = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) vibrator.vibrate(35);
        }
        @JavascriptInterface public void setPreference(String key, String value) {
            if (key == null || !key.matches("[a-zA-Z0-9_.-]{1,40}")) return;
            getSharedPreferences("il_preferences", MODE_PRIVATE).edit().putString(key, value == null ? "" : value.substring(0, Math.min(value.length(), 1000))).apply();
        }
        @JavascriptInterface public String getPreference(String key) {
            if (key == null || !key.matches("[a-zA-Z0-9_.-]{1,40}")) return "";
            return getSharedPreferences("il_preferences", MODE_PRIVATE).getString(key, "");
        }
    }

    private void queueReaderFrom(int requestedOffset) {
        if (!ttsReady || readerText.isEmpty()) return;
        int cursor = Math.max(0, Math.min(requestedOffset, readerText.length()));
        String prefix = "il-reader-" + (++readerRunId) + "-";
        activeUtterancePrefix = prefix;
        boolean first = true;
        while (cursor < readerText.length()) {
            int end = Math.min(cursor + 3500, readerText.length());
            if (end < readerText.length()) {
                int wordBreak = readerText.lastIndexOf(' ', end);
                if (wordBreak > cursor + 2500) end = wordBreak;
                if (end > cursor && Character.isHighSurrogate(readerText.charAt(end - 1))) end--;
            }
            if (end <= cursor) end = Math.min(cursor + 3500, readerText.length());
            String utteranceId = prefix + cursor + "-" + end;
            String chunk = readerText.substring(cursor, end);
            int result = tts.speak(chunk, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, utteranceId);
            if (result == TextToSpeech.ERROR) {
                activeUtterancePrefix = "";
                toast("Nie udało się uruchomić polskiego lektora.");
                return;
            }
            first = false;
            cursor = end;
        }
    }

    private void postNotification(String title, String body) {
        runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
                return;
            }
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(this, CHANNEL_ID)
                    : new android.app.Notification.Builder(this);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
                    .setAutoCancel(true).setContentIntent(pending);
            manager.notify((int) System.currentTimeMillis(), builder.build());
        });
    }

    @Override public void onBackPressed() {
        if (webView != null && !"home".equals(currentScreen)) {
            currentScreen = "home";
            webView.evaluateJavascript("window.IL&&window.IL.handleBack()", null);
            return;
        }
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (webView != null) { webView.removeJavascriptInterface("ILNative"); webView.destroy(); webView = null; }
        io.shutdownNow();
        super.onDestroy();
    }
}
