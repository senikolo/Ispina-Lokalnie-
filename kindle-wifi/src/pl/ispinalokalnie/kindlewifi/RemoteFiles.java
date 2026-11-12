package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All mutation paths are confined to book directories, never system folders. */
public final class RemoteFiles {
    private final ChannelSftp channel;
    private final String[] roots;
    public static final class Entry {
        public final String name, path;
        public final boolean directory;
        public final long size;
        final int modified;
        public Entry(String name, String path, boolean directory, long size, int modified) {
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
        }
    }
    public RemoteFiles(ChannelSftp c, String... r) { channel=c; roots=r; }
    private String literal(String p) { return VerifiedTransfer.literal(p); }
    private String root(String path) throws IOException {
        if (path.contains("/../") || path.endsWith("/..") || path.contains("/./") || path.contains("//"))
            throw new IOException("Nieprawidłowa ścieżka folderu.");
        for (String r : roots) if (path.equals(r) || path.startsWith(r + "/")) return r;
        throw new IOException("Dostęp jest ograniczony do folderów z książkami.");
    }
    public void directory(String path) throws Exception {
        String r = root(path);
        String current = r;
        if (channel.lstat(literal(current)).isLink() || !channel.stat(literal(current)).isDir())
            throw new IOException("Folder książek nie jest zwykłym katalogiem.");
        String realRoot = channel.realpath(r);
        if (!realRoot.equals(r)) throw new IOException("Folder książek prowadzi poza dozwoloną lokalizację.");
        String tail = path.substring(r.length());
        for (String part : tail.split("/")) if (!part.isEmpty()) {
            if (part.equals(".") || part.equals("..") || part.startsWith(".") || part.endsWith(".sdr"))
                throw new IOException("Ten folder nie służy do przechowywania książek.");
            current += "/" + part;
            SftpATTRS a = channel.lstat(literal(current));
            if (a.isLink() || !a.isDir()) throw new IOException("Nie można otworzyć tego folderu.");
        }
    }
    private SftpATTRS file(String path) throws Exception {
        root(path);
        directory(path.substring(0,path.lastIndexOf('/')));
        SftpATTRS a=channel.lstat(literal(path));
        if (a.isLink() || !a.isReg() || !book(path.substring(path.lastIndexOf('/')+1)))
            throw new IOException("Wybierz zwykły plik książki.");
        return a;
    }
    public static boolean book(String name) {
        String lower=name.toLowerCase(Locale.ROOT);
        return !name.startsWith(".") && lower.matches(".*\\.(epub|pdf|mobi|azw|azw3|fb2|txt|djvu|cbz)$");
    }
    public static String validName(String name) throws IOException {
        if (name == null || name.trim().isEmpty() || !name.equals(name.trim()) || name.startsWith(".")
                || name.endsWith(".") || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
                || name.getBytes(StandardCharsets.UTF_8).length>220)
            throw new IOException("Podaj nazwę bez ukośników i znaków specjalnych (maks. 220 bajtów). ");
        return name;
    }
    private void absent(String path) throws Exception {
        try { channel.lstat(literal(path)); }
        catch(SftpException e) { if(e.id==ChannelSftp.SSH_FX_NO_SUCH_FILE) return; throw e; }
        throw new IOException("Plik lub folder o tej nazwie już istnieje. Wybierz inną nazwę.");
    }
    public List<Entry> list(String path) throws Exception {
        directory(path);
        List<Entry> result=new ArrayList<>();
        for(ChannelSftp.LsEntry e: channel.ls(literal(path))) {
            String name=e.getFilename(); SftpATTRS a=e.getAttrs();
            if(name.startsWith(".") || name.endsWith(".sdr") || a.isLink()) continue;
            if(a.isDir() || (a.isReg() && book(name))) result.add(new Entry(name,path+"/"+name,a.isDir(),a.getSize(),a.getMTime()));
        }
        Collections.sort(result,(a,b)->a.directory!=b.directory?(a.directory?-1:1):a.name.compareToIgnoreCase(b.name));
        return result;
    }
    public void mkdir(String parent,String name) throws Exception {
        directory(parent); validName(name);
        if(name.endsWith(".sdr")) throw new IOException("Wybierz inną nazwę folderu.");
        String path=parent+"/"+name; absent(path); channel.mkdir(path); directory(path);
    }
    public void rename(String path,String name) throws Exception {
        validName(name);
        String old=path.substring(path.lastIndexOf('/')+1);
        String ext=old.substring(old.lastIndexOf('.'));
        if(!name.endsWith(ext)) throw new IOException("Końcówka formatu książki musi pozostać taka sama.");
        moveTo(path,path.substring(0,path.lastIndexOf('/'))+"/"+name);
    }
    public void move(String path,String destination) throws Exception {
        directory(destination);
        moveTo(path,destination+path.substring(path.lastIndexOf('/')));
    }
    private void moveTo(String source,String target) throws Exception {
        SftpATTRS before=file(source);
        if(source.equals(target)) return;
        directory(target.substring(0,target.lastIndexOf('/'))); absent(target);
        relocate(source, source, target);
        SftpATTRS after=file(target);
        if(after.getSize()!=before.getSize()) throw new IOException("Nie potwierdzono przeniesienia całego pliku.");
        absent(source);
    }
    private static String sidecar(String bookPath) {
        return bookPath.substring(0, bookPath.lastIndexOf('.')) + ".sdr";
    }

    private SftpATTRS optional(String path) throws SftpException {
        try { return channel.lstat(literal(path)); }
        catch (SftpException error) {
            if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw error;
        }
    }

    private void checkLegacyMetadata(String bookPath) throws Exception {
        String prefix = "/mnt/us/koreader";
        int slash = bookPath.lastIndexOf('/');
        String history = prefix + "/history/[" + bookPath.substring(0, slash + 1).replace('/', '#')
                + "] " + bookPath.substring(slash + 1) + ".lua";
        if (optional(prefix + "/docsettings" + sidecar(bookPath)) != null
                || optional(history) != null || optional(history + ".old") != null
                || optional(bookPath + ".kpdfview.lua") != null)
            throw new IOException("Ta książka ma metadane w osobnym miejscu. Zmień jej nazwę lub przenieś ją z listy plików KOReadera.");
    }

    private Map<String, byte[]> readSidecar(String path) throws Exception {
        SftpATTRS attributes = optional(path);
        if (attributes == null) return Collections.emptyMap();
        if (attributes.isLink() || !attributes.isDir() || !path.equals(channel.realpath(path)))
            throw new IOException("Nie można bezpiecznie odczytać folderu postępu czytania.");
        Map<String, byte[]> files = new LinkedHashMap<>();
        int remaining = 16 * 1024 * 1024;
        for (ChannelSftp.LsEntry entry : channel.ls(literal(path))) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) continue;
            SftpATTRS attr = entry.getAttrs();
            if (!attr.isReg() || attr.isLink() || attr.getSize() < 0 || attr.getSize() > remaining
                    || files.size() == 64 || name.contains("/") || name.contains("\\"))
                throw new IOException("Folder postępu ma nietypową zawartość. Użyj listy plików KOReadera.");
            byte[] bytes;
            try (InputStream input = channel.get(literal(path + "/" + name))) {
                bytes = BoundedInput.read(input, remaining);
            }
            if (bytes.length != attr.getSize()) throw new IOException("Metadane zmieniły się podczas odczytu.");
            remaining -= bytes.length;
            files.put(name, bytes);
        }
        return files;
    }

    private boolean sharedSidecar(String bookPath) throws Exception {
        int slash = bookPath.lastIndexOf('/');
        String name = bookPath.substring(slash + 1);
        String stem = sidecar(bookPath).substring(slash + 1);
        for (Entry entry : list(bookPath.substring(0, slash))) {
            if (!entry.directory && !entry.name.equals(name)
                    && sidecar(entry.path).substring(slash + 1).equalsIgnoreCase(stem)) return true;
        }
        return false;
    }

    private void relocate(String filePath, String original, String target) throws Exception {
        if (original.equals(target)) {
            VerifiedTransfer.renameWithoutReplace(channel, filePath, target);
            return;
        }
        checkLegacyMetadata(original);
        checkLegacyMetadata(target);
        String oldSidecar = sidecar(original);
        String newSidecar = sidecar(target);
        if (oldSidecar.equals(newSidecar)) {
            VerifiedTransfer.renameWithoutReplace(channel, filePath, target);
            return;
        }
        absent(newSidecar);
        Map<String, byte[]> metadata = readSidecar(oldSidecar);
        if (metadata.isEmpty()) {
            VerifiedTransfer.renameWithoutReplace(channel, filePath, target);
            return;
        }
        if (sharedSidecar(original) || sharedSidecar(target))
            throw new IOException("Dwie książki mają tę samą nazwę bez rozszerzenia. Użyj listy plików KOReadera.");
        moveWithSidecar(filePath, target, newSidecar, metadata);
        keepRecoveryCopy(original, oldSidecar);
    }

    private void moveWithSidecar(String filePath, String target, String newSidecar,
                                 Map<String, byte[]> metadata) throws Exception {
        String parent = newSidecar.substring(0, newSidecar.lastIndexOf('/'));
        String stage = parent + "/.kindle-metadata-" + UUID.randomUUID();
        channel.mkdir(literal(stage));
        try {
            for (Map.Entry<String, byte[]> item : metadata.entrySet()) {
                String path = stage + "/" + item.getKey();
                channel.put(new ByteArrayInputStream(item.getValue()), literal(path));
                try (InputStream input = channel.get(literal(path))) {
                    if (!Arrays.equals(item.getValue(), BoundedInput.read(input, item.getValue().length)))
                        throw new IOException("Nie potwierdzono kopii postępu czytania.");
                }
            }
            VerifiedTransfer.renameWithoutReplace(channel, stage, newSidecar);
            try {
                VerifiedTransfer.renameWithoutReplace(channel, filePath, target);
            } catch (Exception failure) {
                // A disconnected client cannot tell whether the last rename reached the reader.
                try {
                    if (optional(filePath) != null)
                        VerifiedTransfer.renameWithoutReplace(channel, newSidecar, stage);
                } catch (Exception rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } finally {
            for (String name : metadata.keySet()) {
                try { channel.rm(literal(stage + "/" + name)); }
                catch (SftpException ignored) { }
            }
            try { channel.rmdir(literal(stage)); } catch (SftpException ignored) { }
        }
    }

    private void keepRecoveryCopy(String original, String oldSidecar) {
        // Retain the original as a recoverable copy, including when the network drops here.
        // Do not detach a folder another book now uses.
        try {
            if (optional(original) == null && !sharedSidecar(original)) {
                String recovery = oldSidecar.substring(0, oldSidecar.lastIndexOf('/')) + "/.kindle-metadata-backup-"
                        + UUID.randomUUID() + ".sdr";
                VerifiedTransfer.renameWithoutReplace(channel, oldSidecar, recovery);
            }
        } catch (Exception ignored) { }
    }

    private void requireUnchanged(Entry selected) throws Exception {
        SftpATTRS current = file(selected.path);
        if (current.getSize() != selected.size || current.getMTime() != selected.modified)
            throw new IOException("Plik zmienił się od zaznaczenia. Odśwież folder i wybierz go ponownie.");
    }

    public void rename(Entry selected, String name) throws Exception {
        requireUnchanged(selected);
        rename(selected.path, name);
    }

    public void move(Entry selected, String destination) throws Exception {
        requireUnchanged(selected);
        move(selected.path, destination);
    }

    public void download(Entry selected, File target) throws Exception {
        requireUnchanged(selected);
        try (OutputStream output = new FileOutputStream(target)) {
            channel.get(literal(selected.path), output, new com.jcraft.jsch.SftpProgressMonitor() {
                public void init(int operation, String source, String destination, long max) {}
                public boolean count(long bytes) { return !Thread.currentThread().isInterrupted(); }
                public void end() {}
            });
        }
        requireUnchanged(selected);
        if (target.length() != selected.size) throw new IOException("Pobrano niepełną książkę.");
    }

    public static final class TrashEntry {
        final String folder;
        final String original;
        final long size;
        final int modified;
        TrashEntry(String folder, String original, long size, int modified) {
            this.folder = folder;
            this.original = original;
            this.size = size;
            this.modified = modified;
        }
        String name() { return original.substring(original.lastIndexOf('/') + 1); }
    }

    private static final String TRASH = ".kindle-wifi-trash";

    private void plainDirectory(String path) throws Exception {
        SftpATTRS attributes = channel.lstat(literal(path));
        if (attributes.isLink() || !attributes.isDir() || !path.equals(channel.realpath(path)))
            throw new IOException("Nieprawidłowy katalog kosza.");
    }

    private String trashFolder(String base, boolean create) throws Exception {
        directory(base);
        String path = base + "/" + TRASH;
        try { plainDirectory(path); }
        catch (SftpException missing) {
            if (missing.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw missing;
            if (!create) return null;
            try { channel.mkdir(path); }
            catch (SftpException concurrent) {
                plainDirectory(path);
                return path;
            }
            plainDirectory(path);
        }
        return path;
    }

    public void trash(Entry selected) throws Exception {
        requireUnchanged(selected);
        String bin = trashFolder(root(selected.path), true);
        String folder = bin + "/" + UUID.randomUUID();
        channel.mkdir(folder);
        plainDirectory(folder);
        byte[] original = selected.path.getBytes(StandardCharsets.UTF_8);
        channel.put(new ByteArrayInputStream(original), folder + "/origin");
        requireUnchanged(selected);
        String sidecar = sidecar(selected.path);
        Map<String, byte[]> metadata = readSidecar(sidecar);
        if (metadata.isEmpty()) {
            VerifiedTransfer.renameWithoutReplace(channel, selected.path, folder + "/book");
        } else {
            if (sharedSidecar(selected.path))
                throw new IOException("Dwie książki mają tę samą nazwę bez rozszerzenia. Użyj listy plików KOReadera.");
            moveWithSidecar(selected.path, folder + "/book", folder + "/sidecar", metadata);
            keepRecoveryCopy(selected.path, sidecar);
        }
    }

    public List<TrashEntry> trashContents() throws Exception {
        List<TrashEntry> contents = new ArrayList<>();
        for (String base : roots) {
            String trash;
            try { trash = trashFolder(base, false); }
            catch (SftpException missing) {
                if (missing.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) continue;
                throw missing;
            }
            if (trash == null) continue;
            for (ChannelSftp.LsEntry entry : channel.ls(literal(trash))) {
                if (!entry.getAttrs().isDir() || entry.getAttrs().isLink()
                        || !entry.getFilename().matches("[a-f0-9-]{36}")) continue;
                try { contents.add(readTrash(trash + "/" + entry.getFilename())); }
                catch (SftpException incomplete) {
                    if (incomplete.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw incomplete;
                } catch (IOException damaged) {
                    // An invalid entry cannot be restored or removed through this screen.
                }
            }
        }
        contents.sort(Comparator.comparing(TrashEntry::name, String.CASE_INSENSITIVE_ORDER));
        return contents;
    }

    private TrashEntry readTrash(String folder) throws Exception {
        String base = root(folder);
        String bin = trashFolder(base, false);
        if (bin == null || !folder.startsWith(bin + "/")
                || !folder.substring(bin.length() + 1).matches("[a-f0-9-]{36}"))
            throw new IOException("Nieprawidłowy wpis kosza.");
        plainDirectory(folder);
        SftpATTRS book = channel.lstat(literal(folder + "/book"));
        SftpATTRS origin = channel.lstat(literal(folder + "/origin"));
        if (!book.isReg() || book.isLink() || !origin.isReg() || origin.isLink() || origin.getSize() > 4096)
            throw new IOException("Nie można odczytać wpisu kosza.");
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        try (InputStream input = channel.get(literal(folder + "/origin"))) {
            byte[] buffer = new byte[512];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (value.size() + n > 4096) throw new IOException("Za długi wpis kosza.");
                value.write(buffer, 0, n);
            }
        }
        String original = new String(value.toByteArray(), StandardCharsets.UTF_8);
        if (!base.equals(root(original)) || !book(original.substring(original.lastIndexOf('/') + 1)))
            throw new IOException("Nieprawidłowa pierwotna ścieżka książki.");
        return new TrashEntry(folder, original, book.getSize(), book.getMTime());
    }

    public void restore(TrashEntry entry) throws Exception {
        restore(entry, entry.original.substring(0, entry.original.lastIndexOf('/')), entry.name());
    }

    public void restore(TrashEntry entry, String destination, String name) throws Exception {
        requireSameTrashEntry(entry);
        directory(destination);
        validName(name);
        if (!name.substring(name.lastIndexOf('.') + 1).equalsIgnoreCase(
                entry.name().substring(entry.name().lastIndexOf('.') + 1)) || !book(name))
            throw new IOException("Zachowaj format przywracanej książki.");
        String target = destination + "/" + name;
        absent(target);
        String archived = entry.folder + "/sidecar";
        Map<String, byte[]> metadata = readSidecar(archived);
        if (!target.equals(entry.original)) {
            if (metadata.isEmpty() && optional(entry.original) != null)
                throw new IOException("Pierwotna nazwa jest już używana przez inną książkę. Najpierw zwolnij tę nazwę.");
            checkLegacyMetadata(entry.original);
            checkLegacyMetadata(target);
        }
        if (metadata.isEmpty()) {
            relocate(entry.folder + "/book", entry.original, target);
        } else {
            String destinationSidecar = sidecar(target);
            if (sharedSidecar(target)) throw new IOException("Docelowa nazwa koliduje z postępem innej książki.");
            if (optional(destinationSidecar) == null) {
                moveWithSidecar(entry.folder + "/book", target, destinationSidecar, metadata);
            } else {
                Map<String, byte[]> existing = readSidecar(destinationSidecar);
                if (!metadata.keySet().equals(existing.keySet()))
                    throw new IOException("W miejscu przywracania są już inne metadane.");
                for (String key : metadata.keySet()) {
                    if (!Arrays.equals(metadata.get(key), existing.get(key)))
                        throw new IOException("W miejscu przywracania są już inne metadane.");
                }
                VerifiedTransfer.renameWithoutReplace(channel, entry.folder + "/book", target);
            }
        }
        tidyTrash(entry.folder);
    }

    private void requireSameTrashEntry(TrashEntry entry) throws Exception {
        TrashEntry current = readTrash(entry.folder);
        if (!entry.original.equals(current.original) || entry.size != current.size || entry.modified != current.modified)
            throw new IOException("Wpis kosza się zmienił. Otwórz kosz ponownie.");
    }

    public void purge(TrashEntry entry) throws Exception {
        requireSameTrashEntry(entry);
        channel.rm(literal(entry.folder + "/book"));
        tidyTrash(entry.folder);
    }

    private void tidyTrash(String folder) {
        try {
            String archived = folder + "/sidecar";
            if (optional(archived) != null) {
                for (String name : readSidecar(archived).keySet()) channel.rm(literal(archived + "/" + name));
                channel.rmdir(literal(archived));
            }
            channel.rm(literal(folder + "/origin"));
            channel.rmdir(literal(folder));
        } catch (Exception ignored) {
            // The book operation already succeeded; leftover metadata is hidden.
        }
    }

    public void delete(Entry selected) throws Exception {
        requireUnchanged(selected);
        channel.rm(literal(selected.path));
        absent(selected.path);
    }
}
