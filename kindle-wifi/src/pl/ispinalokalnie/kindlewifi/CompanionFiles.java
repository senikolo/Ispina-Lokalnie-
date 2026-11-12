package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;

final class CompanionFiles {
    static final String PLUGINS = "/mnt/us/koreader/plugins";
    static final String SETTINGS = "/mnt/us/koreader/settings";
    private static final String[] PLUGIN_FILES = { "_meta.lua", "main.lua" };

    static void install(Context context, Session session, String plugin) throws Exception {
        install(context, session, plugin, new String[0]);
    }

    static void install(Context context, Session session, String plugin, String... upgradeFromHashes) throws Exception {
        byte[] metadata = asset(context, plugin, "_meta.lua");
        byte[] code = asset(context, plugin, "main.lua");
        ChannelSftp sftp = null;
        String stage = null;
        String phase = "otwieranie połączenia SFTP";
        try {
            sftp = channel(session);
            phase = "sprawdzanie folderu wtyczek";
            requireDirectory(sftp, "/mnt/us/koreader");
            if (stat(sftp, PLUGINS) == null) {
                try { sftp.mkdir(PLUGINS); }
                catch (SftpException error) {
                    if (stat(sftp, PLUGINS) == null) throw error;
                }
            }
            requireDirectory(sftp, PLUGINS);
            String destination = PLUGINS + "/" + plugin;
            if (stat(sftp, destination) != null) {
                phase = "sprawdzanie zainstalowanej wtyczki";
                requireDirectory(sftp, destination);
                if (!installed(sftp, destination, metadata, code)) {
                    String previousMain = destination + "/main.lua";
                    if (!matches(sftp, destination + "/_meta.lua", metadata)
                            || !matchesAnyHash(sftp, previousMain, upgradeFromHashes))
                        throw new IOException("Folder tej wtyczki już istnieje i ma inną zawartość. "
                                + "Zmień jego nazwę na kopię zapasową przed ponowną instalacją.");
                    phase = "aktualizowanie znanej wersji wtyczki";
                    replace(sftp, destination, "main.lua", code);
                    if (!installed(sftp, destination, metadata, code))
                        throw new IOException("Nie potwierdzono aktualizacji wtyczki.");
                }
                return;
            }
            phase = "przesyłanie plików wtyczki";
            stage = PLUGINS + "/.kindlewifi-plugin-" + UUID.randomUUID();
            sftp.mkdir(stage);
            putVerified(sftp, stage + "/_meta.lua", metadata);
            putVerified(sftp, stage + "/main.lua", code);
            phase = "zatwierdzanie folderu wtyczki";
            try {
                VerifiedTransfer.renameWithoutReplace(sftp, stage, destination);
            } catch (SftpException error) {
                if (installed(sftp, destination, metadata, code)) return;
                if (!canTryFileInstall(sftp, error, stage, destination)) throw error;
                phase = "zatwierdzanie wtyczki plik po pliku";
                publishFiles(sftp, stage, destination, metadata);
            }
            phase = "weryfikacja wtyczki po instalacji";
            if (!installed(sftp, destination, metadata, code))
                throw new IOException("Kindle nie zwrócił obu plików wtyczki po zapisie.");
        } catch (SftpException error) {
            throw new IOException("Etap: " + phase + ". Kod SFTP " + error.id + " — "
                    + (error.getMessage() == null ? "brak opisu z Kindle’a." : error.getMessage()), error);
        } catch (IOException error) {
            throw new IOException("Etap: " + phase + ". " + error.getMessage(), error);
        } finally {
            if (sftp != null) {
                if (stage != null) cleanStage(sftp, stage);
                sftp.disconnect();
            }
        }
    }

    private static boolean installed(ChannelSftp sftp, String destination, byte[] metadata, byte[] code)
            throws Exception {
        SftpATTRS attrs = stat(sftp, destination);
        return attrs != null && attrs.isDir() && destination.equals(sftp.realpath(destination))
                && matches(sftp, destination + "/_meta.lua", metadata)
                && matches(sftp, destination + "/main.lua", code);
    }

    static boolean canTryFileInstall(ChannelSftp sftp, SftpException error, String stage, String destination)
            throws Exception {
        if (error.id != ChannelSftp.SSH_FX_FAILURE && error.id != ChannelSftp.SSH_FX_OP_UNSUPPORTED)
            return false;
        SftpATTRS source = stat(sftp, stage);
        return source != null && source.isDir() && stat(sftp, destination) == null;
    }

    static void publishFiles(ChannelSftp sftp, String stage, String destination, byte[] metadata) throws Exception {
        // mkdir reserves the destination. The loader only sees a complete main.lua after the last rename.
        sftp.mkdir(destination);
        boolean metadataMoved = false;
        try {
            VerifiedTransfer.renameWithoutReplace(sftp, stage + "/_meta.lua", destination + "/_meta.lua");
            metadataMoved = true;
            VerifiedTransfer.renameWithoutReplace(sftp, stage + "/main.lua", destination + "/main.lua");
        } catch (Exception error) {
            try {
                if (stat(sftp, destination + "/main.lua") == null) {
                    if (metadataMoved && matches(sftp, destination + "/_meta.lua", metadata))
                        remove(sftp, destination + "/_meta.lua");
                    sftp.rmdir(destination);
                }
            } catch (Exception ignored) {
                // Keep a possibly completed installation when the server cannot confirm its state.
            }
            throw error;
        }
    }

    private static void cleanStage(ChannelSftp sftp, String stage) {
        for (String file : PLUGIN_FILES) remove(sftp, stage + "/" + file);
        try { sftp.rmdir(stage); } catch (SftpException ignored) { }
    }

    private static byte[] asset(Context context, String plugin, String file) throws IOException {
        try (InputStream input = context.getAssets().open(plugin + "/" + file)) {
            return BoundedInput.read(input, "ilwifi_guest.koplugin".equals(plugin) ? 49152 : 16384);
        }
    }

    static void replace(ChannelSftp sftp, String directory, String filename, byte[] contents) throws Exception {
        String stage = directory + "/.kindlewifi-" + UUID.randomUUID();
        String target = directory + "/" + filename;
        try {
            requireDirectory(sftp, directory);
            SftpATTRS existing = stat(sftp, target);
            if (existing != null && !existing.isReg()) throw new IOException("Miejsce zapisu jest zajęte.");
            putVerified(sftp, stage, contents);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if ("1".equals(sftp.getExtension("posix-rename@openssh.com"))) sftp.rename(stage, target);
            else KindleTools.run(sftp.getSession(), "mv -f " + stage + " " + target);
            if (!matches(sftp, target, contents)) throw new IOException("Nie potwierdzono zapisu pliku.");
        } finally {
            remove(sftp, stage);
        }
    }

    static ChannelSftp channel(Session session) throws Exception {
        ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
        try {
            sftp.connect(5000);
            return sftp;
        } catch (Exception error) {
            sftp.disconnect();
            throw error;
        }
    }

    static SftpATTRS stat(ChannelSftp sftp, String path) throws SftpException {
        try { return sftp.lstat(path); }
        catch (SftpException error) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw error;
        }
    }

    static void requireDirectory(ChannelSftp sftp, String path) throws Exception {
        SftpATTRS attrs = stat(sftp, path);
        if (attrs == null || !attrs.isDir() || !path.equals(sftp.realpath(path)))
            throw new IOException("Nie znaleziono bezpiecznego folderu KOReadera: " + path);
    }

    static boolean matches(ChannelSftp sftp, String path, byte[] contents) throws Exception {
        SftpATTRS attrs = stat(sftp, path);
        if (attrs == null || !attrs.isReg() || attrs.getSize() != contents.length) return false;
        try (InputStream input = sftp.get(path)) {
            return Arrays.equals(contents, BoundedInput.read(input, contents.length));
        }
    }

    private static boolean matchesAnyHash(ChannelSftp sftp, String path, String[] hashes) throws Exception {
        for (String hash : hashes) if (matchesHash(sftp, path, hash)) return true;
        return false;
    }

    static boolean matchesHash(ChannelSftp sftp, String path, String hash) throws Exception {
        SftpATTRS attrs = stat(sftp, path);
        if (attrs == null || !attrs.isReg() || attrs.getSize() > 49152) return false;
        try (InputStream input = sftp.get(path)) {
            byte[] bytes = BoundedInput.read(input, 49152);
            return hash.equals(VerifiedTransfer.sha256(new ByteArrayInputStream(bytes)));
        }
    }

    static void putVerified(ChannelSftp sftp, String path, byte[] contents) throws Exception {
        sftp.put(new ByteArrayInputStream(contents), path);
        if (!matches(sftp, path, contents)) throw new IOException("Nie potwierdzono pełnego przesłania pliku.");
    }

    static void remove(ChannelSftp sftp, String path) {
        try { sftp.rm(path); } catch (SftpException ignored) { }
    }
}
