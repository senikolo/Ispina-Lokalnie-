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
            // Search several independent indexes and source-specific domains. No paid API key.
            // Indexed results are NOT a guarantee of exhaustive BIP or PDF coverage.
            String baseQuery = "Ispina Drwinia" + (topic.isEmpty() ? "" : " " + topic);
            String[][] profiles = {
                {"Google News", "news", baseQuery},
                {"Bing ogólny", "bing", baseQuery},
                {"Urząd Gminy Drwinia", "bing", "Ispina site:drwinia.pl " + topic},
                {"BIP Małopolska", "bing", "Ispina site:bip.malopolska.pl " + topic},
                {"Powiat bocheński", "bing", "Ispina site:powiat.bochnia.pl " + topic},
                {"Zarząd Dróg Wojewódzkich", "bing", "Ispina site:zdw.krakow.pl " + topic},
                {"Zamówienia publiczne", "bing", "Ispina site:ezamowienia.gov.pl " + topic},
                {"Bochnianin", "bing", "Ispina site:bochnianin.pl " + topic},
                {"E-Bochnia", "bing", "Ispina site:e-bochnia.pl " + topic},
                {"Radio Kraków", "bing", "Ispina site:radiokrakow.pl " + topic},
                {"Małopolska", "bing", "Ispina site:malopolska.pl " + topic},
                {"Koleje Małopolskie", "bing", "Ispina site:kolejemalopolskie.com.pl " + topic},
                {"Ministerstwa i instytucje", "bing", "Ispina site:gov.pl " + topic},
                {"Odmiana nazwy", "bing", "\"w Ispinie\" OR \"do Ispiny\" OR \"z Ispiny\" Drwinia " + topic},
                {"Eko-Drwinia", "rss", "https://eko.drwinia.pl/feed/"}
            };
            LinkedHashMap<String, JSONObject> found = new LinkedHashMap<>();
            JSONArray searchedQueries = new JSONArray(), profileResults = new JSONArray();
            int successful = 0, failed = 0, skipped = 0;
            long started = System.currentTimeMillis();
            for (int index=0; index<profiles.length; index++) {
                control.check();
                // Leave enough time to assemble the report and return partial results.
                if (System.currentTimeMillis() - started > 51000L) {
                    skipped = profiles.length - index;
                    for(int next=index; next<profiles.length;next++)
                        profileResults.put(new JSONObject().put("name",profiles[next][0])
                            .put("status","skipped_timeout").put("newLinks",0));
                    break;
                }
                String[] profile = profiles[index];
                String address = "rss".equals(profile[1]) ? profile[2] :
                    ("news".equals(profile[1]) ?
                        "https://news.google.com/rss/search?q=" + java.net.URLEncoder.encode(profile[2],"UTF-8") + "&hl=pl&gl=PL&ceid=PL:pl" :
                        "https://www.bing.com/search?format=rss&q=" + java.net.URLEncoder.encode(profile[2],"UTF-8"));
                searchedQueries.put(profile[2]);
                try {
                    JSONArray items = parseFeed(fetch(address, control), since, datedOnly);
                    successful++;
                    int matching = 0;
                    for (int i=0; i<items.length(); i++) {
                        JSONObject item = items.getJSONObject(i);
                        if (matchesTopic(item, topic)) {
                            String key = canonicalKey(item.getString("url"));
                            if (!found.containsKey(key)) matching++;
                            found.put(key, item);
                        }
                    }
                    profileResults.put(new JSONObject().put("name",profile[0]).put("status","ok").put("newLinks",matching));
                } catch (Exception ex) {
                    control.check();
                    failed++;
                    profileResults.put(new JSONObject().put("name",profile[0]).put("status","unavailable"));
                }
            }
            control.check();
            if (successful == 0) throw new Exception("Nie udało się pobrać publicznych źródeł. Sprawdź internet i spróbuj później. Nie wykonano zapytania do płatnego AI.");
            JSONArray sources = new JSONArray();
            StringBuilder answer = new StringBuilder();
            List<JSONObject> ordered = new ArrayList<>(found.values());
            ordered.sort((a,b) -> Long.compare(b.optLong("publishedAt",0),a.optLong("publishedAt",0)));
            for (JSONObject item : ordered) {
                if (sources.length() >= 50) break;
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
            if (failed > 0 || skipped > 0)
                answer.append("\nSkan częściowy: niedostępne kanały: ").append(failed)
                      .append(", pominięte z powodu limitu czasu: ").append(skipped).append(".");
            JSONObject result = new JSONObject().put("freeSearch", true).put("answer", answer.toString())
                 .put("text", answer.toString()).put("sources", sources).put("verified", false)
                .put("queries", searchedQueries).put("searchedAt", System.currentTimeMillis())
                .put("profiles", profileResults).put("plannedFeeds",profiles.length)
                .put("successfulFeeds", successful).put("failedFeeds", failed)
                .put("skippedFeeds", skipped).put("partialResults", failed > 0 || skipped > 0)
                .put("sourceLinksFound", sources.length()>0)
                .put("sourceReviewStatus", "DO_SPRAWDZENIA")
                .put("sourcesAreFactChecked", false).put("dateFilterApplied", datedOnly)
                .put("since", since);
            if (!datedOnly) synchronized (CACHE) {
                if (CACHE.size() >= 8) CACHE.remove(CACHE.keySet().iterator().next());
                CACHE.put(topic, new JSONObject(result.toString()));
            }
            return result;
        } finally { control.close(); }
    }

    /** Ignore fragments and common tracking parameters without losing distinct documents. */
    private static String canonicalKey(String raw) {
        try {
            Uri uri=Uri.parse(raw);
            Uri.Builder b=uri.buildUpon().fragment(null).clearQuery();
            Set<String> keys=uri.getQueryParameterNames();
            for(String key:keys) {
                String low=key.toLowerCase(Locale.ROOT);
                if(low.startsWith("utm_")||"gclid".equals(low)||"fbclid".equals(low)||"yclid".equals(low))continue;
                for(String value:uri.getQueryParameters(key))b.appendQueryParameter(key,value);
            }
            return b.build().toString();
        }catch(Exception e){return raw;}
    }

    static boolean matchesTopic(JSONObject item, String topic) {
        if (topic.isEmpty()) return true;
        String text = (item.optString("title") + " " + item.optString("snippet")).toLowerCase(Locale.ROOT);
        if ("paczkomat".equals(topic)) return text.contains("pacz") || text.contains("inpost");
        if ("transport".equals(topic)) return text.contains("transport") || text.contains("autobus") || text.contains("komunik") || text.contains("linia") || text.contains("mld");
        if ("osp".equals(topic)) return text.matches("(?s).*\\bosp\\b.*") || text.contains("straż");
        // Each important term must appear. Matching ANY of "most kapliczka"
        // returned articles about just the bridge and was misleading.
        int tokens = 0, matched = 0;
        for (String word : topic.split("\\s+")) {
            if (word.length() < 4) continue;
            tokens++;
            String root = word.substring(0, Math.min(5,word.length()));
            if (text.contains(root)) matched++;
        }
        return tokens == 0 || matched == tokens;
    }

    static String topic(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).trim();
        // Long instructions belong to the broad radar; never send entire
        // editorial drafts, personal text, or source notes to search providers.
        if (text.length() > 300) return "";
        // A broad brief is not a single search topic. Feed queries stay short
        // and do not try to match every category in one document.
        if ((text.contains("ostatnich") || text.contains("ostatnie")) &&
            (text.contains("lokalne media") || text.contains("źródła urzędowe")
                || text.contains("przetargi") || text.contains("wszystkie"))) return "";
        if (text.contains("paczkomat") || text.contains("inpost")) return "paczkomat";
        if (text.matches("(?s).*\\bosp\\b.*") || text.contains("straż pożarn")) return "osp";
        if (text.contains("autobus") || text.contains("komunikacj") || text.contains("transport")) return "transport";
        text = text.replaceAll("(?iu)\\b(?:czy|są|jest|jakie|co|nowego|nowe|informacje|wiadomo|wiadomości|aktualne|aktualności|wyszukaj|znajdź|sprawdź|ostatnie|ostatnich|ostatnim|dni|dniach|tygodniu|miesiącu|temat|dotyczące|dotyczy|a|no|kiedy|gdzie|ile|jak|dlaczego|kto|więcej|jeszcze|najnowsze|nowości|poszukaj|szukam|szukaj|materiały|materiałów|archiwalne|archiwalnych|źródła|źródeł|opublikowano|wydarzenia|zdarzenia|wieś|wsi|o|w|we|na|z|do|i|oraz|dla|ispina|ispinie|ispiny|gminie|gmina|drwinia|drwini|proszę)\\b", " ");
        text = text.replaceAll("(?iu)\\bmoście\\b", "most");
        text = text.replaceAll("\\b[0-9]{1,3}\\b", " ").replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
        return text.length() > 100 ? text.substring(0,100).trim() : text;
    }

    /**
     * Free RSS search is not a conversational LLM. Only an explicit short
     * follow-up without a new subject inherits the last user's concrete topic.
     * Never send the full chat history to an external RSS provider.
     */
    static String resolveConversationPrompt(String prompt, JSONArray history) {
        if (history == null || history.length() == 0 || prompt == null) return prompt;
        String normalized = prompt.toLowerCase(Locale.ROOT).trim();
        if (!topic(normalized).isEmpty()) return prompt;
        if (!normalized.matches("(?su)^(?:a\\b|i\\b|no\\b|kiedy\\b|gdzie\\b|jak\\b|ile\\b|co\\b|czy\\b|dlaczego\\b).*"))
            return prompt;
        for (int i = history.length()-1; i >= 0; i--) {
            JSONObject entry = history.optJSONObject(i);
            if (entry == null || !"user".equals(entry.optString("role"))) continue;
            String previous = topic(entry.optString("content",""));
            if (!previous.isEmpty() && previous.length() <= 80)
                return "Ispina " + previous;
        }
        return prompt;
    }

    public static JSONObject askConversation(Context context, String prompt, JSONArray history,
                                             ChatGptPlanClient.SearchControl control) throws Exception {
        String effectivePrompt = resolveConversationPrompt(prompt, history);
        JSONObject result = search(context, effectivePrompt, control, 0L, false);
        boolean inherited = !effectivePrompt.equals(prompt);
        result.put("contextInherited", inherited)
              .put("contextTopic", inherited ? topic(effectivePrompt) : "");
        return result;
    }

    private static String fetch(String address, ChatGptPlanClient.SearchControl control) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            control.attach(connection);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml");
            connection.setRequestProperty("User-Agent", "IL-Redaktor-Smart/1.28 PublicRSS");
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
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
            throw new IOException("Niedozwolona deklaracja XML.");
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        JSONArray items = new JSONArray();
        String title="",link="",description="",date="",dateType="BRAK DATY";
        boolean inItem=false, feed=false;
        int scanned=0;
        for (int event=parser.getEventType(); event!=XmlPullParser.END_DOCUMENT; event=parser.next()) {
            String name=parser.getName();
            if (event==XmlPullParser.START_TAG) {
                if ("rss".equals(name) || "feed".equals(name)) feed=true;
                if ("item".equals(name) || "entry".equals(name)) {
                    inItem=true; title="";link="";description="";date="";dateType="BRAK DATY";
                } else if (inItem) {
                    if ("title".equals(name)) title=plain(parser.nextText(),240);
                    else if ("link".equals(name)) {
                        String href=parser.getAttributeValue(null,"href");
                        if (href!=null && !href.isEmpty()) {
                            String rel=parser.getAttributeValue(null,"rel");
                            if (rel==null || rel.isEmpty() || "alternate".equals(rel)) link=href.trim();
                        } else link=parser.nextText().trim();
                    }
                    else if ("description".equals(name) || "summary".equals(name) || "content".equals(name))
                        description=plain(parser.nextText(),450);
                    else if ("pubDate".equals(name) || "published".equals(name)) {
                        date=parser.nextText().trim();dateType="PUBLISHED";
                    } else if ("updated".equals(name)) {
                        String updated=parser.nextText().trim();
                        if (!"PUBLISHED".equals(dateType)) {date=updated;dateType="UPDATED_ONLY";}
                    }
                }
            } else if (event==XmlPullParser.END_TAG && ("item".equals(name) || "entry".equals(name))) {
                inItem=false;
                if (++scanned>100) break;
                if (!(title+" "+description).toLowerCase(Locale.ROOT).matches("(?s).*(ispin|ispni).*")) continue;
                Uri uri=Uri.parse(link);
                if (uri.getHost()==null || uri.getUserInfo()!=null ||
                    !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) continue;
                long published=parseDate(date);
                if (datedOnly && (!"PUBLISHED".equals(dateType) || published<=0L ||
                     published<since || published>System.currentTimeMillis()+86400000L)) continue;
                if (title.isEmpty()) continue;
                items.put(new JSONObject().put("title",title).put("url",link).put("snippet",description)
                    .put("published",date).put("publishedAt",published)
                     .put("dateType",dateType)
                    .put("reviewStatus","DO_SPRAWDZENIA"));
            }
        }
        if (!feed) throw new IOException("Źródło nie zwróciło kanału RSS ani Atom.");
        return items;
    }
    private static String plain(String text, int max) {
        String value=Html.fromHtml(text==null?"":text,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();
        return value.substring(0,Math.min(max,value.length()));
    }
    private static long parseDate(String value) {
        for(String pattern : new String[]{"EEE, dd MMM yyyy HH:mm:ss Z","EEE, d MMM yyyy HH:mm:ss Z","yyyy-MM-dd'T'HH:mm:ss.SSSXXX","yyyy-MM-dd'T'HH:mm:ssXXX","yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            try { SimpleDateFormat f=new SimpleDateFormat(pattern,Locale.US); f.setLenient(false); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.parse(value).getTime(); }
            catch(Exception ignored) {}
        }
        return 0L;
    }
}
