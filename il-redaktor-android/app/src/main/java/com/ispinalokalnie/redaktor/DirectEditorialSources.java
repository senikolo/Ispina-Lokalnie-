package com.ispinalokalnie.redaktor;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.net.URI;

/** Bounded reads of publisher search/listing pages. Results are discovered, never seeded articles. */
final class DirectEditorialSources {
    private DirectEditorialSources() {}
    static JSONObject municipality(ChatGptPlanClient.SearchControl control,long since,String topic)throws Exception {
        final String root="https://www.drwinia.pl";
        final long deadline=android.os.SystemClock.elapsedRealtime()+39000L;
        String robots="";
        control.report("Gmina Drwinia — zasady odczytu",root+"/robots.txt","READING","DIRECT_PUBLISHER",0,"Sprawdzam robots.txt przed odczytem stron.");
        try{robots=FreeIspinaSearchClient.fetch(root+"/robots.txt",control,deadline);}catch(Exception e){if(e.getMessage()==null||!e.getMessage().contains("HTTP 404"))throw e;}
        // This publisher returns its HTML homepage instead of a missing robots.txt (soft 404).
        if(robots.trim().startsWith("<"))robots="";
        control.report("Gmina Drwinia — zasady odczytu",root+"/robots.txt","DONE","DIRECT_PUBLISHER",0,robots.isEmpty()?"Brak opublikowanych dyrektyw robots.txt.":"Odczytano zasady dostępu.");
        String search=root+"/szukaj?f%5Bfraza%5D=Ispina";
        if(!PublicPageSource.allowed(robots,"/szukaj?f%5Bfraza%5D=Ispina"))throw new Exception("robots.txt ogranicza dostęp do wyszukiwarki gminy.");
        control.report("Gmina Drwinia — wyszukiwarka",search,"READING","DIRECT_SEARCH",0,"Szukam także w treści artykułów.");
        String html=FreeIspinaSearchClient.fetch(search,control,deadline);String last=SourceDocumentParser.lastSearchPage(html,search);
        JSONArray first=SourceDocumentParser.municipalLinks(html,search);
        control.report("Gmina Drwinia — wyszukiwarka",search,"DONE","DIRECT_SEARCH",first.length(),"Odkryto adresy publikacji; sprawdzam ich daty i treść.");
        LinkedHashMap<String,JSONObject> candidates=new LinkedHashMap<>();
        add(candidates,first);
        if(!last.isEmpty()&&!last.equals(search)){
            control.report("Gmina Drwinia — najnowsza strona wyników",last,"READING","DIRECT_SEARCH",0,"Odczytuję dalszą stronę wyników.");
            JSONArray links=SourceDocumentParser.municipalLinks(FreeIspinaSearchClient.fetch(last,control,deadline),last);
            add(candidates,links);
            control.report("Gmina Drwinia — najnowsza strona wyników",last,"DONE","DIRECT_SEARCH",links.length(),"Odkryto adresy publikacji.");
        }
        // Also inspect latest listing: it contains news where Ispina occurs only in the body.
        String latest=root+"/aktualnosci";
        if(PublicPageSource.allowed(robots,"/aktualnosci")){
            control.report("Gmina Drwinia — aktualności",latest,"READING","DIRECT_PUBLISHER",0,"Sprawdzam listę publikacji i daty wydawcy.");
            JSONArray links=SourceDocumentParser.municipalLinks(FreeIspinaSearchClient.fetch(latest,control,deadline),latest);
            add(candidates,links);
            control.report("Gmina Drwinia — aktualności",latest,"DONE","DIRECT_PUBLISHER",links.length(),"Odczytano listę najnowszych publikacji.");
        }
        List<JSONObject> ordered=new ArrayList<>(candidates.values());
        ordered.sort((a,b)->Long.compare(newsId(b.optString("url")),newsId(a.optString("url"))));
        // Two bounded article readers avoid losing body-only mentions behind slow,
        // unrelated first articles. Collect completed results before the source deadline.
        List<JSONObject> selected=new ArrayList<>();
        for(JSONObject candidate:ordered){
            if(selected.size()>=24)break;
            String url=candidate.getString("url");URI uri=new URI(url);
            if(!"www.drwinia.pl".equals(uri.getHost())||!PublicPageSource.allowed(robots,uri.getPath()))continue;
            if(candidate.optLong("publishedAt")>0&&candidate.optLong("publishedAt")<since)continue;
            selected.add(candidate);
        }
        JSONArray out=new JSONArray();int read=0;
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CompletionService<JSONObject> completion=new java.util.concurrent.ExecutorCompletionService<>(pool);
        List<java.util.concurrent.Future<JSONObject>> jobs=new ArrayList<>();
        final long stop=Math.min(deadline,android.os.SystemClock.elapsedRealtime()+25000L);
        try{
            for(JSONObject candidate:selected)jobs.add(completion.submit(()->{
                control.check();String url=candidate.getString("url");
                if(android.os.SystemClock.elapsedRealtime()>=stop)return null;
                control.report("Gmina Drwinia — artykuł",url,"READING","DIRECT_PUBLISHER",0,candidate.optString("title"));
                try{
                    JSONObject article=SourceDocumentParser.article(FreeIspinaSearchClient.fetch(url,control,stop),url);
                    boolean relevant=article!=null&&FreeIspinaSearchClient.matchesTopic(article,topic);
                    control.report("Gmina Drwinia — artykuł",url,"DONE","DIRECT_PUBLISHER",relevant?1:0,relevant?"Ispina znaleziona w treści; odczytano metadane wydawcy.":"Treść odczytana; brak dopasowania do tematu.");
                    return new JSONObject().put("article",relevant?article:JSONObject.NULL);
                }catch(Exception e){control.check();control.report("Gmina Drwinia — artykuł",url,"ERROR","DIRECT_PUBLISHER",0,e.getMessage());return new JSONObject().put("error",true);}
            }));
            for(int completed=0;completed<jobs.size();){
                control.check();long remaining=stop-android.os.SystemClock.elapsedRealtime();if(remaining<=0)break;
                java.util.concurrent.Future<JSONObject> next=completion.poll(Math.min(500,remaining),java.util.concurrent.TimeUnit.MILLISECONDS);
                if(next==null)continue;completed++;
                JSONObject result=next.get();if(result==null)continue;
                if(!result.optBoolean("error"))read++;
                JSONObject article=result.optJSONObject("article");if(article!=null)out.put(article);
            }
        }finally{for(java.util.concurrent.Future<JSONObject> job:jobs)if(!job.isDone())job.cancel(true);pool.shutdownNow();}
        control.report("Gmina Drwinia — skan",search,"DONE","DIRECT_SEARCH",out.length(),"Odczytano "+read+" artykułów z "+candidates.size()+" odkrytych adresów. Limit: 24 artykuły i budżet czasu; zakres nie jest pełny.");
        return new JSONObject().put("items",out).put("readArticles",read).put("discoveredArticles",candidates.size());
    }
    private static long newsId(String url){java.util.regex.Matcher m=java.util.regex.Pattern.compile(",news-(\\d+)").matcher(url);return m.find()?Long.parseLong(m.group(1)):0;}
    private static void add(Map<String,JSONObject> map,JSONArray a)throws Exception {for(int i=0;i<a.length();i++){JSONObject v=a.getJSONObject(i);if(!map.containsKey(v.getString("url"))||v.optLong("publishedAt")>0)map.put(v.getString("url"),v);}}
}
