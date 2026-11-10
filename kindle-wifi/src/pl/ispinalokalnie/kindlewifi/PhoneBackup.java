package pl.ispinalokalnie.kindlewifi;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class PhoneBackup {
    private final ContentResolver resolver;
    private final Uri folder;
    private final Set<String> occupied = new HashSet<>();

    PhoneBackup(ContentResolver resolver, Uri tree) throws IOException {
        this.resolver = resolver;
        String id = DocumentsContract.getTreeDocumentId(tree);
        folder = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor rows = resolver.query(children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (rows == null) throw new IOException("Nie można odczytać folderu na telefonie.");
            while (rows.moveToNext()) {
                String name = rows.getString(0);
                if (name != null) occupied.add(name.toLowerCase(Locale.ROOT));
            }
        }
    }

    String save(File source, String original) throws IOException {
        String name = available(original);
        Uri target = DocumentsContract.createDocument(resolver, folder, mime(name), name);
        if (target == null) throw new IOException("Nie można utworzyć pliku na telefonie.");
        boolean complete = false;
        try {
            try (InputStream input = new FileInputStream(source); OutputStream output = resolver.openOutputStream(target)) {
                if (output == null) throw new IOException("Nie można zapisać pliku na telefonie.");
                byte[] buffer = new byte[32768];
                for (int n; (n = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Pobieranie przerwane.");
                    output.write(buffer, 0, n);
                }
            }
            complete = true;
            occupied.add(name.toLowerCase(Locale.ROOT));
            return name;
        } finally {
            if (!complete) {
                try { DocumentsContract.deleteDocument(resolver, target); }
                catch (Exception ignored) {}
            }
        }
    }

    private String available(String name) {
        if (!occupied.contains(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + " (" + n + ")" + suffix;
            if (!occupied.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }

    private static String mime(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".epub")) return "application/epub+zip";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".mobi")) return "application/x-mobipocket-ebook";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
