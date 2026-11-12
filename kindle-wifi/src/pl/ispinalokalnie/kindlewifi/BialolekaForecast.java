package pl.ispinalokalnie.kindlewifi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class BialolekaForecast {
    private static final String URL_STRING = "https://api.open-meteo.com/v1/forecast"
            + "?latitude=52.3210&longitude=20.9709&timezone=Europe%2FWarsaw&forecast_days=3"
            + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max";
    static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final int MAX_RESPONSE_BYTES = 96 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM, HH:mm").withZone(WARSAW);

    static String fetch() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(URL_STRING).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Serwer prognozy jest niedostępny.");
            try (InputStream input = connection.getInputStream()) {
                String payload = new String(readBounded(input, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8);
                return report(new JSONObject(payload), Instant.now());
            }
        } catch (SocketTimeoutException error) {
            throw new IOException("Serwer prognozy nie odpowiedział na czas. Spróbuj ponownie.", error);
        } finally { connection.disconnect(); }
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] part = new byte[1024];
        int count;
        while (true) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Aktualizacja pogody została anulowana.");
            count = input.read(part);
            if (count == -1) break;
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Aktualizacja pogody została anulowana.");
            if (count > limit - buffer.size())
                throw new IOException("Serwer pogody przesłał zbyt dużo danych.");
            buffer.write(part, 0, count);
        }
        return buffer.toByteArray();
    }

    static String report(JSONObject json, Instant fetched) throws Exception {
        JSONObject current = json.getJSONObject("current");
        JSONObject daily = json.getJSONObject("daily");
        JSONArray times = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray minima = daily.getJSONArray("temperature_2m_min");
        JSONArray maxima = daily.getJSONArray("temperature_2m_max");
        JSONArray rain = daily.getJSONArray("precipitation_probability_max");
        if (times.length() < 3) throw new IOException("Prognoza jest niepełna.");
        StringBuilder result = new StringBuilder();
        result.append(fetched.getEpochSecond()).append("\nBiałołęka • Warszawa\n")
                .append("Pobrano ").append(STAMP.format(fetched)).append("\n\n");
        result.append(temperature(current.getDouble("temperature_2m"))).append("°C · ")
                .append(description(current.getInt("weather_code"))).append("\n")
                .append("Odczuwalna ").append(temperature(current.getDouble("apparent_temperature")))
                .append("°C · Wiatr ").append(Math.round(current.getDouble("wind_speed_10m"))).append(" km/h\n\n");
        for (int i = 0; i < 3; i++) {
            String date = times.getString(i);
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IOException("Nieprawidłowa data prognozy.");
            result.append(date.substring(8, 10)).append('.').append(date, 5, 7).append("  ")
                    .append(temperature(minima.getDouble(i))).append("…").append(temperature(maxima.getDouble(i)))
                    .append("°C · ").append(description(codes.getInt(i))).append("\n");
            if (!rain.isNull(i)) result.append("Szansa opadów ").append(rain.getInt(i)).append("%\n");
        }
        return result.append("\nŹródło prognozy: Open-Meteo").toString();
    }

    private static long temperature(double value) throws IOException {
        if (!Double.isFinite(value) || value < -100 || value > 70) throw new IOException("Nieprawidłowa temperatura.");
        return Math.round(value);
    }

    private static String description(int code) {
        switch (code) {
            case 0: return "Bezchmurnie";
            case 1: case 2: return "Częściowe zachmurzenie";
            case 3: return "Pochmurno";
            case 45: case 48: return "Mgła";
            case 51: case 53: case 55: return "Mżawka";
            case 56: case 57: return "Marznąca mżawka";
            case 61: case 63: case 65: return "Deszcz";
            case 66: case 67: return "Marznący deszcz";
            case 71: case 73: case 75: case 77: return "Śnieg";
            case 80: case 81: case 82: return "Przelotny deszcz";
            case 85: case 86: return "Przelotny śnieg";
            case 95: return "Burza";
            case 96: case 99: return "Burza z gradem";
            default: return "Warunki nieokreślone";
        }
    }
}
