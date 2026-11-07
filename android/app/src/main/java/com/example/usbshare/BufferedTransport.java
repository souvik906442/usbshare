package com.example.usbshare;

import java.io.IOException;

/**
 * Base class for both USB transports.
 * The accessory driver (and USB bulk reads) deliver whole packets, and bytes of a packet that do not fit
 * the caller's buffer would be lost - so every raw read uses a full 16 KB buffer and we serve callers from it.
 */
public abstract class BufferedTransport implements Transport {
    public static final int PACKET = 16384;

    protected volatile boolean closed = false;
    private final byte[] rbuf = new byte[PACKET];
    private int rpos = 0;
    private int rlen = 0;

    /** Read one USB transfer (up to buf.length bytes). Return -1 when the link is gone. */
    protected abstract int rawRead(byte[] buf) throws IOException;

    /** Write bytes as USB transfers (split at 16 KB). */
    protected abstract void rawWrite(byte[] b, int off, int len) throws IOException;

    protected abstract void onClose();

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (rpos >= rlen) {
            if (closed) return -1;
            int n = rawRead(rbuf);
            if (n < 0) return -1;
            rpos = 0;
            rlen = n;
        }
        int c = Math.min(len, rlen - rpos);
        System.arraycopy(rbuf, rpos, b, off, c);
        rpos += c;
        return c;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("link closed");
        rawWrite(b, off, len);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        onClose();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
