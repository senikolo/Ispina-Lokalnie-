#!/usr/bin/env python3
"""Read-only public RSS smoke check. No credentials or paid AI requests."""
import urllib.request
import urllib.parse
import xml.etree.ElementTree as ET

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

q=urllib.parse.quote_plus("Ispina Drwinia")
feeds=[
    "https://news.google.com/rss/search?q="+q+"&hl=pl&gl=PL&ceid=PL:pl",
    "https://www.bing.com/search?format=rss&q="+q,
    "https://eko.drwinia.pl/feed/",
]
success=0
for url in feeds:
    host=urllib.parse.urlparse(url).hostname
    try:
        req=urllib.request.Request(url,headers={"User-Agent":"IL-Redaktor-Smart/1.27 PublicRSS","Accept":"application/rss+xml, application/xml, text/xml"})
        with urllib.request.build_opener(NoRedirect()).open(req,timeout=10) as response:
            data=response.read(600001)
        if len(data)>600000: raise ValueError("RSS too large")
        if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper(): raise ValueError("Unsafe XML")
        root=ET.fromstring(data)
        if root.tag!="rss": raise ValueError("Not RSS")
        matches=sum(1 for item in root.findall("./channel/item") if "ispin" in " ".join(item.itertext()).lower())
        print(host, "RSS_OK", "Ispina_items="+str(matches))
        success+=1
    except Exception as exc:
        print(host,"RSS_UNAVAILABLE",type(exc).__name__,str(exc)[:150])
if not success:
    raise SystemExit("All public RSS channels were unavailable; do not present this build as live-source verified.")
print("IL_FREE_RSS_LIVE_CHECK=PASS")
