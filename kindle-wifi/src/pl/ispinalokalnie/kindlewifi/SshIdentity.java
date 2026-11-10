package pl.ispinalokalnie.kindlewifi;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class SshIdentity {
    private final File privateKey;

    SshIdentity(File directory) {
        privateKey = new File(directory, "kindle-user-key");
    }

    synchronized String publicKey() throws Exception {
        if (!privateKey.isFile()) {
            File temporary = File.createTempFile("kindle-key-", ".tmp", privateKey.getParentFile());
            try {
                KeyPair generated = KeyPair.genKeyPair(new JSch(), KeyPair.RSA, 2048);
                try {
                    generated.writePrivateKey(temporary.getPath());
                } finally {
                    generated.dispose();
                }
                KeyPair verified = KeyPair.load(new JSch(), temporary.getPath());
                verified.dispose();
                if (temporary.length() == 0 || !temporary.renameTo(privateKey))
                    throw new IOException("Nie udało się zapisać klucza w aplikacji.");
            } finally {
                temporary.delete();
            }
        }
        KeyPair key = KeyPair.load(new JSch(), privateKey.getPath());
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            key.writePublicKey(output, "Kindle-przez-WiFi");
            return new String(output.toByteArray(), StandardCharsets.US_ASCII).trim();
        } finally {
            key.dispose();
        }
    }

    void addTo(JSch ssh) throws Exception {
        if (!privateKey.isFile()) throw new IOException("Utwórz klucz w ustawieniach połączenia.");
        ssh.addIdentity(privateKey.getPath());
    }
}
