package com.ispinalokalnie.redaktor;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.net.URI;

/** Bounded reads of publisher search/listing pages. Results are discovered, never seeded articles. */
final class DirectEditorialSources {
    private DirectEditorialSources() {}
    static JSONArray municipality(ChatGptPlanClient.SearchControl control,long since,String topic)throws Exception {
        final String root="https://www.drwinia.pl";
        String robots="";
        try{robots=FreeIspinaSearchClient.fetch(root+"/robots.txt",control);}catch(Exception e){if(e.getMessage()==null||!e.getMessage().contains("HTTP 404"))throw e;}
        // This publisher returns its HTML homepage instead of a missing robots.txt (soft 404).
        if(robots.trim().startsWith("<"))robots="";
        String search=root+"/szukaj?f%5Bfraza%5D=Ispina";
        if(!PublicPageSource.allowed(robots,"/szukaj?f%5Bfraza%5D=Ispina"))throw new Exception("robots.txt ogranicza dostęp do wyszukiwarki gminy.");
        control.report("Gmina Drwinia — wyszukiwarka",search,"READING","DIRECT_SEARCH",0,"Szukam także w treści artykułów.");
        String html=FreeIspinaSearchClient.fetch(search,control);String last=SourceDocumentParser.lastSearchPage(html,search);
        JSONArray first=SourceDocumentParser.municipalLinks(html,search);
        LinkedHashMap<String,JSONObject> candidates=new LinkedHashMap<>();
        add(candidates,first);
        if(!last.isEmpty()&&!last.equals(search)){
            control.report("Gmina Drwinia — najnowsza strona wyników",last,"READING","DIRECT_SEARCH",0,"Odczytuję dalszą stronę wyników.");
            add(candidates,SourceDocumentParser.municipalLinks(FreeIspinaSearchClient.fetch(last,control),last));
        }
        // Also inspect latest listing: it contains news where Ispina occurs only in the body.
        String latest=root+"/aktualnosci";
        if(PublicPageSource.allowed(robots,"/aktualnosci")){
            control.report("Gmina Drwinia — aktualności",latest,"READING","DIRECT_PUBLISHER",0,"Sprawdzam listę publikacji i daty wydawcy.");
            add(candidates,SourceDocumentParser.municipalLinks(FreeIspinaSearchClient.fetch(latest,control),latest));
        }
        List<JSONObject> ordered=new ArrayList<>(candidates.values());
        ordered.sort((a,b)->Long.compare(newsId(b.optString("url")),newsId(a.optString("url"))));
        JSONArray out=new JSONArray();int read=0;long stop=android.os.SystemClock.elapsedRealtime()+25000L;
        for(JSONObject candidate:ordered){
            control.check();if(read>=24||android.os.SystemClock.elapsedRealtime()>stop)break;
            String url=candidate.getString("url");URI uri=new URI(url);
            if(!"www.drwinia.pl".equals(uri.getHost())||!PublicPageSource.allowed(robots,uri.getPath()))continue;
            if(candidate.optLong("publishedAt")>0&&candidate.optLong("publishedAt")<since)continue;
            read++;
            control.report("Gmina Drwinia — artykuł",url,"READING","DIRECT_PUBLISHER",out.length(),candidate.optString("title"));
            try{
                JSONObject article=SourceDocumentParser.article(FreeIspinaSearchClient.fetch(url,control),url);
                boolean relevant=article!=null&&FreeIspinaSearchClient.matchesTopic(article,topic);
                if(relevant)out.put(article);
                control.report("Gmina Drwinia — artykuł",url,"DONE","DIRECT_PUBLISHER",relevant?1:0,relevant?"Ispina znaleziona w treści; odczytano datę wydawcy.":"Treść odczytana; brak dopasowania do tematu.");
            }catch(Exception e){control.check();control.report("Gmina Drwinia — artykuł",url,"ERROR","DIRECT_PUBLISHER",0,e.getMessage());}
        }
        control.report("Gmina Drwinia — skan",search,"DONE","DIRECT_SEARCH",out.length(),"Odczytano "+read+" artykułów z "+candidates.size()+" odkrytych adresów. Limit jednego skanu: 24 artykuły.");
        return out;
    }
    private static long newsId(String url){java.util.regex.Matcher m=java.util.regex.Pattern.compile(",news-(\\d+)").matcher(url);return m.find()?Long.parseLong(m.group(1)):0;}
    private static void add(Map<String,JSONObject> map,JSONArray a)throws Exception {for(int i=0;i<a.length();i++){JSONObject v=a.getJSONObject(i);if(!map.containsKey(v.getString("url"))||v.optLong("publishedAt")>0)map.put(v.getString("url"),v);}}
}
