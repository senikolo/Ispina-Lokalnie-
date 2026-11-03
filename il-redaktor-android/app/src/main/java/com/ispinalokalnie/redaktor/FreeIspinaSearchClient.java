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

/** Public feeds and bounded public HTML indexes: no AI inference, tokens or billing. */
public final class FreeIspinaSearchClient {
    private FreeIspinaSearchClient() {}
    private static final Map<String, JSONObject> CACHE = new LinkedHashMap<>();

    public static JSONObject ask(Context context, String prompt, ChatGptPlanClient.SearchControl control) throws Exception {
        return search(context, prompt, control, 0L, false, true);
    }
    public static JSONObject search(Context context, String prompt, ChatGptPlanClient.SearchControl control,
                                    long since, boolean datedOnly) throws Exception {
        return search(context, prompt, control, since, datedOnly, true);
    }
    public static JSONObject search(Context context, String prompt, ChatGptPlanClient.SearchControl control,
                                    long since, boolean datedOnly, boolean readBipDocuments) throws Exception {
        try {
            control.check();
            String topic = topic(prompt);
            if (!datedOnly) synchronized (CACHE) {
                JSONObject previous = CACHE.get(topic);
                if (previous != null && System.currentTimeMillis() - previous.optLong("searchedAt") < 300000L &&
                    (!readBipDocuments || previous.optBoolean("bipDocumentsAnalyzed", false)))
                    return new JSONObject(previous.toString()).put("cached", true);
            }
            // Search several independent indexes and source-specific domains. No paid API key.
            // Indexed results are NOT a guarantee of exhaustive BIP or PDF coverage.
            String baseQuery = "Ispina" + (topic.isEmpty() ? "" : " " + topic);
            List<String[]> prioritized=new ArrayList<>();
            prioritized.add(new String[]{"Eko-Drwinia — pełna treść RSS","rss","https://eko.drwinia.pl/feed/"});
            prioritized.add(new String[]{"Gmina Drwinia — wyszukiwarka i artykuły","municipal","https://www.drwinia.pl/szukaj?f%5Bfraza%5D=Ispina"});
            // Prioritize public records before local archive, under the shared time budget.
            // Discover public-record links before the broader local archive search.
            prioritized.add(new String[]{"Dokumenty PDF — odnośniki z indeksu","bing","Ispina Drwinia filetype:pdf " + topic});
            prioritized.add(new String[]{"BIP Małopolska — indeks dokumentów","bing","Ispina site:bip.malopolska.pl " + topic});
            prioritized.add(new String[]{"BIP Gminy Drwinia — dokumenty i załączniki","bing","site:bip.malopolska.pl/ugdrwinia Ispina " + topic});
            prioritized.add(new String[]{"BIP Gminy Drwinia — pliki PDF","bing","site:bip.malopolska.pl/ugdrwinia filetype:pdf Ispina " + topic});
            prioritized.add(new String[]{"Zamówienia publiczne — indeks","bing","Ispina site:ezamowienia.gov.pl " + topic});
            prioritized.add(new String[]{"Ispina Lokalnie — Twój portal","wordpress","https://public-api.wordpress.com/rest/v1.1/sites/ispinalokalnie.wordpress.com/posts/?number=40"});
            prioritized.add(new String[]{"Bochnianin — wyszukiwanie wydawcy","rss","https://bochnianin.pl/?s=Ispina&feed=rss2"});
            prioritized.add(new String[]{"Bochnia z Bliska — wyszukiwanie wydawcy","rss","https://bochniazbliska.pl/?s=Ispina&feed=rss2"});
            prioritized.add(new String[]{"Informator Brzeski — wyszukiwanie wydawcy","rss","https://www.informatorbrzeski.pl/?s=Ispina&feed=rss2"});
            prioritized.add(new String[]{"Bochnia ON — wyszukiwanie wydawcy","rss","https://bochniaon.pl/?s=Ispina&feed=rss2"});
            prioritized.add(new String[]{"Google News — nowe publikacje","news",baseQuery});
            prioritized.add(new String[]{"Google News — odmiany nazwy","news","Ispinie"+(topic.isEmpty()?"":" "+topic)});
            prioritized.add(new String[]{"Bing — temat ogólny","bing",baseQuery});
            if("osp".equals(topic)) {
                prioritized.add(new String[]{"OSP Ispina — nazwa jednostki","bing","\"OSP Ispina\""});
                prioritized.add(new String[]{"Straż pożarna — wzmianki w treści","bing","Ispina straż pożarna"});
                prioritized.add(new String[]{"PSP — komunikaty urzędowe","bing","Ispina straż site:gov.pl"});
            }else if("most".equals(topic)) {
                prioritized.add(new String[]{"Most — remont i inwestycje","bing","Ispina most remont inwestycja"});
                prioritized.add(new String[]{"Drogi wojewódzkie 775 i 964","bing","Ispina (DW775 OR DW964 OR most)"});
                prioritized.add(new String[]{"ZDW — dokumenty dotyczące mostu","bing","Ispina most site:zdw.krakow.pl"});
            }else if("autobusy".equals(topic)) {
                prioritized.add(new String[]{"Autobusy — A4 A7 A65","bing","Ispina (A4 OR A7 OR A65 OR MLD)"});
                prioritized.add(new String[]{"Linia gminna do Krakowa","bing","Ispina Mikluszowice Kraków autobus"});
                prioritized.add(new String[]{"Autobusy — komunikaty przewoźnika","bing","Ispina site:kolejemalopolskie.com.pl"});
            }else if(topic.isEmpty()) {
                prioritized.add(new String[]{"Ispina — nowe dokumenty i komunikaty","bing","\"Ispina\" (komunikat OR ogłoszenie OR uchwała)"});
                prioritized.add(new String[]{"Ispina — inwestycje i życie lokalne","bing","\"Ispina\" (inwestycja OR OSP OR wydarzenie OR autobus)"});
            }
            String[][] baseProfiles = {

                {"Błędne odmiany nazwy", "bing", "\"Ispini\" OR \"Ispinii\" OR \"Ispni\" Drwinia " + topic},
                {"Urząd Gminy Drwinia", "bing", "Ispina site:drwinia.pl " + topic},

                {"Powiat bocheński", "bing", "Ispina site:powiat.bochnia.pl " + topic},
                {"Zarząd Dróg Wojewódzkich", "bing", "Ispina site:zdw.krakow.pl " + topic},

                {"Bochnianin", "bing", "Ispina site:bochnianin.pl " + topic},
                {"E-Bochnia", "bing", "Ispina site:e-bochnia.pl " + topic},
                {"Radio Kraków", "bing", "Ispina site:radiokrakow.pl " + topic},
                {"Małopolska", "bing", "Ispina site:malopolska.pl " + topic},
                {"Koleje Małopolskie", "bing", "Ispina site:kolejemalopolskie.com.pl " + topic},
                {"Ministerstwa i instytucje", "bing", "Ispina site:gov.pl " + topic},
                {"Odmiana nazwy", "bing", "\"w Ispinie\" OR \"do Ispiny\" OR \"z Ispiny\" Drwinia " + topic},
                {"Drwinia: bezpośredni indeks HTML", "html", "https://drwinia.pl/"},
                {"Eko-Drwinia: bezpośredni indeks HTML", "html", "https://eko.drwinia.pl/"}
            };
            prioritized.addAll(Arrays.asList(baseProfiles));
            String[][] profiles=prioritized.toArray(new String[0][]);
            LinkedHashMap<String, JSONObject> found = new LinkedHashMap<>();
            JSONArray searchedQueries = new JSONArray(), profileResults = new JSONArray();
            int successful = 0, failed = 0, skipped = 0,rawItems=0,olderItems=0;
            LinkedHashMap<String,JSONObject> undated=new LinkedHashMap<>();
            // Three bounded workers prevent slow early domains from starving the rest.
            java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(3);
            java.util.concurrent.CompletionService<JSONObject> completion=new java.util.concurrent.ExecutorCompletionService<>(pool);
            java.util.List<java.util.concurrent.Future<JSONObject>> jobs=new ArrayList<>();
            JSONObject[] responses=new JSONObject[profiles.length];
            long stop=android.os.SystemClock.elapsedRealtime()+51000L;
            try {
                for(int index=0;index<profiles.length;index++) {
                    final int id=index;final String[] profile=profiles[index];
                    searchedQueries.put(profile[2]);
                    jobs.add(completion.submit(()->fetchProfile(profile,id,topic,control,since,datedOnly)));
                }
                for(int received=0;received<profiles.length;received++) {
                    control.check();long remaining=stop-android.os.SystemClock.elapsedRealtime();if(remaining<=0)break;
                    java.util.concurrent.Future<JSONObject> next=completion.poll(Math.min(remaining,500),java.util.concurrent.TimeUnit.MILLISECONDS);
                    if(next==null){received--;continue;}
                    JSONObject response=next.get();responses[response.getInt("index")]=response;
                }
            }finally{
                for(java.util.concurrent.Future<JSONObject> job:jobs)if(!job.isDone())job.cancel(true);
                pool.shutdownNow();
            }
            for(int index=0;index<profiles.length;index++) {
                JSONObject response=responses[index];
                if(response==null){skipped++;profileResults.put(new JSONObject().put("name",profiles[index][0]).put("status","skipped_timeout").put("newLinks",0));continue;}
                String status=response.getString("status");rawItems+=response.optInt("rawItems");
                if(!"ok".equals(status)) {
                    if("unavailable".equals(status))failed++;else skipped++;
                    profileResults.put(response);continue;
                }
                successful++;int matching=0,unknown=0,older=0;JSONArray items=response.getJSONArray("items");
                for(int i=0;i<items.length();i++) {
                    JSONObject item=items.getJSONObject(i);
                    if(!matchesTopic(item,topic) && !(readBipDocuments && isDrwiniaBipCandidate(item)))continue;
                    String key=canonicalKey(item.getString("url"));
                    long published=item.optLong("publishedAt");
                    if(datedOnly&&(!"PUBLISHED".equals(item.optString("dateType"))||published<=0)) {unknown++;undated.put(key,item.put("url",key));continue;}
                    if(datedOnly&&(published<since||published>System.currentTimeMillis())){older++;olderItems++;continue;}
                    if(!found.containsKey(key))matching++;
                    // Prefer dated metadata when the same URL occurs in several indexes.
                    JSONObject previous=found.get(key);
                    if(previous==null||item.optLong("publishedAt")>previous.optLong("publishedAt"))found.put(key,item.put("url",key));
                }
                response.remove("items");response.put("newLinks",matching).put("undated",unknown).put("outsideWindow",older);profileResults.put(response);
                control.report(response.optString("name"),response.optString("address"),"DONE",response.optString("method"),matching,
                    "Odczytano "+response.optInt("rawItems")+" pozycji; w okresie: "+matching+", bez daty: "+unknown+", poza okresem: "+older+".");
            }
            control.check();
            // Empty feeds or blocked providers are an inconclusive search, never proof of no news.
            JSONArray sources = new JSONArray();
            StringBuilder answer = new StringBuilder();
            List<JSONObject> ordered = new ArrayList<>(found.values());
            ordered.sort((a,b) -> {
                int own=Boolean.compare("OWN_PORTAL".equals(a.optString("sourceKind")),"OWN_PORTAL".equals(b.optString("sourceKind")));
                return own!=0?own:Long.compare(b.optLong("publishedAt",0),a.optLong("publishedAt",0));
            });
            int external=0,own=0;
            for (JSONObject item : ordered) {
                if (sources.length() >= 50) break;
                item.remove("searchText");sources.put(item);
                if("OWN_PORTAL".equals(item.optString("sourceKind")))own++;else external++;
            }
            JSONArray documents = readBipDocuments
                ? BipPdfReader.read(context, sources, topic, control) : new JSONArray();
            boolean documentPartial = false;
            int readableDocuments = 0, ocrPages = 0;
            for (int i = 0; i < documents.length(); i++) {
                JSONObject document = documents.optJSONObject(i);
                if (document == null) continue;
                String documentStatus = document.optString("status", "");
                if ("read".equals(documentStatus) || "partial".equals(documentStatus)) readableDocuments++;
                if ("partial".equals(documentStatus) || "unavailable".equals(documentStatus) ||
                    "ocr_incomplete".equals(documentStatus) || "no_readable_text".equals(documentStatus) ||
                    "source_unavailable".equals(documentStatus)) documentPartial = true;
                ocrPages += document.optInt("ocrPages", 0);
            }
            if (sources.length() == 0) {
                answer.append("Nie udało się potwierdzić pełnego wyniku wyszukiwania dla wybranego okresu. Nie mogę stwierdzić, że nic nowego o Ispinie się nie pojawiło.");
                if(!undated.isEmpty())answer.append(" Znaleziono dodatkowo ").append(undated.size()).append(" materiałów wymagających ustalenia daty — pokazuję je osobno.");
                answer.append(" Odczytano ").append(rawItems).append(" pozycji z publicznych kanałów; poza okresem: ").append(olderItems).append(". Sprawdź dziennik źródeł poniżej.");
            }else answer.append("Publikacje z wybranego zakresu: ").append(external).append(" zewnętrznych; ").append(own)
                .append(" z Twojego portalu (osobno). Daty pochodzą od wydawcy lub z indeksu. Otwórz źródła i sprawdź fakty przed wykorzystaniem.");
            if (readBipDocuments) {
                answer.append("\nDokumenty BIP: ").append(readableDocuments).append(" odczytanych; OCR rozpoznał ")
                    .append(ocrPages).append(" stron skanów. Odczyt maszynowy wymaga sprawdzenia w oryginale.");
            } else {
                answer.append("\nSkan w tle odnotowuje linki do PDF. Odczyt treści i OCR wykonuje pełne wyszukiwanie uruchomione w aplikacji.");
            }
            if (failed > 0 || skipped > 0)
                answer.append("\nSkan częściowy: niedostępne kanały: ").append(failed)
                      .append(", pominięte lub bez potwierdzonych dat: ").append(skipped).append(".");
            for(JSONObject item:undated.values())item.remove("searchText");
            JSONObject result = new JSONObject().put("freeSearch", true).put("answer", answer.toString())
                 .put("text", answer.toString()).put("sources", sources).put("verified", false)
                .put("queries", searchedQueries).put("searchedAt", System.currentTimeMillis())
                .put("profiles", profileResults).put("plannedFeeds",profiles.length)
                .put("successfulFeeds", successful).put("failedFeeds", failed)
                .put("skippedFeeds", skipped).put("partialResults", failed > 0 || skipped > 0 || documentPartial)
                .put("sourceLinksFound", sources.length()>0)
                .put("sourceReviewStatus", "DO_SPRAWDZENIA")
                .put("sourcesAreFactChecked", false).put("dateFilterApplied", datedOnly)
                .put("documents", documents).put("bipDocumentsAnalyzed", readBipDocuments)
                .put("since", since).put("rawItems",rawItems).put("outsideWindow",olderItems)
                .put("undatedSources",new JSONArray(undated.values()))
                .put("outcome",sources.length()>0?"FOUND":"INCONCLUSIVE")
                .put("resultLimitApplied",found.size()>50).put("totalMatches",found.size());
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
        String text = (item.optString("title") + " " + item.optString("snippet")+" "+item.optString("searchText")).toLowerCase(Locale.ROOT);
        if ("paczkomat".equals(topic)) return text.contains("pacz") || text.contains("inpost");
        if ("transport".equals(topic)||"autobusy".equals(topic)) return text.contains("transport") || text.contains("autobus") || text.contains("komunik") || text.contains("linia") || text.contains("mld") || text.matches("(?s).*\\b(a4|a7|a65)\\b.*") || text.contains("gminn");
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

    /** Keep targeted Drwinia BIP hits for PDF inspection even when the index snippet omits the query term. */
    private static boolean isDrwiniaBipCandidate(JSONObject item) {
        try {
            Uri uri = Uri.parse(item.optString("url", ""));
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return host.equals("bip.malopolska.pl") && path.contains("/ugdrwinia");
        } catch (Exception ignored) { return false; }
    }

    static boolean broadRequest(String prompt) {
        String t=prompt==null?"":prompt.toLowerCase(Locale.ROOT);
        return t.matches("(?s).*(cokolwiek nowego|wszystko nowe|co nowego o ispin|coś nowego o ispin).*" ) ||
            ((t.contains("ostatnich")||t.contains("ostatnie"))&&(t.contains("lokalne media")||t.contains("źródła urzędowe")||t.contains("przetargi")||t.contains("wszystkie")));
    }
    static int lookbackDays(String prompt,int selected) {
        if(selected!=7&&selected!=30&&selected!=90)throw new IllegalArgumentException("Wybierz okres 7, 30 lub 90 dni.");
        String text=prompt==null?"":prompt.toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|[^\\d])(\\d{1,3})\\s*(?:dni|dniach)\\b").matcher(text);
        if(m.find()){int days=Integer.parseInt(m.group(1));if(days!=7&&days!=30&&days!=90)throw new IllegalArgumentException("Wybierz okres 7, 30 lub 90 dni.");return days;}
        if(text.matches("(?s).*(ostatni(?:m|ego)? tygodni|ostatni tydzień).*"))return 7;
        return selected;
    }
    static String topic(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT).trim();
        // Long instructions belong to the broad radar; never send entire
        // editorial drafts, personal text, or source notes to search providers.
        if (text.length() > 300 || broadRequest(text)) return "";
        // A broad brief is not a single search topic. Feed queries stay short
        // and do not try to match every category in one document.
        if ((text.contains("ostatnich") || text.contains("ostatnie")) &&
            (text.contains("lokalne media") || text.contains("źródła urzędowe")
                || text.contains("przetargi") || text.contains("wszystkie"))) return "";
        if (text.contains("paczkomat") || text.contains("inpost")) return "paczkomat";
        if (text.matches("(?s).*\\bosp\\b.*") || text.contains("straż pożarn")) return "osp";
        if (text.contains("autobus") || text.contains("komunikacj") || text.contains("transport")) return "autobusy";
        if(text.matches("(?s).*\\b(most|mostu|moście|mostem)\\b.*"))return "most";
        text = text.replaceAll("(?iu)\\b(?:czy|są|jest|jakie|co|pojawiło|pojawiły|się|coś|informacji|nowych|nowego|nowe|informacje|wiadomo|wiadomości|aktualne|aktualności|wyszukaj|znajdź|sprawdź|ostatnie|ostatnich|ostatnim|dni|dniach|tygodniu|miesiącu|temat|dotyczące|dotyczy|a|no|kiedy|gdzie|ile|jak|dlaczego|kto|więcej|jeszcze|najnowsze|nowości|poszukaj|szukam|szukaj|materiały|materiałów|archiwalne|archiwalnych|źródła|źródeł|opublikowano|wydarzenia|zdarzenia|wieś|wsi|o|w|we|na|z|do|i|oraz|dla|ispina|ispinie|ispiny|gminie|gmina|drwinia|drwini|proszę)\\b", " ");
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
        if (history == null || history.length() == 0 || prompt == null || broadRequest(prompt)) return prompt;
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
        return askConversation(context,prompt,history,control,30);
    }
    public static JSONObject askConversation(Context context,String prompt,JSONArray history,
                                             ChatGptPlanClient.SearchControl control,int selectedDays) throws Exception {
        int days=lookbackDays(prompt,selectedDays);
        String effectivePrompt = resolveConversationPrompt(prompt, history);
        long since=System.currentTimeMillis()-java.util.concurrent.TimeUnit.DAYS.toMillis(days);
        // The conversational radar has a 60-second budget and presents link evidence.
        // Full PDF extraction/OCR is handled by the dedicated editorial research flow.
        JSONObject result = search(context, effectivePrompt, control, since, true, false);
        result.put("lookbackDays",days);
        boolean inherited = !effectivePrompt.equals(prompt);
        result.put("contextInherited", inherited)
              .put("contextTopic", inherited ? topic(effectivePrompt) : "");
        return result;
    }

    private static JSONObject fetchProfile(String[] profile,int index,String topic,
                                           ChatGptPlanClient.SearchControl control,long since,boolean datedOnly) {
        JSONObject response=new JSONObject();String address=profile[2];String method="DIRECT_FEED";
        try {
            if("news".equals(profile[1])||"bing".equals(profile[1])) {
                method="SEARCH_INDEX";
                address="news".equals(profile[1])?"https://news.google.com/rss/search?q="+java.net.URLEncoder.encode(profile[2],"UTF-8")+"&hl=pl&gl=PL&ceid=PL:pl":
                    "https://www.bing.com/search?format=rss&q="+java.net.URLEncoder.encode(profile[2],"UTF-8");
            }else if("wordpress".equals(profile[1]))method="OWN_PORTAL_API";
            else if("municipal".equals(profile[1]))method="DIRECT_PUBLISHER";
            else if("html".equals(profile[1]))method="DIRECT_INDEX";
            response.put("index",index).put("name",profile[0]).put("address",address).put("method",method);control.check();
            if("html".equals(profile[1])&&datedOnly){
                String reason="Indeks bez dat pominięto; artykuły gminy sprawdzam osobno bezpośrednio.";
                control.report(profile[0],address,"SKIPPED",method,0,reason);
                return response.put("status","undated_index").put("reason",reason).put("newLinks",0);
            }
            control.report(profile[0],address,"READING",method,0,"Łączę się ze źródłem.");
            JSONArray items;int rawCount;
            if("municipal".equals(profile[1])){
                JSONObject publisher=DirectEditorialSources.municipality(control,since,topic);
                items=publisher.getJSONArray("items");rawCount=publisher.getInt("readArticles");
                response.put("discoveredArticles",publisher.getInt("discoveredArticles")).put("boundedRead",true);
            }else if("wordpress".equals(profile[1])){
                String raw=fetch(address,control);items=SourceDocumentParser.wordpress(raw);rawCount=new JSONObject(raw).getJSONArray("posts").length();
            }else if("html".equals(profile[1])) {
                URL page=new URL(address);String robots;
                try{robots=fetch(page.getProtocol()+"://"+page.getAuthority()+"/robots.txt",control);}catch(Exception e){if(e.getMessage()!=null&&e.getMessage().contains("HTTP 404"))robots="";else throw e;}
                if(!PublicPageSource.allowed(robots,page.getPath().isEmpty()?"/":page.getPath()))throw new IOException("Ograniczenie robots.txt; sprawdź źródło ręcznie.");
                items=PublicPageSource.links(fetch(address,control),address,topic);rawCount=items.length();
            }else {
                String xml=fetch(address,control);rawCount=countFeedItems(xml);items=parseFeed(xml,0L,false);
            }
            control.report(profile[0],address,rawCount==0?"EMPTY":"PARSED",method,items.length(),"Odczytano "+rawCount+" pozycji, dopasowano do Ispiny: "+items.length()+". Sprawdzam daty.");
            return response.put("status",rawCount==0?"empty_feed":"ok").put("rawItems",rawCount).put("items",items)
                .put("reason",rawCount==0?"Serwer zwrócił pusty kanał. To nie potwierdza braku publikacji.":"");
        }catch(Exception e){control.report(profile[0],address,"ERROR",method,0,e.getMessage());try{return response.put("status","unavailable").put("reason",e.getMessage()==null?"Błąd odczytu źródła.":e.getMessage()).put("newLinks",0);}catch(Exception ignored){return response;}}
    }
    private static int countFeedItems(String xml){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)<(?:item|entry)(?:\\s|>)").matcher(xml);int count=0;while(m.find())count++;return count;}

    static String fetch(String address, ChatGptPlanClient.SearchControl control) throws Exception {
        return fetch(address,control,Long.MAX_VALUE);
    }
    private static int sourceTimeout(long deadline,int maximum)throws java.net.SocketTimeoutException {
        long remaining=deadline-android.os.SystemClock.elapsedRealtime();
        if(remaining<=0)throw new java.net.SocketTimeoutException("Upłynął budżet czasu źródła; zachowano ukończone odczyty.");
        return (int)Math.min(maximum,remaining);
    }
    /** This publisher's missing robots.txt redirects to an old http:// homepage.
     * Use its verified HTTPS equivalent; never send a plaintext HTTP request.
     * Other HTTP downgrades remain rejected. */
    static URL secureRedirect(URL from,String location)throws IOException {
        URL next=new URL(from,location);
        if("http".equals(next.getProtocol())&&"https".equals(from.getProtocol())
            &&"www.drwinia.pl".equalsIgnoreCase(from.getHost())&&from.getHost().equalsIgnoreCase(next.getHost())
            &&next.getUserInfo()==null&&(next.getPort()==-1||next.getPort()==80))
            next=new URL("https",next.getHost(),next.getFile());
        if(!"https".equals(next.getProtocol()))throw new IOException("Źródło przekierowuje na nieszyfrowany adres. Sprawdź je ręcznie.");
        return next;
    }
    static String fetch(String address, ChatGptPlanClient.SearchControl control,long deadline) throws Exception {
        String current=address;
        for(int redirects=0;redirects<=4;redirects++) {
            URL url=new URL(current);String host=url.getHost().toLowerCase(Locale.ROOT);
            if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||(url.getPort()!=-1&&url.getPort()!=443)||host.isEmpty()||host.equals("localhost")||host.endsWith(".localhost")||host.contains(":")||host.matches("^(?:10|127|0|192\\.168|169\\.254)\\..*")||host.matches("^172\\.(?:1[6-9]|2[0-9]|3[01])\\..*"))throw new IOException("Niebezpieczny adres źródła.");
            control.check();HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            try {
                control.attach(connection);connection.setConnectTimeout(sourceTimeout(deadline,4000));connection.setReadTimeout(sourceTimeout(deadline,6000));connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept","application/rss+xml, application/xml, text/xml, text/html, application/json, text/plain");
                connection.setRequestProperty("User-Agent","IL-Redaktor-Smart/2.0 PublicSources");
                connection.connect();connection.setReadTimeout(sourceTimeout(deadline,6000));int status=connection.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Przekierowanie bez adresu.");current=secureRedirect(url,location).toString();control.report("Przekierowanie HTTPS",current,"READING","REDIRECT_HTTPS",0,"Odczytuję docelowy adres wyłącznie przez HTTPS.");continue;}
                if(status!=200)throw new IOException("Publiczne źródło: HTTP "+status);
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){control.check();sourceTimeout(deadline,6000);if(out.size()+n>1_500_000)throw new IOException("Źródło przekracza limit 1,5 MB.");out.write(buffer,0,n);}
                    if(!current.equals(address))control.report("Przekierowanie HTTPS",current,"DONE","REDIRECT_HTTPS",0,"Odczytano adres docelowy przez HTTPS.");
                    return out.toString(StandardCharsets.UTF_8.name());
                }
            }finally{control.detach(connection);connection.disconnect();}
        }
        throw new IOException("Zbyt wiele przekierowań źródła.");
    }

    static JSONArray parseFeed(String xml, long since, boolean datedOnly) throws Exception {
        String upper = xml.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
            throw new IOException("Niedozwolona deklaracja XML.");
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        JSONArray items = new JSONArray();
        String title="",link="",description="",fullText="",date="",dateType="BRAK DATY";
        boolean inItem=false, feed=false;
        int scanned=0;
        for (int event=parser.getEventType(); event!=XmlPullParser.END_DOCUMENT; event=parser.next()) {
            String name=parser.getName();if(name!=null&&name.contains(":"))name=name.substring(name.lastIndexOf(":")+1);
            if (event==XmlPullParser.START_TAG) {
                if ("rss".equals(name) || "feed".equals(name)) feed=true;
                if ("item".equals(name) || "entry".equals(name)) {
                    inItem=true; title="";link="";description="";fullText="";date="";dateType="BRAK DATY";
                } else if (inItem) {
                    if ("title".equals(name)) title=plain(readElementText(parser),240);
                    else if ("link".equals(name)) {
                        String href=parser.getAttributeValue(null,"href");
                        if (href!=null && !href.isEmpty()) {
                            String rel=parser.getAttributeValue(null,"rel");
                            if (rel==null || rel.isEmpty() || "alternate".equals(rel)) link=href.trim();
                        } else link=readElementText(parser).trim();
                    }
                    else if ("description".equals(name) || "summary".equals(name))description=plain(readElementText(parser),60000);
                    else if ("content".equals(name)||"encoded".equals(name))fullText=plain(readElementText(parser),120000);
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
                String searchable=title+" "+description+" "+fullText;
                if (!SourceDocumentParser.mentionsIspina(searchable)) continue;
                Uri uri=Uri.parse(link);
                if (uri.getHost()==null || uri.getUserInfo()!=null ||
                    !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) continue;
                long published=parseDate(date);
                if (datedOnly && (!"PUBLISHED".equals(dateType) || published<=0L ||
                     published<since || published>System.currentTimeMillis())) continue;
                if (title.isEmpty()) continue;
                items.put(new JSONObject().put("title",title).put("url",link).put("snippet",SourceDocumentParser.excerpt(fullText.isEmpty()?description:fullText)).put("searchText",searchable)
                    .put("published",date).put("publishedAt",published)
                     .put("dateType",dateType)
                    .put("reviewStatus","DO_SPRAWDZENIA"));
            }
        }
        if (!feed) throw new IOException("Źródło nie zwróciło kanału RSS ani Atom.");
        return items;
    }
    private static String readElementText(XmlPullParser parser)throws Exception {
        int depth=1;StringBuilder text=new StringBuilder();while(depth>0){int event=parser.next();if(event==XmlPullParser.END_DOCUMENT)break;if(event==XmlPullParser.START_TAG){depth++;text.append(' ');}else if(event==XmlPullParser.END_TAG){depth--;text.append(' ');}else if(event==XmlPullParser.TEXT||event==XmlPullParser.CDSECT){if(text.length()<180000)text.append(parser.getText());}}
        return text.toString();
    }
    private static String plain(String text,int max){String value=SourceDocumentParser.plain(text);return value.substring(0,Math.min(max,value.length()));}
    private static long parseDate(String value){return SourceDocumentParser.date(value);}
}

