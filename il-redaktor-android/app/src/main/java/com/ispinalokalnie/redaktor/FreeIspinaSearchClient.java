package com.ispinalokalnie.redaktor;

import android.content.Context;
import android.net.Uri;
import android.text.Html;
import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Public RSS only: no AI inference, tokens, billing or application backend. */
public final class FreeIspinaSearchClient {
    private FreeIspinaSearchClient() {}
    private static final Map<String, JSONObject> CACHE = new LinkedHashMap<>();

    public static JSONObject ask(Context context, String prompt, ChatGptPlanClient.SearchControl control) throws Exception {
        return search(context, prompt, control, 0L, false);
    }
    public static JSONObject search(Context context, String prompt, ChatGptPlanClient.SearchControl control,
                                    long since, boolean datedOnly) throws Exception {
        try {
            control.check();
            String topic = topic(prompt);
            if (!datedOnly) synchronized (CACHE) {
                JSONObject previous = CACHE.get(topic);
                if (previous != null && System.currentTimeMillis() - previous.optLong("searchedAt") < 300000L)
                    return new JSONObject(previous.toString()).put("cached", true);
            }
            String query = "Ispina Drwinia" + (topic.isEmpty() ? "" : " " + topic);
            String encoded = java.net.URLEncoder.encode(query, "UTF-8");
            String[] feeds = {
                "https://news.google.com/rss/search?q=" + encoded + "&hl=pl&gl=PL&ceid=PL:pl",
                "https://www.bing.com/search?format=rss&q=" + encoded,
                "https://eko.drwinia.pl/feed/"
            };
            LinkedHashMap<String, JSONObject> found = new LinkedHashMap<>();
            int successful = 0, failed = 0;
            for (String feed : feeds) {
                control.check();
                try {
                    JSONArray items = parseFeed(fetch(feed, control), since, datedOnly);
                    successful++;
                    for (int i=0; i<items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (matchesTopic(item, topic)) found.put(item.getString("url"), item);
                    }
                } catch (Exception ex) { control.check(); failed++; }
            }
            control.check();
            if (successful == 0) throw new Exception("Nie udało się pobrać publicznych źródeł. Sprawdź internet i spróbuj później. Nie wykonano zapytania do płatnego AI.");
            JSONArray sources = new JSONArray();
            StringBuilder answer = new StringBuilder();
            for (JSONObject item : found.values()) {
                if (sources.length() >= 24) break;
                sources.put(item);
                answer.append(sources.length()).append(". ").append(item.getString("title"));
                String date = item.optString("published", "");
                answer.append(date.isEmpty() ? " — data niepodana" : " — " + date);
                String snippet = item.optString("snippet", "");
                if (!snippet.isEmpty()) answer.append("\n").append(snippet);
                answer.append("\n\n");
            }
            if (sources.length() == 0)
                answer.append("W dostępnych kanałach nie znaleziono pasujących materiałów o Ispinie")
                    .append(datedOnly ? " z potwierdzoną datą publikacji w badanym okresie." : ".")
                    .append(" To nie oznacza, że w internecie nie ma innych informacji.");
            else answer.insert(0, "Znalezione materiały do przeczytania w oryginalnych źródłach. To lista wyników, bez automatycznej oceny prawdziwości.\n\n");
            if (failed > 0) answer.append("\nCzęść kanałów jest chwilowo niedostępna: ").append(failed).append(".");
            JSONObject result = new JSONObject().put("freeSearch", true).put("answer", answer.toString())
                .put("text", answer.toString()).put("sources", sources).put("verified", sources.length()>0)
                .put("queries", new JSONArray().put(query)).put("searchedAt", System.currentTimeMillis())
                .put("successfulFeeds", successful).put("failedFeeds", failed);
            if (!datedOnly) synchronized (CACHE) {
                if (CACHE.size() >= 8) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(topic, new JSONObject(result.toString()));
            }
            return result;
        } finally { control.close(); }
    }

    private static boolean matchesTopic(JSONObject item, String topic) {
        if (topic.isEmpty()) return true;
        String text=(item.optString("title")+" "+item.optString("snippet")).toLowerCase(Locale.ROOT);
        if ("paczkomat".equals(topic)) return text.contains("pacz") || text.contains("inpost");
        if ("transport".equals(topic)) return text.contains("transport") || text.contains("autobus") || text.contains("komunik") || text.contains("linia") || text.contains("mld");
        if ("OSP".equals(topic)) return text.matches("(?s).*\\\\bosp\\\\b.*") || text.contains("straż");
        return text.contains(topic);
    }

    private static String topic(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        // Long editorial instructions use the broad radar, rather than leaking the whole prompt to a search provider.
        if (text.length() > 300) return "";
        if (text.contains("pacz") || text.contains("inpost")) return "paczkomat";
        if (text.contains("most")) return "most";
        if (text.contains("transpor") || text.contains("autobus")) return "transport";
        if (text.contains("osp") || text.contains("straż")) return "OSP";
        if (text.contains("przetarg")) return "przetarg";
        if (text.contains("histori")) return "historia";
        return "";
    }

    private static String fetch(String address, ChatGptPlanClient.SearchControl control) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            control.attach(connection);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(7000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml");
            connection.setRequestProperty("User-Agent", "IL-Redaktor-Smart/1.27 PublicRSS");
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("Kanał RSS: HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    control.check();
                    if (out.size()+count > 600000) throw new IOException("Kanał RSS jest za duży.");
                    out.write(buffer, 0, count);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    static JSONArray parseFeed(String xml, long since, boolean datedOnly) throws Exception {
        String upper = xml.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new IOException("Niedozwolona deklaracja XML.");
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        JSONArray items = new JSONArray();
        String title="",link="",description="",date="";
        boolean inItem=false, feed=false;
        int scanned=0;
        for (int event=parser.getEventType(); event!=XmlPullParser.END_DOCUMENT; event=parser.next()) {
            String name = parser.getName();
            if (event==XmlPullParser.START_TAG) {
                if ("rss".equals(name) || "feed".equals(name)) feed=true;
                if ("item".equals(name)) { inItem=true; title="";link="";description="";date=""; }
                else if(inItem) {
                    if ("title".equals(name)) title=plain(parser.nextText(),240);
                    else if("link".equals(name)) link=parser.nextText().trim();
                    else if("description".equals(name)) description=plain(parser.nextText(),450);
                    else if("pubDate".equals(name)) date=parser.nextText().trim();
                }
            } else if(event==XmlPullParser.END_TAG && "item".equals(name)) {
                inItem=false;
                if (++scanned>100) break;
                if (!(title+" "+description).toLowerCase(Locale.ROOT).contains("ispin")) continue;
                Uri uri=Uri.parse(link);
                if(uri.getHost()==null || uri.getUserInfo()!=null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) continue;
                long published=parseDate(date);
                if(datedOnly && (published<=0L || published<since || published>System.currentTimeMillis()+86400000L)) continue;
                if(title.isEmpty()) continue;
                items.put(new JSONObject().put("title",title).put("url",link).put("snippet",description)
                    .put("published",date).put("publishedAt",published));
            }
        }
        if (!feed) throw new IOException("Źródło nie zwróciło kanału RSS.");
        return items;
    }
    private static String plain(String text, int max) {
        String value=Html.fromHtml(text==null?"":text,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();
        return value.substring(0,Math.min(max,value.length()));
    }
    private static long parseDate(String value) {
        for(String pattern : new String[]{"EEE, dd MMM yyyy HH:mm:ss Z","EEE, d MMM yyyy HH:mm:ss Z","yyyy-MM-dd'T'HH:mm:ssXXX"}) {
            try { SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US); f.setLenient(false); return f.parse(value).getTime(); }
            catch(Exception ignored) {}
        }
        return 0L;
    }
}
