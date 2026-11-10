import pl.ispinalokalnie.kindlewifi.VerifiedTransfer;
import com.jcraft.jsch.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class TransferTest {
  public static void main(String[] args) throws Exception {
    Path root = Paths.get(args[0]).toAbsolutePath();
    JSch ssh = new JSch(); ssh.setKnownHosts(root.resolve("server/known_hosts").toString());
    ssh.addIdentity(root.resolve("server/client").toString());
    Session session = ssh.getSession(System.getProperty("user.name"), "127.0.0.1", 22222);
    session.setConfig("StrictHostKeyChecking", "yes");
    session.connect(5000);
    ChannelSftp ch = (ChannelSftp)session.openChannel("sftp"); ch.connect(5000);
    try {
      byte[] content = new byte[2000000]; new java.util.Random(17).nextBytes(content);
      File source = root.resolve("sample.epub").toFile(); Files.write(source.toPath(), content);
      String dest = root.resolve("documents").toString();
      String name = "Zażółć gęślą [Ispina].epub";
      java.util.List<Integer> percentages = new java.util.ArrayList<>();
      String first = VerifiedTransfer.upload(ch, source, name, dest, System.out::println, percentages::add);
      if (percentages.get(0) != 0 || percentages.get(percentages.size()-1) != 100)
          throw new AssertionError("missing progress endpoints");
      for (int i = 1; i < percentages.size(); i++)
          if (percentages.get(i) < percentages.get(i-1)) throw new AssertionError("progress goes backwards");
      if (!percentages.contains(50) || !percentages.contains(99)) throw new AssertionError("verification progress missing");
      System.out.println("PASS: monotonic progress 0–100 including verification");
      if (!Arrays.equals(content, Files.readAllBytes(Paths.get(first)))) throw new AssertionError("bytes");
      System.out.println("PASS: 2 MB upload, Unicode, spaces, read-back SHA-256");
      String second = VerifiedTransfer.upload(ch, source, name, dest, x -> {});
      if (first.equals(second) || !second.endsWith(" (2).epub")) throw new AssertionError("duplicate");
      System.out.println("PASS: duplicate preserved without overwriting");
      String listing = VerifiedTransfer.list(ch, dest);
      if (!listing.contains(name) || !listing.contains("2000000 B")) throw new AssertionError("listing");
      System.out.println("PASS: server file listing confirms name and size");
      byte[] corrupted = content.clone(); corrupted[12] ^= 42; Files.write(Paths.get(first), corrupted);
      try { VerifiedTransfer.verify(ch, source, first); throw new AssertionError("corruption accepted"); }
      catch (IOException expected) { System.out.println("PASS: equal-size corruption rejected"); }
      Files.write(Paths.get(first), new byte[15]);
      try { VerifiedTransfer.verify(ch, source, first); throw new AssertionError("truncation accepted"); }
      catch (IOException expected) { System.out.println("PASS: truncated file rejected"); }
      File empty = root.resolve("empty.epub").toFile(); Files.write(empty.toPath(), new byte[0]);
      try { VerifiedTransfer.upload(ch, empty, "empty.epub", dest, x -> {}); throw new AssertionError("empty accepted"); }
      catch (IOException expected) { System.out.println("PASS: empty source rejected"); }
      try { VerifiedTransfer.upload(ch, source, "book.epub", dest + "/missing", x -> {}); throw new AssertionError("missing accepted"); }
      catch (SftpException expected) { System.out.println("PASS: missing destination rejected, no fallback"); }
      if (Files.list(Paths.get(dest)).anyMatch(p -> p.toString().endsWith(".part"))) throw new AssertionError("partial remains");
      System.out.println("ALL TRANSFER TESTS PASSED");
    } finally { ch.disconnect(); session.disconnect(); }
  }
}
