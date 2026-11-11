package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Atomic SFTP deployment; the phone is not part of mail traffic afterwards. */
final class MailInstaller {
    private static final String NAME = "ilmail_k3.koplugin";
    private static final String[] FILES = {"_meta.lua", "main.lua", "mailk3"};
    private static final String ROOT = CompanionFiles.PLUGINS;
    private static final int MAX_BINARY_BYTES = 14 * 1024 * 1024;

    private MailInstaller() {}

    static synchronized String install(Context context, Session session) throws Exception {
        Map<String, byte[]> files = load(context);
        ChannelSftp sftp = CompanionFiles.channel(session);
        try {
            CompanionFiles.requireDirectory(sftp, "/mnt/us/koreader");
            if (CompanionFiles.stat(sftp, ROOT) == null) sftp.mkdir(ROOT);
            CompanionFiles.requireDirectory(sftp, ROOT);
            String active = ROOT + "/" + NAME;
            boolean replacing = CompanionFiles.stat(sftp, active) != null;
            if (replacing) {
                CompanionFiles.requireDirectory(sftp, active);
                if (!CompanionFiles.matches(sftp, active + "/_meta.lua", files.get("_meta.lua")))
                    throw new IOException("W tym folderze jest inna wtyczka. Nie zostanie nadpisana.");
                if (matches(sftp, active, files))
                    return "Klient poczty jest już aktualny.";
            }
            String unique = UUID.randomUUID().toString();
            String stage = ROOT + "/.ilmail-stage-" + unique;
            String backup = ROOT + "/.ilmail-backup-" + System.currentTimeMillis() + "-" + unique;
            boolean stageExists = false;
            boolean backupExists = false;
            try {
                sftp.mkdir(stage);
                stageExists = true;
                for (Map.Entry<String, byte[]> entry : files.entrySet())
                    CompanionFiles.putVerified(sftp, stage + "/" + entry.getKey(), entry.getValue());
                sftp.chmod(0700, stage + "/mailk3");
                if (!matches(sftp, stage, files))
                    throw new IOException("Kindle nie potwierdził pełnego przesłania poczty.");
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (replacing) {
                    move(sftp, session, active, backup);
                    backupExists = true;
                }
                try {
                    move(sftp, session, stage, active);
                    stageExists = false;
                    if (!matches(sftp, active, files))
                        throw new IOException("Nie potwierdzono instalacji po przeniesieniu.");
                } catch (Exception failure) {
                    if (backupExists && CompanionFiles.stat(sftp, active) == null) {
                        move(sftp, session, backup, active);
                        backupExists = false;
                    }
                    throw failure;
                }
                return replacing
                        ? "Zaktualizowano pocztę. Zatrzymano kopię poprzedniej wersji na Kindle."
                        : "Zainstalowano pocztę i zweryfikowano pliki.";
            } finally {
                if (stageExists) {
                    for (String filename : FILES) CompanionFiles.remove(sftp, stage + "/" + filename);
                    try { sftp.rmdir(stage); } catch (SftpException ignored) {}
                }
            }
        } finally {
            sftp.disconnect();
        }
    }

    private static void move(ChannelSftp sftp, Session session, String source, String target)
            throws Exception {
        if (CompanionFiles.stat(sftp, target) != null)
            throw new IOException("Docelowy folder wtyczki jest zajęty.");
        try {
            VerifiedTransfer.renameWithoutReplace(sftp, source, target);
        } catch (SftpException failure) {
            if (!CompanionFiles.canTryFileInstall(sftp, failure, source, target)) throw failure;
            KindleTools.run(session,"test ! -e " + quoted(target)
                    + " && test ! -L " + quoted(target)
                    + " && mv " + quoted(source) + " " + quoted(target));
        }
        if (CompanionFiles.stat(sftp, source) != null)
            throw new IOException("Kindle nie potwierdził przeniesienia folderu.");
        CompanionFiles.requireDirectory(sftp, target);
    }

    private static String quoted(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static boolean matches(ChannelSftp sftp, String directory, Map<String, byte[]> files)
            throws Exception {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            if (!CompanionFiles.matches(sftp, directory + "/" + entry.getKey(), entry.getValue()))
                return false;
        }
        return true;
    }

    private static Map<String, byte[]> load(Context context) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : FILES) {
            try (InputStream input = context.getAssets().open(NAME + "/" + name)) {
                int max = name.equals("mailk3") ? MAX_BINARY_BYTES : 65536;
                byte[] bytes = BialolekaForecast.readBounded(input, max);
                if (bytes.length == 0)
                    throw new IOException("Brakuje pliku " + name + " w pakiecie aplikacji.");
                if (name.equals("mailk3") && (bytes.length < 4
                        || bytes[0] != 0x7f || bytes[1] != 'E'
                        || bytes[2] != 'L' || bytes[3] != 'F'))
                    throw new IOException("Nieprawidłowy plik silnika poczty (ARM).");
                result.put(name, bytes);
            }
        }
        return result;
    }
}
