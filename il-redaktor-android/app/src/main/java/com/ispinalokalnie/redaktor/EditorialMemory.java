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
    private static final int VERSION = 2;
    private static final int MAX_SOURCES_PER_SCAN = 100;
    private static final int MAX_RESPONSE = 8000;
    private static final int MAX_QUESTION = 2000;

    public EditorialMemory(Context context) {
        this(context,"il-editorial-memory-v1.db");
    }

    EditorialMemory(Context context,String databaseName) {
        super(context.getApplicationContext(), databaseName, null, VERSION);
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
        migrateV2(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) migrateV2(db);
        // Additive migration: preserve source URLs, reviews and search history.
        if (oldVersion > newVersion) throw new IllegalStateException("Unsupported database downgrade.");
    }

    private void migrateV2(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE sources ADD COLUMN date_kind TEXT NOT NULL DEFAULT 'UNCLASSIFIED'");
        db.execSQL("ALTER TABLE sources ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE sources ADD COLUMN notes TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE sources ADD COLUMN links TEXT NOT NULL DEFAULT '[]'");
        db.execSQL("ALTER TABLE sources ADD COLUMN discovery TEXT NOT NULL DEFAULT 'known'");
        db.execSQL("CREATE VIRTUAL TABLE source_fts USING fts4(url, title, snippet, notes, last_query, tokenize=unicode61)");
        db.execSQL("INSERT INTO source_fts SELECT url,title,snippet,notes,last_query FROM sources");
        db.execSQL("CREATE TRIGGER source_fts_insert AFTER INSERT ON sources BEGIN INSERT INTO source_fts VALUES(new.url,new.title,new.snippet,new.notes,new.last_query); END");
        db.execSQL("CREATE TRIGGER source_fts_delete AFTER DELETE ON sources BEGIN DELETE FROM source_fts WHERE url=old.url; END");
        db.execSQL("CREATE TRIGGER source_fts_update AFTER UPDATE ON sources BEGIN DELETE FROM source_fts WHERE url=old.url; INSERT INTO source_fts VALUES(new.url,new.title,new.snippet,new.notes,new.last_query); END");
    }

    private JSONObject annotate(JSONObject arg) throws Exception {
        String url = safeUrl(arg.optString("url"));
        if (url.isEmpty()) throw new IllegalArgumentException("Nieprawidłowy adres źródła.");
        ContentValues values = new ContentValues();
        if (arg.has("notes")) values.put("notes", bounded(arg.optString("notes"), 6000));
        if (arg.has("draftId")) {
            String id = arg.optString("draftId");
            if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Nieprawidłowy identyfikator szkicu.");
            JSONArray links = new JSONArray();
            try (Cursor c = getReadableDatabase().query("sources", new String[]{"links"}, "url=?", new String[]{url}, null, null, null)) {
                if (c.moveToFirst()) links = new JSONArray(c.getString(0));
            }
            JSONObject link = new JSONObject().put("draftId", id)
                .put("title", bounded(arg.optString("title"), 280))
                .put("postId", Math.max(0, arg.optLong("postId", 0)));
            boolean replaced = false;
            for (int i=0;i<links.length();i++) if (id.equals(links.getJSONObject(i).optString("draftId"))) { links.put(i,link); replaced=true; break; }
            if (!replaced) { if(links.length()>=200) throw new IllegalArgumentException("Źródło ma już 200 powiązań."); links.put(link); }
            values.put("links",links.toString());
        }
        if (values.size()==0) throw new IllegalArgumentException("Brak notatki lub powiązania.");
        return new JSONObject().put("updated",getWritableDatabase().update("sources",values,"url=?",new String[]{url}));
    }

    // Stable JSON ordering makes the digest portable across Android versions.
    private static String canonical(Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;
            java.util.TreeSet<String> keys=new java.util.TreeSet<>();
            java.util.Iterator<String> it=object.keys();while(it.hasNext())keys.add(it.next());
            StringBuilder s=new StringBuilder("{");
            for(String key:keys){if(s.length()>1)s.append(',');s.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}
            return s.append('}').toString();
        }
        if(value instanceof JSONArray) {
            JSONArray a=(JSONArray)value;StringBuilder s=new StringBuilder("[");
            for(int i=0;i<a.length();i++){if(i>0)s.append(',');s.append(canonical(a.get(i)));}return s.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String)value) : String.valueOf(value);
    }

    public synchronized JSONObject execute(String action, JSONObject arg) throws Exception {
        if ("annotate".equals(action)) return annotate(arg);
        if ("save".equals(action)) return saveSearch(arg);
        if ("browse".equals(action)) return browse(arg);
        if ("review".equals(action)) return setReviewed(arg);
        if ("delete".equals(action)) return deleteSource(arg);
        if ("history".equals(action)) return history();
        if ("export".equals(action)) return exportAll();
        if ("import".equals(action)) return importAll(arg);
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
                String kind="PUBLISHED".equals(source.optString("dateType"))?"PUBLISHED":
                    "UPDATED_ONLY".equals(source.optString("dateType"))?"UPDATED_ONLY":"UNCLASSIFIED";
                String updated="UPDATED_ONLY".equals(kind)?published:bounded(source.optString("updatedAt",""),80);
                if("0".equals(published)||"UPDATED_ONLY".equals(kind))published="";
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
                values.put("updated_at",updated);values.put("date_kind",kind);
                values.put("last_seen", now);
                values.put("fingerprint", digest);
                values.put("last_query", question);
                if (previous == null) {
                    values.put("url", url);
                    values.put("first_seen", now);
                    values.put("status", "link_found");
                    values.put("discovery", "new");
                    db.insertOrThrow("sources", null, values);
                    added++;
                } else {
                    if (!previous.equals(digest)) {
                        values.put("changed_at", now);
                        values.put("status", "link_found");
                        values.put("discovery", "changed");
                        changed++;
                    } else { repeated++;
                        // Keep an unreviewed change visible until explicit review.
                        db.execSQL("UPDATE sources SET discovery='known' WHERE url=? AND discovery!='changed'",new Object[]{url});
                    }
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
        if (!("all".equals(status) || "link_found".equals(status) || "reviewed".equals(status) || "new".equals(status) || "known".equals(status) || "changed".equals(status)))
            throw new IllegalArgumentException("Nieprawidłowy filtr źródeł.");
        String selection = "1=1";
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        if (!topic.isEmpty()) {
            StringBuilder match=new StringBuilder();
            for(String word:topic.split("[^\\p{L}\\p{N}]+")) {
                if(word.isEmpty())continue;
                if(match.length()>0)match.append(" AND ");
                match.append('"').append(word).append("*\"");
            }
            if(match.length()>0){selection+=" AND url IN (SELECT url FROM source_fts WHERE source_fts MATCH ?)";args.add(match.toString());}
        }
        if (!"all".equals(status)) { selection += ("new".equals(status)||"known".equals(status)||"changed".equals(status)) ? " AND discovery=?" : " AND status=?"; args.add(status); }
        int offset=Math.max(0,Math.min(1000000,arg.optInt("offset",0)));
        long total;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM sources WHERE "+selection,args.toArray(new String[0]))){c.moveToFirst();total=c.getLong(0);}
        JSONArray results = new JSONArray();
        try (Cursor cursor = getReadableDatabase().query("sources",
            new String[]{"url", "title", "snippet", "published_at", "first_seen",
                "last_seen", "changed_at", "status", "last_query", "notes", "links", "discovery", "date_kind", "updated_at"},
            selection, args.toArray(new String[0]), null, null, "last_seen DESC, url ASC", offset+",50")) {
            while (cursor.moveToNext()) {
                results.put(new JSONObject().put("url", cursor.getString(0))
                    .put("title", cursor.getString(1))
                    .put("snippet", cursor.getString(2))
                    .put("publishedAt", cursor.getString(3))
                    .put("firstSeen", cursor.getLong(4))
                    .put("lastSeen", cursor.getLong(5))
                    .put("changedAt", cursor.getLong(6))
                    .put("status", cursor.getString(7))
                    .put("lastQuery", cursor.getString(8))
                    .put("notes",cursor.getString(9)).put("links",new JSONArray(cursor.getString(10)))
                    .put("discovery",cursor.getString(11)).put("dateType",cursor.getString(12)).put("updatedAt",cursor.getString(13)));
            }
        }
        return new JSONObject().put("items", results).put("topic", topic).put("status", status).put("offset",offset).put("total",total).put("hasMore",offset+results.length()<total);
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
        values.put("discovery", "known");
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


    /**
     * Imports a versioned export transactionally, without clearing existing sources or
     * searches. Existing reviewed links are not overwritten by older backup metadata.
     * Returns counts for a clear user-facing restoration summary.
     */
    private JSONObject importAll(JSONObject archive) throws Exception {
        if (!"IL_EDITORIAL_MEMORY_V1".equals(archive.optString("format")))
            throw new IllegalArgumentException("Nieobsługiwany format kopii pamięci.");
        JSONArray sources = archive.optJSONArray("sources");
        JSONArray searches = archive.optJSONArray("searches");
        if (sources == null || searches == null || sources.length() > 6000 ||
            searches.length() > 3000)
            throw new IllegalArgumentException("Niepoprawna struktura lub rozmiar archiwum.");
        if (archive.optInt("schemaVersion",1)>2) throw new IllegalArgumentException("Kopia pochodzi z nowszej wersji aplikacji.");
        String expected=archive.optString("sha256","");
        if(archive.optInt("schemaVersion",1)>=2 && expected.isEmpty()) throw new IllegalArgumentException("Brak sumy kontrolnej kopii.");
        if(!expected.isEmpty() && !expected.equals(fingerprint(canonical(sources),canonical(searches),"")))
            throw new IllegalArgumentException("Suma kontrolna kopii nie zgadza się. Dane pozostają bez zmian.");
        int inserted=0, existing=0, skipped=0, restoredSearches=0;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i=0; i<sources.length(); i++) {
                JSONObject src = sources.optJSONObject(i);
                if (src == null) { skipped++; continue; }
                String url=safeUrl(src.optString("url", ""));
                if (url.isEmpty()) { skipped++; continue; }
                boolean found;
                try (Cursor cursor=db.query("sources", new String[]{"url"},
                    "url=?", new String[]{url}, null, null, null, "1")) {
                    found=cursor.moveToFirst();
                }
                if (found) { existing++; continue; }
                String title=bounded(src.optString("title", ""), 280);
                String snippet=bounded(src.optString("snippet", ""), 1800);
                String date=bounded(src.optString("publishedAt", ""), 80);
                long first=Math.max(1L,src.optLong("firstSeen",System.currentTimeMillis()));
                long last=Math.max(first,src.optLong("lastSeen",first));
                ContentValues values=new ContentValues();
                values.put("url",url);values.put("title",title);
                values.put("snippet",snippet);values.put("published_at",date);
                values.put("first_seen",first);values.put("last_seen",last);
                values.put("changed_at",Math.max(0L,src.optLong("changedAt",0L)));
                values.put("fingerprint",fingerprint(title,snippet,date));
                values.put("status","reviewed".equals(src.optString("status"))?"reviewed":"link_found");
                values.put("last_query",bounded(src.optString("lastQuery",""),MAX_QUESTION));
                values.put("notes",bounded(src.optString("notes",""),6000));
                String kind=src.optString("dateType","UNCLASSIFIED");
                values.put("date_kind",("PUBLISHED".equals(kind)||"UPDATED_ONLY".equals(kind))?kind:"UNCLASSIFIED");
                values.put("updated_at",bounded(src.optString("updatedAt",""),80));
                JSONArray links=src.optJSONArray("links");
                JSONArray safeLinks=new JSONArray();
                if(links!=null){if(links.length()>200)throw new IllegalArgumentException("Za dużo powiązań w kopii.");
                    for(int j=0;j<links.length();j++){JSONObject link=links.getJSONObject(j);String id=link.optString("draftId");
                        if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("Nieprawidłowe powiązanie szkicu.");
                        safeLinks.put(new JSONObject().put("draftId",id).put("title",bounded(link.optString("title"),280)).put("postId",Math.max(0,link.optLong("postId",0))));}}
                values.put("links",safeLinks.toString());
                String discovery=src.optString("discovery","known");
                values.put("discovery",("new".equals(discovery)||"changed".equals(discovery))?discovery:"known");
                db.insertOrThrow("sources",null,values);
                inserted++;
            }
            for (int i=0; i<searches.length(); i++) {
                JSONObject s=searches.optJSONObject(i);
                if (s==null) {skipped++;continue;}
                String q=bounded(s.optString("question",""),MAX_QUESTION);
                if(q.isEmpty()){skipped++;continue;}
                String answer=bounded(s.optString("answer",""),MAX_RESPONSE);
                long at=Math.max(1L,s.optLong("at",0L));
                boolean found;
                try(Cursor cursor=db.query("searches",new String[]{"id"},
                    "question=? AND searched_at=?",new String[]{q,String.valueOf(at)},null,null,null,"1")){
                    found=cursor.moveToFirst();
                }
                if(found)continue;
                ContentValues values=new ContentValues();
                values.put("question",q);values.put("answer",answer);
                values.put("searched_at",at);
                values.put("result_count",Math.max(0,Math.min(10000,s.optInt("count",0))));
                db.insertOrThrow("searches",null,values);
                restoredSearches++;
            }
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
        return new JSONObject().put("imported",inserted).put("existing",existing)
            .put("skipped",skipped).put("searches",restoredSearches);
    }

    private JSONObject exportAll() throws Exception {
        // Export data only; caller performs Android SAF document creation.
        JSONArray sources = new JSONArray(), searches = new JSONArray();
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT url,title,snippet,published_at,first_seen,last_seen,changed_at,status,last_query,notes,links,discovery,date_kind,updated_at FROM sources",null)) {
            while(c.moveToNext())sources.put(new JSONObject()
                .put("url",c.getString(0)).put("title",c.getString(1)).put("snippet",c.getString(2))
                .put("publishedAt",c.getString(3)).put("firstSeen",c.getLong(4))
                .put("lastSeen",c.getLong(5)).put("changedAt",c.getLong(6))
                .put("status",c.getString(7)).put("lastQuery",c.getString(8))
                .put("notes",c.getString(9)).put("links",new JSONArray(c.getString(10))).put("discovery",c.getString(11)).put("dateType",c.getString(12)).put("updatedAt",c.getString(13)));
        }
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT question,answer,searched_at,result_count FROM searches",null)) {
            while(c.moveToNext())searches.put(new JSONObject()
                .put("question",c.getString(0)).put("answer",c.getString(1))
                .put("at",c.getLong(2)).put("count",c.getInt(3)));
        }
        return new JSONObject().put("format","IL_EDITORIAL_MEMORY_V1")
            .put("schemaVersion",2).put("sha256",fingerprint(canonical(sources),canonical(searches),""))
            .put("exportedAt",System.currentTimeMillis())
            .put("sources",sources).put("searches",searches);
    }
}
