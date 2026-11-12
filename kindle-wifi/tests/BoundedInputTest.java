package pl.ispinalokalnie.kindlewifi;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class BoundedInputTest {
    public static void main(String[] args) throws Exception {
        byte[] book = "EPUB-TEST".getBytes(StandardCharsets.UTF_8);
        byte[] result = BoundedInput.read(new ByteArrayInputStream(book), book.length);
        if (!java.util.Arrays.equals(book, result))
            throw new AssertionError("Exact limit must preserve the whole file");

        boolean failed = false;
        try {
            BoundedInput.read(new ByteArrayInputStream(book), book.length - 1);
        } catch (IOException expected) {
            failed = true;
        }
        if (!failed) throw new AssertionError("Oversized plugin must be rejected");

        if (BoundedInput.read(new ByteArrayInputStream(new byte[0]), 0).length != 0)
            throw new AssertionError("Empty file must be read successfully");

        Thread.currentThread().interrupt();
        try {
            BoundedInput.read(new ByteArrayInputStream(book), book.length);
            throw new AssertionError("Interrupted read should stop");
        } catch (java.io.InterruptedIOException expected) {
            // Intended cancellation.
        } finally {
            Thread.interrupted();
        }
        System.out.println("PASS: bounded stream shared by Radio and Mail installers");
    }
}
