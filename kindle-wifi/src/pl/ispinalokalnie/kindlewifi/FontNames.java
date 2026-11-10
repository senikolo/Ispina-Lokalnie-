package pl.ispinalokalnie.kindlewifi;

import java.io.File;
import java.io.RandomAccessFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Reads only bounded SFNT name records; no filename-based font substitutions. */
final class FontNames {
    static Set<String> read(File file) throws IOException {
        Set<String> full = new LinkedHashSet<>(), family = new LinkedHashSet<>();
        boolean regular = false;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long length = in.length();
            if (length < 12) return full;
            int signature = in.readInt();
            if (signature != 0x00010000 && signature != 0x4f54544f && signature != 0x74727565) return full;
            int tables = in.readUnsignedShort();
            if (tables > 256 || 12L + tables * 16L > length) return full;
            long offset = -1, size = 0;
            for (int i = 0; i < tables; i++) {
                in.seek(12L + 16L * i);
                int tag = in.readInt(); in.readInt();
                long at = Integer.toUnsignedLong(in.readInt());
                long bytes = Integer.toUnsignedLong(in.readInt());
                if (tag == 0x6e616d65) { offset = at; size = bytes; }
            }
            if (offset < 0 || size < 6 || offset + size > length) return full;
            in.seek(offset); in.readUnsignedShort();
            int count = in.readUnsignedShort(), strings = in.readUnsignedShort();
            if (count > 4096 || 6L + count * 12L > size || strings > size) return full;
            for (int i = 0; i < count; i++) {
                in.seek(offset + 6L + i * 12L);
                int platform = in.readUnsignedShort(); in.readUnsignedShort();
                in.readUnsignedShort(); int id = in.readUnsignedShort();
                int bytes = in.readUnsignedShort(), at = in.readUnsignedShort();
                if ((platform != 0 && platform != 3) || bytes == 0 || bytes > 1024
                        || (bytes & 1) != 0 || strings + (long) at + bytes > size) continue;
                in.seek(offset + strings + at);
                byte[] raw = new byte[bytes]; in.readFully(raw);
                String name = new String(raw, StandardCharsets.UTF_16BE).trim();
                if (name.isEmpty() || name.matches("(?s).*[\\p{Cntrl}].*")) continue;
                if (id == 4 || id == 6) full.add(name);
                if (id == 1 || id == 16) family.add(name);
                if ((id == 2 || id == 17) && name.matches("(?i)regular|normal|roman|book")) regular = true;
            }
        }
        if (regular) full.addAll(family);
        return full;
    }
}
