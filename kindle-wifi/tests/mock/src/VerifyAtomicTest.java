import pl.ispinalokalnie.kindlewifi.VerifiedTransfer;
import com.jcraft.jsch.ChannelSftp;
import java.io.*;import java.nio.file.*;
public class VerifyAtomicTest {
 public static void main(String[] ignored)throws Exception {
  Path tmp=Files.createTempDirectory("kindle-atomic-");
  File input=tmp.resolve("source.epub").toFile();byte[] bytes=new byte[1_000_000];new java.util.Random(8).nextBytes(bytes);Files.write(input.toPath(),bytes);
  Path documents=Files.createDirectory(tmp.resolve("documents"));
  ChannelSftp remote=new ChannelSftp(documents);remote.corrupt=true;
  try {VerifiedTransfer.upload(remote,input,"książka.epub",documents.toString(),x->{});throw new AssertionError("Accepted corrupted book");}
  catch(IOException expected) { System.out.println("PASS: corrupted transfer rejected"); }
  try(java.util.stream.Stream<Path> stream=Files.list(documents)) {
   if(stream.findAny().isPresent()) throw new AssertionError("corrupt final or partial remains");
  }
  System.out.println("PASS: no corrupt final or temporary file");
  remote.corrupt=false;
  String first=VerifiedTransfer.upload(remote,input,"książka.epub",documents.toString(),x->{});
  if(!first.endsWith("/książka.epub")) throw new AssertionError("resend got duplicate suffix: "+first);
  if(!java.util.Arrays.equals(Files.readAllBytes(Paths.get(first)),bytes))throw new AssertionError("bad data");
  System.out.println("PASS: retry retains original filename and exact contents");
  String second=VerifiedTransfer.upload(remote,input,"książka.epub",documents.toString(),x->{});
  if(!second.endsWith("/książka (2).epub"))throw new AssertionError("duplicate not preserved");
  System.out.println("PASS: existing book never overwritten");
  Thread.currentThread().interrupt();
  try {VerifiedTransfer.upload(remote,input,"cancel.epub",documents.toString(),x->{});throw new AssertionError("cancel ignored");}
  catch(Exception expected) {System.out.println("PASS: interruption aborts transfer");}
  finally {Thread.interrupted();}
  if(Files.exists(documents.resolve("cancel.epub")))throw new AssertionError("Cancelled book exposed as finished");
  System.out.println("ALL ATOMIC TRANSFER TESTS PASSED");
 }
}
