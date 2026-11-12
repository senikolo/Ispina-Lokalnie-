package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class RadioSettings {
    static final String[] MODES = {"ultra", "eco", "auto", "high"};
    static final String[] LIMITS = {"", "24", "32", "48", "64", "96", "128", "0"};
    static final String[] CODECS = {"auto", "aac", "mp3"};
    static final String[] BUFFERS = {"64", "128", "256"};
    private static final int MAX_BYTES = 65536;
    private static final String NAME = "ilradio-phone.tsv";

    static final class Config {
        String revision = "-", mode = "eco", limit = "", codec = "auto", buffer = "128";
        boolean minimum, showVolume = true;
        final Map<String, List<String>> custom = new LinkedHashMap<>();
    }

    static Config read(Session session) throws Exception {
        ChannelSftp files = CompanionFiles.channel(session);
        try {
            CompanionFiles.requireDirectory(files, CompanionFiles.SETTINGS);
            return read(files);
        } finally {
            files.disconnect();
        }
    }

    private static Config read(ChannelSftp files) throws Exception {
        String path = CompanionFiles.SETTINGS + "/" + NAME;
        SftpATTRS entry = CompanionFiles.stat(files, path);
        if (entry == null) return new Config();
        if (!entry.isReg() || entry.isLink() || entry.getSize() > MAX_BYTES)
            throw new IOException("Plik ustawień radia jest niedostępny.");
        try (InputStream input = files.get(path)) {
            return parse(new String(BoundedInput.read(input, MAX_BYTES), StandardCharsets.UTF_8));
        }
    }

    static Config parse(String text) {
        Config config = new Config();
        for (String line : text.split("\\r?\\n")) {
            String[] row = line.split("\\t", -1);
            if (row.length >= 8 && row[0].equals("@")) {
                config.revision = row[1];
                config.mode = MODES[index(MODES, row[2], 1)];
                config.limit = LIMITS[index(LIMITS, row[3], 0)];
                config.codec = CODECS[index(CODECS, row[4], 0)];
                config.buffer = BUFFERS[index(BUFFERS, row[5], 1)];
                config.minimum = row[6].equals("1");
                config.showVolume = !row[7].equals("0");
            } else if (row.length == 2 && !row[0].isEmpty() && validUrl(row[1])) {
                config.custom.computeIfAbsent(row[0], key -> new ArrayList<>()).add(row[1]);
            }
        }
        return config;
    }

    static int index(String[] options, String value, int fallback) {
        for (int i = 0; i < options.length; i++) if (options[i].equals(value)) return i;
        return fallback;
    }

    static boolean validUrl(String address) {
        if (address.length() > 1024) return false;
        try {
            URI uri = URI.create(address);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static synchronized void update(Session session, Config quality, String station, List<String> urls) throws Exception {
        ChannelSftp files = CompanionFiles.channel(session);
        try {
            CompanionFiles.requireDirectory(files, CompanionFiles.SETTINGS);
            Config saved = read(files);
            if (quality != null) {
                saved.revision = UUID.randomUUID().toString();
                saved.mode = quality.mode;
                saved.limit = quality.limit;
                saved.codec = quality.codec;
                saved.buffer = quality.buffer;
                saved.minimum = quality.minimum;
                saved.showVolume = quality.showVolume;
            }
            if (station != null) {
                if (urls.isEmpty()) saved.custom.remove(station);
                else saved.custom.put(station, urls);
            }
            StringBuilder data = new StringBuilder("@\t").append(saved.revision).append('\t')
                    .append(saved.mode).append('\t').append(saved.limit).append('\t').append(saved.codec)
                    .append('\t').append(saved.buffer).append('\t').append(saved.minimum ? '1' : '0')
                    .append('\t').append(saved.showVolume ? '1' : '0').append('\n');
            for (Map.Entry<String, List<String>> row : saved.custom.entrySet()) {
                for (String url : row.getValue()) data.append(row.getKey()).append('\t').append(url).append('\n');
            }
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) throw new IOException("Lista własnych adresów jest za długa.");
            CompanionFiles.replace(files, CompanionFiles.SETTINGS, NAME, bytes);
        } finally {
            files.disconnect();
        }
    }

    static Map<String, List<String[]>> catalog(Context context) throws IOException {
        Map<String, List<String[]>> catalog = new LinkedHashMap<>();
        try (InputStream input = context.getAssets().open("ilradio_k3.koplugin/streams.tsv")) {
            String contents = new String(BoundedInput.read(input, MAX_BYTES), StandardCharsets.UTF_8);
            for (String line : contents.split("\\r?\\n")) {
                if (line.startsWith("#")) continue;
                String[] row = line.split("\\t", -1);
                if (row.length == 9) catalog.computeIfAbsent(row[0], key -> new ArrayList<>()).add(row);
            }
        }
        return catalog;
    }
}
