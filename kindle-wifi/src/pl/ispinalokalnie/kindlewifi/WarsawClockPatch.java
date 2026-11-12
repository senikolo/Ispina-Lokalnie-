package pl.ispinalokalnie.kindlewifi;

import android.content.Context;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

final class WarsawClockPatch {
    private static final String ROOT = "/mnt/us/koreader";
    private static final String DIRECTORY = ROOT + "/patches";
    private static final String FILE = "1-warszawski-czas.lua";
    private static final String TARGET = DIRECTORY + "/" + FILE;

    enum Status { MISSING, INSTALLED, CONFLICT }
    enum Action { CHECK, INSTALL, REMOVE }

    static Status run(Context context, Session session, Action action) throws Exception {
        byte[] contents;
        try (InputStream asset = context.getAssets().open("patches/" + FILE)) {
            contents = BoundedInput.read(asset, 4096);
        }
        ChannelSftp sftp = CompanionFiles.channel(session);
        try { return run(sftp, action, contents); }
        finally { sftp.disconnect(); }
    }

    static Status run(ChannelSftp sftp, Action action, byte[] contents) throws Exception {
        CompanionFiles.requireDirectory(sftp, ROOT);
        if (CompanionFiles.stat(sftp, DIRECTORY) == null) {
            if (action != Action.INSTALL) return Status.MISSING;
            try { sftp.mkdir(DIRECTORY); }
            catch (SftpException error) {
                if (CompanionFiles.stat(sftp, DIRECTORY) == null) throw error;
            }
        }
        CompanionFiles.requireDirectory(sftp, DIRECTORY);
        Status status = status(sftp, contents);
        if (action == Action.INSTALL && status == Status.MISSING) {
            String stage = DIRECTORY + "/.kindlewifi-clock-" + UUID.randomUUID() + ".part";
            try {
                CompanionFiles.putVerified(sftp, stage, contents);
                try { VerifiedTransfer.renameWithoutReplace(sftp, stage, TARGET); }
                catch (SftpException error) {
                    Status current = status(sftp, contents);
                    if (current == Status.MISSING) throw error;
                    return current;
                }
            } finally {
                CompanionFiles.remove(sftp, stage);
            }
            if (status(sftp, contents) != Status.INSTALLED)
                throw new IOException("Nie potwierdzono zapisu poprawki zegara.");
            return Status.INSTALLED;
        }
        if (action == Action.REMOVE && status == Status.INSTALLED) {
            try { sftp.rm(TARGET); }
            catch (SftpException error) {
                if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) throw error;
            }
            if (CompanionFiles.stat(sftp, TARGET) != null)
                throw new IOException("Nie potwierdzono usunięcia poprawki zegara.");
            return Status.MISSING;
        }
        return status;
    }

    private static Status status(ChannelSftp sftp, byte[] contents) throws Exception {
        if (CompanionFiles.stat(sftp, TARGET) == null) return Status.MISSING;
        return CompanionFiles.matches(sftp, TARGET, contents) ? Status.INSTALLED : Status.CONFLICT;
    }
}
