package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public final class AudioFilesTest {
    static void check(boolean yes, String message) { if(!yes) throw new AssertionError(message); }
    static void fail(Unsafe task) throws Exception {
        boolean blocked=false;
        try {task.run();} catch(Exception e){blocked=true;}
        check(blocked,"unsafe operation was accepted");
    }
    interface Unsafe {void run() throws Exception;}
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("kindle-audio-test");
        String music = root.resolve("music").toString();
        String books = root.resolve("audiobooks").toString();
        ChannelSftp channel = new ChannelSftp(root);
        AudioFiles f = new AudioFiles(channel, root.toString());
        f.ensureRoots();
        String dir="test-audio-"+UUID.randomUUID().toString();
        f.mkdir(music,dir);
        String src=music+"/"+dir;
        try {
            check(AudioFiles.audio("Moja książka.M4B"),"M4B");
            check(AudioFiles.audio("radio.mp3"),"MP3");
            check(!AudioFiles.audio("ebook.epub") && !AudioFiles.audio(".hidden.mp3"),"only audio");
            fail(()->AudioFiles.validName("../hack.mp3"));
            fail(()->AudioFiles.validName("bad/name.mp3"));
            Path p=Files.createTempFile("audio-test-", ".mp3");
            Files.write(p,new byte[]{1,2,3,4,5,6,7});
            try {
                String target=f.upload(p.toFile(),"test.mp3",src,text->{},num->{});
                check(target.endsWith("test.mp3"),"remote name");
                String again=f.upload(p.toFile(),"test.mp3",src,text->{},num->{});
                check(!target.equals(again),"never overwrite existing audio");
                check(f.list(src).size()==2,"listed audio");
                f.rename(target,"renamed.mp3");
                fail(()->f.rename(src+"/renamed.mp3","oops.epub"));
                fail(()->f.rename(src+"/renamed.mp3","test (2).mp3"));
                f.move(src+"/renamed.mp3",books);
                check(Files.exists(Paths.get(books,"renamed.mp3")),"moved audiobook");
                AudioFiles.Entry snapshot = f.list(books).get(0);
                Files.write(Paths.get(snapshot.path), new byte[]{1});
                fail(() -> f.delete(snapshot));
                f.delete(f.list(books).get(0));
                check(!Files.exists(Paths.get(books,"renamed.mp3")),"deleted");
                fail(()->f.list("/mnt/us/koreader/plugins"));
                fail(()->f.list(music + "/../audiobooks"));
                f.mkdir(books, "Koncerty [2026]");
                fail(() -> f.mkdir(books, "książka.SDR"));
                Path outside = root.resolve("guard.epub");
                Files.write(outside, new byte[]{42});
                Files.createSymbolicLink(Paths.get(books, "link.mp3"), outside);
                check(f.list(books).size() == 1, "symbolic link is hidden");
                check(Files.readAllBytes(outside)[0] == 42, "unrelated book remains");
                AudioFiles.Upload retry = new AudioFiles.Upload();
                String initial = f.upload(p.toFile(), "network.mp3", src, message -> {}, value -> {}, retry, false);
                String recovered = f.upload(p.toFile(), "network.mp3", src, message -> {}, value -> {}, retry, true);
                check(initial.equals(recovered), "retry verifies original publication without duplicates");
                Files.write(Paths.get(initial), new byte[]{42});
                fail(() -> f.upload(p.toFile(), "network.mp3", src, message -> {}, value -> {}, retry, true));
            } finally { Files.deleteIfExists(p); }
        }finally {
            try(java.util.stream.Stream<Path> paths=Files.list(Paths.get(src))) {
                paths.forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});
            }
            Files.deleteIfExists(Paths.get(src));
        }
        System.out.println("AudioFilesTest OK");
    }
}
