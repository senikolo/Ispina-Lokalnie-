package pl.ispinalokalnie.kindlewifi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

/** Bounded, interruptible reads shared by the radio and mail plugin installers. */
final class BoundedInput {
    private BoundedInput() { }

    static byte[] read(InputStream input, int maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("Invalid maximum file size");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Przerwano odczyt pliku.");
            int n = input.read(buffer);
            if (n == -1) break;
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Przerwano odczyt pliku.");
            if (n > maxBytes - output.size())
                throw new IOException("Plik przekracza dozwolony rozmiar: " + maxBytes + " bajtów.");
            if (n > 0) output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }
}
