package pl.ispinalokalnie.kindlewifi;

import android.os.SystemClock;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class KindleTools {
    private static final long SYNC_TOLERANCE_SECONDS = 15;
    private static final DateTimeFormatter WARSAW_CLOCK =
            DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(java.time.ZoneId.of("Europe/Warsaw"));
    private static final DateTimeFormatter UTC_DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    static long readTime(Session session) throws Exception {
        String response = run(session, "date +%s");
        if (!response.matches("[0-9]{1,12}")) throw new IOException("Kindle zwrócił nieprawidłowy czas.");
        return Long.parseLong(response);
    }

    static void setTime(Session session) throws Exception {
        long now = Instant.now().getEpochSecond();
        run(session, clockCommand(now));
        long actual = readTime(session);
        if (Math.abs(actual - Instant.now().getEpochSecond()) > SYNC_TOLERANCE_SECONDS)
            throw new IOException("Zegar Kindle’a nadal różni się od zegara telefonu.");
    }

    static final class ClockReading {
        final long epoch;
        final String localTime;

        ClockReading(long epoch, String localTime) {
            this.epoch = epoch;
            this.localTime = localTime;
        }

        boolean isSynchronized(long phoneEpoch) {
            return Math.abs(phoneEpoch - epoch) <= SYNC_TOLERANCE_SECONDS;
        }

        boolean displaysWarsawTime() {
            return localTime.equals(warsawClock(epoch));
        }
    }

    static String warsawClock(long epoch) {
        return WARSAW_CLOCK.format(Instant.ofEpochSecond(epoch));
    }

    static ClockReading readClock(Session session) throws Exception {
        String observed = run(session, "date '+%s %H:%M'");
        if (!observed.matches("[0-9]{1,12} [0-9]{2}:[0-9]{2}"))
            throw new IOException("Nie udało się odczytać godziny ustawionej na Kindle’u.");
        int split = observed.indexOf(' ');
        return new ClockReading(Long.parseLong(observed.substring(0, split)), observed.substring(split + 1));
    }

    static String clockCommand(long epoch) {
        String utc = UTC_DATE.format(Instant.ofEpochSecond(epoch));
        return "if [ -x /usr/sbin/setdate ]; then /usr/sbin/setdate " + epoch
                + "; else date -u -s '" + utc + "' >/dev/null && hwclock -u -w; fi";
    }

    static String run(Session session, String command) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);
        try (InputStream input = channel.getInputStream()) {
            channel.connect(4500);
            long deadline = SystemClock.elapsedRealtime() + 6500;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            while (true) {
                int available = input.available();
                if (available > 0) {
                    int count = input.read(buffer, 0, Math.min(available, buffer.length));
                    if (count > 0) output.write(buffer, 0, count);
                    if (output.size() > 4096) throw new IOException("Za długa odpowiedź Kindle’a.");
                }
                if (channel.isClosed() && input.available() == 0) break;
                if (SystemClock.elapsedRealtime() >= deadline)
                    throw new IOException("Kindle nie zakończył operacji w oczekiwanym czasie.");
                Thread.sleep(35);
            }
            if (channel.getExitStatus() != 0)
                throw new IOException("Kindle odrzucił operację (kod " + channel.getExitStatus() + ").");
            return new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
        } finally {
            channel.disconnect();
        }
    }
}
