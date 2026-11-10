package pl.ispinalokalnie.kindlewifi;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.List;

public class QueueTest {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("kindle-queue-test");
        var queue = new TransferQueue(root.toFile());
        var item = queue.add("Ispina.pdf", new ByteArrayInputStream(new byte[]{1, 2, 3}));
        queue.checkpoint(item, "reader-key", "/mnt/us/documents/Ispina.pdf", null, true);
        queue = new TransferQueue(root.toFile());
        if (!queue.files().equals(List.of(item))) throw new AssertionError("Queue was lost after restart");
        var record = queue.info(item);
        if (!record.getProperty("pdf").endsWith("Ispina.pdf") || !record.getProperty("epub").isEmpty()
                || !Boolean.parseBoolean(record.getProperty("local")) || !record.getProperty("reader").equals("reader-key"))
            throw new AssertionError("Partial confirmation lost");
        try {
            queue.add("bad.pdf", new ByteArrayInputStream(new byte[0]));
            throw new AssertionError("An empty import was added");
        } catch (java.io.IOException expected) {
            if (queue.files().size() != 1) throw new AssertionError("Partial item exposed");
        }
        if (!queue.remove(item) || !new TransferQueue(root.toFile()).files().isEmpty())
            throw new AssertionError("Completed item returned after restart");
        var cancelledQueue = new TransferQueue(root.toFile());
        var reading = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        var outcome = new java.util.concurrent.FutureTask<Void>(() -> {
            try {
                cancelledQueue.add("slow.epub", new java.io.InputStream() {
                    public int read() { throw new UnsupportedOperationException(); }
                    public int read(byte[] bytes) throws java.io.IOException {
                        reading.countDown();
                        try { release.await(); }
                        catch (InterruptedException error) { throw new java.io.InterruptedIOException(); }
                        bytes[0] = 1;
                        return 1;
                    }
                }, cancelled::get);
                throw new AssertionError("Cancelled import was published");
            } catch (java.io.InterruptedIOException expected) { return null; }
        });
        Thread worker = new Thread(outcome);
        worker.setDaemon(true);
        worker.start();
        try {
            if (!reading.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Import did not start");
            var visible = new java.util.concurrent.FutureTask<>(cancelledQueue::files);
            Thread query = new Thread(visible);
            query.setDaemon(true);
            query.start();
            if (!visible.get(2, java.util.concurrent.TimeUnit.SECONDS).isEmpty())
                throw new AssertionError("In-progress file exposed");
            cancelled.set(true);
        } finally { release.countDown(); }
        outcome.get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (!cancelledQueue.files().isEmpty()) throw new AssertionError("Cancelled file exposed");
        System.out.println("PASS: blocked source does not lock the queue and cancellation prevents publication.");
        System.out.println("PASS: queue survived restart, partial PDF result preserved, invalid imports and completed items excluded.");
    }
}
