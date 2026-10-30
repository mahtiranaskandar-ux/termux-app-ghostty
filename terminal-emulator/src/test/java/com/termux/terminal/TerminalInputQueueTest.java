package com.termux.terminal;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class TerminalInputQueueTest {
    @Test(timeout = 3000)
    public void longPasteDoesNotWaitForConsumerAndPreservesOrder() throws Exception {
        TerminalInputQueue queue = new TerminalInputQueue();
        byte[] prefix = "\033[200~".getBytes(StandardCharsets.UTF_8);
        byte[] paste = new byte[1024 * 1024];
        paste[0] = 42;
        byte[] suffix = "\033[201~".getBytes(StandardCharsets.UTF_8);
        queue.write(prefix, 0, prefix.length);
        queue.write(paste, 0, paste.length);
        queue.write(suffix, 0, suffix.length);
        byte[] reusedKeyBuffer = {1, 2, 3};
        queue.write(reusedKeyBuffer, 1, 1);
        reusedKeyBuffer[1] = 99;
        assertArrayEquals(prefix, queue.read());
        assertArrayEquals(paste, queue.read());
        assertArrayEquals(suffix, queue.read());
        assertArrayEquals(new byte[] {2}, queue.read());
        queue.close();
        queue.write(paste, 0, paste.length);
        assertNull(queue.read());
    }

    @Test(timeout = 3000)
    public void closeReleasesWaitingWriterAndDropsPendingInput() throws Exception {
        TerminalInputQueue queue = new TerminalInputQueue();
        CountDownLatch started = new CountDownLatch(1);
        FutureTask<byte[]> read = new FutureTask<>(() -> {
            started.countDown();
            return queue.read();
        });
        Thread thread = new Thread(read);
        thread.setDaemon(true);
        thread.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        queue.close();
        assertNull(read.get(1, TimeUnit.SECONDS));

        TerminalInputQueue pending = new TerminalInputQueue();
        pending.write(new byte[] {1}, 0, 1);
        pending.close();
        assertNull(pending.read());
    }
}
