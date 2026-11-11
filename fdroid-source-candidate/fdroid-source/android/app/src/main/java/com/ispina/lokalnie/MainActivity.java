package com.ispina.lokalnie;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.DownloadListener;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.ispina.lokalnie.sync.NativeScheduleBridge;
import com.ispina.lokalnie.sync.ScheduleSync;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://ispinalokalnie.wordpress.com/";
    private static final long SPLASH_MS = 2500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private String mainScript = "";
    private boolean portalStarted = false;
    private FrameLayout splashRoot;
    private TextView weatherText;
    private TextView weatherIcon;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(21, 91, 115));
        getWindow().setNavigationBarColor(Color.rgb(21, 91, 115));
        mainScript = readAsset("main_ui.js");
        ScheduleSync.scheduleDaily(this);
        ScheduleSync.syncIfStale(this);
        showSplash();
        fetchWeather();
        handler.postDelayed(this::openPortal, SPLASH_MS);
    }

    private void showSplash() {
        splashRoot = new FrameLayout(this);
        setSky(0, 1);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24), dp(60), dp(24), dp(28));

        TextView hello = new TextView(this);
        hello.setText("Dzień dobry Ispino ;-)");
        hello.setTextColor(Color.WHITE);
        hello.setTextSize(29);
        hello.setGravity(Gravity.CENTER);
        hello.setTypeface(null, android.graphics.Typeface.BOLD);
        column.addView(hello, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ContourView contour = new ContourView(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210));
        cp.setMargins(0, dp(18), 0, dp(8));
        column.addView(contour, cp);

        weatherIcon = new TextView(this);
        weatherIcon.setText("☀️");
        weatherIcon.setTextSize(38);
        weatherIcon.setGravity(Gravity.CENTER);
        column.addView(weatherIcon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        weatherText = new TextView(this);
        weatherText.setText("Pobieram aktualną pogodę…");
        weatherText.setTextColor(Color.WHITE);
        weatherText.setTextSize(18);
        weatherText.setGravity(Gravity.CENTER);
        weatherText.setPadding(0, dp(5), 0, 0);
        column.addView(weatherText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView brand = new TextView(this);
        brand.setText("Ispina Lokalnie");
        brand.setTextColor(Color.argb(220,255,255,255));
        brand.setTextSize(14);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(18), 0, 0);
        column.addView(brand, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        splashRoot.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(splashRoot);
    }

    private void fetchWeather() {
        executor.execute(() -> {
            HttpURLConnection c = null;
            try {
                String ep = "https://api.open-meteo.com/v1/forecast?latitude=50.1136&longitude=20.3781&current=temperature_2m,apparent_temperature,weather_code,is_day&timezone=Europe%2FWarsaw";
                c = (HttpURLConnection) new URL(ep).openConnection();
                c.setConnectTimeout(2200);
                c.setReadTimeout(2200);
                c.setRequestProperty("Accept", "application/json");
                JSONObject cur = new JSONObject(readAll(c.getInputStream())).getJSONObject("current");
                double temp = cur.getDouble("temperature_2m");
                double apparent = cur.optDouble("apparent_temperature", temp);
                int code = cur.optInt("weather_code", 0);
                int isDay = cur.optInt("is_day", 1);
                String desc = weatherDescription(code);
                String icon = weatherIcon(code, isDay);
                handler.post(() -> {
                    if (splashRoot != null) {
                        setSky(code, isDay);
                        weatherIcon.setText(icon);
                        weatherText.setText(String.format(Locale.forLanguageTag("pl-PL"), "%.0f°C • %s\nOdczuwalna %.0f°C", temp, desc, apparent));
                    }
                });
            } catch (Exception ignored) {
                handler.post(() -> {
                    if (weatherText != null) weatherText.setText("Ispina • pogoda chwilowo niedostępna");
                });
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void setSky(int code, int isDay) {
        if (splashRoot == null && code != 0) return;
        int a, b;
        if (isDay == 0) { a = Color.rgb(8, 25, 49); b = Color.rgb(37, 66, 91); }
        else if (code >= 95) { a = Color.rgb(41, 48, 68); b = Color.rgb(83, 89, 104); }
        else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) { a = Color.rgb(74, 101, 116); b = Color.rgb(129, 151, 159); }
        else if (code == 45 || code == 48) { a = Color.rgb(139, 155, 155); b = Color.rgb(202, 211, 207); }
        else if (code == 3) { a = Color.rgb(100, 124, 133); b = Color.rgb(167, 181, 184); }
        else if (code == 2) { a = Color.rgb(60, 144, 185); b = Color.rgb(176, 210, 220); }
        else { a = Color.rgb(28, 145, 214); b = Color.rgb(109, 205, 235); }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a,b});
        if (splashRoot != null) splashRoot.setBackground(g);
    }

    private String weatherDescription(int code) {
        if (code == 0) return "bezchmurnie";
        if (code == 1) return "prawie bezchmurnie";
        if (code == 2) return "częściowe zachmurzenie";
        if (code == 3) return "pochmurno";
        if (code == 45 || code == 48) return "mgła";
        if (code >= 51 && code <= 57) return "mżawka";
        if (code >= 61 && code <= 67) return "deszcz";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "śnieg";
        if (code >= 80 && code <= 82) return "przelotne opady";
        if (code >= 95) return "burza";
        return "aktualne warunki";
    }

    private String weatherIcon(int code, int isDay) {
        if (code >= 95) return "⛈️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄️";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "🌧️";
        if (code == 45 || code == 48) return "🌫️";
        if (code == 3) return "☁️";
        if (code == 2) return "🌤️";
        return isDay == 0 ? "🌙" : "☀️";
    }

    private void openPortal() {
        if (portalStarted || isFinishing() || isDestroyed()) return;
        portalStarted = true;
        createWebView();
        webView.loadUrl(HOME_URL);
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.addJavascriptInterface(new NativeScheduleBridge(this), "IspinaNative");
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setUserAgentString(s.getUserAgentString() + " IspinaLokalnie/1.80.60-sync-draft");

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                openExternal(uri); return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("http") && url.contains("ispinalokalnie") && !mainScript.isEmpty()) {
                    view.postDelayed(() -> { if (webView != null && !isFinishing() && !isDestroyed()) webView.evaluateJavascript(mainScript, null); }, 120L);
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
            }
        });
        webView.setDownloadListener((url, ua, cd, mt, len) -> openExternal(Uri.parse(url)));
        setContentView(webView);
        splashRoot = null;
        weatherText = null;
        weatherIcon = null;
    }

    private String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder(); String line;
        while ((line = r.readLine()) != null) b.append(line);
        r.close(); return b.toString();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name); BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) b.append(line).append('\n');
            return b.toString();
        } catch (Exception e) { return ""; }
    }

    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (ActivityNotFoundException ignored) { }
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); executor.shutdownNow();
        if (webView != null) { webView.stopLoading(); webView.setWebViewClient(null); webView.destroy(); webView = null; }
        super.onDestroy();
    }

    static class ContourView extends View {
        private final Path path = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ContourView(android.content.Context c) {
            super(c);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.WHITE);
            paint.setAlpha(255);
            paint.clearShadowLayer();
            paint.setStrokeWidth(5.5f * getResources().getDisplayMetrics().density);
            buildPath();
        }
        private void buildPath() {
            path.moveTo(796,278); path.lineTo(792,269); path.lineTo(781,263); path.lineTo(690,253); path.lineTo(693,176); path.lineTo(679,153); path.lineTo(642,128); path.lineTo(633,117); path.lineTo(629,106); path.lineTo(629,35); path.lineTo(624,25); path.lineTo(597,11); path.lineTo(534,0); path.lineTo(509,0); path.lineTo(482,5); path.lineTo(432,37); path.lineTo(418,52); path.lineTo(408,116); path.lineTo(398,123); path.lineTo(364,135); path.lineTo(322,135); path.lineTo(277,86); path.lineTo(259,77); path.lineTo(219,74); path.lineTo(181,79); path.lineTo(109,131); path.lineTo(37,173); path.lineTo(13,201); path.lineTo(0,229); path.lineTo(2,243); path.lineTo(56,325); path.lineTo(93,394); path.lineTo(103,398); path.lineTo(128,399); path.lineTo(154,470); path.lineTo(167,477); path.lineTo(199,475); path.lineTo(259,463); path.lineTo(377,425); path.lineTo(486,400); path.lineTo(582,398); path.lineTo(637,402); path.lineTo(716,402); path.lineTo(782,392); path.lineTo(790,386); path.lineTo(793,378); path.close();
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float pad = 16f * getResources().getDisplayMetrics().density;
            float sx = (getWidth()-2*pad)/797f, sy = (getHeight()-2*pad)/479f, s = Math.min(sx,sy);
            float dx=(getWidth()-797f*s)/2f, dy=(getHeight()-479f*s)/2f;
            canvas.save(); canvas.translate(dx,dy); canvas.scale(s,s); canvas.drawPath(path,paint); canvas.restore();
        }
    }
}
