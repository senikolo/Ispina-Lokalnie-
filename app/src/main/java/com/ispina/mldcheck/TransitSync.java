package com.ispina.mldcheck;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and converts public GTFS files; no feed binaries are used at build time. */
final class TransitSync {
    private static final String FEED_URL = "https://kolejemalopolskie.com.pl/rozklady_jazdy/ald-gtfs.zip";
    private static final long MAX_ZIP_BYTES = 80L * 1024L * 1024L;
    private static final long MAX_DOWNLOAD_BYTES = 24L * 1024L * 1024L;
    private static final int MAX_OUTPUT_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_BRIDGE_CHUNK_BYTES = 64 * 1024;
    private static final int MAX_TRIPS = 12000;
    private static final int MAX_STOP_TIMES = 400000;

    private TransitSync() { }

    static int cachedSize(Context context) {
        File file = new File(context.getFilesDir(), "mld-schedule.json");
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_OUTPUT_BYTES) return 0;
        return (int) file.length();
    }

    static String readCachedChunk(Context context, int offset, int length) {
        int size = cachedSize(context);
        if (size == 0 || offset < 0 || offset >= size || length <= 0 || length > MAX_BRIDGE_CHUNK_BYTES) return "";
        int bytesToRead = Math.min(length, size - offset);
        byte[] buffer = new byte[bytesToRead];
        try (RandomAccessFile file = new RandomAccessFile(new File(context.getFilesDir(), "mld-schedule.json"), "r")) {
            file.seek(offset);
            int count = 0;
            while (count < bytesToRead) {
                int read = file.read(buffer, count, bytesToRead - count);
                if (read < 0) break;
                count += read;
            }
            if (count == 0) return "";
            return Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP);
        } catch (Exception ignored) { return ""; }
    }

    static void downloadAndSave(Context context) throws Exception {
        HttpURLConnection connection = openFeedConnection();
        try {
            long contentLength = connection.getContentLength();
            if (contentLength > MAX_DOWNLOAD_BYTES) throw new IllegalStateException("Plik GTFS jest zbyt duży do bezpiecznego pobrania");
            Map<String, byte[]> files = readZip(connection.getInputStream());
            String json = convert(files);
            File target = new File(context.getFilesDir(), "mld-schedule.json");
            File temporary = new File(context.getFilesDir(), "mld-schedule.json.tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(json.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
            if (!temporary.renameTo(target)) {
                File backup = new File(context.getFilesDir(), "mld-schedule.json.bak");
                if (backup.exists() && !backup.delete()) throw new IllegalStateException("Nie można przygotować zapisu rozkładu");
                boolean hadTarget = target.exists();
                if (hadTarget && !target.renameTo(backup)) throw new IllegalStateException("Nie można zachować poprzedniego rozkładu");
                if (!temporary.renameTo(target)) {
                    if (hadTarget) backup.renameTo(target);
                    throw new IllegalStateException("Nie udało się zapisać pobranego rozkładu");
                }
                if (hadTarget) backup.delete();
            }
        } finally { connection.disconnect(); }
    }

    private static HttpURLConnection openFeedConnection() throws Exception {
        URL current = new URL(FEED_URL);
        for (int redirects = 0; redirects <= 4; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("User-Agent", "IspinaLokalnie/2.0 (Android; GTFS reader)");
            connection.setRequestProperty("Accept", "application/zip,application/octet-stream");
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) return connection;
            if (status < 300 || status >= 400 || redirects == 4) {
                connection.disconnect();
                throw new IllegalStateException("Źródło GTFS zwróciło HTTP " + status);
            }
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isEmpty()) throw new IllegalStateException("Brak adresu przekierowania GTFS");
            current = new URL(current, location);
            String host = current.getHost();
            if (!"https".equals(current.getProtocol()) || !("kolejemalopolskie.com.pl".equals(host) || "www.kolejemalopolskie.com.pl".equals(host))) {
                throw new IllegalStateException("Przekierowanie GTFS prowadzi poza oficjalną domenę");
            }
        }
        throw new IllegalStateException("Nie udało się pobrać rozkładu GTFS");
    }

    private static Map<String, byte[]> readZip(InputStream input) throws Exception {
        Map<String, byte[]> found = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new BoundedInputStream(input, MAX_DOWNLOAD_BYTES))) {
            ZipEntry entry;
            int entryCount = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES) throw new IllegalStateException("Archiwum GTFS ma zbyt wiele plików");
                String name = new File(entry.getName()).getName();
                boolean needed = isNeeded(name);
                ByteArrayOutputStream out = needed ? new ByteArrayOutputStream() : null;
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_ZIP_BYTES) throw new IllegalStateException("Pliki GTFS przekraczają limit bezpieczeństwa");
                    if (out != null) out.write(buffer, 0, n);
                }
                if (needed) {
                    if (found.containsKey(name)) throw new IllegalStateException("Archiwum zawiera powtórzony plik " + name);
                    found.put(name, out.toByteArray());
                }
            }
        }
        for (String required : new String[]{"routes.txt", "trips.txt", "stops.txt", "stop_times.txt"}) {
            if (!found.containsKey(required)) throw new IllegalStateException("Feed GTFS nie zawiera " + required);
        }
        return found;
    }

    private static boolean isNeeded(String name) {
        return name.equals("routes.txt") || name.equals("trips.txt") || name.equals("stops.txt") ||
                name.equals("stop_times.txt") || name.equals("calendar.txt") || name.equals("calendar_dates.txt") ||
                name.equals("feed_info.txt");
    }

    private static String convert(Map<String, byte[]> files) throws Exception {
        JSONObject stops = parseStops(files.get("stops.txt"));
        Map<String, String> routes = parseRoutes(files.get("routes.txt"));
        Map<String, JSONObject> tripsById = parseTrips(files.get("trips.txt"), routes);
        int stopTimeCount = parseStopTimes(files.get("stop_times.txt"), tripsById);
        if (stopTimeCount == 0) throw new IllegalStateException("Feed nie zawiera odjazdów");
        JSONArray trips = new JSONArray();
        for (JSONObject trip : tripsById.values()) {
            JSONArray times = trip.getJSONArray("stopTimes");
            if (times.length() < 2) continue;
            List<JSONObject> ordered = new ArrayList<>();
            for (int i = 0; i < times.length(); i++) ordered.add(times.getJSONObject(i));
            Collections.sort(ordered, Comparator.comparingInt(x -> x.optInt("seq", 0)));
            JSONArray sorted = new JSONArray();
            for (JSONObject item : ordered) sorted.put(item);
            trip.put("stopTimes", sorted);
            trips.put(trip);
        }
        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("fetchedAt", System.currentTimeMillis());
        root.put("sourceUrl", FEED_URL);
        root.put("stops", stops);
        root.put("trips", trips);
        root.put("calendar", parseCalendar(files.get("calendar.txt")));
        root.put("calendarDates", parseCalendarDates(files.get("calendar_dates.txt")));
        root.put("feedInfo", parseFeedInfo(files.get("feed_info.txt")));
        String json = root.toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) throw new IllegalStateException("Rozkład GTFS przekracza limit pamięci aplikacji");
        return json;
    }

    private static JSONObject parseStops(byte[] data) throws Exception {
        JSONObject result = new JSONObject();
        for (Map<String, String> row : rows(data)) {
            String id = row.get("stop_id");
            if (id == null || id.isEmpty()) continue;
            JSONObject stop = new JSONObject().put("name", value(row, "stop_name"));
            String lat = row.get("stop_lat"), lon = row.get("stop_lon");
            if (isNumber(lat) && isNumber(lon)) { stop.put("lat", Double.parseDouble(lat)); stop.put("lon", Double.parseDouble(lon)); }
            result.put(id, stop);
        }
        return result;
    }

    private static Map<String, String> parseRoutes(byte[] data) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (Map<String, String> row : rows(data)) {
            String id = row.get("route_id");
            if (id == null) continue;
            String shortName = value(row, "route_short_name");
            if (shortName.isEmpty()) shortName = value(row, "route_long_name");
            result.put(id, shortName.isEmpty() ? id : shortName);
        }
        return result;
    }

    private static Map<String, JSONObject> parseTrips(byte[] data, Map<String, String> routes) throws Exception {
        Map<String, JSONObject> result = new HashMap<>();
        for (Map<String, String> row : rows(data)) {
            if (result.size() >= MAX_TRIPS) throw new IllegalStateException("Feed ma zbyt wiele kursów dla telefonu");
            String id = row.get("trip_id");
            if (id == null || id.isEmpty()) continue;
            String routeId = value(row, "route_id");
            JSONObject trip = new JSONObject();
            trip.put("tripId", id);
            trip.put("route", routes.containsKey(routeId) ? routes.get(routeId) : routeId);
            trip.put("serviceId", value(row, "service_id"));
            trip.put("headsign", value(row, "trip_headsign"));
            trip.put("stopTimes", new JSONArray());
            result.put(id, trip);
        }
        return result;
    }

    private static int parseStopTimes(byte[] data, Map<String, JSONObject> trips) throws Exception {
        int count = 0;
        if (data == null) return 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first == null) return 0;
            List<String> header = parseCsv(first.replace("\uFEFF", ""));
            int tripColumn = header.indexOf("trip_id");
            int stopColumn = header.indexOf("stop_id");
            int arrivalColumn = header.indexOf("arrival_time");
            int departureColumn = header.indexOf("departure_time");
            int sequenceColumn = header.indexOf("stop_sequence");
            if (tripColumn < 0 || stopColumn < 0 || arrivalColumn < 0 || sequenceColumn < 0) {
                throw new IllegalStateException("stop_times.txt ma niekompletny nagłówek");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsv(line);
                JSONObject trip = trips.get(field(values, tripColumn));
                if (trip == null) continue;
                if (++count > MAX_STOP_TIMES) throw new IllegalStateException("Feed ma zbyt wiele przystanków w kursach");
                JSONObject item = new JSONObject();
                item.put("stopId", field(values, stopColumn));
                item.put("arrival", field(values, arrivalColumn));
                item.put("departure", departureColumn >= 0 && !field(values, departureColumn).isEmpty() ? field(values, departureColumn) : field(values, arrivalColumn));
                item.put("seq", parseInt(field(values, sequenceColumn), 0));
                trip.getJSONArray("stopTimes").put(item);
            }
        }
        return count;
    }

    private static JSONObject parseCalendar(byte[] data) throws Exception {
        JSONObject result = new JSONObject();
        if (data == null) return result;
        for (Map<String, String> row : rows(data)) {
            String id = row.get("service_id");
            if (id == null) continue;
            JSONObject item = new JSONObject();
            for (String key : new String[]{"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "start_date", "end_date"}) {
                item.put(key, value(row, key));
            }
            item.put("startDate", value(row, "start_date"));
            item.put("endDate", value(row, "end_date"));
            result.put(id, item);
        }
        return result;
    }

    private static JSONArray parseCalendarDates(byte[] data) throws Exception {
        JSONArray result = new JSONArray();
        if (data == null) return result;
        for (Map<String, String> row : rows(data)) {
            JSONObject item = new JSONObject();
            item.put("serviceId", value(row, "service_id"));
            item.put("date", value(row, "date"));
            item.put("exceptionType", value(row, "exception_type"));
            result.put(item);
        }
        return result;
    }

    private static JSONObject parseFeedInfo(byte[] data) throws Exception {
        JSONObject result = new JSONObject();
        if (data == null) return result;
        List<Map<String, String>> rows = rows(data);
        if (!rows.isEmpty()) {
            Map<String, String> row = rows.get(0);
            result.put("publisher", value(row, "feed_publisher_name"));
            result.put("publisherUrl", value(row, "feed_publisher_url"));
            result.put("startDate", value(row, "feed_start_date"));
            result.put("endDate", value(row, "feed_end_date"));
            result.put("version", value(row, "feed_version"));
        }
        return result;
    }

    private static List<Map<String, String>> rows(byte[] data) throws Exception {
        List<Map<String, String>> result = new ArrayList<>();
        if (data == null) return result;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return result;
            List<String> header = parseCsv(headerLine.replace("\uFEFF", ""));
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsv(line);
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < values.size() ? values.get(i) : "");
                result.add(row);
            }
        }
        return result;
    }

    private static List<String> parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { fields.add(value.toString()); value.setLength(0); }
            else value.append(c);
        }
        fields.add(value.toString());
        return fields;
    }

    private static String value(Map<String, String> row, String key) { String v = row.get(key); return v == null ? "" : v; }
    private static String field(List<String> values, int index) { return index >= 0 && index < values.size() ? values.get(index) : ""; }
    private static int parseInt(String v, int fallback) { try { return Integer.parseInt(v); } catch (Exception ignored) { return fallback; } }
    private static boolean isNumber(String v) { try { Double.parseDouble(v); return true; } catch (Exception ignored) { return false; } }

    private static final class BoundedInputStream extends FilterInputStream {
        private final long limit;
        private long count;
        BoundedInputStream(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws java.io.IOException {
            int value = super.read();
            if (value != -1 && ++count > limit) throw new java.io.IOException("Plik GTFS przekracza limit pobierania");
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            int value = super.read(buffer, offset, length);
            if (value > 0 && (count += value) > limit) throw new java.io.IOException("Plik GTFS przekracza limit pobierania");
            return value;
        }
    }
}
