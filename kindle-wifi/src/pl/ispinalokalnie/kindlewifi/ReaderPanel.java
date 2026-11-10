package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ReaderPanel {
    static final String PLUGIN = "kindlewifi-panel.koplugin";
    static final String LEGACY_PLUGIN_HASH = "b4f53c931de30166205ed44c4090eaad0013926a2c3ef455d120dbfeb8bbcc87";
    static final String PREVIOUS_PLUGIN_HASH = "4202f56a17317bb6d13deb72d7b66b6c04a9d5dee1cefec32e3b7aba4fad7d89";
    static final String CURRENT_PLUGIN_HASH = "8ad458ea9d7938a2ba3f8ff78762196a8731d8f7485e47dabf0e88a91b581b9f";
    static final List<String> KEYS = Arrays.asList("font", "size", "spacing", "margins", "reflow",
            "home", "sort", "reverse", "view");
    private static final String PREFIX = "kindlewifi-panel-";
    private static final String DIR = CompanionFiles.SETTINGS + "/";
    private static final String STATE = DIR + PREFIX + "state.tsv";
    private static final String REQUEST = DIR + PREFIX + "request.tsv";
    private static final String WORK = DIR + PREFIX + "work.tsv";
    private static final String RESULT = DIR + PREFIX + "result.tsv";
    private static final String DIAGNOSTIC = DIR + PREFIX + "diagnostic.txt";

    static final class StateUnavailable extends IOException {
        StateUnavailable(String message) { super(message); }
    }

    static final class Snapshot {
        final Map<String, String> values = new LinkedHashMap<>();
        final List<String> fonts = new ArrayList<>();
        final List<String> sorts = new ArrayList<>();
        long savedAt;

        static Snapshot parse(String text) throws IOException {
            Snapshot snapshot = new Snapshot();
            String[] lines = text.split("\n", -1);
            if (lines.length == 0 || !"panel-v1".equals(lines[0]))
                throw new IOException("Odczyt ma nieobsługiwany format.");
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty() && i == lines.length - 1) break;
                int separator = lines[i].indexOf('\t');
                if (separator < 1) throw new IOException("Odczyt ustawień jest niepełny.");
                String key = lines[i].substring(0, separator);
                String value = lines[i].substring(separator + 1);
                if (!safe(value)) throw new IOException("Odczyt zawiera nieprawidłowe dane.");
                if ("font-option".equals(key)) snapshot.fonts.add(value);
                else if ("sort-option".equals(key)) snapshot.sorts.add(value);
                else if (!KEYS.contains(key) || snapshot.values.put(key, value) != null)
                    throw new IOException("Odczyt zawiera nieznane ustawienie.");
            }
            if (snapshot.values.size() != KEYS.size()) throw new IOException("Odczyt ustawień jest niepełny.");
            return snapshot;
        }

        String backup() {
            StringBuilder data = new StringBuilder("panel-v1\n");
            for (String key : KEYS) data.append(key).append('\t').append(values.get(key)).append('\n');
            return data.toString();
        }
    }

    static String read(ChannelSftp sftp) throws Exception {
        CompanionFiles.requireDirectory(sftp, CompanionFiles.SETTINGS);
        String folder = CompanionFiles.PLUGINS + "/" + PLUGIN;
        if (CompanionFiles.stat(sftp, folder + "/main.lua") == null
                || CompanionFiles.stat(sftp, folder + "/_meta.lua") == null)
            throw new StateUnavailable("Brakuje plików wtyczki na Kindle’u. W aplikacji wybierz "
                    + "„Zainstaluj lub zaktualizuj panel”, a potem uruchom ponownie KOReadera z KUAL.");
        String detail = readSmall(sftp, DIAGNOSTIC);
        if (detail != null && !detail.trim().isEmpty())
            throw new StateUnavailable("Wtyczka zgłosiła problem z odczytem ustawień.\n\n" + detail.trim());
        String result = readSmall(sftp, STATE);
        if (result == null) {
            throw new StateUnavailable("Wtyczka jest na Kindle’u, ale KOReader jeszcze nie udostępnił ustawień. "
                    + "Po ponownym uruchomieniu z KUAL przejdź do listy plików i wybierz "
                    + "Narzędzia → Ustawienia z telefonu. Jeśli tej pozycji brakuje, sprawdź zarządzanie wtyczkami.");
        }
        return result;
    }

    static String readForEditor(ChannelSftp sftp) throws Exception {
        String state = read(sftp);
        SftpATTRS attributes = CompanionFiles.stat(sftp, STATE);
        return (attributes == null ? 0 : Integer.toUnsignedLong(attributes.getMTime())) + "\n" + state;
    }

    static Snapshot editorSnapshot(String response) throws IOException {
        int newline = response.indexOf('\n');
        if (newline < 1) throw new IOException("Odczyt ustawień jest niepełny.");
        Snapshot snapshot = Snapshot.parse(response.substring(newline + 1));
        try { snapshot.savedAt = Long.parseLong(response.substring(0, newline)); }
        catch (NumberFormatException error) { throw new IOException("Odczyt ustawień ma nieprawidłową datę.", error); }
        return snapshot;
    }

    private static String resultLine(ChannelSftp sftp, String id) throws Exception {
        String result = readSmall(sftp, RESULT);
        if (result == null || id.isEmpty() || !result.startsWith(id + "\t")) return "";
        return result.substring(id.length() + 1).trim();
    }

    static String resultCode(ChannelSftp sftp, String id) throws Exception {
        return resultLine(sftp, id).split("\t", 2)[0];
    }

    static String status(ChannelSftp sftp, String id) throws Exception {
        String[] result = resultLine(sftp, id).split("\t", 2);
        String detail = result.length == 2 ? "\n\nSzczegóły z KOReadera: " + result[1] : "";
        switch (result[0]) {
            case "applied": return "KOReader potwierdził zapis ustawień.";
            case "conflict": return "Ustawienia zmieniły się na Kindle’u. Odczytaj je ponownie i spróbuj jeszcze raz.";
            case "invalid": return "KOReader odrzucił wartość. Sprawdź, czy czcionka i folder nadal są dostępne.";
            case "cancelled": return "KOReader potwierdził anulowanie zmian.";
            case "rolledback": return "Zapis się nie powiódł. KOReader potwierdził przywrócenie poprzednich ustawień." + detail;
            case "": break;
            default: return "Zapis się nie powiódł. Odczytaj ustawienia ponownie, aby sprawdzić ich stan." + detail;
        }
        if (CompanionFiles.stat(sftp, REQUEST) != null || CompanionFiles.stat(sftp, WORK) != null) {
            if (!id.isEmpty() && CompanionFiles.stat(sftp, DIR + PREFIX + "cancel-" + id) != null)
                return "Prośba o anulowanie czeka na KOReadera. Przejdź na Kindle’u do listy plików.";
            return "Zmiany czekają. Otwórz przeglądarkę plików w KOReaderze albo wybierz Narzędzia → Ustawienia z telefonu.";
        }
        return id.isEmpty() ? "Brak oczekujących zmian." : "Brak potwierdzenia tej zmiany od KOReadera.";
    }

    static String cancel(ChannelSftp sftp, String id) throws Exception {
        if (!id.matches("[0-9a-f-]{36}")) return "Brak oczekującej zmiany z tego telefonu.";
        String request = readSmall(sftp, REQUEST);
        if (request == null || !request.startsWith("panel-v1\n" + id + "\n"))
            return status(sftp, id);
        String main = CompanionFiles.PLUGINS + "/" + PLUGIN + "/main.lua";
        if (!CompanionFiles.matchesHash(sftp, main, CURRENT_PLUGIN_HASH))
            return "Anulowanie wymaga aktualizacji panelu na Kindle’u i ponownego uruchomienia KOReadera.";
        CompanionFiles.replace(sftp, CompanionFiles.SETTINGS, PREFIX + "cancel-" + id,
                id.getBytes(StandardCharsets.UTF_8));
        return status(sftp, id);
    }

    interface BeforePublish { void backup(String id) throws IOException; }

    static String queue(ChannelSftp sftp, Snapshot base, Map<String, String> changes, BeforePublish save)
            throws Exception {
        Snapshot live = Snapshot.parse(read(sftp));
        if (CompanionFiles.stat(sftp, REQUEST) != null || CompanionFiles.stat(sftp, WORK) != null)
            throw new IOException("Na Kindle’u są już oczekujące ustawienia. Najpierw zastosuj je w KOReaderze.");
        String id = UUID.randomUUID().toString();
        byte[] bytes = request(id, base, live, changes);
        String stage = DIR + "." + PREFIX + id + ".part";
        try {
            CompanionFiles.putVerified(sftp, stage, bytes);
            save.backup(id);
            VerifiedTransfer.renameWithoutReplace(sftp, stage, REQUEST);
        } finally {
            CompanionFiles.remove(sftp, stage);
        }
        return id;
    }

    static byte[] request(String id, Snapshot base, Snapshot live, Map<String, String> changes) throws IOException {
        if (changes.isEmpty()) throw new IOException("Nie wybrano żadnych zmian.");
        StringBuilder data = new StringBuilder("panel-v1\n").append(id).append('\n');
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String key = change.getKey();
            String old = base.values.get(key);
            String next = change.getValue();
            if (!KEYS.contains(key) || !safe(old) || !safe(next) || "!".equals(next))
                throw new IOException("Nieprawidłowa wartość ustawienia.");
            if (!old.equals(live.values.get(key)))
                throw new IOException("Ustawienia na Kindle’u się zmieniły. Pobierz je ponownie.");
            data.append(key).append('\t').append(old).append('\t').append(next).append('\n');
        }
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String readSmall(ChannelSftp sftp, String path) throws Exception {
        SftpATTRS attrs = CompanionFiles.stat(sftp, path);
        if (attrs == null) return null;
        if (!attrs.isReg() || attrs.getSize() > 32768) throw new IOException("Plik panelu jest nieprawidłowy.");
        try (InputStream input = sftp.get(path)) {
            return new String(BialolekaForecast.readBounded(input, 32768), StandardCharsets.UTF_8);
        }
    }

    private static boolean safe(String value) {
        return value != null && value.getBytes(StandardCharsets.UTF_8).length <= 1024
                && !value.matches("(?s).*[\\p{Cntrl}].*");
    }
}
