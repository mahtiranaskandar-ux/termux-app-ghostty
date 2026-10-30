package com.termux.terminal;

import java.util.ArrayDeque;
import java.util.Arrays;

/** Pending user input. Only the PTY writer waits; a full PTY must never block the UI thread. */
final class TerminalInputQueue {
    private final ArrayDeque<byte[]> mPending = new ArrayDeque<>();
    private boolean mOpen = true;

    public synchronized void write(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || offset > data.length - count) {
            throw new IndexOutOfBoundsException();
        }
        if (!mOpen || count == 0) return;
        // Callers may immediately reuse their UTF-8 buffer after this returns.
        mPending.addLast(Arrays.copyOfRange(data, offset, offset + count));
        notifyAll();
    }

    public synchronized byte[] read() throws InterruptedException {
        while (mOpen && mPending.isEmpty()) wait();
        return mOpen ? mPending.removeFirst() : null;
    }

    public synchronized void close() {
        mOpen = false;
        mPending.clear();
        notifyAll();
    }
}
