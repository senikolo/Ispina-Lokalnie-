import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public final class SignApk {
    public static void main(String[] args) throws Exception {
        String value = System.getenv("KINDLE_APK_KEY_PASSWORD");
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing signing password");
        char[] password = value.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream stream = new FileInputStream(args[0])) {
            store.load(stream, password);
        }
        PrivateKey key = (PrivateKey) store.getKey("kindle-wifi", password);
        X509Certificate certificate = (X509Certificate) store.getCertificate("kindle-wifi");
        ApkSigner.SignerConfig config = new ApkSigner.SignerConfig.Builder(
                "KINDLE", key, Collections.singletonList(certificate)).build();
        new ApkSigner.Builder(Collections.singletonList(config))
                .setInputApk(new File(args[1]))
                .setOutputApk(new File(args[2]))
                .setMinSdkVersion(26)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .build()
                .sign();
    }
}
