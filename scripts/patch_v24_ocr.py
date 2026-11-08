from pathlib import Path
root=Path("kindle-wifi-v5")
pkg=root/"src/pl/ispinalokalnie/kindlewifi"
converter=pkg/"PdfEpubConverter.java"
s=converter.read_text()
s=s.replace('public boolean includeImages = false;', 'public boolean includeImages = false;\n        public boolean ocrScans = true;\n        public boolean forceOcr = false;')
old='''            StringBuilder result = new StringBuilder();
            for (int page = first; page <= Math.min(first + 1, last); page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                result.append("Strona ").append(page).append(" z ").append(total).append("\\n\\n")
                      .append(cleanText(stripper.getText(doc), o)).append("\\n\\n");
            }
            String output = result.toString().trim();
            if (output.replaceAll("\\\\s", "").length() < 15) return "Brak tekstu do odczytania. PDF może być skanem i wymagać OCR.";'''
new='''            StringBuilder result = new StringBuilder();
            PdfOcr ocr = null;
            try {
                for (int page = first; page <= Math.min(first + 1, last); page++) {
                    stripper.setStartPage(page); stripper.setEndPage(page);
                    String pageText=cleanText(stripper.getText(doc),o);
                    if(o.forceOcr || (o.ocrScans && pageText.replaceAll("\\\\s","").length()<35)) {
                        if(ocr==null) ocr=new PdfOcr(pdf);
                        String recognized=cleanText(ocr.readPage(page),o);
                        if(!recognized.isEmpty() || o.forceOcr) pageText=recognized;
                    }
                    result.append("Strona ").append(page).append(" z ").append(total).append("\\n\\n")
                          .append(pageText).append("\\n\\n");
                }
            } finally { if(ocr!=null) ocr.close(); }
            String output = result.toString().trim();
            if (output.replaceAll("\\\\s", "").length() < 15) return "Nie udało się rozpoznać tekstu w podglądzie.";'''
assert old in s, "preview"
s=s.replace(old,new,1)
s=s.replace('            try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {','            PdfOcr ocr = null;\n            try (ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {',1)
old='''                    String pageText = cleanText(stripper.getText(doc), o);
                    meaningfulCharacters += pageText.replaceAll("\\\\s", "").length();'''
new='''                    String pageText = cleanText(stripper.getText(doc), o);
                    if(o.forceOcr || (o.ocrScans && pageText.replaceAll("\\\\s","").length()<35)) {
                        if(ocr==null) ocr=new PdfOcr(pdf);
                        String recognized=cleanText(ocr.readPage(p),o);
                        if(!recognized.isEmpty() || o.forceOcr) pageText=recognized;
                    }
                    meaningfulCharacters += pageText.replaceAll("\\\\s", "").length();'''
assert old in s, "convert"
s=s.replace(old,new,1)
s=s.replace('if (meaningfulCharacters < Math.min(15, count * 5)) throw new IOException("PDF nie zawiera dostępnego tekstu. Zeskanowane strony wymagają OCR; oryginalny PDF pozostaje bez zmian.");', 'if (meaningfulCharacters < Math.min(15, count * 5)) throw new IOException("OCR nie rozpoznał tekstu. Oryginalny PDF pozostał bez zmian.");')
s=s.replace('            }\n            if (!temp.renameTo(epub)) {','            } finally { if(ocr!=null) ocr.close(); }\n            if (!temp.renameTo(epub)) {',1)
converter.write_text(s)

p=pkg/"MainActivity.java"
s=p.read_text().replace("Kindle przez Wi‑Fi 2.3","Kindle przez Wi‑Fi 2.4")
old='''        CheckBox images = new CheckBox(this); images.setText("Dodaj osadzone ilustracje (większy EPUB)"); images.setChecked(false); add(fields,images,0);
        add(fields,label("PDF ze skanu bez tekstu wymaga OCR. Układ wielokolumnowy i tabele mogą nie zostać zachowane.",13,INK),8);'''
new='''        CheckBox images = new CheckBox(this); images.setText("Dodaj osadzone ilustracje (większy EPUB)"); images.setChecked(false); add(fields,images,0);
        CheckBox ocr = new CheckBox(this); ocr.setText("OCR skanowanych stron automatycznie (offline)"); ocr.setChecked(true); add(fields,ocr,4);
        CheckBox forceOcr = new CheckBox(this); forceOcr.setText("Wymuś OCR wszystkich stron (wolniej)"); forceOcr.setChecked(false); add(fields,forceOcr,0);
        add(fields,label("OCR działa bez internetu. Bardzo niewyraźne skany i tabele mogą wymagać poprawek.",13,INK),8);'''
assert old in s,"ui"
s=s.replace(old,new,1)
s=s.replace('getPdfOptions(title,author,language,first,last,hyphens,numbers,images)', 'getPdfOptions(title,author,language,first,last,hyphens,numbers,images,ocr,forceOcr)')
s=s.replace('EditText first,EditText last,CheckBox hyphens,CheckBox numbers,CheckBox images) throws IOException {','EditText first,EditText last,CheckBox hyphens,CheckBox numbers,CheckBox images,CheckBox ocr,CheckBox forceOcr) throws IOException {')
s=s.replace('o.joinHyphens=hyphens.isChecked(); o.removePageNumbers=numbers.isChecked(); o.includeImages=images.isChecked();','o.joinHyphens=hyphens.isChecked(); o.removePageNumbers=numbers.isChecked(); o.includeImages=images.isChecked();\n        o.ocrScans=ocr.isChecked();o.forceOcr=forceOcr.isChecked();')
s=s.replace('current.joinHyphens=pdfOptions.joinHyphens;current.removePageNumbers=pdfOptions.removePageNumbers;current.includeImages=pdfOptions.includeImages;', 'current.joinHyphens=pdfOptions.joinHyphens;current.removePageNumbers=pdfOptions.removePageNumbers;current.includeImages=pdfOptions.includeImages;\n            current.ocrScans=pdfOptions.ocrScans;current.forceOcr=pdfOptions.forceOcr;')
p.write_text(s)
(pkg/"PdfOcr.java").write_text(r'''package pl.ispinalokalnie.kindlewifi;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** OCR scanned PDF pages using the bundled offline Latin-script ML Kit model. */
public final class PdfOcr implements Closeable {
    private final ParcelFileDescriptor descriptor;
    private final PdfRenderer renderer;
    private final TextRecognizer recognizer;

    public PdfOcr(File pdf) throws IOException {
        descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer local;
        try { local = new PdfRenderer(descriptor); }
        catch (Exception e) { descriptor.close(); throw new IOException("Nie można odczytać PDF do OCR: " + e.getMessage(),e); }
        renderer = local;
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public String readPage(int pageNo) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Przerwano OCR.");
        if (pageNo < 1 || pageNo > renderer.getPageCount()) throw new IOException("Nieprawidłowy numer strony OCR.");
        PdfRenderer.Page page = renderer.openPage(pageNo - 1);
        Bitmap bitmap = null;
        try {
            double scale = Math.min(3.0, Math.sqrt(3200000.0 / ((double)page.getWidth()*page.getHeight())));
            int w = Math.max(1, (int)Math.round(page.getWidth()*scale));
            int h = Math.max(1, (int)Math.round(page.getHeight()*scale));
            bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            String text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)),120,TimeUnit.SECONDS).getText();
            return text == null ? "" : text;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("OCR strony " + pageNo + " nie powiódł się: " + e.getMessage(),e);
        } finally {
            if(bitmap != null) bitmap.recycle();
            page.close();
        }
    }

    @Override public void close() throws IOException {
        recognizer.close();
        renderer.close();
        descriptor.close();
    }
}
''')

p=root/"AndroidManifest.xml"
s=p.read_text().replace('package="pl.ispinalokalnie.kindlewifi.v23" ', '').replace('android:versionCode="6" android:versionName="2.3"','android:versionCode="7" android:versionName="2.4"').replace("Kindle przez Wi-Fi 2.3","Kindle przez Wi-Fi 2.4")
p.write_text(s)
(root/"settings.gradle").write_text("""pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = 'KindleOcr'
""")
(root/"build.gradle").write_text("""plugins { id 'com.android.application' version '8.6.1' }
android {
 namespace 'pl.ispinalokalnie.kindlewifi'
 compileSdk 35
 defaultConfig { applicationId 'pl.ispinalokalnie.kindlewifi.v23'; minSdk 26; targetSdk 34; versionCode 7; versionName '2.4' }
 sourceSets { main { manifest.srcFile 'AndroidManifest.xml'; java.srcDirs=['src']; res.srcDirs=['res'] } }
 buildTypes { release { minifyEnabled false } }
 packaging { resources { excludes += ['/META-INF/{AL2.0,LGPL2.1}', '/META-INF/LICENSE.md', '/META-INF/NOTICE.md'] } }
 compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }
}
dependencies {
 implementation 'com.github.mwiede:jsch:0.2.21'
 implementation 'com.tom-roush:pdfbox-android:2.0.27.0'
 implementation 'org.bouncycastle:bcprov-jdk15to18:1.72'
 implementation 'org.bouncycastle:bcpkix-jdk15to18:1.72'
 implementation 'com.google.mlkit:text-recognition:16.0.1'
}
""")
print("OCR patched")

(root/"gradle.properties").write_text("android.useAndroidX=true\norg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8\norg.gradle.workers.max=2\n")
