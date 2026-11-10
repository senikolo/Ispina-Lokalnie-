package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

final class RecentUploads {
    static final String PLUGIN = "kindlewifi-recent.koplugin";
    private static final String BOOKS = "/mnt/us/documents/";
    private static final String DIRECTORY = CompanionFiles.SETTINGS + "/kindlewifi-recent";

    static void record(ChannelSftp channel, String path) throws Exception {
        if (!path.startsWith(BOOKS)) throw new IOException("Nieprawidłowa ścieżka przesłanej książki.");
        String name = RemoteFiles.validName(path.substring(BOOKS.length()));
        CompanionFiles.requireDirectory(channel, CompanionFiles.SETTINGS);
        if (CompanionFiles.stat(channel, DIRECTORY) == null) {
            try { channel.mkdir(DIRECTORY); }
            catch (SftpException error) {
                if (CompanionFiles.stat(channel, DIRECTORY) == null) throw error;
            }
        }
        String key = VerifiedTransfer.sha256(new ByteArrayInputStream(name.getBytes(StandardCharsets.UTF_8))) + ".txt";
        byte[] payload = (System.currentTimeMillis() + "\n" + name).getBytes(StandardCharsets.UTF_8);
        CompanionFiles.replace(channel, DIRECTORY, key, payload);
        trim(channel);
    }

    private static void trim(ChannelSftp channel) throws SftpException {
        ArrayList<ChannelSftp.LsEntry> records = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : channel.ls(DIRECTORY)) {
            if (entry.getAttrs().isReg() && entry.getFilename().matches("[0-9a-f]{64}\\.txt"))
                records.add(entry);
        }
        records.sort(Comparator.comparingInt(entry -> entry.getAttrs().getMTime()));
        for (int i = 0; i < records.size() - 50; i++)
            CompanionFiles.remove(channel, DIRECTORY + "/" + records.get(i).getFilename());
    }
}
