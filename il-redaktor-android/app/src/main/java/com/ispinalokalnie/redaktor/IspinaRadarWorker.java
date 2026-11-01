package com.ispinalokalnie.redaktor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Checks public, source-grounded Ispina mentions and alerts only on new source links. */
public final class IspinaRadarWorker extends Worker {
    public static final String PREF_ENABLED = "ispina_radar_enabled";
    public static final String PREF_LAST_CHECKED = "ispina_radar_last_checked";
    public static final String PREF_LAST_STATUS = "ispina_radar_last_status";
    public static final String PREF_LAST_MESSAGE = "ispina_radar_last_message";
    private static final String PREFS = "il_redaktor_settings";
    private static final String PREF_SEEN = "ispina_radar_seen_sources";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_REFRESH = "refresh_token";
    private static final String PREF_EXPIRY = "expiry";
    private static final String PREF_CLIENT_ID = "oauth21_client_id";
    private static final String KEY_ALIAS = "il_redaktor_oauth_aes_v1";
    private static final String PERIODIC_WORK = "il_ispina_radar_every_8_hours";
    private static final String INITIAL_WORK = "il_ispina_radar_initial_scan";
    private static final String CHANNEL_ID = "il_ispina_radar";
    private static final Object TOKEN_LOCK = new Object();

    public IspinaRadarWorker(Context context, WorkerParameters params) { super(context, params); }

    public static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Radar Ispiny · IL Redaktor",
            NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Nowe, źródłowe wzmianki dotyczące Ispiny.");
        manager.createNotificationChannel(channel);
    }

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest initial = new OneTimeWorkRequest.Builder(IspinaRadarWorker.class)
            .setConstraints(constraints).setInitialDelay(1, TimeUnit.MINUTES).build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
            IspinaRadarWorker.class, 8, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(constraints).setInitialDelay(8, TimeUnit.HOURS).build();
        WorkManager manager = WorkManager.getInstance(app);
        manager.enqueueUniqueWork(INITIAL_WORK, ExistingWorkPolicy.KEEP, initial);
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, periodic);
    }

    public static void cancel(Context context) {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        manager.cancelUniqueWork(INITIAL_WORK);
        manager.cancelUniqueWork(PERIODIC_WORK);
    }

    @Override public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ENABLED, false)) return Result.success();
        if (!notificationsAllowed(context)) {
            prefs.edit().putBoolean(PREF_ENABLED, false).putString(PREF_LAST_STATUS, "notifications_disabled")
                .putString(PREF_LAST_MESSAGE, "Włącz powiadomienia Androida dla IL Redaktora.").commit();
            cancel(context);
            return Result.success();
        }

        try {
            long now = System.currentTimeMillis();
            long last = prefs.getLong(PREF_LAST_CHECKED, 0L);
            long since = last > 0 ? Math.max(0L, last - TimeUnit.HOURS.toMillis(24))
                : now - TimeUnit.HOURS.toMillis(24);
            JSONObject result = sendChat(context, buildPrompt(since, now));
            String answer = clean(result.optString("answer", ""));
            JSONArray array = result.optJSONArray("sources");
            boolean verified = result.optBoolean("verified", false) && !answer.isEmpty()
                && array != null && array.length() > 0;
            ArrayList<Source> sources = verified ? parseSources(array) : new ArrayList<>();
            LinkedHashSet<String> seen = readSeen(prefs);
            ArrayList<Source> newSources = new ArrayList<>();
            for (Source source : sources) {
                String key = fingerprint(source.url);
                if (key.isEmpty()) continue;
                if (!seen.contains(key)) newSources.add(source);
                seen.add(key);
            }
            while (seen.size() > 500) seen.remove(seen.iterator().next());
            writeSeen(prefs, seen);

            if (containsNoNews(answer)) newSources.clear();
            if (!prefs.getBoolean(PREF_ENABLED, false)) return Result.success();
            if (newSources.isEmpty()) {
                saveStatus(prefs, "none", "Nie znaleziono nowej, potwierdzonej wzmianki.", true, now);
                return Result.success();
            }

            Source first = newSources.get(0);
            String host = Uri.parse(first.url).getHost();
            String summary = first.title + (host == null ? "" : " · " + host);
            if (newSources.size() > 1) summary += " · +" + (newSources.size() - 1) + " nowych źródeł";
            saveStatus(prefs, "found", summary, true, now);
            if (prefs.getBoolean(PREF_ENABLED, false)) postNotification(context, newSources, answer, now);
            return Result.success();
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? "Sprawdź połączenie i ustawienia radaru." : ex.getMessage();
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("token") || lower.contains("wordpress"))
                saveStatus(prefs, "login", "Połącz ponownie konto WordPress.", false, System.currentTimeMillis());
            else saveStatus(prefs, "error", message, false, System.currentTimeMillis());
            return Result.success();
        }
    }

    private static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    private static String buildPrompt(long since, long now) {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "Wykonaj szybki radar internetowy IL. Teraz: " + format.format(now)
            + ". Szukaj wyłącznie nowych publicznych wzmianek opublikowanych od " + format.format(since)
            + " do teraz o wsi Ispina w gminie Drwinia w Małopolsce. Uwzględnij formy Ispina, w Ispinie, do Ispiny, z Ispiny i prawdopodobne literówki. "
            + "Sprawdź źródła urzędowe i BIP, zamówienia i przetargi, lokalne media, drogi i most, transport, OSP, infrastrukturę, wydarzenia i InPost. "
            + "Pobliskie miejscowości uwzględnij tylko przy wyraźnym związku z Ispiną. Podaj krótko tytuł lub ustalenie, datę publikacji (oddziel ją od daty wydarzenia) i źródła znalezione przez Google Search. "
            + "Nie zgaduj, nie dopisuj faktów i nie powtarzaj ogólnych stron bez nowej informacji. Jeśli nie znajdziesz nowej wzmianki ze źródłem, odpowiedz dokładnie: BRAK NOWYCH WZMIANEK.";
    }

    private static JSONObject sendChat(Context context, String prompt) throws Exception {
        String token = accessToken(context, false);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(BuildConfig.ASSISTANT_ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(70000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                JSONObject payload = new JSONObject().put("mode", "chat")
                    .put("prompt", prompt).put("history", new JSONArray());
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                String response = readReply(connection, status);
                if (status == 401 && attempt == 0) { token = accessToken(context, true); continue; }
                if (status < 200 || status >= 300) {
                    String message = "HTTP " + status;
                    try { message = new JSONObject(response).optString("error", message); } catch (Exception ignored) { }
                    if (status == 401) message = "Sesja WordPress wygasła. Połącz konto ponownie.";
                    throw new Exception(message);
                }
                return new JSONObject(response);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new Exception("Nie udało się odświeżyć sesji WordPress.");
    }

    private static ArrayList<Source> parseSources(JSONArray array) {
        ArrayList<Source> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(array.length(), 20); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String url = safeUrl(item.optString("url", ""));
            if (url.isEmpty()) continue;
            String fingerprint = fingerprint(url);
            if (fingerprint.isEmpty() || !unique.add(fingerprint)) continue;
            Uri uri = Uri.parse(url);
            String title = clip(item.optString("title", uri.getHost()), 160);
            result.add(new Source(title.isEmpty() ? uri.getHost() : title, url));
        }
        return result;
    }

    private static String safeUrl(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null || raw.length() > 1500
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return "";
            return uri.toString();
        } catch (Exception ex) { return ""; }
    }

    private static String fingerprint(String raw) {
        try {
            Uri uri = Uri.parse(raw);
            String canonical = uri.buildUpon().fragment(null).build().toString()
                .replaceAll("(?i)(utm_[^=&#]+|gclid|fbclid|yclid)=[^&#]*", "")
                .replace("?&", "?").replace("&&", "&").replaceAll("[?&]$", "");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception ex) { return ""; }
    }

    private static LinkedHashSet<String> readSeen(SharedPreferences prefs) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        try {
            JSONArray items = new JSONArray(prefs.getString(PREF_SEEN, "[]"));
            for (int i = 0; i < items.length(); i++) {
                String item = items.optString(i, "");
                if (item.matches("[a-f0-9]{64}")) seen.add(item);
            }
        } catch (Exception ignored) { }
        return seen;
    }

    private static void writeSeen(SharedPreferences prefs, Set<String> seen) {
        JSONArray items = new JSONArray();
        for (String key : seen) items.put(key);
        prefs.edit().putString(PREF_SEEN, items.toString()).commit();
    }

    private static boolean containsNoNews(String answer) {
        String text = answer.toLowerCase(Locale.ROOT);
        return text.contains("brak nowych wzmianek") || text.contains("nie znaleziono nowych")
            || text.contains("nie ma nowych informacji") || text.contains("nie pojawiły się nowe");
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String clip(String value, int limit) {
        String text = clean(value);
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private static void saveStatus(SharedPreferences prefs, String status, String message,
                                   boolean checked, long now) {
        SharedPreferences.Editor edit = prefs.edit().putString(PREF_LAST_STATUS, status)
            .putString(PREF_LAST_MESSAGE, clip(message, 240));
        if (checked) edit.putLong(PREF_LAST_CHECKED, now);
        edit.commit();
    }

    private static String readReply(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "{}";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > 2_000_000) throw new Exception("Odpowiedź radaru przekroczyła bezpieczny limit.");
            }
            return out.toString("UTF-8");
        }
    }

    private static String accessToken(Context context, boolean forceRefresh) throws Exception {
        synchronized (TOKEN_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String access = decrypt(prefs.getString(PREF_TOKEN, ""));
            if (access.isEmpty()) throw new Exception("Zaloguj się ponownie do WordPressa.");
            long expiry = prefs.getLong(PREF_EXPIRY, 0L);
            if (!forceRefresh && expiry > System.currentTimeMillis() + 15000L) return access;
            String refresh = decrypt(prefs.getString(PREF_REFRESH, ""));
            String clientId = prefs.getString(PREF_CLIENT_ID, "");
            if (refresh.isEmpty() || clientId.isEmpty()) {
                if (expiry > System.currentTimeMillis()) return access;
                throw new Exception("Sesja WordPress wygasła. Połącz konto ponownie.");
            }
            JSONObject response = WpMcp.refresh(clientId, refresh);
            String nextAccess = response.optString("access_token", "");
            if (nextAccess.isEmpty()) throw new Exception("WordPress nie odświeżył sesji.");
            long lifetime = Math.max(60L, response.optLong("expires_in", 3600L));
            long margin = Math.min(120L, Math.max(10L, lifetime / 10L));
            long nextExpiry = System.currentTimeMillis() + Math.max(1L, lifetime - margin) * 1000L;
            SharedPreferences.Editor edit = prefs.edit().putString(PREF_TOKEN, encrypt(nextAccess)).putLong(PREF_EXPIRY, nextExpiry);
            String nextRefresh = response.optString("refresh_token", "");
            if (!nextRefresh.isEmpty()) edit.putString(PREF_REFRESH, encrypt(nextRefresh));
            if (!edit.commit()) throw new Exception("Android nie zapisał odświeżonej sesji.");
            return nextAccess;
        }
    }

    private static String decrypt(String stored) throws Exception {
        if (stored == null || stored.isEmpty()) return "";
        String[] parts = stored.split(":", 2);
        if (parts.length != 2) return "";
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key == null) throw new Exception("Klucz sesji Android nie jest dostępny.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
            new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private static String encrypt(String value) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key == null) throw new Exception("Klucz sesji Android nie jest dostępny.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
            + Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    private static void postNotification(Context context, ArrayList<Source> sources, String answer, long now) {
        if (sources.isEmpty() || !notificationsAllowed(context)) return;
        ensureNotificationChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Source first = sources.get(0);
        int id = (int) (now & 0x7fffffff);
        Uri firstUri = Uri.parse(first.url);
        Intent sourceIntent = new Intent(Intent.ACTION_VIEW, firstUri);
        sourceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openSource = PendingIntent.getActivity(context, id, sourceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent appIntent = new Intent(context, MainActivity.class);
        appIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(context, id + 1, appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = first.title + " · " + (firstUri.getHost() == null ? "źródło internetowe" : firstUri.getHost());
        if (sources.size() > 1) text += " · +" + (sources.size() - 1) + " nowych";
        StringBuilder expanded = new StringBuilder(clip(answer, 500)).append("\n\nŹródła:");
        for (int i = 0; i < Math.min(5, sources.size()); i++) {
            Source source = sources.get(i);
            expanded.append("\n• ").append(source.title).append(" — ").append(Uri.parse(source.url).getHost());
        }
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setLargeIcon(BitmapFactory.decodeResource(context.getResources(), R.mipmap.ic_launcher))
            .setContentTitle("Nowa wzmianka o Ispinie").setContentText(clip(text, 180))
            .setStyle(new Notification.BigTextStyle().bigText(clip(expanded.toString(), 900))
                .setSummaryText("Radar Ispiny · IL Redaktor"))
            .setColor(0xffb33d42).setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true).setOnlyAlertOnce(false).setWhen(now).setContentIntent(openSource)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(context, R.mipmap.ic_launcher),
                "Otwórz Redaktor", openApp).build())
            .build();
        manager.notify(id, notification);
    }

    private static final class Source {
        final String title;
        final String url;
        Source(String title, String url) { this.title = title; this.url = url; }
    }
}
