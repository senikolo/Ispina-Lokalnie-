package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

final class WeatherSync {
    static final String LOCATION = "Białołęka · Warszawa";
    static final String ACTIVE_PIN = "weather_reader_key";
    static final String LAST_SENT = "weather_sent_at";
    static final String LAST_TRY = "weather_try_at";
    static final String LAST_RESULT = "weather_try_result";
    private static final String REPORT = "weather_report";
    private static final String FETCHED = "weather_fetched_at";
    static final String SENT_FORECAST = "weather_sent_forecast";
    private static final String PLUGIN = "bialoleka.koplugin";
    private static final ReentrantLock lock = new ReentrantLock();

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("kindle", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(SharedPreferences settings) {
        String pin = settings.getString(ACTIVE_PIN, "");
        return !pin.isEmpty() && pin.equals(settings.getString("kindle_key", ""));
    }

    static String refresh(Context context, boolean manual) throws Exception {
        if (!lock.tryLock(manual ? 15 : 0, TimeUnit.SECONDS)) {
            if (manual) throw new IOException("Pogoda już się aktualizuje. Spróbuj za chwilę.");
            return "";
        }
        try {
            SharedPreferences settings = settings(context);
            if (!isEnabled(settings)) return "Najpierw włącz pogodę dla sparowanego Kindle’a.";
            String expectedPin = settings.getString(ACTIVE_PIN, "");
            long fetched = settings.getLong(FETCHED, 0);
            long age = System.currentTimeMillis() - fetched;
            String report = settings.getString(REPORT, "");
            boolean failedFetch = false;
            if (manual || report.isEmpty() || age < 0 || age >= TimeUnit.HOURS.toMillis(3)) {
                try {
                    report = BialolekaForecast.fetch();
                    fetched = System.currentTimeMillis();
                    settings.edit().putString(REPORT, report).putLong(FETCHED, fetched).apply();
                } catch (Exception error) {
                    if (report.isEmpty() || Thread.currentThread().isInterrupted()) {
                        record(settings, expectedPin, "Nie udało się pobrać prognozy.");
                        throw error;
                    }
                    failedFetch = true;
                }
            }
            if (!manual && settings.getLong(SENT_FORECAST, 0) == fetched) return "";
            if (!expectedPin.equals(settings.getString(ACTIVE_PIN, "")) || !isEnabled(settings))
                return "";
            String host = settings.getString("address", "");
            Session session = null;
            try {
                if (!MainActivity.privateAddress(host)) throw new IOException("Brakuje adresu sparowanego Kindle’a.");
                byte[] key = Base64.decode(expectedPin, Base64.DEFAULT);
                KindleFinder.Pin pin = new KindleFinder.Pin(new HostKey(host, key), key);
                KindleFinder finder = new KindleFinder(context);
                try {
                    session = connect(context, settings, host, finder.localRoute(host), pin);
                } catch (Exception connectionError) {
                    if (Thread.currentThread().isInterrupted() || !stillEnabled(settings, expectedPin)) throw connectionError;
                    java.util.List<KindleFinder.Found> found = finder.find(host, pin);
                    if (found.isEmpty()) throw connectionError;
                    KindleFinder.Match route = found.get(0).match;
                    if (!stillEnabled(settings, expectedPin)) return "";
                    session = connect(context, settings, route.host, route, pin);
                    if (!host.equals(route.host) && stillEnabled(settings, expectedPin)
                            && host.equals(settings.getString("address", "")))
                        settings.edit().putString("address", route.host).apply();
                }
                if (!stillEnabled(settings, expectedPin)) return "";
                publish(session, report.getBytes(StandardCharsets.UTF_8));
                if (stillEnabled(settings, expectedPin))
                    settings.edit().putLong(SENT_FORECAST, fetched).putLong(LAST_SENT, System.currentTimeMillis()).apply();
                String result = failedFetch ? "Przesłano ostatnią zapisaną prognozę. Nowa jest chwilowo niedostępna."
                        : "Prognoza została przesłana na Kindle’a.";
                record(settings, expectedPin, result);
                return result;
            } catch (Exception error) {
                record(settings, expectedPin, "Nie udało się przesłać prognozy. Sprawdź Wi‑Fi i serwer SSH w KOReaderze.");
                throw error;
            } finally {
                if (session != null) session.disconnect();
            }
        } finally {
            lock.unlock();
        }
    }

    private static Session connect(Context context, SharedPreferences settings, String host,
                                   KindleFinder.Match route, KindleFinder.Pin pin) throws Exception {
        return KindleConnection.open(new JSch(), new SshIdentity(context.getFilesDir()), host, route, pin,
                settings.getBoolean("ssh_use_key", false), null, 6500);
    }

    private static boolean stillEnabled(SharedPreferences settings, String expectedPin) {
        return expectedPin.equals(settings.getString(ACTIVE_PIN, "")) && isEnabled(settings);
    }

    private static void record(SharedPreferences settings, String pin, String result) {
        if (stillEnabled(settings, pin)) settings.edit().putLong(LAST_TRY, System.currentTimeMillis())
                .putString(LAST_RESULT, result).apply();
    }

    static void install(Context context, Session session) throws Exception {
        CompanionFiles.install(context, session, PLUGIN);
    }

    private static void publish(Session session, byte[] contents) throws Exception {
        ChannelSftp sftp = CompanionFiles.channel(session);
        try {
            CompanionFiles.requireDirectory(sftp, CompanionFiles.PLUGINS + "/" + PLUGIN);
            CompanionFiles.replace(sftp, CompanionFiles.SETTINGS, "kindlewifi-bialoleka.txt", contents);
        } finally {
            sftp.disconnect();
        }
    }
}
