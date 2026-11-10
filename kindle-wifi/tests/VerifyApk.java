import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
public class VerifyApk {
 public static void main(String[] args) throws Exception {
  byte[] first = null;
  for (String path : args) {
   ApkVerifier.Result r = new ApkVerifier.Builder(new File(path)).build().verify();
   if (!r.isVerified()) throw new AssertionError(r.getErrors().toString());
   byte[] cert = r.getSignerCertificates().get(0).getEncoded();
   if (first == null) first = cert;
   else if (!MessageDigest.isEqual(first, cert)) throw new AssertionError("Different signing key");
   System.out.println("PASS APK signature: " + path);
  }
  System.out.println("PASS: same signing certificate, upgrade compatible");
 }
}
