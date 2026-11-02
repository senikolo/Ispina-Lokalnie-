package com.ispinalokalnie.redaktor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Publisher HTML / public WordPress JSON. Parsing only; no HTTP, credentials or UI. */
public final class SourceDocumentParser {
    private SourceDocumentParser() {}
    public static String plain(String html) {
        Document d=Jsoup.parse(html==null?"":html);d.select("script,style,nav,footer,header").remove();
        return d.text().replace('\u00a0',' ').replaceAll("\\s+"," ").trim();
    }
    public static boolean mentionsIspina(String text){return text!=null&&Pattern.compile("(?iu)\\b(?:ispin[\\p{L}]*|ispni[\\p{L}]*|inspina)\\b").matcher(text).find();}
    public static String excerpt(String text) {
        String value=text==null?"":text;Matcher match=Pattern.compile("(?iu)ispin|ispni|inspina").matcher(value);
        int start=match.find()?Math.max(0,match.start()-140):0;
        return value.substring(start,Math.min(value.length(),start+650));
    }
    public static long date(String text) {
        if(text==null||text.trim().isEmpty())return 0L;String value=text.trim();
        try{return OffsetDateTime.parse(value).toInstant().toEpochMilli();}catch(Exception ignored){}
        try{return Instant.parse(value).toEpochMilli();}catch(Exception ignored){}
        try{return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}catch(Exception ignored){}
        try{return LocalDate.parse(value).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli();}catch(Exception ignored){}
        Matcher m=Pattern.compile("(?iu)(\\d{1,2})\\s+(stycznia|lutego|marca|kwietnia|maja|czerwca|lipca|sierpnia|września|października|listopada|grudnia)\\s+(20\\d{2})").matcher(value);
        if(m.find()){
            String[] months={"stycznia","lutego","marca","kwietnia","maja","czerwca","lipca","sierpnia","września","października","listopada","grudnia"};
            try{int month=Arrays.asList(months).indexOf(m.group(2).toLowerCase(Locale.ROOT))+1;return LocalDate.of(Integer.parseInt(m.group(3)),month,Integer.parseInt(m.group(1))).atStartOfDay(ZoneId.of("Europe/Warsaw")).toInstant().toEpochMilli();}catch(Exception ignored){}
        }
        for(String pattern:new String[]{"EEE, dd MMM yyyy HH:mm:ss Z","EEE, d MMM yyyy HH:mm:ss Z","yyyy-MM-dd'T'HH:mm:ss","dd.MM.yyyy"}){
            try{java.text.SimpleDateFormat f=new java.text.SimpleDateFormat(pattern,Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("Europe/Warsaw"));java.text.ParsePosition p=new java.text.ParsePosition(0);java.util.Date parsed=f.parse(value,p);if(parsed!=null&&p.getIndex()==value.length())return parsed.getTime();}catch(Exception ignored){}
        }
        return 0L;
    }
    private static String meta(Document d,String selector){Element e=d.selectFirst(selector);return e==null?"":e.attr("content");}
    private static String structuredDate(Object json,int depth) {
        if(depth>10)return "";
        if(json instanceof JSONArray){JSONArray a=(JSONArray)json;for(int i=0;i<a.length();i++){String found=structuredDate(a.opt(i),depth+1);if(!found.isEmpty())return found;}}
        if(json instanceof JSONObject){JSONObject o=(JSONObject)json;
            String type=o.optString("@type");if((type.contains("Article")||type.contains("BlogPosting")||type.contains("Report"))&&o.has("datePublished"))return o.optString("datePublished");
            Iterator<String> keys=o.keys();while(keys.hasNext()){Object child=o.opt(keys.next());if(child instanceof JSONObject||child instanceof JSONArray){String found=structuredDate(child,depth+1);if(!found.isEmpty())return found;}}
        }
        return "";
    }
    /** Reads the article body, excluding site navigation and its date widget. */
    public static JSONObject article(String html,String address) throws Exception {
        Document d=Jsoup.parse(html,address);String published=meta(d,"meta[property='article:published_time']");
        if(published.isEmpty())published=meta(d,"meta[itemprop=datePublished]");
        if(published.isEmpty())for(Element script:d.select("script[type=application/ld+json]")){
            try{String raw=script.data().trim();if(raw.length()>250000)continue;String found=structuredDate(raw.startsWith("[")?new JSONArray(raw):new JSONObject(raw),0);if(!found.isEmpty()){published=found;break;}}catch(Exception ignored){}
        }
        if(published.isEmpty()){Element time=d.selectFirst("time.published[datetime],time[itemprop=datePublished][datetime]");if(time!=null)published=time.attr("datetime");}
        Element heading=d.selectFirst("h1");String title=heading==null?d.title():heading.text();
        Element body=d.selectFirst("article .news-description,article .entry-content,article .post-content,.td-post-content,.entry-content,article");
        if(body==null)return null;
        if(published.isEmpty()){Element localDate=body.selectFirst(".news-list-el-date");if(localDate==null){Element article=d.selectFirst("article");if(article!=null)localDate=article.selectFirst(".news-list-el-date");}if(localDate!=null)published=localDate.text();}
        String text=plain(body.html());if(!mentionsIspina(title+" "+text))return null;
        String canonical=address;Element c=d.selectFirst("link[rel=canonical][href]");
        if(c!=null){String candidate=c.absUrl("href");try{if(new URI(address).getHost().equalsIgnoreCase(new URI(candidate).getHost()))canonical=candidate;}catch(Exception ignored){}}
        return item(title,canonical,text,published,"PUBLISHER_HTML","EXTERNAL");
    }
    public static JSONObject item(String title,String url,String text,String published,String method,String kind)throws Exception {
        long stamp=date(published);
        return new JSONObject().put("title",title.length()>280?title.substring(0,280):title).put("url",url)
            .put("snippet",excerpt(text)).put("searchText",text.length()>60000?text.substring(0,60000):text)
            .put("published",published).put("publishedAt",stamp).put("dateType",stamp>0?"PUBLISHED":"BRAK DATY")
            .put("sourceMethod",method).put("sourceKind",kind).put("reviewStatus","DO_SPRAWDZENIA");
    }
    /** Search/listing URLs, not a list of hard-coded results. */
    public static JSONArray municipalLinks(String html,String base)throws Exception {
        Document d=Jsoup.parse(html,base);LinkedHashMap<String,JSONObject> candidates=new LinkedHashMap<>();
        for(Element a:d.select(".news-list-el-title[href],#wyszukiwarka_wyniki h3 a[href]")){
            String url=a.absUrl("href");if(!url.contains(",news-"))continue;
            Element parent=a.closest(".news-list-el");String date="";
            if(parent!=null){Element e=parent.selectFirst(".news-list-el-date");if(e!=null)date=e.text();}
            candidates.put(url,new JSONObject().put("title",a.text()).put("url",url).put("published",date).put("publishedAt",date(date)));
        }
        return new JSONArray(candidates.values());
    }
    public static String lastSearchPage(String html,String base) {
        Document d=Jsoup.parse(html,base);String last="";int max=0;
        for(Element a:d.select("a[href]")){String url=a.absUrl("href");Matcher m=Pattern.compile("/szukaj,str-(\\d+)").matcher(url);if(m.find()){int page=Integer.parseInt(m.group(1));if(page>max){max=page;last=url;}}}
        return last;
    }
    public static JSONArray wordpress(String json)throws Exception {
        JSONObject root=new JSONObject(json);JSONArray posts=root.optJSONArray("posts"),out=new JSONArray();if(posts==null)throw new IllegalArgumentException("WordPress nie zwrócił listy artykułów.");
        for(int i=0;i<posts.length();i++){
            JSONObject p=posts.getJSONObject(i);String title=plain(p.optString("title")),text=plain(p.optString("content"));
            if(!mentionsIspina(title+" "+text))continue;String url=p.optString("URL");if(!url.startsWith("https://ispinalokalnie.wordpress.com/"))continue;
            out.put(item(title,url,text,p.optString("date"),"WORDPRESS_PUBLIC_API","OWN_PORTAL"));
        }
        return out;
    }
}
