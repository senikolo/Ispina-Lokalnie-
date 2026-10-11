package com.ispina.mldcheck;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Periodically checks user-enabled news and waste reminders using Android JobScheduler. */
public final class NotificationJobService extends JobService {
    private static final int JOB_ID = 5201;
    private static final String CHANNEL_ID = "ispina_local_updates";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean stopped;

    static void schedule(android.content.Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID, new ComponentName(context, NotificationJobService.class))
                .setPeriodic(12 * 60 * 60 * 1000L);
        if (Build.VERSION.SDK_INT >= 24) builder.setPersisted(true);
        scheduler.schedule(builder.build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        executor.execute(() -> {
            checkWasteReminder();
            checkLatestNews();
            if (!stopped) jobFinished(params, false);
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }

    private void checkWasteReminder() {
        if (!"1".equals(preferences().getString("waste_notifications", "0"))) return;
        try (InputStream in = getAssets().open("data/waste-2026.json"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            JSONObject root = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            String tomorrow = dateString(daysFromNow(1));
            JSONArray events = root.optJSONArray("events");
            if (events == null) return;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null || !tomorrow.equals(event.optString("date"))) continue;
                String marker = "waste_notified_" + tomorrow;
                if (preferences().getBoolean(marker, false)) return;
                JSONArray categories = event.optJSONArray("categories");
                StringBuilder text = new StringBuilder("Jutro odbiór: ");
                if (categories != null) for (int n = 0; n < categories.length(); n++) {
                    if (n > 0) text.append(", ");
                    text.append(categories.optString(n));
                }
                post("Przypomnienie o odpadach", text.toString());
                preferences().edit().putBoolean(marker, true).apply();
                return;
            }
        } catch (Exception ignored) { }
    }

    private void checkLatestNews() {
        if (!"1".equals(preferences().getString("news_notifications", "0"))) return;
        try {
            String url = "https://ispinalokalnie.wordpress.com/wp-json/wp/v2/posts?per_page=1&_fields=id,title";
            if (!MainActivity.isAllowedApi(url)) return;
            JSONArray posts = new JSONArray(MainActivity.readHttpsJson(url));
            if (posts.length() == 0) return;
            JSONObject latest = posts.getJSONObject(0);
            String id = latest.optString("id", "");
            if (id.isEmpty()) return;
            String oldId = preferences().getString("last_news_post_id", "");
            if (oldId.isEmpty()) {
                preferences().edit().putString("last_news_post_id", id).apply();
                return;
            }
            if (!oldId.equals(id)) {
                String title = Html.fromHtml(latest.optJSONObject("title") == null ? "Nowa wiadomość z Ispiny" : latest.getJSONObject("title").optString("rendered", "Nowa wiadomość z Ispiny")).toString();
                post("Nowa aktualność z Ispiny", title);
                preferences().edit().putString("last_news_post_id", id).apply();
            }
        } catch (Exception ignored) { }
    }

    private android.content.SharedPreferences preferences() {
        return getSharedPreferences("il_preferences", MODE_PRIVATE);
    }

    private void post(String title, String body) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Ispina Lokalnie", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(this, CHANNEL_ID)
                : new android.app.Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
                .setAutoCancel(true).setContentIntent(pending);
        manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
    }

    private static Date daysFromNow(int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, days);
        return calendar.getTime();
    }

    private static String dateString(Date date) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date); }

    @Override public void onDestroy() {
        stopped = true;
        executor.shutdownNow();
        super.onDestroy();
    }
}
