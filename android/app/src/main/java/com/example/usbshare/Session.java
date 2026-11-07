package com.example.usbshare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File-transfer protocol on top of a Transport. Symmetric: both phones run the same code.
 *
 * Frame: [type u8][length u32 big endian][payload]
 *
 * Every frame is written with ONE transport write and never ends exactly on a 512-byte boundary
 * (a pad frame is appended if it would). USB bulk transfers only complete on a short packet,
 * so a frame that ended aligned would sit unseen in the other side's read buffer.
 */
public class Session {
    public interface Listener {
        void onLog(String msg);
        void onRemoteList(String path, List<Entry> entries);
        void onRemoteError(String msg);
        void onRemoteScan(int kind, List<FileItem> items);
        void onThumb(String path, byte[] jpeg);
        void onProgress(String name, long done, long total, boolean sending);
        void onFileReceived(String relPath, long size, File saved);
        void onBatchDone(boolean sending, int files, long bytes, long ms);
        void onClosed(String reason);
    }

    static final int T_PAD = 0x00;
    static final int T_HELLO = 0x01;
    static final int T_LIST_REQ = 0x10;
    static final int T_LIST_ENTRY = 0x11;
    static final int T_LIST_END = 0x12;
    static final int T_SCAN_REQ = 0x13;     // payload: [kind u8]
    static final int T_SCAN_ITEMS = 0x14;   // payload: [count i32] then count x ([mtime i64][size i64][type u8][pathLen u16][path])
    static final int T_SCAN_END = 0x15;     // payload: [kind u8]
    static final int T_THUMB_REQ = 0x16;    // payload: path
    static final int T_THUMB_DATA = 0x17;   // payload: [pathLen u16][path][jpeg]  (jpeg empty = failed)
    static final int T_ERROR = 0x1F;
    static final int T_GET_REQ = 0x20;
    static final int T_FILE_BEGIN = 0x21;
    static final int T_FILE_DATA = 0x22;
    static final int T_FILE_END = 0x23;
    static final int T_BATCH_END = 0x24;

    public static final int SCAN_MEDIA = 0;    // all photos + videos, newest first
    public static final int SCAN_RECENT = 1;   // every file, newest first (capped)
    public static final int THUMB_PX = 320;

    /** Internal storage root, and extra volumes (SD card, USB drive). Set by the app before use. */
    public static volatile File primaryRoot;
    public static volatile List<File> extraRoots = new ArrayList<>();

    static final int CHUNK = 16000;   // 5 + 16000 = 16005 < 16384, and never aligned together with CHUNK sizes
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private final Transport t;
    private final File root;
    private final File inbox;
    private final String myName;
    private final Listener l;
    private final Object writeLock = new Object();
    private final ExecutorService sender = daemonExecutor("usbshare-send");
    private final ExecutorService ctl = daemonExecutor("usbshare-ctl");
    private final ExecutorService scanner = daemonExecutor("usbshare-scan");
    private final ExecutorService thumbs = daemonExecutor("usbshare-thumb");
    private volatile boolean closed = false;

    // receive state (touched by the reader thread only)
    private ArrayList<Entry> pending = new ArrayList<>();
    private ArrayList<FileItem> pendingScan = new ArrayList<>();
    private FileOutputStream rxOut;
    private File rxFile;
    private String rxRel = "";
    private long rxSize, rxMtime, rxDone, rxLastReport;
    private int rxFiles;
    private long rxBytes, rxBatchStart;

    public Session(Transport t, File root, File inbox, String myName, Listener l) {
        this.t = t;
        this.root = root;
        this.inbox = inbox;
        this.myName = myName;
        this.l = l;
    }

    private static ExecutorService daemonExecutor(final String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, name);
            th.setDaemon(true);
            return th;
        });
    }

    // ------------------------------------------------------------------ public API

    public void start() {
        Thread rd = new Thread(this::readLoop, "usbshare-read");
        rd.setDaemon(true);
        rd.start();
        ctl.execute(() -> {
            try {
                frame(T_HELLO, myName.getBytes(UTF8));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Ask the other phone to list a folder ("" = its storage root). Answer arrives in onRemoteList. */
    public void requestList(final String path) {
        ctl.execute(() -> {
            try {
                frame(T_LIST_REQ, path.getBytes(UTF8));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Ask the other phone to send us a file or a whole folder. */
    public void requestGet(final String path) {
        ctl.execute(() -> {
            try {
                frame(T_GET_REQ, path.getBytes(UTF8));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Ask the other phone to send us several files/folders as one batch. */
    public void requestGetMany(List<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        requestGet(sb.toString());
    }

    /** Ask the other phone for its photo/video list (SCAN_MEDIA) or newest-files list (SCAN_RECENT). */
    public void requestScan(final int kind) {
        ctl.execute(() -> {
            try {
                frame(T_SCAN_REQ, new byte[]{(byte) kind});
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Ask the other phone for a thumbnail of one of its photos/videos. Answer arrives in onThumb. */
    public void requestThumb(final String path) {
        ctl.execute(() -> {
            try {
                frame(T_THUMB_REQ, path.getBytes(UTF8));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /** Send files/folders from this phone to the other phone's inbox. */
    public void sendFiles(List<File> files) {
        final List<File> copy = new ArrayList<>(files);
        sender.execute(() -> sendBatch(copy));
    }

    public void close() {
        shutdown("closed");
    }

    public boolean isClosed() {
        return closed;
    }

    public static List<Entry> listLocal(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        Arrays.sort(files, (a, b) -> {
            boolean da = a.isDirectory(), db = b.isDirectory();
            if (da != db) return da ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        ArrayList<Entry> out = new ArrayList<>();
        File pr = primaryRoot;
        if (pr != null && sameDir(dir, pr)) {
            // extra volumes show up as folders named "@/absolute/path" at the top of internal storage
            for (File x : extraRoots) out.add(new Entry("@" + x.getPath(), true, 0));
        }
        for (File f : files) {
            boolean d = f.isDirectory();
            out.add(new Entry(f.getName(), d, d ? 0 : f.length()));
        }
        return out;
    }

    /** Walks the storage and returns photos/videos (SCAN_MEDIA) or all files (SCAN_RECENT), newest first. */
    public static List<FileItem> scanLocal(File root, int kind) {
        final int limit = kind == SCAN_MEDIA ? 40000 : 3000;
        PriorityQueue<FileItem> pq = new PriorityQueue<>(1024, (a, b) -> Long.compare(a.mtime, b.mtime));
        walk(root, kind, 0, pq, limit);
        for (File x : extraRoots) walk(x, kind, 0, pq, limit);
        ArrayList<FileItem> out = new ArrayList<>(pq);
        out.sort((a, b) -> Long.compare(b.mtime, a.mtime));
        return out;
    }

    private static void walk(File dir, int kind, int depth, PriorityQueue<FileItem> pq, int limit) {
        if (depth > 14) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        if (kind == SCAN_MEDIA) {
            for (File f : fs) {
                if (f.getName().equals(".nomedia")) return;
            }
        }
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && n.equals("Android")) continue;
                walk(f, kind, depth + 1, pq, limit);
            } else {
                int type = FileItem.typeOf(n);
                if (kind == SCAN_MEDIA && type == FileItem.OTHER) continue;
                pq.add(new FileItem(f.getPath(), f.lastModified(), f.length(), type));
                if (pq.size() > limit) pq.poll();
            }
        }
    }

    private static boolean sameDir(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException e) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------ framing

    private void frame(int type, byte[] p) throws IOException {
        frame(type, p, 0, p.length);
    }

    private void frame(int type, byte[] p, int off, int len) throws IOException {
        int total = 5 + len;
        boolean pad = (total % 512) == 0;
        byte[] f = new byte[total + (pad ? 5 : 0)];   // pad frame = 5 zero bytes (type 0, length 0)
        f[0] = (byte) type;
        f[1] = (byte) (len >>> 24);
        f[2] = (byte) (len >>> 16);
        f[3] = (byte) (len >>> 8);
        f[4] = (byte) len;
        System.arraycopy(p, off, f, 5, len);
        synchronized (writeLock) {
            t.write(f, 0, f.length);
        }
    }

    private void fail(IOException e) {
        shutdown(e.getMessage() == null ? "I/O error" : e.getMessage());
    }

    private void shutdown(String reason) {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        t.close();
        sender.shutdownNow();
        ctl.shutdownNow();
        scanner.shutdownNow();
        thumbs.shutdownNow();
        closeRx();
        l.onClosed(reason == null ? "closed" : reason);
    }

    // ------------------------------------------------------------------ receiving

    private void readLoop() {
        DataInputStream in = new DataInputStream(new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = t.read(one, 0, 1);
                return n < 0 ? -1 : (one[0] & 0xff);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return t.read(b, off, len);
            }
        });
        String reason = "closed";
        try {
            byte[] hdr = new byte[5];
            while (!closed) {
                in.readFully(hdr);
                int type = hdr[0] & 0xff;
                int len = ((hdr[1] & 0xff) << 24) | ((hdr[2] & 0xff) << 16) | ((hdr[3] & 0xff) << 8) | (hdr[4] & 0xff);
                if (len < 0 || len > (1 << 20)) throw new IOException("bad frame length " + len);
                byte[] p = new byte[len];
                in.readFully(p);
                handle(type, p);
            }
        } catch (EOFException e) {
            reason = "cable disconnected";
        } catch (IOException e) {
            reason = e.getMessage() == null ? "I/O error" : e.getMessage();
        } catch (RuntimeException e) {
            reason = "protocol error: " + e;
        } finally {
            shutdown(reason);
        }
    }

    private void handle(int type, byte[] p) throws IOException {
        switch (type) {
            case T_PAD:
                break;
            case T_HELLO:
                l.onLog("Connected to: " + new String(p, UTF8));
                break;
            case T_LIST_REQ: {
                final String path = new String(p, UTF8);
                sender.execute(() -> {
                    try {
                        doList(path);
                    } catch (IOException e) {
                        fail(e);
                    }
                });
                break;
            }
            case T_LIST_ENTRY: {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
                boolean dir = d.readByte() != 0;
                long size = d.readLong();
                byte[] nb = new byte[p.length - 9];
                d.readFully(nb);
                pending.add(new Entry(new String(nb, UTF8), dir, size));
                break;
            }
            case T_LIST_END: {
                ArrayList<Entry> done = pending;
                pending = new ArrayList<>();
                l.onRemoteList(new String(p, UTF8), done);
                break;
            }
            case T_SCAN_REQ: {
                final int kind = p.length > 0 ? p[0] : 0;
                scanner.execute(() -> {
                    try {
                        doScan(kind);
                    } catch (IOException e) {
                        fail(e);
                    }
                });
                break;
            }
            case T_SCAN_ITEMS: {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
                int cnt = d.readInt();
                for (int i = 0; i < cnt; i++) {
                    long m = d.readLong();
                    long sz = d.readLong();
                    int ty = d.readByte();
                    int pl = d.readUnsignedShort();
                    byte[] pb = new byte[pl];
                    d.readFully(pb);
                    pendingScan.add(new FileItem(new String(pb, UTF8), m, sz, ty));
                }
                break;
            }
            case T_SCAN_END: {
                ArrayList<FileItem> done = pendingScan;
                pendingScan = new ArrayList<>();
                l.onRemoteScan(p.length > 0 ? p[0] : 0, done);
                break;
            }
            case T_THUMB_REQ: {
                final String path = new String(p, UTF8);
                thumbs.execute(() -> {
                    try {
                        doThumb(path);
                    } catch (IOException e) {
                        fail(e);
                    }
                });
                break;
            }
            case T_THUMB_DATA: {
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
                int pl = d.readUnsignedShort();
                byte[] pb = new byte[pl];
                d.readFully(pb);
                byte[] jp = new byte[p.length - 2 - pl];
                d.readFully(jp);
                l.onThumb(new String(pb, UTF8), jp);
                break;
            }
            case T_ERROR:
                pending = new ArrayList<>();
                l.onRemoteError(new String(p, UTF8));
                break;
            case T_GET_REQ: {
                final String path = new String(p, UTF8);
                sender.execute(() -> {
                    try {
                        List<File> many = new ArrayList<>();
                        for (String part : path.split("\n")) {
                            if (!part.isEmpty()) many.add(resolve(part));
                        }
                        if (many.isEmpty()) many.add(resolve(""));
                        sendBatch(many);
                    } catch (IOException e) {
                        try {
                            frame(T_ERROR, String.valueOf(e.getMessage()).getBytes(UTF8));
                        } catch (IOException e2) {
                            fail(e2);
                        }
                    }
                });
                break;
            }
            case T_FILE_BEGIN:
                rxBegin(p);
                break;
            case T_FILE_DATA:
                rxData(p);
                break;
            case T_FILE_END:
                rxEnd();
                break;
            case T_BATCH_END: {
                long ms = Math.max(1, System.currentTimeMillis() - rxBatchStart);
                l.onBatchDone(false, rxFiles, rxBytes, ms);
                rxFiles = 0;
                rxBytes = 0;
                break;
            }
            default:
                break;
        }
    }

    static String cleanRel(String rel) {
        StringBuilder sb = new StringBuilder();
        for (String part : rel.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.length() == 0 ? "file" : sb.toString();
    }

    private File uniqueDest(String rel) throws IOException {
        File dest = new File(inbox, rel);
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        if (!dest.exists()) return dest;
        String n = dest.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n;
        String ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            File c = new File(parent, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        throw new IOException("too many duplicates of " + n);
    }

    private void rxBegin(byte[] p) {
        closeRx();
        try {
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(p));
            rxSize = d.readLong();
            rxMtime = d.readLong();
            byte[] nb = new byte[p.length - 16];
            d.readFully(nb);
            rxRel = cleanRel(new String(nb, UTF8));
            rxFile = uniqueDest(rxRel);
            rxOut = new FileOutputStream(rxFile);
            rxDone = 0;
            rxLastReport = 0;
            if (rxFiles == 0) rxBatchStart = System.currentTimeMillis();
        } catch (IOException e) {
            rxOut = null;
            l.onLog("Cannot save " + rxRel + ": " + e.getMessage());
        }
    }

    private void rxData(byte[] p) {
        if (rxOut == null) return;
        try {
            rxOut.write(p);
        } catch (IOException e) {
            l.onLog("Write failed: " + e.getMessage());
            closeRx();
            return;
        }
        rxDone += p.length;
        long now = System.currentTimeMillis();
        if (now - rxLastReport > 100) {
            rxLastReport = now;
            l.onProgress(rxRel, rxDone, rxSize, false);
        }
    }

    private void rxEnd() {
        if (rxOut == null) return;
        try {
            rxOut.close();
        } catch (IOException e) {
            l.onLog("Close failed: " + e.getMessage());
        }
        rxOut = null;
        if (rxMtime > 0 && rxFile != null) rxFile.setLastModified(rxMtime);   // keep the photo's original date
        rxFiles++;
        rxBytes += rxDone;
        l.onProgress(rxRel, rxDone, rxSize, false);
        l.onFileReceived(rxRel, rxDone, rxFile);
    }

    /** Closes (and deletes) a half-written file. */
    private void closeRx() {
        if (rxOut != null) {
            try {
                rxOut.close();
            } catch (IOException e) {
                // ignore
            }
            rxOut = null;
            if (rxFile != null) rxFile.delete();
        }
    }

    // ------------------------------------------------------------------ sending

    private File resolve(String path) throws IOException {
        File f = (path == null || path.isEmpty()) ? root : new File(path);
        String c = f.getCanonicalPath();
        if (!inside(c, root)) {
            boolean ok = false;
            for (File x : extraRoots) {
                if (inside(c, x)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) throw new IOException("outside shared storage: " + path);
        }
        return f.getCanonicalFile();
    }

    private static boolean inside(String canonical, File r) throws IOException {
        String rc = r.getCanonicalPath();
        return canonical.equals(rc) || canonical.startsWith(rc + "/");
    }

    private void doList(String path) throws IOException {
        File dir;
        try {
            dir = resolve(path);
        } catch (IOException e) {
            frame(T_ERROR, String.valueOf(e.getMessage()).getBytes(UTF8));
            return;
        }
        List<Entry> list = listLocal(dir);
        if (list == null) {
            frame(T_ERROR, ("cannot read " + dir.getPath()).getBytes(UTF8));
            return;
        }
        for (Entry e : list) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bo);
            d.writeByte(e.dir ? 1 : 0);
            d.writeLong(e.size);
            d.write(e.name.getBytes(UTF8));
            frame(T_LIST_ENTRY, bo.toByteArray());
        }
        frame(T_LIST_END, dir.getPath().getBytes(UTF8));
    }

    private void doScan(int kind) throws IOException {
        List<FileItem> items = scanLocal(root, kind);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(body);
        int cnt = 0;
        for (FileItem it : items) {
            byte[] pb = it.path.getBytes(UTF8);
            if (pb.length > 4000) continue;
            if (cnt > 0 && body.size() + 19 + pb.length > 15000) {
                sendScanBatch(cnt, body.toByteArray());
                body.reset();
                cnt = 0;
            }
            d.writeLong(it.mtime);
            d.writeLong(it.size);
            d.writeByte(it.type);
            d.writeShort(pb.length);
            d.write(pb);
            cnt++;
        }
        if (cnt > 0) sendScanBatch(cnt, body.toByteArray());
        frame(T_SCAN_END, new byte[]{(byte) kind});
    }

    private void sendScanBatch(int cnt, byte[] body) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeInt(cnt);
        d.write(body);
        frame(T_SCAN_ITEMS, bo.toByteArray());
    }

    private void doThumb(String path) throws IOException {
        byte[] jpeg = new byte[0];
        try {
            File f = resolve(path);
            jpeg = Thumbs.jpeg(f, FileItem.typeOf(f.getName()), THUMB_PX);
        } catch (IOException e) {
            // outside shared storage: answer with an empty thumbnail
        }
        byte[] pb = path.getBytes(UTF8);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bo);
        d.writeShort(pb.length);
        d.write(pb);
        d.write(jpeg);
        frame(T_THUMB_DATA, bo.toByteArray());
    }

    private static class Stats {
        int files;
        long bytes;
    }

    private void sendBatch(List<File> files) {
        long t0 = System.currentTimeMillis();
        Stats st = new Stats();
        try {
            for (File f : files) sendTree(f, f.getName(), st);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bo);
            d.writeInt(st.files);
            d.writeLong(st.bytes);
            frame(T_BATCH_END, bo.toByteArray());
            l.onBatchDone(true, st.files, st.bytes, Math.max(1, System.currentTimeMillis() - t0));
        } catch (IOException e) {
            fail(e);
        }
    }

    private void sendTree(File f, String rel, Stats st) throws IOException {
        if (closed) throw new IOException("link closed");
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch == null) return;
            Arrays.sort(ch);
            for (File c : ch) sendTree(c, rel + "/" + c.getName(), st);
        } else {
            sendOne(f, rel, st);
        }
    }

    private void sendOne(File f, String rel, Stats st) throws IOException {
        long size = f.length();
        FileInputStream in;
        try {
            in = new FileInputStream(f);
        } catch (IOException e) {
            l.onLog("Skipped (cannot read): " + rel);
            return;
        }
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(bo);
            d.writeLong(size);
            d.writeLong(f.lastModified());
            d.write(rel.getBytes(UTF8));
            frame(T_FILE_BEGIN, bo.toByteArray());
            byte[] buf = new byte[CHUNK];
            long done = 0;
            long last = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                frame(T_FILE_DATA, buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - last > 100) {
                    last = now;
                    l.onProgress(rel, done, size, true);
                }
            }
            frame(T_FILE_END, new byte[0]);
            l.onProgress(rel, done, size, true);
            st.files++;
            st.bytes += done;
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
