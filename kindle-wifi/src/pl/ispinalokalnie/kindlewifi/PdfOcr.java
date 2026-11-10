package pl.ispinalokalnie.kindlewifi;

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

/** Lazily renders a scanned PDF page and recognizes Latin-script text offline. */
public final class PdfOcr implements Closeable {
    private final ParcelFileDescriptor descriptor;
    private final PdfRenderer renderer;
    private final TextRecognizer recognizer;

    public PdfOcr(File pdf) throws IOException {
        descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer openRenderer;
        try { openRenderer = new PdfRenderer(descriptor); }
        catch (Exception e) { descriptor.close(); throw new IOException("Nie można otworzyć obrazu PDF do OCR: " + e.getMessage(), e); }
        renderer = openRenderer;
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    public String readPage(int pageNumber) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("OCR przerwany.");
        if (pageNumber < 1 || pageNumber > renderer.getPageCount()) throw new IOException("Nieprawidłowy numer strony OCR: " + pageNumber);
        PdfRenderer.Page page = renderer.openPage(pageNumber - 1);
        Bitmap bitmap = null;
        try {
            double scale = Math.min(3.0, Math.sqrt(3_200_000.0 / ((double)page.getWidth() * page.getHeight())));
            int width = Math.max(1, (int)Math.round(page.getWidth() * scale));
            int height = Math.max(1, (int)Math.round(page.getHeight() * scale));
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            String recognized = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 120, TimeUnit.SECONDS).getText();
            return recognized == null ? "" : recognized;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("OCR nie powiódł się dla strony " + pageNumber + ": " + e.getMessage(), e);
        } finally {
            if (bitmap != null) bitmap.recycle();
            page.close();
        }
    }

    @Override public void close() throws IOException {
        recognizer.close();
        renderer.close();
        descriptor.close();
    }
}
