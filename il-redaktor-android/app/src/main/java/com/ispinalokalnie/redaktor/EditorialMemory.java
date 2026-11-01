package com.ispinalokalnie.redaktor;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Offline, durable editorial memory. Never stores WordPress credentials or ChatGPT cookies.
 * An RSS/search hit is a LINK, not a verified claim. Marking a source "reviewed" requires
 * an explicit editor action; if the metadata later changes its status becomes "link_found".
 * Schema updates are additive and NEVER drop old tables. Draft files remain untouched.
 */
public final class EditorialMemory extends SQLiteOpenHelper {
    private static final int VERSION = 1;
    private static final int MAX_SOURCES_PER_SCAN = 100;
    private static final int MAX_RESPONSE = 8000;
    private static final int MAX_QUESTION = 2000;

    public EditorialMemory(Context context) {
        super(context.getApplicationContext(), "il-editorial-memory-v1.db", null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sources (" +
            "url TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, snippet TEXT NOT NULL DEFAULT '', " +
            "published_at TEXT NOT NULL DEFAULT '', first_seen INTEGER NOT NULL, " +
            "last_seen INTEGER NOT NULL, changed_at INTEGER NOT NULL DEFAULT 0, " +
            "fingerprint TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'link_found', " +
            "last_query TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_il_sources_seen ON sources(last_seen DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_il_sources_status ON sources(status, last_seen DESC)");
        db.execSQL("CREATE TABLE IF NOT EXISTS searches (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, question TEXT NOT NULL, " +
            "answer TEXT NOT NULL, searched_at INTEGER NOT NULL, " +
            "result_count INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_il_searches_time ON searches(searched_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations must ADD columns/tables, never DROP existing editorial data.
        if (oldVersion > newVersion) throw new IllegalStateException("Unsupported database downgrade.");
    }

    public synchronized JSONObject execute(String action, JSONObject arg) throws Exception {
        if ("save".equals(action)) return saveSearch(arg);
        if ("browse".equals(action)) return browse(arg);
        if ("review".equals(action)) return setReviewed(arg);
        if ("delete".equals(action)) return deleteSource(arg);
        if ("history".equals(action)) return history();
        if ("export".equals(action)) return exportAll();
        throw new IllegalArgumentException("Nieobsługiwana operacja pamięci.");
    }

    private static String bounded(String value, int length) {
        return value == null ? "" : value.trim().substring(0, Math.min(length, value.trim().length()));
    }

    private static String safeUrl(String value) {
        try {
            if (value == null || value.length() > 2000) return "";
            Uri uri = Uri.parse(value.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())))
                return "";
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if ("localhost".equals(host) || host.endsWith(".localhost") ||
                host.matches("^(10|127|192\\.168)\\..*") ||
                host.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")) return "";
            // Keep query parameters: different document URLs may genuinely identify different documents.
            return uri.buildUpon().fragment(null).authority(
                (uri.getPort() == -1 ? host : host + ":" + uri.getPort())
            ).build().toString();
        } catch (RuntimeException e) { return ""; }
    }

    private static String fingerprint(String title, String snippet, String date) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((title + "\n" + snippet + "\n" + date)
                .getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return value.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private JSONObject saveSearch(JSONObject arg) throws Exception {
        String question = bounded(arg.optString("question", ""), MAX_QUESTION);
        String answer = bounded(arg.optString("answer", ""), MAX_RESPONSE);
        if (question.isEmpty()) throw new IllegalArgumentException("Brak pytania do zapisu.");
        JSONArray sources = arg.optJSONArray("sources");
        if (sources == null) sources = new JSONArray();
        if (sources.length() > MAX_SOURCES_PER_SCAN)
            throw new IllegalArgumentException("Przekroczono limit źródeł jednego skanu.");
        long now = System.currentTimeMillis();
        int added = 0, changed = 0, repeated = 0, invalid = 0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues search = new ContentValues();
            search.put("question", question);
            search.put("answer", answer);
            search.put("searched_at", now);
            search.put("result_count", sources.length());
            db.insertOrThrow("searches", null, search);
            for (int i = 0; i < sources.length(); i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source == null) { invalid++; continue; }
                String url = safeUrl(source.optString("url", ""));
                if (url.isEmpty()) { invalid++; continue; }
                String title = bounded(source.optString("title", ""), 280);
                String snippet = bounded(source.optString("snippet", ""), 1800);
                String published = bounded(source.optString("publishedAt",
                    source.optString("published", source.optString("date", ""))), 80);
                String digest = fingerprint(title, snippet, published);
                String previous = null;
                try (Cursor cursor = db.query("sources", new String[]{"fingerprint"},
                    "url=?", new String[]{url}, null, null, null)) {
                    if (cursor.moveToFirst()) previous = cursor.getString(0);
                }
                ContentValues values = new ContentValues();
                values.put("title", title);
                values.put("snippet", snippet);
                values.put("published_at", published);
                values.put("last_seen", now);
                values.put("fingerprint", digest);
                values.put("last_query", question);
                if (previous == null) {
                    values.put("url", url);
                    values.put("first_seen", now);
                    values.put("status", "link_found");
                    db.insertOrThrow("sources", null, values);
                    added++;
                } else {
                    if (!previous.equals(digest)) {
                        values.put("changed_at", now);
                        values.put("status", "link_found");
                        changed++;
                    } else repeated++;
                    db.update("sources", values, "url=?", new String[]{url});
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return new JSONObject().put("saved", true).put("new", added)
            .put("changed", changed).put("known", repeated)
            .put("ignored", invalid).put("at", now);
    }

    private JSONObject browse(JSONObject arg) throws Exception {
        String topic = bounded(arg.optString("topic", ""), 160);
        String status = bounded(arg.optString("status", "all"), 30);
        if (!("all".equals(status) || "link_found".equals(status) || "reviewed".equals(status)))
            throw new IllegalArgumentException("Nieprawidłowy filtr źródeł.");
        String selection = "1=1";
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (!topic.isEmpty()) {
            selection += " AND (title LIKE ? ESCAPE '\\' OR snippet LIKE ? ESCAPE '\\' OR last_query LIKE ? ESCAPE '\\')";
            String like = "%" + topic.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (!"all".equals(status)) { selection += " AND status=?"; args.add(status); }
        JSONArray results = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query("sources",
            new String[]{"url", "title", "snippet", "published_at", "first_seen",
                "last_seen", "changed_at", "status", "last_query"},
            selection, args.toArray(new String[0]), null, null, "last_seen DESC", "50")) {
            while (cursor.moveToNext()) {
                results.put(new JSONObject().put("url", cursor.getString(0))
                    .put("title", cursor.getString(1))
                    .put("snippet", cursor.getString(2))
                    .put("publishedAt", cursor.getString(3))
                    .put("firstSeen", cursor.getLong(4))
                    .put("lastSeen", cursor.getLong(5))
                    .put("changedAt", cursor.getLong(6))
                    .put("status", cursor.getString(7))
                    .put("lastQuery", cursor.getString(8)));
            }
        }
        return new JSONObject().put("items", results).put("topic", topic).put("status", status);
    }

    private JSONObject history() throws Exception {
        JSONArray items = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query("searches",
            new String[]{"id","question","answer","searched_at","result_count"},
            null, null, null, null, "searched_at DESC, id DESC", "30")) {
            while (cursor.moveToNext()) {
                items.put(new JSONObject().put("id", cursor.getLong(0))
                    .put("question",cursor.getString(1))
                    .put("answer",cursor.getString(2))
                    .put("at",cursor.getLong(3))
                    .put("count",cursor.getInt(4)));
            }
        }
        return new JSONObject().put("items",items);
    }

    private JSONObject setReviewed(JSONObject arg) throws Exception {
        String url = safeUrl(arg.optString("url", ""));
        if (url.isEmpty()) throw new IllegalArgumentException("Nieprawidłowy adres źródła.");
        if (!arg.optBoolean("confirmed", false))
            throw new IllegalArgumentException("Oznaczenie wymaga potwierdzenia redaktora.");
        ContentValues values = new ContentValues();
        values.put("status", "reviewed");
        int updated = getWritableDatabase().update("sources", values, "url=?",
            new String[]{url});
        return new JSONObject().put("updated",updated).put("status","reviewed");
    }

    private JSONObject deleteSource(JSONObject arg) throws Exception {
        String url = safeUrl(arg.optString("url", ""));
        if (url.isEmpty()) throw new IllegalArgumentException("Nieprawidłowy adres źródła.");
        if (!arg.optBoolean("confirmed", false))
            throw new IllegalArgumentException("Usunięcie wymaga potwierdzenia.");
        int deleted = getWritableDatabase().delete("sources", "url=?", new String[]{url});
        return new JSONObject().put("deleted",deleted);
    }

    private JSONObject exportAll() throws Exception {
        // Export data only; caller performs Android SAF document creation.
        JSONArray sources = new JSONArray(), searches = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT url,title,snippet,published_at,first_seen,last_seen,changed_at,status,last_query FROM sources",null)) {
            while(c.moveToNext())sources.put(new JSONObject()
                .put("url",c.getString(0)).put("title",c.getString(1)).put("snippet",c.getString(2))
                .put("publishedAt",c.getString(3)).put("firstSeen",c.getLong(4))
                .put("lastSeen",c.getLong(5)).put("changedAt",c.getLong(6))
                .put("status",c.getString(7)).put("lastQuery",c.getString(8)));
        }
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT question,answer,searched_at,result_count FROM searches",null)) {
            while(c.moveToNext())searches.put(new JSONObject()
                .put("question",c.getString(0)).put("answer",c.getString(1))
                .put("at",c.getLong(2)).put("count",c.getInt(3)));
        }
        return new JSONObject().put("format","IL_EDITORIAL_MEMORY_V1")
            .put("exportedAt",System.currentTimeMillis())
            .put("sources",sources).put("searches",searches);
    }
}
