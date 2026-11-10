package pl.ispinalokalnie.kindlewifi;

import android.graphics.Typeface;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

final class FontPreviewStore {
    private final File cache;
    private final List<String> wanted;
    private final Map<String, Typeface> faces = new LinkedHashMap<>();
    private long bytesLeft = 32L * 1024 * 1024;
    private int filesLeft = 160;
    private final long deadline = System.nanoTime() + 45000000000L;

    private FontPreviewStore(File cache, List<String> wanted) {
        this.cache = cache; this.wanted = wanted;
    }

    static Map<String, Typeface> load(ChannelSftp sftp, File parent, String pin, List<String> wanted) throws Exception {
        File cache = new File(parent, "reader-fonts-" + hash(pin));
        if (!cache.isDirectory() && !cache.mkdirs()) throw new java.io.IOException("Brak miejsca na podglądy.");
        FontPreviewStore store = new FontPreviewStore(cache, wanted);
        store.scan(sftp, "/mnt/us/koreader/fonts", 0);
        if (store.faces.size() < wanted.size()) store.scan(sftp, "/mnt/us/fonts", 0);
        return store.faces;
    }

    @SuppressWarnings("unchecked")
    private void scan(ChannelSftp sftp, String path, int depth) throws Exception {
        if (depth > 4 || filesLeft <= 0 || System.nanoTime() > deadline || faces.size() >= wanted.size()) return;
        Vector<ChannelSftp.LsEntry> entries;
        try { entries = sftp.ls(path); }
        catch (com.jcraft.jsch.SftpException error) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE || error.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) return;
            throw error;
        }
        for (ChannelSftp.LsEntry entry : entries) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (filesLeft <= 0 || System.nanoTime() > deadline || faces.size() >= wanted.size()) return;
            String name = entry.getFilename();
            if (name.startsWith(".") || name.contains("/") || name.contains("\\")) continue;
            SftpATTRS a = entry.getAttrs();
            if (a.isLink()) continue;
            String remote = path + "/" + name;
            if (a.isDir()) { scan(sftp, remote, depth + 1); continue; }
            if (!a.isReg() || !name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(ttf|otf)")
                    || a.getSize() < 12 || a.getSize() > 6 * 1024 * 1024) continue;
            filesLeft--;
            File local = new File(cache, hash(remote + "/" + a.getSize() + "/" + a.getMTime()) + ".font");
            try {
                if (!local.isFile() || local.length() != a.getSize()) {
                    if (a.getSize() > bytesLeft) continue;
                    bytesLeft -= a.getSize();
                    File part = new File(cache, local.getName() + ".part");
                    try {
                        try (InputStream input = sftp.get(remote); FileOutputStream out = new FileOutputStream(part)) {
                            byte[] buffer = new byte[16384]; long total = 0; int n;
                            while ((n = input.read(buffer)) != -1) {
                                total += n;
                                if (total > a.getSize() || System.nanoTime() > deadline)
                                    throw new java.io.IOException("Pobieranie podglądów trwa zbyt długo.");
                                out.write(buffer, 0, n);
                            }
                            if (total != a.getSize()) throw new java.io.IOException("Niepełny plik czcionki.");
                        }
                        if (!part.renameTo(local)) throw new java.io.IOException("Nie udało się zapisać podglądu.");
                    } finally { part.delete(); }
                }
                java.util.Set<String> names = FontNames.read(local);
                Typeface face = null;
                for (String candidate : wanted) {
                    if (!faces.containsKey(candidate) && names.contains(candidate)) {
                        if (face == null) face = Typeface.createFromFile(local);
                        faces.put(candidate, face);
                    }
                }
            } catch (java.io.IOException | RuntimeException error) {
                // A malformed or unsupported font must not prevent settings editing.
            }
        }
    }

    private static String hash(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
