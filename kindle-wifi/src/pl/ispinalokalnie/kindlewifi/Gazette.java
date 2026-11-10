package pl.ispinalokalnie.kindlewifi;

import android.text.Html;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.HttpsURLConnection;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

final class Gazette {
    static final String DEFAULT_FEED = "https://ispinalokalnie.wordpress.com/feed/";
    private static final int PER_FEED = 5;
    private static final int MAX_FEEDS = 4;
    private static final int MAX_FEED_BYTES = 2 * 1024 * 1024;
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    static final class Article {
        String source;
        String title = "";
        String link = "";
        String summary = "";
        String date = "";
    }

    static final class Edition {
        final List<Article> articles = new ArrayList<>();
        final List<String> unavailable = new ArrayList<>();
    }

    static List<URI> feeds(String addresses) throws Exception {
        LinkedHashSet<URI> result = new LinkedHashSet<>();
        for (String line : addresses.split("\\R")) {
            if (line.trim().isEmpty()) continue;
            result.add(https(line.trim()));
            if (result.size() > MAX_FEEDS) throw new IOException("Wpisz maksymalnie cztery kanały.");
        }
        if (result.isEmpty()) throw new IOException("Wpisz przynajmniej jeden adres RSS.");
        return new ArrayList<>(result);
    }

    private static URI https(String text) throws IOException {
        try {
            URI url = URI.create(text);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                    || url.getUserInfo() != null || url.getFragment() != null || text.length() > 2048)
                throw new IllegalArgumentException();
            return url;
        } catch (IllegalArgumentException error) {
            throw new IOException("Adres kanału powinien zaczynać się od https:// i zawierać nazwę serwisu.");
        }
    }

    static Edition fetch(List<URI> sources) throws Exception {
        Edition edition = new Edition();
        HashSet<String> seen = new HashSet<>();
        for (URI source : sources) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                byte[] xml = download(source);
                List<Article> entries = parse(new ByteArrayInputStream(xml), source);
                if (entries.isEmpty()) throw new IOException("Kanał nie ma wpisów.");
                for (Article item : entries) {
                    String key = item.link.isEmpty() ? item.source + "\n" + item.title : item.link;
                    if (seen.add(key)) edition.articles.add(item);
                }
            } catch (Exception error) {
                if (Thread.currentThread().isInterrupted()) throw error;
                edition.unavailable.add(source.getHost());
            }
        }
        if (edition.articles.isEmpty()) throw new IOException("Brak wpisów w dostępnych kanałach. Sprawdź internet i adresy RSS.");
        return edition;
    }

    private static byte[] download(URI source) throws Exception {
        URI uri = source;
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(5500);
            connection.setReadTimeout(6500);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml");
            connection.setRequestProperty("User-Agent", "Kindle-przez-WiFi/2.16");
            try {
                int code = connection.getResponseCode();
                if (code == 200) {
                    try (InputStream input = connection.getInputStream()) {
                        return BialolekaForecast.readBounded(input, MAX_FEED_BYTES);
                    }
                }
                String location = connection.getHeaderField("Location");
                if (code < 300 || code >= 400 || location == null) throw new IOException("Kanał jest niedostępny.");
                uri = https(uri.resolve(location).toString());
            } finally { connection.disconnect(); }
        }
        throw new IOException("Za dużo przekierowań do kanału RSS.");
    }

    static List<Article> parse(InputStream input, URI feed) throws Exception {
        XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(true);
        XmlPullParser parser = factory.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        parser.setInput(input, null);
        ArrayList<Article> result = new ArrayList<>();
        Article item = null;
        int itemDepth = 0;
        String source = feed.getHost();
        for (int event = parser.nextToken(); event != XmlPullParser.END_DOCUMENT; event = parser.nextToken()) {
            if (event == XmlPullParser.DOCDECL) throw new IOException("Kanał zawiera deklarację DTD.");
            if (event == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                int depth = parser.getDepth();
                if (depth == 1 && !(tag.equals("rss") || tag.equals("feed") || tag.equals("RDF")))
                    throw new IOException("Adres nie prowadzi do kanału RSS ani Atom.");
                if (item == null && (tag.equals("item") || tag.equals("entry")) && (depth == 2 || depth == 3)) {
                    item = new Article();
                    item.source = source;
                    itemDepth = depth;
                } else if (item == null && tag.equals("title") && (depth == 2 || depth == 3)) {
                    String title = text(readField(parser), 140);
                    if (!title.isEmpty()) source = title;
                } else if (item != null && depth == itemDepth + 1) {
                    switch (tag) {
                        case "title": item.title = text(readField(parser), 180); break;
                        case "description": case "summary":
                            item.summary = text(readField(parser), 950);
                            break;
                        case "content": case "encoded":
                            if (item.summary.isEmpty()) item.summary = text(readField(parser), 950);
                            break;
                        case "pubDate": case "published": case "updated":
                            if (item.date.isEmpty()) item.date = text(readField(parser), 90);
                            break;
                        case "link":
                            String rel = parser.getAttributeValue(null, "rel");
                            String href = parser.getAttributeValue(null, "href");
                            if ((rel == null || "alternate".equals(rel)) && item.link.isEmpty()) {
                                String address = href == null ? readField(parser).trim() : href.trim();
                                if (!address.isEmpty()) {
                                    try { item.link = https(feed.resolve(address).toString()).toString(); }
                                    catch (IllegalArgumentException | IOException ignored) { }
                                }
                            }
                            break;
                        default: break;
                    }
                }
            } else if (event == XmlPullParser.END_TAG && item != null && parser.getDepth() == itemDepth) {
                if (!item.title.isEmpty()) result.add(item);
                item = null;
                if (result.size() >= PER_FEED) break;
            }
        }
        return result;
    }

    private static String readField(XmlPullParser parser) throws Exception {
        int depth = parser.getDepth();
        StringBuilder result = new StringBuilder();
        for (int event = parser.nextToken(); event != XmlPullParser.END_DOCUMENT; event = parser.nextToken()) {
            if (event == XmlPullParser.DOCDECL) throw new IOException("Niedozwolona deklaracja w kanale.");
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
            if (result.length() >= 4096) continue;
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT || event == XmlPullParser.ENTITY_REF) {
                String part = parser.getText();
                if (part != null) result.append(part, 0, Math.min(part.length(), 4096 - result.length()));
            } else if (event == XmlPullParser.END_TAG) {
                result.append(' ');
            }
        }
        return result.toString();
    }

    private static String text(String html, int limit) {
        String plain = Html.fromHtml(html).toString()
                .replaceAll("[\\p{Cc}\\s\\u00a0]+", " ").trim();
        int end = plain.offsetByCodePoints(0, Math.min(limit, plain.codePointCount(0, plain.length())));
        return end == plain.length() ? plain : plain.substring(0, end).trim() + "…";
    }

    static void write(File target, Edition edition, LocalDate date) throws IOException {
        String bookTitle = "Gazetka na Kindle · " + date;
        String id = "urn:uuid:" + UUID.randomUUID();
        StringBuilder toc = new StringBuilder("<h1>").append(bookTitle).append("</h1><p>")
                .append("Najnowsze wpisy z wybranych kanałów. Wydanie do czytania offline.</p><ol>");
        StringBuilder nav = new StringBuilder("<navPoint id=\"toc\" playOrder=\"1\"><navLabel><text>Spis treści</text></navLabel><content src=\"spis.xhtml\"/></navPoint>");
        StringBuilder manifest = new StringBuilder("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                + "<item id=\"toc\" href=\"spis.xhtml\" media-type=\"application/xhtml+xml\"/>");
        StringBuilder spine = new StringBuilder("<itemref idref=\"toc\"/>");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(target))) {
            PdfEpubConverter.putEpubHeader(zip);
            for (int i = 0; i < edition.articles.size(); i++) {
                Article entry = edition.articles.get(i);
                String title = PdfEpubConverter.xml(entry.title);
                String name = "wpis" + (i + 1);
                String href = name + ".xhtml";
                String body = "<h1>" + title + "</h1><p>" + PdfEpubConverter.xml(entry.source)
                        + (entry.date.isEmpty() ? "" : " · " + PdfEpubConverter.xml(entry.date)) + "</p>"
                        + "<p>" + (entry.summary.isEmpty() ? "Kanał udostępnia tytuł wpisu." : PdfEpubConverter.xml(entry.summary)) + "</p>"
                        + (entry.link.isEmpty() ? "" : "<p><a href=\"" + PdfEpubConverter.xml(entry.link) + "\">Oryginalny wpis</a></p>")
                        + "<p><a href=\"spis.xhtml\">Wróć do spisu treści</a></p>";
                PdfEpubConverter.put(zip, "EPUB/" + href, page(title, body));
                manifest.append("<item id=\"").append(name).append("\" href=\"").append(href).append("\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"").append(name).append("\"/>");
                toc.append("<li><a href=\"").append(href).append("\">").append(title).append("</a></li>");
                nav.append("<navPoint id=\"").append(name).append("\" playOrder=\"").append(i + 2)
                        .append("\"><navLabel><text>").append(title).append("</text></navLabel><content src=\"")
                        .append(href).append("\"/></navPoint>");
            }
            toc.append("</ol>");
            if (!edition.unavailable.isEmpty()) toc.append("<p>Pominięte kanały: ")
                    .append(PdfEpubConverter.xml(String.join(", ", edition.unavailable))).append("</p>");
            PdfEpubConverter.put(zip, "EPUB/spis.xhtml", page("Spis treści", toc.toString()));
            PdfEpubConverter.put(zip, "EPUB/package.opf", XML
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"id\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">" + id
                    + "</dc:identifier><dc:title>" + bookTitle + "</dc:title><dc:language>pl</dc:language></metadata>"
                    + "<manifest>" + manifest + "</manifest><spine toc=\"ncx\">" + spine + "</spine></package>");
            PdfEpubConverter.put(zip, "EPUB/toc.ncx", XML + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">"
                    + "<head><meta name=\"dtb:uid\" content=\"" + id + "\"/><meta name=\"dtb:depth\" content=\"1\"/>"
                    + "<meta name=\"dtb:totalPageCount\" content=\"0\"/><meta name=\"dtb:maxPageNumber\" content=\"0\"/></head>"
                    + "<docTitle><text>" + bookTitle + "</text></docTitle><navMap>" + nav + "</navMap></ncx>");
        }
    }

    private static String page(String title, String contents) {
        return XML + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"pl\"><head><title>" + title
                + "</title></head><body>" + contents + "</body></html>";
    }
}
