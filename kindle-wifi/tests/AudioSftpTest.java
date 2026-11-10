package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AudioSftpTest {
    private static void check(boolean valid, String label) {
        if (!valid) throw new AssertionError(label);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        JSch ssh = new JSch();
        ssh.addIdentity(root.resolve("server/client").toString());
        ssh.setKnownHosts(root.resolve("server/known_hosts").toString());
        Session session = ssh.getSession(System.getProperty("user.name"), "127.0.0.1", Integer.parseInt(args[1]));
        session.setConfig("StrictHostKeyChecking", "yes");
        session.connect(5000);
        ChannelSftp channel = CompanionFiles.channel(session);
        try {
            audio(channel, root);
            radio(channel, root);
        } finally {
            channel.disconnect();
            session.disconnect();
        }
    }

    private static void audio(ChannelSftp channel, Path root) throws Exception {
        AudioFiles files = new AudioFiles(channel, root.toString());
        files.ensureRoots();
        String music = root.resolve("music").toString();
        String books = root.resolve("audiobooks").toString();
        files.mkdir(books, "Podróże [1]");
        String target = books + "/Podróże [1]";
        byte[] original = {10, 20, 30, 40};
        Path sample = Files.write(root.resolve("sample.mp3"), original);
        AudioFiles.Upload attempt = new AudioFiles.Upload();
        String saved = files.upload(sample.toFile(), "Zażółć.mp3", music, message -> {}, percent -> {}, attempt, false);
        check(saved.equals(files.upload(sample.toFile(), "Zażółć.mp3", music, message -> {}, percent -> {}, attempt, true)),
                "retry must confirm the same stored path");
        files.move(saved, target);
        String moved = target + "/Zażółć.mp3";
        files.rename(moved, "Rozdział 01.mp3");
        AudioFiles.Entry entry = files.list(target).get(0);
        check(entry.bytes == 4 && entry.name.equals("Rozdział 01.mp3"), "size and Unicode name");
        files.delete(entry);
        check(files.list(target).isEmpty(), "deletion confirmed");
        System.out.println("PASS real SSH/SFTP: upload, confirmation after retry, Unicode move and rename, checked delete");
    }

    private static void radio(ChannelSftp channel, Path root) throws Exception {
        Path parent = Files.createDirectory(root.resolve("koreader")).resolve("plugins");
        Path active = parent.resolve("ilradio_k3.koplugin");
        Map<String, byte[]> v1 = bundle("previous");
        Map<String, byte[]> v2 = bundle("current");
        RadioInstaller.RemoteCommand noFallback = command -> {
            throw new AssertionError("OpenSSH should support regular directory rename: " + command);
        };
        RadioInstaller.install(channel, parent.toString(), v1, noFallback);
        check(Files.readString(active.resolve("main.lua")).equals("previous"), "initial installation");
        RadioInstaller.install(channel, parent.toString(), v2, noFallback);
        check(Files.readString(active.resolve("main.lua")).equals("current"), "update");
        RadioInstaller.change(channel, parent.toString(), v2.get("_meta.lua"), true, noFallback);
        check(Files.readString(active.resolve("main.lua")).equals("previous"), "restore older bundle");
        RadioInstaller.change(channel, parent.toString(), v2.get("_meta.lua"), false, noFallback);
        check(!Files.exists(active), "disabled");
        RadioInstaller.change(channel, parent.toString(), v2.get("_meta.lua"), true, noFallback);
        check(Files.readString(active.resolve("main.lua")).equals("previous"), "restore disabled plugin");
        Files.writeString(active.resolve("_meta.lua"), "foreign plugin");
        try {
            RadioInstaller.install(channel, parent.toString(), v2, noFallback);
            throw new AssertionError("Foreign plugin was overwritten");
        } catch (java.io.IOException expected) {
            check(Files.readString(active.resolve("_meta.lua")).equals("foreign plugin"), "foreign plugin intact");
        }
        System.out.println("PASS real SSH/SFTP: install, update, restore, remove, preserve an unrelated plugin");
    }

    private static Map<String, byte[]> bundle(String version) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : new String[]{"_meta.lua", "stations.lua", "player.sh", "main.lua"}) {
            result.put(name, (name.equals("main.lua") ? version : name).getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }
}
