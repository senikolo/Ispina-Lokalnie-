package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.UUID;

public final class RecoveryTest {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path docs = Files.createDirectory(root.resolve("recovery-docs"));
        Path source = Files.writeString(root.resolve("local.tmp"), "bytes of a test book");
        String hash;
        try (InputStream input = Files.newInputStream(source)) { hash = VerifiedTransfer.sha256(input); }
        String directory = docs.toString();
        JSch ssh = new JSch();
        ssh.addIdentity(root.resolve("server/client").toString());
        UUID attempt = UUID.randomUUID();
        Path partial = docs.resolve(".kindle-upload-" + attempt + ".part");
        Session first = connect(ssh);
        try {
            VerifiedTransfer.upload(open(first), source.toFile(), "Book.epub", directory,
                    step -> { if (step.startsWith("Sprawdzam")) first.disconnect(); },
                    percent -> {}, attempt);
            throw new AssertionError("Disconnected transfer reported success");
        } catch (IOException | SftpException expected) {
            require(Files.isRegularFile(partial) && !Files.exists(docs.resolve("Book.epub")), "Interrupted upload staging");
        } finally {
            first.disconnect();
        }

        Session retry = connect(ssh);
        try {
            ChannelSftp channel = open(retry);
            Files.copy(source, docs.resolve("Book.bak"));
            require(VerifiedTransfer.identical(channel, source.toFile(), directory, "Book.epub") == null, "Wrong extension");
            VerifiedTransfer.removeAttempt(channel, directory, attempt);
            require(!Files.exists(partial), "Own interrupted file remains");
            String path = VerifiedTransfer.upload(channel, source.toFile(), "Book.epub", directory, step -> {});
            require(path.equals(VerifiedTransfer.identical(channel, source.toFile(), directory, "Book.EPUB")), "Duplicate not confirmed");
            require(VerifiedTransfer.matches(channel, directory, path, hash), "Valid receipt");
            Files.writeString(Path.of(path), "other contents here!");
            require(!VerifiedTransfer.matches(channel, directory, path, hash), "Changed book accepted");
            Files.delete(Path.of(path));
            require(!VerifiedTransfer.matches(channel, directory, path, hash), "Missing book accepted");

            Path abandoned = Files.writeString(docs.resolve(".kindle-upload-" + UUID.randomUUID() + ".part"), "old");
            Path active = Files.writeString(docs.resolve(".kindle-upload-" + UUID.randomUUID() + ".part"), "active");
            Files.setLastModifiedTime(abandoned, FileTime.from(Instant.now().minusSeconds(100000)));
            VerifiedTransfer.cleanAbandoned(channel, directory);
            require(!Files.exists(abandoned) && Files.exists(active), "Cleanup of old and fresh uploads");

            Path previous = Files.createDirectory(docs.resolve("previous"));
            Files.writeString(previous.resolve("Original.epub"), "original");
            RemoteFiles remote = new RemoteFiles(channel, directory);
            remote.trash(remote.list(previous.toString()).get(0));
            Files.delete(previous);
            RemoteFiles.TrashEntry entry = remote.trashContents().get(0);
            remote.restore(entry, directory, "Restored.epub");
            require(Files.readString(docs.resolve("Restored.epub")).equals("original"), "Alternate restore");
            remote.trash(remote.list(directory).get(0));
            entry = remote.trashContents().get(0);
            Files.writeString(Path.of(entry.folder).resolve("book"), "replaced payload");
            try {
                remote.purge(entry);
                throw new AssertionError("Stale trash selection deleted");
            } catch (IOException expected) {
                require(Files.exists(Path.of(entry.folder).resolve("book")), "Replacement was deleted");
            }
        } finally {
            retry.disconnect();
        }

        Path phone = Files.createDirectory(root.resolve("phone-recovery"));
        TransferQueue queue = new TransferQueue(phone.toFile());
        var ready = queue.add("Ready.epub", new ByteArrayInputStream(new byte[]{1, 2, 3}));
        Path unfinished = Files.createDirectory(phone.resolve("transfer-queue").resolve(UUID.randomUUID().toString()));
        Files.writeString(unfinished.resolve("book.part"), "abandoned");
        queue.cleanAbandoned();
        require(!Files.exists(unfinished) && queue.files().equals(java.util.List.of(ready)), "Local queue cleanup");
        System.out.println("PASS: disconnect and retry, remote and local cleanup, format-aware duplicate check, stale receipt, alternate restore, stale trash protection.");
    }

    private static Session connect(JSch ssh) throws Exception {
        Session session = ssh.getSession("root", "127.0.0.1", 22222);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(5000);
        return session;
    }

    private static ChannelSftp open(Session session) throws Exception {
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(5000);
        return channel;
    }

    private static void require(boolean condition, String test) {
        if (!condition) throw new AssertionError(test);
    }
}
