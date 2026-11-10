package pl.ispinalokalnie.kindlewifi;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Each queued book has a private copy, so temporary share permissions can expire safely. */
final class TransferQueue {
    private static final long MAX_BOOK = 512L * 1024 * 1024;
    private static final long MAX_QUEUE = 2 * MAX_BOOK;
    private final File root;

    TransferQueue(File appFiles) {
        root = new File(appFiles, "transfer-queue");
    }

    synchronized List<File> files() {
        ArrayList<File> ready = new ArrayList<>();
        File[] folders = root.listFiles();
        if (folders != null) {
            Arrays.sort(folders, Comparator.comparingLong(folder -> new File(folder, "book").lastModified()));
            for (File folder : folders) {
                File book = new File(folder, "book");
                if (book.isFile() && new File(folder, "info").isFile()) ready.add(book);
            }
        }
        return ready;
    }

    File add(String name, InputStream source) throws IOException {
        return add(name, source, () -> false);
    }

    File add(String name, InputStream source, java.util.function.BooleanSupplier cancelled) throws IOException {
        if (source == null) throw new IOException("Brak dostępu do pliku.");
        long limit = Math.min(MAX_BOOK, availableBytes());
        File folder = new File(root, UUID.randomUUID().toString());
        if (!folder.mkdirs()) throw new IOException("Brak miejsca na kolejkę.");
        File partial = new File(folder, "book.part");
        File book = new File(folder, "book");
        boolean published = false;
        try {
            try (FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768];
                long total = 0;
                while (!cancelled.getAsBoolean()) {
                    int count = source.read(buffer);
                    if (count == -1) break;
                    total += count;
                    if (total > limit) throw new IOException("Przekroczono limit pliku lub kolejki.");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            synchronized (this) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Przerwano dodawanie książki.");
                if (partial.length() == 0) throw new IOException("Wybrany plik jest pusty.");
                if (partial.length() > availableBytes()) throw new IOException("Przekroczono limit kolejki.");
                Properties info = new Properties();
                info.setProperty("name", name);
                write(folder, info);
                Files.move(partial.toPath(), book.toPath(), StandardCopyOption.ATOMIC_MOVE);
                published = true;
            }
            return book;
        } finally {
            if (!published) {
                partial.delete();
                new File(folder, "info").delete();
                folder.delete();
            }
        }
    }

    private synchronized long availableBytes() {
        long available = MAX_QUEUE;
        for (File queued : files()) available -= queued.length();
        return available;
    }

    synchronized Properties info(File book) throws IOException {
        File folder = folderFor(book);
        Properties result = new Properties();
        try (InputStream input = new FileInputStream(new File(folder, "info"))) {
            result.load(input);
        }
        return result;
    }

    synchronized void checkpoint(File book, String reader, String pdf, String epub, boolean local) throws IOException {
        Properties data = info(book);
        data.setProperty("reader", reader);
        data.setProperty("pdf", pdf == null ? "" : pdf);
        data.setProperty("epub", epub == null ? "" : epub);
        data.setProperty("local", Boolean.toString(local));
        write(folderFor(book), data);
    }

    synchronized void update(File book, Properties data) throws IOException {
        write(folderFor(book), data);
    }

    synchronized void cleanAbandoned() {
        File[] folders = root.listFiles();
        if (folders == null) return;
        for (File folder : folders) {
            if (!folder.getName().matches("[0-9a-f-]{36}")) continue;
            File book = new File(folder, "book");
            File info = new File(folder, "info");
            new File(folder, "info.part").delete();
            new File(folder, "book.part").delete();
            if (!book.isFile() || !info.isFile()) {
                book.delete();
                info.delete();
                folder.delete();
            }
        }
    }

    synchronized boolean remove(File book) throws IOException {
        File folder = folderFor(book);
        if (book.exists() && !book.delete()) return false;
        new File(folder, "info").delete();
        folder.delete();
        return true;
    }

    private File folderFor(File book) throws IOException {
        File folder = book.getParentFile();
        if (!"book".equals(book.getName()) || folder == null || !root.equals(folder.getParentFile())
                || !folder.getName().matches("[0-9a-f-]{36}"))
            throw new IOException("Nieprawidłowy wpis kolejki.");
        return folder;
    }

    private void write(File folder, Properties info) throws IOException {
        File temporary = new File(folder, "info.part");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                info.store(output, null);
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), new File(folder, "info").toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            temporary.delete();
        }
    }
}
