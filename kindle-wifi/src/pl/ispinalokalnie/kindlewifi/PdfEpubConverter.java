package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import android.graphics.Bitmap;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.zip.*;

/** PDF to EPUB 3; scanned pages use bundled offline ML Kit OCR when requested. */
public final class PdfEpubConverter {
    public static final class Options {
        public String title = "";
        public String author = "";
        public String language = "pl";
        public int firstPage = 1;
        public int lastPage = 0;
        public boolean joinHyphens = true;
        public boolean removePageNumbers = true;
        public boolean includeImages = false;
        public boolean ocrScans = true;
        public boolean forceOcr = false;
    }

    public interface Progress { void onProgress(int page, int count); }
    public static final class ConversionSummary {
        public final int pages;
        public final List<Integer> emptyPages;
        public final List<Integer> ocrErrors;
        ConversionSummary(int pages, List<Integer> emptyPages, List<Integer> ocrErrors) {
            this.pages = pages;
            this.emptyPages = Collections.unmodifiableList(new ArrayList<>(emptyPages));
            this.ocrErrors = Collections.unmodifiableList(new ArrayList<>(ocrErrors));
        }
    }
    private PdfEpubConverter() {}

    private static final class PageOcr implements Closeable {
        private final File pdf;
        private PdfOcr reader;
        private boolean unavailable;
        final List<Integer> errors = new ArrayList<>();

        PageOcr(File pdf) { this.pdf = pdf; }

        String read(int page, String text, Options options) throws IOException {
            if (!options.forceOcr && (!options.ocrScans || text.replaceAll("\\s", "").length() >= 35))
                return text;
            if (unavailable) {
                errors.add(page);
                return text;
            }
            try {
                if (reader == null) reader = new PdfOcr(pdf);
                String recognized = cleanText(reader.readPage(page), options);
                return recognized.isEmpty() ? text : recognized;
            } catch (IOException | RuntimeException error) {
                if (Thread.currentThread().isInterrupted() || error instanceof InterruptedIOException) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Przerwano odczyt PDF.");
                }
                if (reader == null) unavailable = true;
                errors.add(page);
                return text;
            }
        }

        @Override public void close() throws IOException {
            if (reader != null) reader.close();
        }
    }

    public static int pages(Context context, File pdf) throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try (PDDocument doc = PDDocument.load(pdf, MemoryUsageSetting.setupMixed(32L * 1024 * 1024))) { return doc.getNumberOfPages(); }
    }

    public static String preview(Context context, File pdf, Options o) throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try (PDDocument doc = PDDocument.load(pdf, MemoryUsageSetting.setupMixed(32L * 1024 * 1024))) {
            int total = doc.getNumberOfPages();
            int first = first(o, total), last = last(o, total);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder result = new StringBuilder();
            int textCharacters = 0;
            PageOcr recognizer = new PageOcr(pdf);
            try (PageOcr pages = recognizer) {
                for (int page = first; page <= Math.min(first + 1, last); page++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano podgląd.");
                    stripper.setStartPage(page); stripper.setEndPage(page);
                    String pageText = cleanText(stripper.getText(doc), o);
                    pageText = pages.read(page, pageText, o);
                    textCharacters += pageText.replaceAll("\\s", "").length();
                    result.append("Strona ").append(page).append(" z ").append(total).append("\n\n")
                          .append(pageText.isEmpty() ? "[Nie udało się rozpoznać tekstu tej strony.]" : pageText)
                          .append("\n\n");
                }
            }
            if (!recognizer.errors.isEmpty()) result.append("OCR pominięto na stronach: ").append(recognizer.errors);
            String output = result.toString().trim();
            if (textCharacters == 0) return "Nie udało się odczytać tekstu. Sprawdź czy strony nie są puste lub zbyt nieczytelne.";
            return output.length() > 4000 ? output.substring(0, 4000) + "\n…" : output;
        }
    }

    static CharSequence previewEpub(Context context, File pdf, Options requested) throws IOException {
        int total = pages(context, pdf);
        Options sample = new Options();
        sample.firstPage = first(requested, total);
        sample.lastPage = Math.min(last(requested, total), sample.firstPage + 2);
        sample.title = requested.title;
        sample.author = requested.author;
        sample.language = requested.language;
        sample.joinHyphens = requested.joinHyphens;
        sample.removePageNumbers = requested.removePageNumbers;
        sample.ocrScans = requested.ocrScans;
        sample.forceOcr = requested.forceOcr;
        File book = File.createTempFile("epub-sample-", ".epub", context.getCacheDir());
        try {
            ConversionSummary summary = convert(context, pdf, book, sample, (page, count) -> {});
            android.text.SpannableStringBuilder output = new android.text.SpannableStringBuilder();
            try (ZipFile epub = new ZipFile(book)) {
                for (int i = 1; i <= summary.pages; i++) {
                    String name = String.format(Locale.ROOT, "EPUB/chapter%04d.xhtml", i);
                    ZipEntry section = epub.getEntry(name);
                    if (section == null) throw new IOException("Brakuje strony w próbce EPUB.");
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    try (InputStream input = epub.getInputStream(section)) {
                        byte[] bytes = new byte[4096];
                        for (int n; buffer.size() < 40000 && (n = input.read(bytes)) != -1;)
                            buffer.write(bytes, 0, n);
                    }
                    String html = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                    int body = html.indexOf("<body>");
                    if (body >= 0) output.append(android.text.Html.fromHtml(html.substring(body + 6)));
                    if (output.length() > 10000) {
                        output.delete(10000, output.length());
                        output.append("…");
                        break;
                    }
                }
            }
            if (!summary.ocrErrors.isEmpty()) output.append("\nOCR pominięto na stronach: ")
                    .append(summary.ocrErrors.toString());
            return output;
        } finally {
            book.delete();
        }
    }

    public static ConversionSummary convert(Context context, File pdf, File epub, Options o, Progress progress) throws IOException {
        PDFBoxResourceLoader.init(context.getApplicationContext());
        if (pdf.length() == 0) throw new IOException("Plik PDF jest pusty.");
        File parent = epub.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Nie można utworzyć folderu EPUB.");
        File temp = new File(epub.getAbsolutePath() + ".part");
        if (temp.exists()) temp.delete();
        boolean complete = false;
        List<Integer> emptyPages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf, MemoryUsageSetting.setupMixed(32L * 1024 * 1024))) {
            if (doc.isEncrypted()) throw new IOException("Ten PDF jest zabezpieczony. Użyj pliku bez szyfrowania.");
            int total = doc.getNumberOfPages();
            int first = first(o, total), last = last(o, total);
            String title = o.title == null || o.title.trim().isEmpty() ? stem(pdf.getName()) : o.title.trim();
            String author = o.author == null ? "" : o.author.trim();
            String language = o.language != null && o.language.matches("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?") ? o.language : "pl";
            String identifier = "urn:uuid:" + UUID.randomUUID();
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int count = last - first + 1;
            int meaningfulCharacters = 0;
            int imageNumber = 0;
            StringBuilder manifest = new StringBuilder();
            StringBuilder spine = new StringBuilder();
            StringBuilder toc = new StringBuilder("<nav epub:type=\"toc\" id=\"toc\"><h1>Spis treści</h1><ol>");
            PageOcr recognizer = new PageOcr(pdf);
            try (PageOcr pages = recognizer; ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
                putEpubHeader(out);
                put(out,"EPUB/style.css", "body{font-family:serif;line-height:1.42;} p{margin:.1em 0 .55em;text-align:left;} h2{page-break-before:always;font-size:1.15em;margin-top:1em} img{max-width:100%;height:auto} figure{text-align:center;margin:.8em 0}");
                manifest.append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/><item id=\"toc\" href=\"toc.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
                for (int p = first; p <= last; p++) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Przerwano konwersję.");
                    stripper.setStartPage(p); stripper.setEndPage(p);
                    String pageText = cleanText(stripper.getText(doc), o);
                    pageText = pages.read(p, pageText, o);
                    meaningfulCharacters += pageText.replaceAll("\\s", "").length();
                    String href = String.format(Locale.ROOT, "chapter%04d.xhtml", p-first+1);
                    String id = "c" + (p-first+1);
                    manifest.append("<item id=\"").append(id).append("\" href=\"").append(href).append("\" media-type=\"application/xhtml+xml\"/>");
                    spine.append("<itemref idref=\"").append(id).append("\"/>");
                    toc.append("<li><a href=\"").append(href).append("\">Strona ").append(p).append("</a></li>");
                    StringBuilder html = new StringBuilder();
                    html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><!DOCTYPE html><html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"")
                        .append(xml(language)).append("\"><head><title>Strona ").append(p)
                        .append("</title><link rel=\"stylesheet\" href=\"style.css\" type=\"text/css\"/></head><body><h2>Strona ").append(p).append("</h2>");
                    if (pageText.trim().isEmpty()) {
                        emptyPages.add(p);
                        html.append("<p>Nie odczytano tekstu na tej stronie. Sprawdź stronę w oryginalnym PDF.</p>");
                    }
                    appendParagraphs(html, pageText);
                    if (o.includeImages && imageNumber < 100) {
                        PDResources resources = doc.getPage(p-1).getResources();
                        if (resources != null) for (COSName name: resources.getXObjectNames()) {
                            if (imageNumber >= 100) break;
                            try {
                                PDXObject x = resources.getXObject(name);
                                if (!(x instanceof PDImageXObject)) continue;
                                PDImageXObject imageObject = (PDImageXObject)x;
                                if (imageObject.getWidth() <= 0 || imageObject.getHeight() <= 0
                                        || (long)imageObject.getWidth() * imageObject.getHeight() > 8_000_000L) continue;
                                Bitmap image = imageObject.getImage();
                                if (image == null) continue;
                                Bitmap scaled = image;
                                if (image.getWidth() > 1100 || image.getHeight() > 1600) {
                                    double factor = Math.min(1100.0/image.getWidth(), 1600.0/image.getHeight());
                                    scaled = Bitmap.createScaledBitmap(image,Math.max(1,(int)(image.getWidth()*factor)),Math.max(1,(int)(image.getHeight()*factor)),true);
                                }
                                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                                scaled.compress(Bitmap.CompressFormat.JPEG,78,bytes);
                                if (scaled != image) scaled.recycle();
                                if (bytes.size() > 2_500_000) continue;
                                String imagePath = String.format(Locale.ROOT,"image%04d.jpg",++imageNumber);
                                ZipEntry entry = new ZipEntry("EPUB/"+imagePath); out.putNextEntry(entry); bytes.writeTo(out);out.closeEntry();
                                manifest.append("<item id=\"im").append(imageNumber).append("\" href=\"").append(imagePath).append("\" media-type=\"image/jpeg\"/>");
                                html.append("<figure><img src=\"").append(imagePath).append("\" alt=\"Ilustracja ze strony ").append(p).append("\"/></figure>");
                            } catch (Exception ignored) { /* Unsupported image codec: keep the page text. */ }
                        }
                    }
                    html.append("</body></html>");
                    put(out, "EPUB/"+href, html.toString());
                    if (progress != null) progress.onProgress(p-first+1, count);
                }
                if (meaningfulCharacters < Math.min(15, count * 5)) throw new IOException("Nie rozpoznano tekstu w wybranych stronach. Sprawdź czy skan jest wyraźny; oryginalny PDF pozostaje bez zmian.");
                toc.append("</ol></nav>");
                put(out,"EPUB/toc.xhtml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Spis treści</title></head><body>"+toc+"</body></html>");
                put(out,"EPUB/package.opf","<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"uid\">"+xml(identifier)+"</dc:identifier><dc:title>"+xml(title)+"</dc:title><dc:creator>"+xml(author)+"</dc:creator><dc:language>"+xml(language)+"</dc:language><meta property=\"dcterms:modified\">"+new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT){{setTimeZone(TimeZone.getTimeZone("UTC"));}}.format(new Date())+"</meta></metadata><manifest>"+manifest+"</manifest><spine>"+spine+"</spine></package>");
            }
            if (!temp.renameTo(epub)) {
                try (InputStream in = new FileInputStream(temp);OutputStream output = new FileOutputStream(epub)) {byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)output.write(b,0,n);}
                temp.delete();
            }
            complete = true;
            return new ConversionSummary(last - first + 1, emptyPages, recognizer.errors);
        } finally { if (!complete) { temp.delete(); epub.delete(); } }
    }

    private static int first(Options o, int total) throws IOException { if (total < 1) throw new IOException("PDF nie zawiera stron."); if (o.firstPage < 1 || o.firstPage > total) throw new IOException("Nieprawidłowa strona początkowa (1–"+total+")."); return o.firstPage; }
    private static int last(Options o, int total) throws IOException { int last=o.lastPage==0?total:o.lastPage; if (last < o.firstPage || last > total) throw new IOException("Nieprawidłowa strona końcowa (max " + total + ")."); return last; }
    private static String stem(String name) { int dot=name.lastIndexOf('.'); return dot>0?name.substring(0,dot):name; }
    static String xml(String text) { return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;").replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]",""); }
    static void putEpubHeader(ZipOutputStream out) throws IOException {
        byte[] mime = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        ZipEntry mimeEntry = new ZipEntry("mimetype");
        mimeEntry.setMethod(ZipEntry.STORED);
        mimeEntry.setSize(mime.length);
        CRC32 crc = new CRC32(); crc.update(mime); mimeEntry.setCrc(crc.getValue());
        out.putNextEntry(mimeEntry); out.write(mime); out.closeEntry();
        put(out,"META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"EPUB/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
    }
    static void put(ZipOutputStream out, String name, String content) throws IOException { out.putNextEntry(new ZipEntry(name));out.write(content.getBytes(StandardCharsets.UTF_8));out.closeEntry(); }
    private static String cleanText(String input, Options o) {
        String text=Normalizer.normalize(input,Normalizer.Form.NFC).replace("\r\n","\n").replace('\r','\n');
        if(o.joinHyphens) text=text.replaceAll("(?<=\\p{L})-\\s*\\n\\s*(?=\\p{Ll})", "");
        if(o.removePageNumbers) text=text.replaceAll("(?m)^\\s*(?:[-–]?\\s*\\d{1,4}\\s*[-–]?)\\s*$", "");
        text=text.replaceAll("[ \\t]+", " ").replaceAll("(?m)^[ \\t]+|[ \\t]+$", "").trim();
        return text;
    }
    private static void appendParagraphs(StringBuilder out, String text) {
        if(text.isEmpty()) return;
        String[] paragraphs=text.split("\\n\\s*\\n+");
        for(String paragraph:paragraphs) {
            String normalized=paragraph.trim().replaceAll("(?<![.!?:;])\\n", " ").replace('\n',' ');
            if (!normalized.isEmpty()) out.append("<p>").append(xml(normalized)).append("</p>");
        }
    }
}
