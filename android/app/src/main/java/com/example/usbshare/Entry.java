package com.example.usbshare;

/** One row of a folder listing (local or from the other phone). */
public class Entry {
    public final String name;
    public final boolean dir;
    public final long size;

    public Entry(String name, boolean dir, long size) {
        this.name = name;
        this.dir = dir;
        this.size = size;
    }
}
