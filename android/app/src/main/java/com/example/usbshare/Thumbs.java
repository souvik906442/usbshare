package com.example.usbshare;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;

import java.io.ByteArrayOutputStream;
import java.io.File;

/** Small thumbnails for photos and videos. */
public final class Thumbs {
    private Thumbs() { }

    /** Returns a bitmap whose shorter side is about px, or null if it cannot be decoded. */
    public static Bitmap load(File f, int type, int px) {
        try {
            return type == FileItem.VIDEO ? video(f, px) : image(f, px);
        } catch (Throwable t) {
            return null;
        }
    }

    /** JPEG bytes of the thumbnail, or an empty array on failure. */
    public static byte[] jpeg(File f, int type, int px) {
        Bitmap b = load(f, type, px);
        if (b == null) return new byte[0];
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 72, bo);
            return bo.toByteArray();
        } catch (Throwable t) {
            return new byte[0];
        } finally {
            b.recycle();
        }
    }

    private static Bitmap image(File f, int px) {
        String path = f.getPath();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return null;
        int deg = rotation(path);
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    private static int rotation(String path) {
        try {
            ExifInterface e = new ExifInterface(path);
            switch (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Bitmap video(File f, int px) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getPath());
            Bitmap b = r.getFrameAtTime();
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight();
            float sc = (float) px / Math.min(w, h);
            if (sc < 1f) {
                Bitmap sb = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)), true);
                if (sb != b) b.recycle();
                b = sb;
            }
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable t) { /* ignore */ }
        }
    }
}
