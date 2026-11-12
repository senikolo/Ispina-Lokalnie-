package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.HttpsURLConnection;

/** Installs only the verified MPlayer executable from the original Kindle 3 port. */
final class MplayerInstaller {
    private static final String ARCHIVE =
            "https://www.mobileread.com/forums/attachment.php?attachmentid=66277&d=1297101459";
    private static final String ARCHIVE_SHA256 =
            "19a7e5140e947e03d1081f2b189215d812e327a43b2fbec07dbd4cc506366a33";
    private static final String PLAYER_SHA256 =
            "40e97fdf9937e2fdad56194d8d525fba1ade2babb57e9f42fe7fce6c2465fa45";
    private static final int ARCHIVE_BYTES = 4158142;
    private static final int PLAYER_BYTES = 8588844;
    private static final String ROOT = "/mnt/us/koreader";
    private static final String DIRECTORY = ROOT + "/ilradio-bin";
    private static final String PLAYER = DIRECTORY + "/mplayer";

    private MplayerInstaller() {}

    static synchronized void install(Session session) throws Exception {
        String platform = KindleTools.run(session, "uname -m; uname -r");
        if (!platform.matches("armv6l\\n2\\.6\\.26[-.][A-Za-z0-9._-]+"))
            throw new IOException("Ten silnik jest przeznaczony dla Kindle Keyboard 3. Rozpoznano: " + platform);
        ChannelSftp files = CompanionFiles.channel(session);
        try {
            CompanionFiles.requireDirectory(files, ROOT);
            if (CompanionFiles.stat(files, DIRECTORY) == null) {
                try { files.mkdir(DIRECTORY); }
                catch (SftpException error) {
                    if (CompanionFiles.stat(files, DIRECTORY) == null) throw error;
                }
            }
            CompanionFiles.requireDirectory(files, DIRECTORY);
            SftpATTRS existing = CompanionFiles.stat(files, PLAYER);
            if (existing != null) {
                verify(files, PLAYER);
                probe(session, PLAYER);
                return;
            }
            byte[] contents = download();
            String stage = DIRECTORY + "/.mplayer-" + UUID.randomUUID();
            try {
                files.put(new ByteArrayInputStream(contents), stage);
                verify(files, stage);
                if ((files.lstat(stage).getPermissions() & 0100) == 0) files.chmod(0700, stage);
                probe(session, stage);
                VerifiedTransfer.renameWithoutReplace(files, stage, PLAYER);
            } finally {
                CompanionFiles.remove(files, stage);
            }
        } finally {
            files.disconnect();
        }
    }

    private static byte[] download() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(ARCHIVE).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200)
                throw new IOException("Serwer MobileRead nie udostępnił archiwum MPlayera.");
            try (InputStream input = connection.getInputStream()) {
                return playerFromArchive(BoundedInput.read(input, ARCHIVE_BYTES));
            }
        } finally {
            connection.disconnect();
        }
    }

    static byte[] playerFromArchive(byte[] archive) throws Exception {
        if (archive.length != ARCHIVE_BYTES
                || !ARCHIVE_SHA256.equals(VerifiedTransfer.sha256(new ByteArrayInputStream(archive))))
            throw new IOException("Archiwum MPlayera ma inną zawartość niż wersja sprawdzona dla Kindle’a.");
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!entry.getName().equals("mplayer/mplayer")) continue;
                byte[] binary = BoundedInput.read(zip, PLAYER_BYTES);
                if (binary.length == PLAYER_BYTES
                        && PLAYER_SHA256.equals(VerifiedTransfer.sha256(new ByteArrayInputStream(binary))))
                    return binary;
                break;
            }
        }
        throw new IOException("Archiwum nie zawiera sprawdzonego programu MPlayer.");
    }

    private static void verify(ChannelSftp files, String path) throws Exception {
        SftpATTRS item = CompanionFiles.stat(files, path);
        if (item != null && item.isReg() && !item.isLink() && item.getSize() == PLAYER_BYTES) {
            try (InputStream input = files.get(path)) {
                if (PLAYER_SHA256.equals(VerifiedTransfer.sha256(input))) return;
            }
        }
        throw new IOException("Kindle nie zwrócił zweryfikowanego pliku MPlayer. Istniejącego pliku nie nadpisano.");
    }

    private static void probe(Session session, String path) throws Exception {
        String output = KindleTools.run(session, "result=$(MPLAYER_HOME=" + DIRECTORY + " " + path
                + " -noconfig all -ao help 2>&1); "
                + "case \"$result\" in *MPlayer*alsa*) echo ready;; *) printf '%s\\n' \"$result\" | head -n 8;; esac");
        if (!"ready".equals(output))
            throw new IOException("MPlayer nie wystartował z obsługą ALSA. " + output);
    }
}
