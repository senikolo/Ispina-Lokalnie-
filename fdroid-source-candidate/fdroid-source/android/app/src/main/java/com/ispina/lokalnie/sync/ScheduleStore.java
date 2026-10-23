package com.ispina.lokalnie.sync;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class ScheduleStore {
    private ScheduleStore() {}

    public static File scheduleDir(Context context) {
        File dir = new File(context.getFilesDir(), "schedules");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File file(Context context, String name) {
        return new File(scheduleDir(context), name);
    }

    public static void writeAtomic(Context context, String name, String text) throws Exception {
        File target = file(context, name);
        File tmp = new File(target.getParentFile(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Nie można zastąpić " + target.getName());
        }
        if (!tmp.renameTo(target)) {
            throw new IllegalStateException("Nie można aktywować " + target.getName());
        }
    }

    public static String read(Context context, String name) {
        File f = file(context, name);
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(data, 0, off, StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
        }
        if (SyncConfig.FILE_GMINNA.equals(name)) return readBundledGminna(context);
        return "";
    }

    public static boolean hasGminna(Context context) {
        return file(context, SyncConfig.FILE_GMINNA).isFile() || !readBundledGminna(context).isEmpty();
    }

    private static String readBundledGminna(Context context) {
        try (InputStream in = context.getAssets().open("linia-gminna.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SyncConfig.PREFS, Context.MODE_PRIVATE);
    }

    public static String statusJson(Context context) {
        SharedPreferences p = prefs(context);
        try {
            JSONObject j = new JSONObject();
            j.put("lastAttempt", p.getLong("last_attempt", 0));
            j.put("lastSuccess", p.getLong("last_success", 0));
            j.put("mldLastSuccess", p.getLong("mld_last_success", 0));
            j.put("gminnaLastSuccess", p.getLong("gminna_last_success", 0));
            j.put("mldSource", p.getString("mld_source", ""));
            j.put("mldError", p.getString("mld_error", ""));
            j.put("gminnaError", p.getString("gminna_error", ""));
            j.put("gminnaConfigured", !getGminnaUrl(context).isEmpty());
            j.put("mldCached", file(context, SyncConfig.FILE_MLD).isFile());
            j.put("gminnaCached", hasGminna(context));
            return j.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String getGminnaUrl(Context context) {
        String value = prefs(context).getString(SyncConfig.PREF_GMINNA_URL, "");
        if (value == null || value.trim().isEmpty()) value = SyncConfig.DEFAULT_GMINNA_API_URL;
        return value == null ? "" : value.trim();
    }

    public static void setGminnaUrl(Context context, String url) {
        prefs(context).edit().putString(SyncConfig.PREF_GMINNA_URL, url == null ? "" : url.trim()).apply();
    }
}
