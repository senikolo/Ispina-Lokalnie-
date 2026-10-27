package com.ispina.lokalnie.sync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GtfsSubsetParser {
    private static final Set<String> TARGET = new HashSet<>(Arrays.asList("A4", "A7", "A65"));

    private GtfsSubsetParser() {}

    public static String parse(byte[] zipBytes, String sourceUrl, long fetchedAt) throws Exception {
        Map<String, byte[]> files = unzipNeeded(zipBytes);
        require(files, "routes.txt");
        require(files, "trips.txt");
        require(files, "stop_times.txt");
        require(files, "stops.txt");

        Map<String, String> routeNames = readRoutes(files.get("routes.txt"));
        if (!new HashSet<>(routeNames.values()).containsAll(TARGET)) {
            throw new IllegalArgumentException("GTFS nie zawiera kompletu A4/A7/A65");
        }

        Map<String, Trip> trips = readTrips(files.get("trips.txt"), routeNames);
        if (trips.isEmpty()) throw new IllegalArgumentException("Brak kursów A4/A7/A65");

        Set<String> usedStopIds = new HashSet<>();
        readStopTimes(files.get("stop_times.txt"), trips, usedStopIds);
        Map<String, Stop> stops = readStops(files.get("stops.txt"), usedStopIds);

        Map<String, JSONObject> calendar = files.containsKey("calendar.txt")
                ? readCalendar(files.get("calendar.txt"), usedServices(trips)) : new LinkedHashMap<>();
        JSONArray calendarDates = files.containsKey("calendar_dates.txt")
                ? readCalendarDates(files.get("calendar_dates.txt"), usedServices(trips)) : new JSONArray();

        JSONObject out = new JSONObject();
        out.put("schemaVersion", 1);
        out.put("provider", "Koleje Małopolskie / MLD GTFS");
        out.put("fetchedAt", fetchedAt);
        out.put("sourceUrl", sourceUrl);

        JSONObject stopObj = new JSONObject();
        for (String id : usedStopIds) {
            Stop s = stops.get(id);
            if (s == null) continue;
            JSONObject j = new JSONObject();
            j.put("name", s.name);
            if (!s.lat.isEmpty()) j.put("lat", s.lat);
            if (!s.lon.isEmpty()) j.put("lon", s.lon);
            stopObj.put(id, j);
        }
        out.put("stops", stopObj);

        JSONObject calObj = new JSONObject();
        for (Map.Entry<String, JSONObject> e : calendar.entrySet()) calObj.put(e.getKey(), e.getValue());
        out.put("calendar", calObj);
        out.put("calendarDates", calendarDates);

        List<Trip> ordered = new ArrayList<>(trips.values());
        for (int i = ordered.size() - 1; i >= 0; i--) { if (ordered.get(i).times.isEmpty()) ordered.remove(i); }
        ordered.sort(Comparator.comparing((Trip t) -> t.route).thenComparing(t -> t.tripId));
        JSONArray tripArray = new JSONArray();
        Set<String> present = new HashSet<>();
        for (Trip t : ordered) {
            present.add(t.route);
            Collections.sort(t.times, Comparator.comparingInt(st -> st.seq));
            JSONObject j = new JSONObject();
            j.put("tripId", t.tripId);
            j.put("route", t.route);
            j.put("serviceId", t.serviceId);
            j.put("directionId", t.directionId);
            j.put("headsign", t.headsign);
            JSONArray arr = new JSONArray();
            for (StopTime st : t.times) {
                JSONObject sj = new JSONObject();
                sj.put("stopId", st.stopId);
                sj.put("arrival", st.arrival);
                sj.put("departure", st.departure);
                sj.put("seq", st.seq);
                arr.put(sj);
            }
            j.put("stopTimes", arr);
            tripArray.put(j);
        }
        if (!present.containsAll(TARGET)) throw new IllegalArgumentException("Po parsowaniu brak jednej z linii A4/A7/A65");
        out.put("trips", tripArray);
        return out.toString();
    }

    private static Map<String, byte[]> unzipNeeded(byte[] zipBytes) throws Exception {
        Set<String> names = new HashSet<>(Arrays.asList("routes.txt", "trips.txt", "stop_times.txt", "stops.txt", "calendar.txt", "calendar_dates.txt"));
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            byte[] buf = new byte[16 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                int slash = n.lastIndexOf('/');
                if (slash >= 0) n = n.substring(slash + 1);
                if (!names.contains(n)) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                int r;
                while ((r = zin.read(buf)) >= 0) b.write(buf, 0, r);
                out.put(n, b.toByteArray());
            }
        }
        return out;
    }

    private static void require(Map<String, byte[]> m, String n) {
        if (!m.containsKey(n)) throw new IllegalArgumentException("Brak " + n + " w GTFS");
    }

    private static Map<String, String> readRoutes(byte[] data) throws Exception {
        List<String[]> rows = csv(data);
        Map<String, String> out = new HashMap<>();
        if (rows.isEmpty()) return out;
        Map<String,Integer> h = header(rows.get(0));
        for (int i=1;i<rows.size();i++) {
            String[] r=rows.get(i);
            String shortName=get(r,h,"route_short_name").trim().toUpperCase(Locale.ROOT);
            if (TARGET.contains(shortName)) out.put(get(r,h,"route_id"), shortName);
        }
        return out;
    }

    private static Map<String, Trip> readTrips(byte[] data, Map<String,String> routes) throws Exception {
        List<String[]> rows=csv(data); Map<String,Trip> out=new LinkedHashMap<>();
        if(rows.isEmpty())return out; Map<String,Integer> h=header(rows.get(0));
        for(int i=1;i<rows.size();i++){
            String[] r=rows.get(i); String rid=get(r,h,"route_id"); String line=routes.get(rid); if(line==null)continue;
            String id=get(r,h,"trip_id"); Trip t=new Trip(); t.tripId=id; t.route=line; t.serviceId=get(r,h,"service_id");
            t.directionId=get(r,h,"direction_id"); t.headsign=get(r,h,"trip_headsign"); out.put(id,t);
        }
        return out;
    }

    private static void readStopTimes(byte[] data, Map<String,Trip> trips, Set<String> used) throws Exception {
        List<String[]> rows=csv(data); if(rows.isEmpty())return; Map<String,Integer> h=header(rows.get(0));
        for(int i=1;i<rows.size();i++){
            String[] r=rows.get(i); Trip t=trips.get(get(r,h,"trip_id")); if(t==null)continue;
            StopTime st=new StopTime(); st.stopId=get(r,h,"stop_id"); st.arrival=get(r,h,"arrival_time"); st.departure=get(r,h,"departure_time");
            try{st.seq=Integer.parseInt(get(r,h,"stop_sequence"));}catch(Exception e){st.seq=t.times.size();}
            t.times.add(st); used.add(st.stopId);
        }
    }

    private static Map<String,Stop> readStops(byte[] data, Set<String> used) throws Exception {
        List<String[]> rows=csv(data); Map<String,Stop> out=new HashMap<>(); if(rows.isEmpty())return out; Map<String,Integer> h=header(rows.get(0));
        for(int i=1;i<rows.size();i++){
            String[] r=rows.get(i); String id=get(r,h,"stop_id"); if(!used.contains(id))continue;
            Stop s=new Stop(); s.name=get(r,h,"stop_name"); s.lat=get(r,h,"stop_lat"); s.lon=get(r,h,"stop_lon"); out.put(id,s);
        }
        return out;
    }

    private static Set<String> usedServices(Map<String,Trip> trips){Set<String>s=new HashSet<>();for(Trip t:trips.values())s.add(t.serviceId);return s;}

    private static Map<String,JSONObject> readCalendar(byte[] data, Set<String> used) throws Exception {
        List<String[]> rows=csv(data); Map<String,JSONObject> out=new LinkedHashMap<>(); if(rows.isEmpty())return out; Map<String,Integer> h=header(rows.get(0));
        String[] days={"monday","tuesday","wednesday","thursday","friday","saturday","sunday"};
        for(int i=1;i<rows.size();i++){
            String[] r=rows.get(i); String id=get(r,h,"service_id"); if(!used.contains(id))continue; JSONObject j=new JSONObject();
            for(String d:days)j.put(d,get(r,h,d)); j.put("startDate",get(r,h,"start_date")); j.put("endDate",get(r,h,"end_date")); out.put(id,j);
        }
        return out;
    }

    private static JSONArray readCalendarDates(byte[] data, Set<String> used) throws Exception {
        List<String[]> rows=csv(data); JSONArray out=new JSONArray(); if(rows.isEmpty())return out; Map<String,Integer> h=header(rows.get(0));
        for(int i=1;i<rows.size();i++){
            String[] r=rows.get(i); String id=get(r,h,"service_id"); if(!used.contains(id))continue; JSONObject j=new JSONObject();
            j.put("serviceId",id); j.put("date",get(r,h,"date")); j.put("exceptionType",get(r,h,"exception_type")); out.put(j);
        }
        return out;
    }

    private static List<String[]> csv(byte[] data) throws Exception {
        List<String[]> out=new ArrayList<>();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(data),StandardCharsets.UTF_8))){
            String line; while((line=br.readLine())!=null)out.add(parseCsvLine(line));
        }
        return out;
    }

    private static String[] parseCsvLine(String line){
        List<String> f=new ArrayList<>(); StringBuilder b=new StringBuilder(); boolean q=false;
        for(int i=0;i<line.length();i++){
            char c=line.charAt(i); if(c=='\"'){
                if(q&&i+1<line.length()&&line.charAt(i+1)=='\"'){b.append('\"');i++;}else q=!q;
            }else if(c==','&&!q){f.add(b.toString());b.setLength(0);}else b.append(c);
        }
        f.add(b.toString()); return f.toArray(new String[0]);
    }

    private static Map<String,Integer> header(String[] row){Map<String,Integer>m=new HashMap<>();for(int i=0;i<row.length;i++)m.put(row[i].replace("\uFEFF","").trim(),i);return m;}
    private static String get(String[] r,Map<String,Integer>h,String k){Integer i=h.get(k);return i==null||i<0||i>=r.length?"":r[i];}

    private static final class Trip{String tripId,route,serviceId,directionId,headsign;List<StopTime> times=new ArrayList<>();}
    private static final class StopTime{String stopId,arrival,departure;int seq;}
    private static final class Stop{String name="",lat="",lon="";}
}
