package com.mono.player;

import android.content.ContentUris;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends BridgeActivity {

    private static final int ART_SIZE = 600;
    private final Object[] artLocks = new Object[16];
    private final Set<Long> noArt = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    @Override
    public void onCreate(Bundle savedInstanceState) {
        for (int i = 0; i < artLocks.length; i++) artLocks[i] = new Object();
        registerPlugin(MusicLibraryPlugin.class);
        super.onCreate(savedInstanceState);

        getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String p = request.getUrl().getPath();
                if (p != null) {
                    try {
                        if (p.startsWith("/_music/")) {
                            WebResourceResponse r = serveTrack(p.substring(8), request);
                            if (r != null) return r;
                        } else if (p.startsWith("/_art/")) {
                            return serveArt(p.substring(6), request);
                        }
                    } catch (Throwable ignored) { }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    // ---------------------------------------------------------------- audio
    /** Streams a track from MediaStore with HTTP Range support so seeking works. */
    private WebResourceResponse serveTrack(String idStr, WebResourceRequest request) {
        try {
            long id = Long.parseLong(idStr);
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) return null;
            long total = afd.getLength();
            if (total < 0) total = afd.getParcelFileDescriptor().getStatSize();
            String mime = getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("audio")) mime = "audio/mpeg";

            long start = 0, end = total - 1;
            boolean partial = false;
            String range = request.getRequestHeaders().get("Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] parts = range.substring(6).split("-", 2);
                try {
                    if (!parts[0].isEmpty()) start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && !parts[1].trim().isEmpty()) end = Long.parseLong(parts[1].trim());
                    if (parts[0].isEmpty() && parts.length > 1) { // suffix range
                        start = Math.max(0, total - Long.parseLong(parts[1].trim()));
                        end = total - 1;
                    }
                    partial = true;
                } catch (NumberFormatException ignored) { start = 0; end = total - 1; }
            }
            if (end >= total) end = total - 1;
            if (start > end) start = 0;

            FileInputStream fis = afd.createInputStream();
            fis.getChannel().position(afd.getStartOffset() + start);
            InputStream body = new Bounded(fis, end - start + 1);

            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(end - start + 1));
            h.put("Access-Control-Allow-Origin", "*");
            if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);

            WebResourceResponse resp = new WebResourceResponse(mime, null, body);
            resp.setResponseHeaders(h);
            if (partial) resp.setStatusCodeAndReasonPhrase(206, "Partial Content");
            else resp.setStatusCodeAndReasonPhrase(200, "OK");
            return resp;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- cover art
    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    /** /_art/<trackId>?a=<albumId> -> small JPEG (max 600px), cached on disk. */
    private WebResourceResponse serveArt(String idStr, WebResourceRequest request) {
        try {
            long id = Long.parseLong(idStr);
            if (noArt.contains(id)) return notFound();
            File dir = new File(getCacheDir(), "art");
            dir.mkdirs();
            File f = new File(dir, id + ".jpg");
            synchronized (artLocks[(int) (id & 15)]) {
                if (!f.exists() || f.length() == 0) {
                    String a = request.getUrl().getQueryParameter("a");
                    long albumId = 0;
                    try { if (a != null) albumId = Long.parseLong(a); } catch (NumberFormatException ignored) { }
                    byte[] jpg = makeArt(id, albumId);
                    if (jpg == null) { noArt.add(id); return notFound(); }
                    File tmp = new File(dir, id + ".tmp");
                    try (FileOutputStream fo = new FileOutputStream(tmp)) { fo.write(jpg); }
                    if (!tmp.renameTo(f)) { return new WebResourceResponse("image/jpeg", null, new ByteArrayInputStream(jpg)); }
                }
            }
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "max-age=604800");
            h.put("Content-Length", String.valueOf(f.length()));
            WebResourceResponse r = new WebResourceResponse("image/jpeg", null, new FileInputStream(f));
            r.setResponseHeaders(h);
            return r;
        } catch (Throwable e) {
            return notFound();
        }
    }

    private byte[] makeArt(long id, long albumId) {
        Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        Bitmap bm = null;
        // 1) picture embedded in the file
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            byte[] pic = r.getEmbeddedPicture();
            if (pic != null && pic.length > 0) bm = decodeScaled(pic);
        } catch (Throwable ignored) {
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        // 2) Android's own thumbnail (Android 10+)
        if (bm == null && Build.VERSION.SDK_INT >= 29) {
            try { bm = getContentResolver().loadThumbnail(uri, new Size(ART_SIZE, ART_SIZE), null); } catch (Throwable ignored) { }
        }
        // 3) old album-art provider (Android 9 and below)
        if (bm == null && albumId > 0) {
            try (InputStream in = getContentResolver().openInputStream(
                    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId))) {
                if (in != null) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    bm = decodeScaled(bo.toByteArray());
                }
            } catch (Throwable ignored) { }
        }
        if (bm == null) return null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            bm.compress(Bitmap.CompressFormat.JPEG, 85, bo);
            return bo.toByteArray();
        } finally {
            bm.recycle();
        }
    }

    /** Decodes with sub-sampling so huge embedded covers never blow up memory. */
    private Bitmap decodeScaled(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while ((o.outWidth / (sample * 2)) >= ART_SIZE && (o.outHeight / (sample * 2)) >= ART_SIZE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        if (b == null) return null;
        int max = Math.max(b.getWidth(), b.getHeight());
        if (max > ART_SIZE) {
            float k = (float) ART_SIZE / max;
            Bitmap s = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
            if (s != b) b.recycle();
            b = s;
        }
        return b;
    }

    private static class Bounded extends FilterInputStream {
        private long left;
        Bounded(InputStream in, long len) { super(in); left = len; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
