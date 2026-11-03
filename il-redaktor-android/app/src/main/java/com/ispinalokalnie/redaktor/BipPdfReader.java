package com.ispinalokalnie.redaktor;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Reads public BIP PDF attachments on-device. Search results are still evidence to verify,
 * not verified facts. Scanned pages use bundled ML Kit OCR; documents are never uploaded here.
 */
public final class BipPdfReader {
    private static final int MAX_SOURCE_PAGES = 5;
    private static final int MAX_DOCUMENTS = 4;
    private static final int MAX_PDF_BYTES = 8 * 1024 * 1024;
    private static final int MAX_HTML_BYTES = 1_250_000;
    private static final int MAX_PDF_PAGES = 120;
    private static final int MAX_OCR_PAGES_PER_DOCUMENT = 24;
    private static final int MAX_OCR_PAGES_PER_SEARCH = 40;
    private static final int MAX_DOCUMENT_TEXT = 18_000;
    private static final int MAX_RENDER_DIMENSION = 1_600;
    private static final long MAX_READ_MILLIS = 72_000L;
    private static final String SEARCH_METHOD = "BIP_PDF";
    private static volatile boolean pdfBoxReady;

    private BipPdfReader() {}

    public static JSONArray read(Context context, JSONArray sources, String topic,
                                 ChatGptPlanClient.SearchControl control) throws Exception {
        JSONArray output = new JSONArray();
        if (sources == null || sources.length() == 0) return output;

        long deadline = SystemClock.elapsedRealtime() + MAX_READ_MILLIS;
        ArrayList<SourcePage> pages = new ArrayList<>();
        LinkedHashMap<String, PdfCandidate> directPdfs = new LinkedHashMap<>();

        for (int i = 0; i < sources.length(); i++) {
            control.check();
            JSONObject source = sources.optJSONObject(i);
            if (source == null) continue;
            String address = secureBipUrl(source.optString("url", ""));
            String title = cleanTitle(source.optString("title", ""));
            if (address.isEmpty() || !isTrustedBipUrl(address)) continue;

            long publishedAt = source.optLong("publishedAt", 0L);
            String published = source.optString("published", "");
            if (looksLikePdf(address, title)) {
                String key = canonical(address);
                if (!directPdfs.containsKey(key)) {
                    directPdfs.put(key, new PdfCandidate(title, address, address, published, publishedAt));
                }
            } else {
                String key = canonical(address);
                boolean exists = false;
                for (SourcePage page : pages) if (canonical(page.url).equals(key)) { exists = true; break; }
                if (!exists) pages.add(new SourcePage(title, address, published, publishedAt));
            }
        }

        pages.sort(Comparator.comparingInt(page -> {
            try {
                String path = Uri.parse(page.url).getPath();
                return path != null && path.toLowerCase(Locale.ROOT).contains("/ugdrwinia") ? 0 : 1;
            } catch (Exception ignored) { return 1; }
        }));
        int pageSourcesRead = 0;
        for (SourcePage page : pages) {
            if (pageSourcesRead >= MAX_SOURCE_PAGES || directPdfs.size() >= MAX_DOCUMENTS ||
                SystemClock.elapsedRealtime() >= deadline) break;
            control.check();
            pageSourcesRead++;
            control.report("BIP · " + page.title, page.url, "READING", SEARCH_METHOD, 0,
                "Sprawdzam, czy wpis ma załącznik PDF.");
            try {
                ArrayList<PdfCandidate> attachments = findPdfAttachments(page, control, deadline);
                if (attachments.isEmpty()) {
                    output.put(new JSONObject()
                        .put("title", page.title)
                        .put("url", page.url)
                        .put("sourcePage", page.url)
                        .put("published", page.published)
                        .put("publishedAt", page.publishedAt)
                        .put("status", "no_pdf")
                        .put("method", "BIP_HTML")
                        .put("text", "")
                        .put("preview", "")
                        .put("message", "Wpis BIP odczytano, ale nie znaleziono w nim załącznika PDF."));
                    control.report("BIP · " + page.title, page.url, "DONE", SEARCH_METHOD, 0,
                        "Wpis BIP nie zawiera rozpoznanego załącznika PDF.");
                    continue;
                }
                for (PdfCandidate attachment : attachments) {
                    if (directPdfs.size() >= MAX_DOCUMENTS) break;
                    String key = canonical(attachment.pdfUrl);
                    directPdfs.putIfAbsent(key, attachment);
                }
                control.report("BIP · " + page.title, page.url, "DONE", SEARCH_METHOD,
                    attachments.size(), "Znaleziono załączniki PDF. Przekazuję je do odczytu.");
            } catch (Exception error) {
                control.check();
                output.put(new JSONObject()
                    .put("title", page.title)
                    .put("url", page.url)
                    .put("sourcePage", page.url)
                    .put("published", page.published)
                    .put("publishedAt", page.publishedAt)
                    .put("status", "source_unavailable")
                    .put("method", "BIP_HTML")
                    .put("text", "")
                    .put("preview", "")
                    .put("message", safeMessage(error, "Nie udało się odczytać strony BIP.")));
                control.report("BIP · " + page.title, page.url, "ERROR", SEARCH_METHOD, 0,
                    safeMessage(error, "Nie udało się odczytać strony BIP."));
            }
        }

        int ocrPagesUsed = 0;
        int documentsProcessed = 0;
        for (PdfCandidate candidate : directPdfs.values()) {
            if (documentsProcessed >= MAX_DOCUMENTS || SystemClock.elapsedRealtime() >= deadline) break;
            control.check();
            JSONObject result = readPdf(context, candidate, topic, control, deadline,
                MAX_OCR_PAGES_PER_SEARCH - ocrPagesUsed);
            ocrPagesUsed += result.optInt("ocrAttempts", 0);
            output.put(result);
            documentsProcessed++;
        }
        return output;
    }

    private static ArrayList<PdfCandidate> findPdfAttachments(SourcePage source,
                                                               ChatGptPlanClient.SearchControl control,
                                                               long deadline) throws Exception {
        ArrayList<PdfCandidate> found = new ArrayList<>();
        Response response = open(source.url, control, 5_000, 7_000);
        try {
            if (response.connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new java.io.IOException("BIP zwrócił HTTP " + response.connection.getResponseCode() + ".");
            byte[] bytes = readLimited(response.connection, MAX_HTML_BYTES, control, deadline);
            Document page = Jsoup.parse(new ByteArrayInputStream(bytes), "UTF-8", response.url.toString());
            String title = cleanTitle(page.title());
            for (Element anchor : page.select("a[href]")) {
                control.check();
                String url = secureBipUrl(anchor.absUrl("href"));
                String label = cleanTitle(anchor.text());
                if (url.isEmpty() || !isTrustedBipUrl(url) || !looksLikeAttachment(url, label)) continue;
                String documentTitle = label.isEmpty() ? source.title : label;
                found.add(new PdfCandidate(documentTitle, url, source.url,
                    source.published, source.publishedAt));
                if (found.size() >= 3) break;
            }
            if (!title.isEmpty() && (found.isEmpty() || source.title.isEmpty())) {
                // Keep the BIP page title when the search index supplied none.
                for (int i = 0; i < found.size(); i++) found.set(i, found.get(i).withTitle(title));
            }
        } finally {
            close(response.connection, control);
        }
        return found;
    }

    private static JSONObject readPdf(Context context, PdfCandidate candidate, String topic,
                                      ChatGptPlanClient.SearchControl control, long deadline,
                                      int ocrRemaining) throws Exception {
        File file = null;
        JSONObject result = new JSONObject()
            .put("title", cleanTitle(candidate.title))
            .put("url", candidate.pdfUrl)
            .put("sourcePage", candidate.sourcePage)
            .put("published", candidate.published)
            .put("publishedAt", candidate.publishedAt)
            .put("status", "unread")
            .put("method", "PDF")
            .put("text", "")
            .put("preview", "")
            .put("ocrPages", 0)
            .put("ocrAttempts", 0)
            .put("pagesRead", 0)
            .put("pageCount", 0)
            .put("complete", false)
            .put("message", "Odczyt dokumentu rozpoczęty.");

        Response response = null;
        try {
            control.check();
            control.report("PDF BIP · " + candidate.title, candidate.pdfUrl, "READING",
                "BIP_PDF_TEXT", 0, "Pobieram dokument z publicznego BIP.");
            response = open(candidate.pdfUrl, control, 6_000, 9_000);
            file = downloadPdf(response.connection, context, control, deadline);
            close(response.connection, control);
            response = null;

            ensurePdfBox(context);
            String extracted = "";
            int pageCount;
            int processedPages;
            int ocrPages = 0, ocrAttempts = 0, pdfTextPages = 0;
            int failedOcrPages = 0;
            TextRecognizer recognizer = null;
            PDFRenderer renderer = null;
            StringBuilder text = new StringBuilder();
            boolean truncated = false;
            boolean ocrLimitReached = false;
            int pagesRead = 0;

            try (PDDocument document = PDDocument.load(file)) {
                if (document.isEncrypted()) throw new java.io.IOException("PDF jest zabezpieczony hasłem.");
                pageCount = document.getNumberOfPages();
                if (pageCount <= 0) throw new java.io.IOException("PDF nie zawiera stron.");
                processedPages = Math.min(pageCount, MAX_PDF_PAGES);
                truncated = pageCount > processedPages;

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setStartPage(1);
                stripper.setEndPage(processedPages);
                stripper.setPageEnd("\f");
                extracted = stripper.getText(document);
                String[] pageTexts = extracted.split("\\f", -1);

                for (int pageIndex = 0; pageIndex < processedPages; pageIndex++) {
                    control.check();
                    if (SystemClock.elapsedRealtime() >= deadline) { truncated = true; break; }
                    pagesRead++;
                    result.put("pagesRead", pagesRead);
                    String pageText = pageIndex < pageTexts.length ? normalizePage(pageTexts[pageIndex]) : "";
                    boolean hadPdfText = pageText.length() >= 24;
                    boolean needsOcr = needsOcr(pageText);
                    if (needsOcr && ocrAttempts < Math.min(MAX_OCR_PAGES_PER_DOCUMENT, Math.max(0, ocrRemaining))) {
                        ocrAttempts++;
                        result.put("ocrAttempts", ocrAttempts);
                        if (recognizer == null) recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                        if (renderer == null) renderer = new PDFRenderer(document);
                        try {
                            Bitmap bitmap = renderPage(renderer, document.getPage(pageIndex), pageIndex);
                            if (bitmap != null) {
                                OcrImage image = new OcrImage(bitmap);
                                try {
                                    String ocr = recognize(image, recognizer, control, deadline);
                                    if (!ocr.trim().isEmpty()) {
                                        pageText = normalizePage(ocr);
                                        ocrPages++;
                                        result.put("ocrPages", ocrPages);
                                        control.report("PDF BIP · " + candidate.title, candidate.pdfUrl,
                                            "READING", "BIP_OCR", ocrPages,
                                            "Rozpoznano tekst OCR ze strony " + (pageIndex + 1) + " z " + pageCount + ".");
                                    }
                                } finally {
                                    image.release();
                                }
                            } else failedOcrPages++;
                        } catch (Exception ocrError) {
                            control.check();
                            failedOcrPages++;
                            // A failed OCR page does not discard readable PDF text or later pages.
                        }
                    } else if (needsOcr) { ocrLimitReached = true; truncated = true; }
                    if (hadPdfText) pdfTextPages++;
                    if (!pageText.isEmpty()) {
                        if (text.length() + pageText.length() > MAX_DOCUMENT_TEXT) {
                            int remaining = MAX_DOCUMENT_TEXT - text.length();
                            if (remaining > 0) text.append("\n").append("Strona ").append(pageIndex + 1)
                                .append(":\n").append(pageText, 0, Math.min(remaining, pageText.length()));
                            truncated = true;
                            break;
                        }
                        if (text.length() > 0) text.append("\n\n");
                        text.append("Strona ").append(pageIndex + 1).append(":\n").append(pageText);
                    }
                }
            } finally {
                if (recognizer != null) recognizer.close();
            }

            String body = text.toString().trim();
            boolean bodyMentionsLocality = body.toLowerCase(Locale.ROOT).matches("(?s).*ispin.*|.*drwini.*");
            boolean matchedTopic = matchesTopic(body, topic);
            String method = ocrAttempts == 0 ? "PDF_TEXT" : (pdfTextPages > 0 ? "PDF_TEXT_AND_OCR" : "OCR");
            String status;
            String message;
            if (body.length() < 80) {
                status = failedOcrPages > 0 || ocrLimitReached ? "ocr_incomplete" : "no_readable_text";
                message = failedOcrPages > 0 || ocrLimitReached
                    ? "Nie udało się rozpoznać części skanu. Otwórz oryginał i sprawdź go ręcznie."
                    : "Nie udało się wydobyć czytelnej treści z PDF. Otwórz oryginał i sprawdź go ręcznie.";
            } else if (truncated || failedOcrPages > 0) {
                status = "partial";
                message = "Odczyt częściowy. Sprawdź dalsze strony i oryginał przed wykorzystaniem.";
            } else {
                status = "read";
                message = "Tekst odczytany automatycznie. Wymaga sprawdzenia w oryginale.";
            }
            result.put("status", status)
                .put("method", method)
                .put("text", body)
                .put("preview", preview(body))
                .put("ocrPages", ocrPages)
                .put("ocrAttempts", ocrAttempts)
                .put("pagesRead", pagesRead)
                .put("pageCount", pageCount)
                .put("complete", "read".equals(status))
                .put("bodyMentionsLocality", bodyMentionsLocality)
                .put("matchesTopic", matchedTopic)
                .put("truncated", truncated)
                .put("message", message);
            control.report("PDF BIP · " + candidate.title, candidate.pdfUrl, "DONE",
                method.equals("PDF_TEXT") ? "BIP_PDF_TEXT" : "BIP_OCR",
                ocrPages, "Odczytano " + pagesRead + " z " + pageCount +
                    " stron; OCR: " + ocrPages + ". Treść wymaga weryfikacji.");
            return result;
        } catch (Exception error) {
            control.check();
            result.put("status", "unavailable")
                .put("message", safeMessage(error, "Nie udało się odczytać PDF."))
                .put("complete", false);
            control.report("PDF BIP · " + candidate.title, candidate.pdfUrl, "ERROR",
                "BIP_PDF_TEXT", 0, safeMessage(error, "Nie udało się odczytać PDF."));
            return result;
        } finally {
            if (response != null) close(response.connection, control);
            if (file != null && file.exists()) file.delete();
        }
    }

    private static Bitmap renderPage(PDFRenderer renderer, PDPage page, int pageIndex) throws Exception {
        float largestPointDimension = Math.max(page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
        if (largestPointDimension <= 0f) return null;
        float scale = Math.min(1.6f, MAX_RENDER_DIMENSION / largestPointDimension);
        if (scale < 0.35f) return null;
        return renderer.renderImage(pageIndex, scale);
    }

    private static String recognize(OcrImage image, TextRecognizer recognizer,
                                    ChatGptPlanClient.SearchControl control, long deadline) throws Exception {
        Task<Text> task = recognizer.process(InputImage.fromBitmap(image.bitmap, 0));
        while (!task.isComplete()) {
            try {
                control.check();
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new java.net.SocketTimeoutException("Przekroczono limit czasu OCR.");
                Thread.sleep(120L);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                image.deferReleaseUntil(task);
                throw new java.io.IOException("Odczyt OCR przerwany.", interrupted);
            } catch (Exception stopped) {
                image.deferReleaseUntil(task);
                throw stopped;
            }
        }
        control.check();
        if (task.isSuccessful()) {
            Text result = task.getResult();
            return result == null ? "" : result.getText();
        }
        Exception error = task.getException();
        throw error == null ? new java.io.IOException("Usługa OCR nie zwróciła tekstu.") : error;
    }

    private static void ensurePdfBox(Context context) {
        if (pdfBoxReady) return;
        synchronized (BipPdfReader.class) {
            if (!pdfBoxReady) {
                PDFBoxResourceLoader.init(context.getApplicationContext());
                pdfBoxReady = true;
            }
        }
    }

    private static File downloadPdf(HttpURLConnection connection, Context context,
                                   ChatGptPlanClient.SearchControl control, long deadline) throws Exception {
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK)
            throw new java.io.IOException("Serwer PDF zwrócił HTTP " + status + ".");
        long advertised = connection.getContentLengthLong();
        if (advertised > MAX_PDF_BYTES)
            throw new java.io.IOException("PDF przekracza bezpieczny limit 8 MB.");
        File file = File.createTempFile("il-bip-", ".pdf", context.getCacheDir());
        boolean keep = false;
        try (InputStream raw = connection.getInputStream();
             BufferedInputStream input = new BufferedInputStream(raw);
             FileOutputStream output = new FileOutputStream(file)) {
            input.mark(1024);
            byte[] signature = new byte[1024];
            int count = 0, next;
            while (count < signature.length && (next = input.read(signature, count, signature.length - count)) != -1) count += next;
            input.reset();
            boolean pdfHeader = false;
            for (int i = 0; i <= count - 5; i++) {
                if (signature[i] == '%' && signature[i + 1] == 'P' && signature[i + 2] == 'D' &&
                    signature[i + 3] == 'F' && signature[i + 4] == '-') { pdfHeader = true; break; }
            }
            if (!pdfHeader)
                throw new java.io.IOException("Odpowiedź serwera nie jest plikiem PDF.");
            byte[] buffer = new byte[12_288];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                control.check();
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new java.net.SocketTimeoutException("Przekroczono limit czasu pobierania PDF.");
                total += read;
                if (total > MAX_PDF_BYTES)
                    throw new java.io.IOException("PDF przekracza bezpieczny limit 8 MB.");
                output.write(buffer, 0, read);
            }
            output.flush();
            keep = true;
            return file;
        } finally {
            if (!keep) file.delete();
        }
    }

    private static byte[] readLimited(HttpURLConnection connection, int maxBytes,
                                      ChatGptPlanClient.SearchControl control, long deadline) throws Exception {
        long advertised = connection.getContentLengthLong();
        if (advertised > maxBytes) throw new java.io.IOException("Strona BIP przekracza limit odczytu.");
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[10_240];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                control.check();
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new java.net.SocketTimeoutException("Przekroczono limit czasu strony BIP.");
                total += read;
                if (total > maxBytes) throw new java.io.IOException("Strona BIP przekracza limit odczytu.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Response open(String address, ChatGptPlanClient.SearchControl control,
                                 int connectTimeout, int readTimeout) throws Exception {
        URL current = new URL(address);
        for (int redirects = 0; redirects <= 4; redirects++) {
            control.check();
            validatePublicBipUrl(current);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            control.attach(connection);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Accept", "application/pdf, text/html;q=0.9, */*;q=0.1");
            connection.setRequestProperty("User-Agent", "IL-Redaktor/2.0 (public BIP document reader)");
            connection.setRequestProperty("Connection", "close");
            int status;
            try { status = connection.getResponseCode(); }
            catch (Exception error) { close(connection, control); throw error; }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                close(connection, control);
                if (location == null || location.trim().isEmpty())
                    throw new java.io.IOException("Przekierowanie BIP nie zawiera adresu.");
                current = new URL(current, location);
                continue;
            }
            return new Response(connection, current);
        }
        throw new java.io.IOException("Za dużo przekierowań w źródle BIP.");
    }

    private static void validatePublicBipUrl(URL url) throws Exception {
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getUserInfo() != null ||
            (url.getPort() != -1 && url.getPort() != 443) || !isTrustedBipHost(url.getHost()))
            throw new java.io.IOException("Odczyt ograniczono do publicznych stron BIP/urzędowych przez HTTPS.");
        InetAddress[] addresses = InetAddress.getAllByName(url.getHost());
        if (addresses.length == 0) throw new java.io.IOException("Nie udało się odnaleźć serwera BIP.");
        for (InetAddress address : addresses) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                address.isMulticastAddress())
                throw new java.io.IOException("Adres BIP nie prowadzi do publicznego serwera.");
        }
    }

    private static boolean isTrustedBipUrl(String address) {
        try {
            URL url = new URL(address);
            return "https".equalsIgnoreCase(url.getProtocol()) && url.getUserInfo() == null &&
                (url.getPort() == -1 || url.getPort() == 443) && isTrustedBipHost(url.getHost());
        } catch (Exception ignored) { return false; }
    }

    /** Upgrade legacy official BIP links to HTTPS without ever requesting HTTP. */
    private static String secureBipUrl(String raw) {
        try {
            URL url = new URL(raw == null ? "" : raw.trim());
            String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
            if (url.getUserInfo() != null || !isTrustedBipHost(url.getHost())) return "";
            if ("https".equals(protocol) && (url.getPort() == -1 || url.getPort() == 443))
                return url.toString();
            if ("http".equals(protocol) && (url.getPort() == -1 || url.getPort() == 80))
                return new URL("https", url.getHost(), -1, url.getFile()).toString();
            return "";
        } catch (Exception ignored) { return ""; }
    }

    private static boolean isTrustedBipHost(String value) {
        String host = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return host.equals("bip.malopolska.pl") || host.endsWith(".bip.malopolska.pl") ||
            host.equals("gov.pl") || host.endsWith(".gov.pl") ||
            host.equals("drwinia.pl") || host.endsWith(".drwinia.pl") ||
            host.equals("malopolska.uw.gov.pl") || host.endsWith(".malopolska.uw.gov.pl");
    }

    private static boolean looksLikePdf(String address, String title) {
        try {
            String path = Uri.parse(address).getPath();
            String text = ((path == null ? "" : path) + " " + address + " " + title).toLowerCase(Locale.ROOT);
            return text.contains(".pdf") || text.contains("filetype=pdf");
        } catch (Exception ignored) { return false; }
    }

    private static boolean looksLikeAttachment(String address, String label) {
        String text = (address + " " + label).toLowerCase(Locale.ROOT);
        return text.contains(".pdf") || text.contains("download") ||
            text.contains("attachment") || text.contains("załącz") ||
            text.contains("zalacz") || text.contains("pobierz") || text.contains("pdf");
    }

    private static String canonical(String value) {
        try {
            Uri uri = Uri.parse(value);
            return uri.buildUpon().fragment(null).build().toString();
        } catch (Exception ignored) { return value; }
    }

    private static String host(String value) {
        try { return Uri.parse(value).getHost() == null ? "" : Uri.parse(value).getHost().toLowerCase(Locale.ROOT); }
        catch (Exception ignored) { return ""; }
    }

    private static String normalizePage(String value) {
        return (value == null ? "" : value).replace('\u0000', ' ')
            .replace('\r', '\n').replaceAll("[\\t ]+", " ")
            .replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    private static boolean needsOcr(String text) {
        if (text == null || text.length() < 24) return true;
        int useful = 0, replacement = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) useful++;
            if (ch == '\uFFFD' || Character.getType(ch) == Character.PRIVATE_USE) replacement++;
        }
        return useful < Math.max(16, text.length() / 5) || replacement > Math.max(2, text.length() / 20);
    }

    private static boolean matchesTopic(String text, String topic) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        boolean local = lower.contains("ispin") || lower.contains("drwini");
        if (!local) return false;
        if (topic == null || topic.trim().isEmpty() || "ispina".equalsIgnoreCase(topic.trim())) return true;
        if ("paczkomat".equals(topic)) return lower.contains("pacz") || lower.contains("inpost");
        if ("transport".equals(topic) || "autobusy".equals(topic))
            return lower.contains("transport") || lower.contains("autobus") || lower.contains("komunik")
                || lower.contains("linia") || lower.contains("mld") || lower.contains("gminn");
        if ("osp".equals(topic)) return lower.matches("(?s).*\\bosp\\b.*") || lower.contains("straż");
        if ("most".equals(topic)) return lower.contains("most");
        for (String token : topic.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.length() < 4) continue;
            if (!lower.contains(token.substring(0, Math.min(5, token.length())))) return false;
        }
        return true;
    }

    private static String preview(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        int hit = lower.indexOf("ispin");
        if (hit < 0) hit = lower.indexOf("drwini");
        if (hit < 0) hit = 0;
        int start = Math.max(0, hit - 450);
        int end = Math.min(text.length(), start + 3_000);
        return text.substring(start, end).trim();
    }

    private static String cleanTitle(String value) {
        return (value == null ? "" : value).replaceAll("\\s+", " ").trim()
            .replaceAll("^\\s*(pobierz|download)\\s*[:—-]?\\s*", "");
    }

    private static String safeMessage(Exception error, String fallback) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message.trim().substring(0, Math.min(240, message.trim().length()));
    }

    private static void close(HttpURLConnection connection, ChatGptPlanClient.SearchControl control) {
        if (connection == null) return;
        control.detach(connection);
        try { connection.disconnect(); } catch (Exception ignored) {}
    }

    private static final class OcrImage {
        final Bitmap bitmap;
        private boolean deferred, recycled;
        OcrImage(Bitmap bitmap) { this.bitmap = bitmap; }
        synchronized void deferReleaseUntil(Task<?> task) {
            if (recycled || deferred) return;
            deferred = true;
            task.addOnCompleteListener(done -> releaseNow());
        }
        synchronized void release() {
            if (!deferred) releaseNow();
        }
        private synchronized void releaseNow() {
            if (recycled) return;
            recycled = true;
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private static final class Response {
        final HttpURLConnection connection;
        final URL url;
        Response(HttpURLConnection connection, URL url) { this.connection = connection; this.url = url; }
    }

    private static final class SourcePage {
        final String title, url, published;
        final long publishedAt;
        SourcePage(String title, String url, String published, long publishedAt) {
            this.title = title; this.url = url; this.published = published; this.publishedAt = publishedAt;
        }
    }

    private static final class PdfCandidate {
        final String title, pdfUrl, sourcePage, published;
        final long publishedAt;
        PdfCandidate(String title, String pdfUrl, String sourcePage, String published, long publishedAt) {
            this.title = title; this.pdfUrl = pdfUrl; this.sourcePage = sourcePage;
            this.published = published; this.publishedAt = publishedAt;
        }
        PdfCandidate withTitle(String value) {
            return new PdfCandidate(value, pdfUrl, sourcePage, published, publishedAt);
        }
    }
}

