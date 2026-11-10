package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Sandboxed SFTP file operations for the music/audiobook locations read by IL Radio. */
final class AudioFiles {
    static final String MUSIC = "/mnt/us/music";
    static final String BOOKS = "/mnt/us/audiobooks";
    private final ChannelSftp sftp;
    private final String base, music, books;
    static final class Entry {
        final String name, path;
        final boolean dir;
        final long bytes;
        final int modified;
        Entry(String name, String path, boolean dir, long bytes, int modified) {
            this.name = name;
            this.path = path;
            this.dir = dir;
            this.bytes = bytes;
            this.modified = modified;
        }
    }
    AudioFiles(ChannelSftp channel) {
        this(channel, "/mnt/us");
    }
    AudioFiles(ChannelSftp channel, String base) {
        this.sftp = channel;
        this.base = base;
        music = base + "/music";
        books = base + "/audiobooks";
    }
    boolean connected() { return sftp.isConnected(); }

    static boolean audio(String file) {
        return file != null && !file.startsWith(".") &&
            file.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4a|m4b|aac|ogg|opus|flac|wav|aif|aiff|mp4)$");
    }
    static String validName(String name) throws IOException {
        if (name == null || name.isEmpty() || !name.trim().equals(name) || name.startsWith(".")
                || name.endsWith(".") || name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>200
                || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"))
            throw new IOException("Nazwa nie może być pusta, zaczynać się od kropki ani zawierać znaków / \\ : * ? lub innych znaków specjalnych.");
        return name;
    }
    private String root(String path) throws IOException {
        if (path == null || path.indexOf('\\') != -1 || path.contains("//") || path.contains("/../")
                || path.endsWith("/..") || path.contains("/./") || path.endsWith("/."))
            throw new IOException("Nieprawidłowa ścieżka audio.");
        for (String r: new String[]{music, books}) {
            if (path.equals(r) || path.startsWith(r+"/")) return r;
        }
        throw new IOException("Dostęp tylko do folderów Muzyka i Audiobooki.");
    }
    private SftpATTRS stat(String path) throws Exception {
        try { return sftp.lstat(VerifiedTransfer.literal(path)); }
        catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw e;
        }
    }
    private void checkDir(String path) throws Exception {
        String root = root(path);
        String current=root;
        SftpATTRS a=stat(current);
        if (a==null || !a.isDir() || a.isLink() || !current.equals(sftp.realpath(current)))
            throw new IOException("Katalog audio nie jest bezpiecznym folderem.");
        String tail=path.substring(root.length());
        for(String part:tail.split("/")) if(!part.isEmpty()) {
            if (part.startsWith(".") || sidecar(part)) throw new IOException("Folder ukryty jest niedostępny.");
            current+="/"+part;
            a=stat(current);
            if(a==null || !a.isDir() || a.isLink() || !current.equals(sftp.realpath(current)))
                throw new IOException("Nie można otworzyć wybranego folderu audio.");
        }
    }
    private static boolean sidecar(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".sdr");
    }
    void ensureRoots() throws Exception {
        SftpATTRS parent = stat(base);
        if (parent == null || !parent.isDir() || parent.isLink() || !base.equals(sftp.realpath(base))) throw new IOException("Nie znaleziono pamięci Kindle.");
        for(String root: new String[]{music, books}) {
            if (stat(root) == null) {
                try { sftp.mkdir(root); }
                catch (SftpException failure) {
                    if (stat(root) == null) throw failure;
                }
            }
            checkDir(root);
        }
    }
    List<Entry> list(String dir) throws Exception {
        checkDir(dir);
        List<Entry> out=new ArrayList<>();
        for(ChannelSftp.LsEntry e: sftp.ls(VerifiedTransfer.literal(dir))) {
            String name=e.getFilename(); SftpATTRS a=e.getAttrs();
            if(name.startsWith(".") || a.isLink() || sidecar(name)) continue;
            if(a.isDir() || (a.isReg() && audio(name))) out.add(new Entry(name,dir+"/"+name,a.isDir(),a.getSize(),a.getMTime()));
        }
        Collections.sort(out,(a,b)->a.dir!=b.dir?(a.dir?-1:1):a.name.compareToIgnoreCase(b.name));
        return out;
    }
    void mkdir(String parent,String name) throws Exception {
        checkDir(parent); validName(name);
        if (sidecar(name)) throw new IOException("Nazwa folderu jest zarezerwowana.");
        String target=parent+"/"+name;
        if(stat(target)!=null) throw new IOException("Folder już istnieje.");
        sftp.mkdir(VerifiedTransfer.literal(target)); checkDir(target);
    }
    private void checkFile(String path) throws Exception {
        String parent=path.substring(0,path.lastIndexOf('/'));
        checkDir(parent);
        String name=path.substring(path.lastIndexOf('/')+1);
        if(!audio(name)) throw new IOException("To nie jest obsługiwany plik audio.");
        SftpATTRS a=stat(path);
        if(a==null || !a.isReg() || a.isLink()) throw new IOException("Nie znaleziono zwykłego pliku audio.");
    }
    void rename(String path, String name) throws Exception {
        checkFile(path); validName(name);
        String old=path.substring(path.lastIndexOf('/')+1);
        String ext=old.substring(old.lastIndexOf('.'));
        if(!name.toLowerCase(Locale.ROOT).endsWith(ext.toLowerCase(Locale.ROOT)))
            throw new IOException("Zachowaj rozszerzenie oryginalnego formatu: " + ext);
        if(!audio(name)) throw new IOException("Wymagany format audio.");
        String target=path.substring(0,path.lastIndexOf('/')+1)+name;
        moveFile(path,target);
    }
    void move(String path, String destinationFolder) throws Exception {
        checkDir(destinationFolder); checkFile(path);
        moveFile(path,destinationFolder+path.substring(path.lastIndexOf('/')));
    }
    private void moveFile(String path, String target) throws Exception {
        if(path.equals(target)) return;
        if(stat(target)!=null) throw new IOException("W miejscu docelowym plik już istnieje. Nie nadpisano go.");
        long original=stat(path).getSize();
        VerifiedTransfer.renameWithoutReplace(sftp,path,target);
        SftpATTRS after=stat(target);
        if(after==null || !after.isReg() || after.getSize()!=original || stat(path)!=null)
            throw new IOException("Nie udało się potwierdzić przeniesienia. Odśwież listę i sprawdź oba foldery.");
    }
    void delete(Entry selected) throws Exception {
        checkFile(selected.path);
        SftpATTRS current = stat(selected.path);
        if (current == null || !current.isReg() || current.isLink()
                || current.getSize() != selected.bytes || current.getMTime() != selected.modified)
            throw new IOException("Plik zmienił się od otwarcia listy. Odśwież bibliotekę.");
        sftp.rm(VerifiedTransfer.literal(selected.path));
        if (stat(selected.path) != null) throw new IOException("Nie potwierdzono usunięcia. Odśwież bibliotekę.");
    }
    String upload(File local,String filename,String folder,VerifiedTransfer.Progress info,
                  VerifiedTransfer.Percent percent) throws Exception {
        return upload(local, filename, folder, info, percent, new Upload(), false);
    }

    static final class Upload {
        final UUID id = UUID.randomUUID();
        String candidate;
    }

    String upload(File local, String filename, String folder, VerifiedTransfer.Progress info,
                  VerifiedTransfer.Percent percent, Upload attempt, boolean reconnect) throws Exception {
        checkDir(folder);
        validName(filename);
        if (!audio(filename)) throw new IOException("Wybierz plik w obsługiwanym formacie audio.");
        if (reconnect) {
            if (attempt.candidate != null) {
                String previous = folder + "/" + attempt.candidate;
                if (stat(previous) != null) {
                    String expected;
                    try (java.io.InputStream in = new java.io.FileInputStream(local)) {
                        expected = VerifiedTransfer.sha256(in);
                    }
                    if (!VerifiedTransfer.matches(sftp, folder, previous, expected))
                        throw new IOException("Nie udało się potwierdzić ostatniego zapisu. Sprawdź folder przed ponowieniem.");
                    VerifiedTransfer.removeAttempt(sftp, folder, attempt.id);
                    percent.update(100);
                    return previous;
                }
            }
            attempt.candidate = null;
        }
        return VerifiedTransfer.upload(sftp, local, filename, folder, info, percent, attempt.id,
                name -> attempt.candidate = name);
    }
}
