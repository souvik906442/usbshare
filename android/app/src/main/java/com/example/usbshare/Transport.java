package com.example.usbshare;

import java.io.IOException;

/** A byte pipe to the other phone (USB host bulk endpoints, or the accessory file descriptor). */
public interface Transport {
    /** Blocks until at least 1 byte is available. Returns -1 when the link is closed. */
    int read(byte[] b, int off, int len) throws IOException;

    /** Writes all bytes. */
    void write(byte[] b, int off, int len) throws IOException;

    void close();

    boolean isClosed();
}
