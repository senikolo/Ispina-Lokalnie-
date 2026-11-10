package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import java.nio.file.Files;
import java.nio.file.Path;

public class LibraryFeaturesTest {
    public static void main(String[] args) throws Exception {
        Path work = Path.of(args[0]).toAbsolutePath();
        Path docs = Files.createDirectory(work.resolve("library-213"));
        Path original = Files.writeString(docs.resolve("Ispina.epub"), "original book");
        Path matching = Files.writeString(work.resolve("copy.epub"), "original book");
        Path other = Files.writeString(work.resolve("different.epub"), "different txt");
        JSch ssh = new JSch();
        ssh.addIdentity(work.resolve("server/client").toString());
        var session = ssh.getSession(System.getProperty("user.name"), "127.0.0.1", 22222);
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(5000);
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(5000);
        try {
            var remote = new RemoteFiles(channel, docs.toString());
            if (!original.toString().equals(VerifiedTransfer.identical(channel, matching.toFile(), docs.toString()))
                    || VerifiedTransfer.identical(channel, other.toFile(), docs.toString()) != null)
                throw new AssertionError("Wrong duplicate decision");
            var item = remote.list(docs.toString()).get(0);
            Path downloaded = work.resolve("download.epub");
            remote.download(item, downloaded.toFile());
            if (!Files.readString(downloaded).equals("original book")) throw new AssertionError("Backup bytes changed");
            remote.trash(item);
            if (Files.exists(original) || !remote.list(docs.toString()).isEmpty()) throw new AssertionError("Book still in library");
            var deleted = remote.trashContents().get(0);
            Files.writeString(original, "replacement");
            try {
                remote.restore(deleted);
                throw new AssertionError("Restore overwrote an existing book");
            } catch (java.io.IOException expected) {
                if (!Files.readString(original).equals("replacement")) throw new AssertionError("Replacement damaged");
            }
            Files.delete(original);
            remote.restore(deleted);
            if (!Files.readString(original).equals("original book") || !remote.trashContents().isEmpty())
                throw new AssertionError("Restore did not return original book");
            remote.trash(remote.list(docs.toString()).get(0));
            remote.purge(remote.trashContents().get(0));
            if (Files.exists(original) || !remote.trashContents().isEmpty()) throw new AssertionError("Purge left a book");
        } finally {
            channel.disconnect();
            session.disconnect();
        }
        System.out.println("PASS: identical files, size-matched differences, download, trash, protected restore and permanent deletion.");
    }
}
