import pl.ispinalokalnie.kindlewifi.RemoteFiles;
import com.jcraft.jsch.*;
import java.nio.file.*;
import java.util.*;
public class RemoteFilesTest {
 interface Throwing {void run() throws Exception;}
 static void rejected(Throwing task)throws Exception{try{task.run();}catch(Exception e){return;}throw new AssertionError("Unsafe operation accepted");}
 static RemoteFiles.Entry snapshot(Path path) {
  return new RemoteFiles.Entry(path.getFileName().toString(), path.toString(), false, 0, 0);
 }
 public static void main(String[] args)throws Exception{
  Path work=Paths.get(args[0]).toAbsolutePath();Path docs=Files.createTempDirectory(work,"documents-");Path books=Files.createTempDirectory(work,"Books-");
  JSch ssh=new JSch();ssh.setKnownHosts(work.resolve("server/known_hosts").toString());ssh.addIdentity(work.resolve("server/client").toString());
  Session session=ssh.getSession(System.getProperty("user.name"),"127.0.0.1",22222);session.setConfig("StrictHostKeyChecking","yes");session.connect(5000);
  ChannelSftp c=(ChannelSftp)session.openChannel("sftp");c.connect();
  try{
   RemoteFiles f=new RemoteFiles(c,docs.toString(),books.toString());
   byte[] data="Historia Ispiny".getBytes("UTF-8");
   Path a=docs.resolve("Ispina [1].epub");Files.write(a,data);
   Files.write(docs.resolve("keep.pdf"),data);Files.write(docs.resolve("settings.lua"),data);
   Files.writeString(Files.createDirectory(docs.resolve("Ispina [1].sdr")).resolve("metadata.epub.lua"),
       "return { last_page = 42, annotations = {{ text = 'Ispina' }} }");
   if(f.list(docs.toString()).size()!=2)throw new AssertionError("Visible files filter");
   f.mkdir(docs.toString(),"Polskie książki");
   f.rename(a.toString(),"Życie Ispiny.epub");
   Path renamed=docs.resolve("Życie Ispiny.epub");
   if(Files.exists(a)||!Arrays.equals(data,Files.readAllBytes(renamed)))throw new AssertionError("Rename bytes");
   System.out.println("PASS: list filters book files; folder creation; Unicode rename preserves bytes");
   Files.write(docs.resolve("taken.epub"),"existing".getBytes("UTF-8"));
   rejected(()->f.rename(renamed.toString(),"taken.epub"));
   if(!new String(Files.readAllBytes(docs.resolve("taken.epub")),"UTF-8").equals("existing"))throw new AssertionError("Overwritten");
   rejected(()->f.rename(renamed.toString(),"wrong.pdf"));
   rejected(()->f.rename(renamed.toString(),"../escape.epub"));
   rejected(()->f.mkdir(docs.toString(),"../escape"));
   rejected(()->f.delete(snapshot(docs.resolve("settings.lua"))));
   rejected(()->f.delete(snapshot(docs.resolve("Polskie książki"))));
   System.out.println("PASS: duplicate, format change, traversal, non-book deletion and folder deletion rejected");
   f.move(renamed.toString(),books.toString());
   Path moved=books.resolve(renamed.getFileName());
   if(Files.exists(renamed)||!Arrays.equals(data,Files.readAllBytes(moved)))throw new AssertionError("Move");
   f.move(moved.toString(),docs.resolve("Polskie książki").toString());
   Path finalFile=docs.resolve("Polskie książki").resolve(renamed.getFileName());
   RemoteFiles.Entry selected = f.list(finalFile.getParent().toString()).stream()
       .filter(entry -> entry.path.equals(finalFile.toString())).findFirst().orElseThrow();
   f.delete(selected);if(Files.exists(finalFile))throw new AssertionError("Delete");
   rejected(()->f.delete(selected));
   System.out.println("PASS: move across roots and nested folder; delete checked on disk; missing file fails");
   Path protectedBook = docs.resolve("Chroniona.epub");
   Files.write(protectedBook, data);
   Path sidecar = Files.createDirectory(docs.resolve("Chroniona.sdr"));
   String reading = "return { last_page = 42, annotations = {{ text = 'Ispina' }} }";
   Files.writeString(sidecar.resolve("metadata.epub.lua"), reading);
   Path conflicting = Files.createDirectory(books.resolve("Chroniona.sdr"));
   rejected(() -> f.move(protectedBook.toString(), books.toString()));
   if (!Files.isRegularFile(protectedBook) || !Files.readString(sidecar.resolve("metadata.epub.lua")).equals(reading))
       throw new AssertionError("Metadata conflict disturbed source");
   Files.delete(conflicting);
   f.move(protectedBook.toString(), books.toString());
   Path transferred = books.resolve("Chroniona.sdr/metadata.epub.lua");
   if (!Files.readString(transferred).equals(reading)) throw new AssertionError("Lost reading metadata on move");
   RemoteFiles.Entry saved = f.list(books.toString()).stream()
       .filter(entry -> entry.name.equals("Chroniona.epub")).findFirst().orElseThrow();
   f.trash(saved);
   f.restore(f.trashContents().get(0), docs.toString(), "Przywrócona.epub");
   if (!Files.readString(docs.resolve("Przywrócona.sdr/metadata.epub.lua")).equals(reading))
       throw new AssertionError("Lost reading metadata on alternate trash restore");
   System.out.println("PASS: reading metadata moved and restored, metadata conflict refused");
   Path outside=Files.createTempDirectory(work,"outside-");Files.write(outside.resolve("secret.epub"),data);
   Files.createSymbolicLink(docs.resolve("shortcut"),outside);
   Files.createSymbolicLink(docs.resolve("link.epub"),outside.resolve("secret.epub"));
   rejected(()->f.list(docs.resolve("shortcut").toString()));
   rejected(()->f.delete(snapshot(docs.resolve("link.epub"))));
   rejected(()->f.move(docs.resolve("keep.pdf").toString(),outside.toString()));
   if(!Files.exists(outside.resolve("secret.epub")))throw new AssertionError("Outside file lost");
   System.out.println("PASS: links and paths outside book roots rejected; unrelated files preserved");
   System.out.println("ALL FILE MANAGER TESTS PASSED");
  }finally{c.disconnect();session.disconnect();}
 }
}
