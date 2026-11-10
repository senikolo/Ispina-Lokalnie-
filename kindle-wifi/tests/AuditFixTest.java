package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AuditFixTest {
    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath();
        Path docs = Files.createDirectory(base.resolve("audit-documents"));
        Path outside = Files.createDirectory(base.resolve("audit-outside"));
        Path input = Files.writeString(base.resolve("audit-upload.epub"), "new verified book");
        JSch jsch = new JSch();
        jsch.addIdentity(base.resolve("server/client").toString());
        Session session = jsch.getSession(System.getProperty("user.name"), "127.0.0.1", 22222);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(5000);
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(5000);
        try {
            Path target = docs.resolve("same-name.epub");
            String published = VerifiedTransfer.upload(channel, input.toFile(), "same-name.epub", docs.toString(), status -> {
                if (status.startsWith("Przesyłam")) {
                    try { Files.writeString(target, "book from second sender"); }
                    catch (IOException e) { throw new UncheckedIOException(e); }
                }
            });
            if (!"book from second sender".equals(Files.readString(target))
                    || !"new verified book".equals(Files.readString(Path.of(published))))
                throw new AssertionError("A transfer replaced an existing book");

            Path staged = Files.writeString(docs.resolve("staged.epub"), "staged file");
            try {
                VerifiedTransfer.renameWithoutReplace(channel, staged.toString(), target.toString());
                throw new AssertionError("SFTP rename overwrote an existing book");
            } catch (com.jcraft.jsch.SftpException expected) {
                if (!Files.exists(staged) || !"book from second sender".equals(Files.readString(target)))
                    throw new AssertionError("Unsafe rename changed one of the files");
            }

            Path link = Files.createSymbolicLink(base.resolve("linked-documents"), outside);
            try {
                VerifiedTransfer.upload(channel, input.toFile(), "redirected.epub", link.toString(), status -> {});
                throw new AssertionError("Upload followed a symbolic link");
            } catch (IOException expected) {
                if (Files.exists(outside.resolve("redirected.epub"))) throw new AssertionError("Unexpected file outside root");
            }

            Path selected = Files.writeString(docs.resolve("selected.epub"), "original selection");
            RemoteFiles library = new RemoteFiles(channel, docs.toString());
            RemoteFiles.Entry displayed = library.list(docs.toString()).stream()
                    .filter(entry -> entry.name.equals("selected.epub")).findFirst().orElseThrow();
            Files.move(selected, docs.resolve("selected-before.epub"));
            Files.writeString(selected, "replacement");
            try {
                library.delete(displayed);
                throw new AssertionError("Deleted a replacement selected under the old metadata");
            } catch (IOException expected) {
                if (!Files.exists(selected)) throw new AssertionError("Replacement is missing");
            }
        } finally {
            channel.disconnect();
            session.disconnect();
        }
        System.out.println("PASS: upload collision, no-replace rename, redirected directory and stale deletion.");
    }
}
