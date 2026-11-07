package com.example.usbshare;

import java.util.Locale;

/** One file found by a scan (photo/video list or "recent files" list). path is absolute. */
public class FileItem {
    public static final int OTHER = 0, IMAGE = 1, VIDEO = 2;

    public final String path;
    public final long mtime;
    public final long size;
    public final int type;

    public FileItem(String path, long mtime, long size, int type) {
        this.path = path;
        this.mtime = mtime;
        this.size = size;
        this.type = type;
    }

    public String name() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public String parent() {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    public static int typeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return OTHER;
        switch (name.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "jpg": case "jpeg": case "png": case "gif": case "webp":
            case "bmp": case "heic": case "heif": case "dng":
                return IMAGE;
            case "mp4": case "mkv": case "mov": case "3gp": case "webm":
            case "avi": case "m4v": case "3gpp":
                return VIDEO;
            default:
                return OTHER;
        }
    }
}
