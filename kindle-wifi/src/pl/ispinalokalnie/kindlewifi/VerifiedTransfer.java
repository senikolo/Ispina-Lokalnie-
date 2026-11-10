package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import java.io.*;
import java.security.*;
import java.util.*;

/** No Android dependencies: the exact transfer implementation is integration tested. */
public final class VerifiedTransfer {
    public interface Progress { void update(String message); }
    public interface Percent { void update(int value); }
    public static String upload(ChannelSftp channel, File local, String name,
                                String directory, Progress progress) throws Exception {
        return upload(channel, local, name, directory, progress, value -> {});
    }
    public static String upload(ChannelSftp channel, File local, String name,
                                String directory, Progress progress, Percent percent) throws Exception {
        return upload(channel, local, name, directory, progress, percent, UUID.randomUUID());
    }

    static String upload(ChannelSftp channel, File local, String name, String directory,
                         Progress progress, Percent percent, UUID attempt) throws Exception {
        return upload(channel, local, name, directory, progress, percent, attempt, target -> {});
    }

    static String upload(ChannelSftp channel, File local, String name, String directory,
                         Progress progress, Percent percent, UUID attempt,
                         java.util.function.Consumer<String> beforePublish) throws Exception {
        percent.update(0);
        if (local.length() == 0) throw new IOException("Wybrany plik jest pusty.");
        if (name.startsWith(".") || name.contains("/") || name.contains("\\"))
            throw new IOException("Nieprawidłowa nazwa pliku.");
        if (channel.lstat(literal(directory)).isLink() || !directory.equals(channel.realpath(directory)))
            throw new IOException("Folder książek prowadzi w inne miejsce. Wysyłkę wstrzymano.");
        channel.cd(literal(directory));
        String actual = channel.pwd();
        if (!directory.equals(actual)) throw new IOException("Nie potwierdzono folderu książek.");
        boolean audio = directory.equals("/mnt/us/music") || directory.equals("/mnt/us/audiobooks")
                || directory.startsWith("/mnt/us/music/") || directory.startsWith("/mnt/us/audiobooks/");
        String temporary = ".kindle-upload-" + attempt + ".part";
        removePartial(channel, temporary);
        try {
            progress.update(audio ? "Przesyłam audio na Kindle…" : "Przesyłam książkę do documents…");
            try (InputStream in = new FileInputStream(local)) { channel.put(in, temporary, new com.jcraft.jsch.SftpProgressMonitor() {
                private long count; private int last = -1;
                public void init(int operation, String src, String dest, long max) {}
                public boolean count(long bytes) {
                    count += bytes;
                    int value = (int)Math.min(50, count * 50 / local.length());
                    if (value != last) { last = value; percent.update(value); }
                    return !Thread.currentThread().isInterrupted();
                }
                public void end() {}
            }); }
            if (channel.lstat(temporary).getSize() != local.length())
                throw new IOException("Kindle zapisał niepełny plik. Spróbuj ponownie.");
            progress.update(audio ? "Sprawdzam audio na Kindle…" : "Sprawdzam zawartość książki zapisanej na Kindle…");
            verify(channel, local, temporary, percent);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano przesyłkę przed zatwierdzeniem pliku.");
            String target = publish(channel, temporary, name, beforePublish);
            temporary = null;
            percent.update(100);
            return actual + "/" + target;
        } finally {
            if (temporary != null && channel.isConnected()) {
                try { channel.rm(temporary); } catch (Exception ignored) { }
            }
        }
    }

    public static void verify(ChannelSftp channel, File local, String remote) throws Exception {
        verify(channel, local, remote, value -> {});
    }
    private static void verify(ChannelSftp channel, File local, String remote, Percent percent) throws Exception {
        if (channel.lstat(literal(remote)).getSize() != local.length())
            throw new IOException("Nie potwierdzono pełnego zapisu książki na Kindle.");
        byte[] expected;
        try (InputStream in = new FileInputStream(local)) { expected = digest(in); }
        byte[] actual;
        try (InputStream in = channel.get(literal(remote))) { MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32768]; int n; long read = 0; int last = -1;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano weryfikację książki.");
                hash.update(buffer, 0, n); read += n;
                int value = (int)Math.min(99, 50 + read * 49 / Math.max(1, local.length()));
                if (value != last) { last = value; percent.update(value); }
            }
            actual = hash.digest(); }
        if (!MessageDigest.isEqual(expected, actual))
            throw new IOException("Plik na Kindle różni się od pliku w telefonie. Zapis niepotwierdzony.");
    }

    private static byte[] digest(InputStream in) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano odczyt książki.");
            hash.update(buffer, 0, count);
        }
        return hash.digest();
    }

    public static String literal(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    static void renameWithoutReplace(ChannelSftp channel, String from, String to) throws Exception {
        // Request SFTP v3 RENAME, which rejects an existing target. JSch otherwise
        // uses the OpenSSH POSIX extension, whose contract permits replacement.
        if (!"1".equals(channel.getExtension("posix-rename@openssh.com"))) {
            channel.rename(literal(from), literal(to));
            return;
        }
        java.lang.reflect.Field extension = ChannelSftp.class.getDeclaredField("extension_posix_rename");
        extension.setAccessible(true);
        synchronized (channel) {
            boolean enabled = extension.getBoolean(channel);
            extension.setBoolean(channel, false);
            try {
                channel.rename(literal(from), literal(to));
            } finally {
                extension.setBoolean(channel, enabled);
            }
        }
    }

    private static String publish(ChannelSftp channel, String temporary, String name,
                                  java.util.function.Consumer<String> beforePublish) throws Exception {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i <= 999; i++) {
            String candidate = i == 1 ? name : stem + " (" + i + ")" + ext;
            try { channel.lstat(literal(candidate)); }
            catch (SftpException e) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw e;
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Przerwano przesyłkę przed zatwierdzeniem pliku.");
                try {
                    beforePublish.accept(candidate);
                    renameWithoutReplace(channel, temporary, candidate);
                    return candidate;
                } catch (SftpException renameError) {
                    try { channel.lstat(literal(candidate)); }
                    catch (SftpException lookupError) { throw renameError; }
                    if (!channel.isConnected()) throw renameError;
                }
            }
        }
        throw new IOException("Zbyt wiele plików o tej samej nazwie.");
    }

    private static void removePartial(ChannelSftp channel, String path) throws Exception {
        try {
            com.jcraft.jsch.SftpATTRS attributes = channel.lstat(literal(path));
            if (!attributes.isReg() || attributes.isLink()) throw new IOException("Nieprawidłowy plik roboczy.");
            channel.rm(literal(path));
        } catch (SftpException error) {
            if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error;
        }
    }

    static void removeAttempt(ChannelSftp channel, String directory, UUID attempt) throws Exception {
        if (channel.lstat(literal(directory)).isLink() || !directory.equals(channel.realpath(directory))) return;
        removePartial(channel, directory + "/.kindle-upload-" + attempt + ".part");
    }

    static void cleanAbandoned(ChannelSftp channel, String directory) throws Exception {
        if (channel.lstat(literal(directory)).isLink() || !directory.equals(channel.realpath(directory))) return;
        long cutoff = System.currentTimeMillis() / 1000 - 86400;
        for (ChannelSftp.LsEntry entry : channel.ls(literal(directory))) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano wysyłkę.");
            if (entry.getAttrs().isReg() && entry.getAttrs().getMTime() < cutoff
                    && entry.getFilename().matches("\\.kindle-upload-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.part")) {
                removePartial(channel, directory + "/" + entry.getFilename());
            }
        }
    }

    static String sha256(InputStream input) throws Exception {
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest(input)) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    static boolean matches(ChannelSftp channel, String directory, String path, String hash) throws Exception {
        if (path == null || !path.startsWith(directory + "/") || !hash.matches("[0-9a-f]{64}")) return false;
        String name = path.substring(directory.length() + 1);
        if (name.contains("/") || name.contains("\\") || name.startsWith(".") || extension(name).isEmpty()
                || !directory.equals(channel.realpath(directory))) return false;
        try {
            com.jcraft.jsch.SftpATTRS before = channel.lstat(literal(path));
            if (!before.isReg() || before.isLink()) return false;
            String actual;
            try (InputStream input = channel.get(literal(path))) { actual = sha256(input); }
            com.jcraft.jsch.SftpATTRS after = channel.lstat(literal(path));
            return after.isReg() && !after.isLink() && before.getSize() == after.getSize()
                    && before.getMTime() == after.getMTime() && hash.equals(actual);
        } catch (SftpException error) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
            throw error;
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    public static String identical(ChannelSftp channel, File local, String directory) throws Exception {
        return identical(channel, local, directory, local.getName());
    }

    static String identical(ChannelSftp channel, File local, String directory, String requested) throws Exception {
        if (channel.lstat(literal(directory)).isLink() || !directory.equals(channel.realpath(directory)))
            throw new IOException("Nie potwierdzono folderu książek.");
        String expected = null;
        for (ChannelSftp.LsEntry entry : channel.ls(literal(directory))) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Przerwano sprawdzanie.");
            String name = entry.getFilename();
            if (!entry.getAttrs().isReg() || entry.getAttrs().isLink() || entry.getAttrs().getSize() != local.length()
                    || !extension(requested).equals(extension(name)) || name.startsWith(".")) continue;
            if (expected == null) {
                try (InputStream input = new FileInputStream(local)) { expected = sha256(input); }
            }
            String path = directory + "/" + name;
            if (matches(channel, directory, path, expected)) return path;
        }
        return null;
    }

    public static String list(ChannelSftp channel, String directory) throws Exception {
        Vector<ChannelSftp.LsEntry> files = channel.ls(directory);
        List<String> names = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : files) {
            String name = entry.getFilename();
            if (!entry.getAttrs().isDir() && !name.startsWith("."))
                names.add(name + "  (" + entry.getAttrs().getSize() + " B)");
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        StringBuilder text = new StringBuilder(directory + "\n");
        if (names.isEmpty()) text.append("Brak plików.\n");
        for (String name : names) text.append(name).append('\n');
        return text.toString();
    }
}
