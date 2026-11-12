package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Installs the bundled IL Radio only on the authenticated, paired reader. */
final class RadioInstaller {
    private static final String NAME = "ilradio_k3.koplugin";
    private static final String[] LEGACY_FILES = {"_meta.lua", "stations.lua", "player.sh", "main.lua"};
    private static final String[] FILES = {"_meta.lua", "stations.lua", "player.sh", "main.lua", "tuning.lua", "streams.tsv",
            "test-lc48.aac", "test-he32.aac", "test-hev224.aac", "test-lc48.m4a", "test-mp364.mp3", "test-real-he32.aac", "test-real-hev224.aac"};
    private static final int MAX_FILE = 65536;
    private static final String LEGACY_META =
            "5846de1c14e95256bdc7ecf0600a84177d23142eb41544bf617952c057bc7f08";
    private static final String ACTIVE = CompanionFiles.PLUGINS + "/" + NAME;

    interface RemoteCommand { void run(String command) throws Exception; }

    private RadioInstaller() {}

    static synchronized String install(Context context, Session session) throws Exception {
        ChannelSftp sftp = CompanionFiles.channel(session);
        try {
            return install(sftp, CompanionFiles.PLUGINS, load(context), command -> KindleTools.run(session, command));
        } finally {
            sftp.disconnect();
        }
    }

    static String install(ChannelSftp sftp, String parent, Map<String, byte[]> contents,
                          RemoteCommand shell) throws Exception {
        CompanionFiles.requireDirectory(sftp, parent.substring(0, parent.lastIndexOf('/')));
        if (CompanionFiles.stat(sftp, parent) == null) {
            try { sftp.mkdir(parent); }
            catch (SftpException failure) {
                if (CompanionFiles.stat(sftp, parent) == null) throw failure;
            }
        }
        CompanionFiles.requireDirectory(sftp, parent);
        String active = parent + "/" + NAME;
        boolean replacing = CompanionFiles.stat(sftp, active) != null;
        if (replacing) {
            requireOwned(sftp, active, contents.get("_meta.lua"));
            if (matches(sftp, active, contents)) return "Masz już aktualną wersję IL Radio.";
        }
        String stage = hidden(parent, "stage");
        String backup = hidden(parent, "backup");
        boolean staged = false;
        boolean published = false;
        try {
            sftp.mkdir(stage);
            staged = true;
            for (Map.Entry<String, byte[]> file : contents.entrySet())
                CompanionFiles.putVerified(sftp, stage + "/" + file.getKey(), file.getValue());
            if (!matches(sftp, stage, contents)) throw new IOException("Przesłane pliki wymagają ponowienia.");
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (replacing) move(sftp, active, backup, shell);
            move(sftp, stage, active, shell);
            published = true;
            if (!matches(sftp, active, contents)) throw new IOException("Nie potwierdzono zawartości wtyczki po instalacji.");
            return replacing
                    ? "Sprawdzono wszystkie pliki. Poprzednia wersja jest dostępna w opcji „Przywróć kopię IL Radio”."
                    : "Przesłano i sprawdzono wszystkie pliki wtyczki.";
        } catch (Exception failure) {
            try {
                if (published) move(sftp, active, hidden(parent, "failed"), shell);
                restoreMissing(sftp, active, backup, shell);
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw new IOException("Nie udało się zakończyć instalacji. Po odzyskaniu połączenia możesz użyć opcji „Przywróć kopię IL Radio”.", failure);
        } finally {
            if (staged && sftp.isConnected()) {
                for (String file : contents.keySet()) CompanionFiles.remove(sftp, stage + "/" + file);
                try { sftp.rmdir(stage); } catch (SftpException ignored) {}
            }
        }
    }

    static synchronized String remove(Context context, Session session) throws Exception {
        return change(context, session, false);
    }

    static synchronized String restore(Context context, Session session) throws Exception {
        return change(context, session, true);
    }

    private static String change(Context context, Session session, boolean restore) throws Exception {
        ChannelSftp sftp = CompanionFiles.channel(session);
        try {
            return change(sftp, CompanionFiles.PLUGINS, load(context).get("_meta.lua"), restore,
                    command -> KindleTools.run(session, command));
        } finally {
            sftp.disconnect();
        }
    }

    static String change(ChannelSftp sftp, String parent, byte[] metadata, boolean restore,
                         RemoteCommand shell) throws Exception {
        if (CompanionFiles.stat(sftp, parent) == null)
            return restore ? "Nie znaleziono kopii IL Radio." : "IL Radio jest już usunięte.";
        CompanionFiles.requireDirectory(sftp, parent);
        String active = parent + "/" + NAME;
        boolean present = CompanionFiles.stat(sftp, active) != null;
        if (present) requireOwned(sftp, active, metadata);
        if (!restore) {
            if (!present) return "IL Radio jest już usunięte.";
            move(sftp, active, hidden(parent, "removed"), shell);
            return "Wtyczka została wyłączona i zachowana w kopii. Uruchom ponownie KOReadera.";
        }
        String backup = latestBackup(sftp, parent, metadata);
        if (backup == null) return "Nie znaleziono pełnej kopii IL Radio.";
        String saved = hidden(parent, "backup");
        try {
            if (present) move(sftp, active, saved, shell);
            move(sftp, backup, active, shell);
            requireComplete(sftp, active, metadata);
        } catch (Exception failure) {
            try {
                if (CompanionFiles.stat(sftp, backup) == null && CompanionFiles.stat(sftp, active) != null)
                    move(sftp, active, backup, shell);
                restoreMissing(sftp, active, saved, shell);
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
            }
            throw new IOException("Nie udało się potwierdzić przywrócenia IL Radio. Połącz Kindle ponownie i sprawdź stan wtyczki.", failure);
        }
        return "Przywrócono kopię IL Radio. Uruchom ponownie KOReadera.";
    }

    private static String hidden(String parent, String type) {
        return parent + "/.ilradio-" + type + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID();
    }

    private static String latestBackup(ChannelSftp sftp, String parent, byte[] metadata) throws Exception {
        String selected = null;
        long latest = 0;
        for (ChannelSftp.LsEntry item : sftp.ls(VerifiedTransfer.literal(parent))) {
            String name = item.getFilename();
            if (!item.getAttrs().isDir() || item.getAttrs().isLink()
                    || !name.matches("\\.ilradio-(backup|removed)-([0-9]{13}-)?[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                continue;
            String date = name.substring(name.indexOf('-', 9) + 1).split("-", 2)[0];
            long time = date.length() == 13 ? Long.parseLong(date) : item.getAttrs().getMTime() * 1000L;
            String path = parent + "/" + name;
            if (time < latest) continue;
            try { requireComplete(sftp, path, metadata); }
            catch (IOException incomplete) { continue; }
            selected = path;
            latest = time;
        }
        return selected;
    }

    private static void requireOwned(ChannelSftp sftp, String directory, byte[] metadata) throws Exception {
        CompanionFiles.requireDirectory(sftp, directory);
        String path = directory + "/_meta.lua";
        if (!CompanionFiles.matches(sftp, path, metadata)
                && !CompanionFiles.matchesHash(sftp, path, LEGACY_META))
            throw new IOException("Pod nazwą IL Radio znaleziono nierozpoznaną wtyczkę. Sprawdź ją w KOReaderze.");
        for (String file : FILES) {
            SftpATTRS entry = CompanionFiles.stat(sftp, directory + "/" + file);
            if (entry != null && (!entry.isReg() || entry.isLink()))
                throw new IOException("Folder IL Radio zawiera nieoczekiwany typ pliku.");
        }
    }

    private static void requireComplete(ChannelSftp sftp, String directory, byte[] metadata) throws Exception {
        requireOwned(sftp, directory, metadata);
        String[] expected = CompanionFiles.stat(sftp, directory + "/tuning.lua") == null ? LEGACY_FILES : FILES;
        for (String file : expected) {
            SftpATTRS entry = CompanionFiles.stat(sftp, directory + "/" + file);
            if (entry == null || entry.getSize() == 0 || entry.getSize() > MAX_FILE)
                throw new IOException("Kopia IL Radio jest niepełna.");
        }
    }

    private static void restoreMissing(ChannelSftp sftp, String active, String backup, RemoteCommand shell)
            throws Exception {
        if (CompanionFiles.stat(sftp, active) == null && CompanionFiles.stat(sftp, backup) != null)
            move(sftp, backup, active, shell);
    }

    private static Map<String, byte[]> load(Context context) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String name : FILES) {
            try (InputStream in = context.getAssets().open(NAME + "/" + name)) {
                byte[] bytes = BoundedInput.read(in, MAX_FILE);
                if (bytes.length == 0) throw new IOException("Brakuje pliku potrzebnego do instalacji IL Radio.");
                files.put(name, bytes);
            }
        }
        return files;
    }

    private static boolean matches(ChannelSftp sftp, String directory, Map<String, byte[]> expected) throws Exception {
        for (Map.Entry<String, byte[]> file : expected.entrySet())
            if (!CompanionFiles.matches(sftp, directory + "/" + file.getKey(), file.getValue())) return false;
        return true;
    }

    private static void move(ChannelSftp sftp, String source, String target, RemoteCommand shell) throws Exception {
        if (CompanionFiles.stat(sftp, target) != null) throw new IOException("Folder docelowy jest już zajęty.");
        try {
            VerifiedTransfer.renameWithoutReplace(sftp, source, target);
        } catch (SftpException failure) {
            if (!CompanionFiles.canTryFileInstall(sftp, failure, source, target)) throw failure;
            shell.run("test ! -e " + quote(target) + " && test ! -L " + quote(target)
                    + " && mv " + quote(source) + " " + quote(target));
        }
        CompanionFiles.requireDirectory(sftp, target);
        if (CompanionFiles.stat(sftp, source) != null) throw new IOException("Kindle nie potwierdził przeniesienia.");
    }

    private static String quote(String path) {
        return "'" + path.replace("'", "'\"'\"'") + "'";
    }

    static String diagnostics(Context context, Session session) throws Exception {
        ChannelSftp sftp = CompanionFiles.channel(session);
        try {
            if (CompanionFiles.stat(sftp, ACTIVE) == null)
                return "Wybierz instalację IL Radio, żeby sprawdzić odtwarzacz na Kindle’u.";
            CompanionFiles.requireDirectory(sftp, ACTIVE);
            if (!CompanionFiles.matches(sftp, ACTIVE + "/player.sh", load(context).get("player.sh")))
                return "Zaktualizuj IL Radio przed uruchomieniem diagnostyki.";
        } finally {
            sftp.disconnect();
        }
        String details = KindleTools.run(session, "sh " + ACTIVE + "/player.sh diagnose");
        return (details.startsWith("engine=none")
                ? "Nie wykryto odtwarzacza. Na Kindle Keyboard 3 wybierz „Zainstaluj MPlayer dla Kindle K3”."
                : "Sprawdź poniżej wyniki prób i stan ostatniego źródła. Próbę dekodowania uruchomisz w menu IL Radio na Kindle’u.")
                + "\n" + details;
    }
}
