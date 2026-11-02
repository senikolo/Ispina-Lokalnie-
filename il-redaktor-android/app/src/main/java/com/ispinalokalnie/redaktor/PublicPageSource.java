package com.ispinalokalnie.redaktor;

import android.text.Html;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, bounded HTML index reader. No scripts, cookies, login or hidden endpoints. */
final class PublicPageSource {
    private static final Pattern ANCHOR=Pattern.compile("(?is)<a\\b[^>]*\\bhref\\s*=\\s*([\"'])(.*?)\\1[^>]*>(.*?)</a\\s*>");
    private PublicPageSource() {}

    // Conservative robots policy: explicit matching groups override *, longest rule wins.
    // Unknown restrictions and crawl-delay fail closed rather than pretending full coverage.
    static boolean allowed(String robots,String path) {
        boolean active=false,seenRule=false,hasSpecific=false;
        for(String raw:robots.split("\\r?\\n")) {
            String line=raw.split("#",2)[0].trim().toLowerCase(Locale.ROOT);
            if(line.startsWith("user-agent:")&&line.substring(11).trim().contains("il-redaktor"))hasSpecific=true;
        }
        int longest=-1;boolean allow=true;
        for(String raw:robots.split("\\r?\\n")) {
            String line=raw.split("#",2)[0].trim();int colon=line.indexOf(':');if(colon<0)continue;
            String key=line.substring(0,colon).trim().toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();
            if("user-agent".equals(key)) {
                if(seenRule){active=false;seenRule=false;}
                String agent=value.toLowerCase(Locale.ROOT);
                active |= hasSpecific ? agent.contains("il-redaktor") : "*".equals(agent);
                continue;
            }
            if(!active)continue;
            if("crawl-delay".equals(key))return false;
            if(!"allow".equals(key)&&!"disallow".equals(key))continue;
            seenRule=true;if(value.isEmpty())continue;
            boolean end=value.endsWith("$");String rule=end?value.substring(0,value.length()-1):value;
            StringBuilder regex=new StringBuilder("^");
            for(String part:rule.split("\\*",-1)){if(regex.length()>1)regex.append(".*");regex.append(Pattern.quote(part));}
            regex.append(end?"$":".*");
            int specificity=rule.replace("*","").length();
            if(path.matches(regex.toString())&&(specificity>longest||(specificity==longest&&"allow".equals(key)))){
                longest=specificity;allow="allow".equals(key);
            }
        }
        return allow;
    }

    static JSONArray links(String html,String address,String topic) throws Exception {
        if(html==null||html.length()>600000)throw new IllegalArgumentException("Strona przekracza limit odczytu.");
        // Script/style text is neither an editorial source nor an article excerpt.
        String safe=html.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>","");
        Matcher m=ANCHOR.matcher(safe);JSONArray found=new JSONArray();java.util.HashSet<String> seen=new java.util.HashSet<>();
        int count=0;
        while(m.find()&&count++<3000&&found.length()<30) {
            String title=plain(m.group(3));String href=Html.fromHtml(m.group(2),Html.FROM_HTML_MODE_LEGACY).toString();
            URI uri;try{uri=new URI(address).resolve(href);}catch(Exception invalid){continue;}
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)continue;
            String url=uri.toString();
            // The title or actual URL must mention the locality; unrelated menu links are excluded.
            if(!(title+" "+url).toLowerCase(Locale.ROOT).matches("(?s).*(ispin|ispni).*"))continue;
            if(title.isEmpty())title=url;
            if(title.length()>240)title=title.substring(0,240);
            JSONObject item=new JSONObject().put("title",title).put("url",url)
                .put("snippet","Bezpośredni link ze strony urzędowej. Treść i daty wymagają odczytania w oryginale.")
                .put("publishedAt",0).put("published","").put("dateType","BRAK DATY")
                .put("sourceMethod","PUBLIC_HTML_INDEX").put("reviewStatus","DO_SPRAWDZENIA");
            if(FreeIspinaSearchClient.matchesTopic(item,topic)&&seen.add(url))found.put(item);
        }
        return found;
    }
    private static String plain(String value){return Html.fromHtml(value,Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+"," ").trim();}
}
