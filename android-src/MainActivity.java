package com.mono.player;

import android.content.ContentUris;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MusicLibraryPlugin.class);
        super.onCreate(savedInstanceState);

        getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String p = request.getUrl().getPath();
                if (p != null && p.startsWith("/_music/")) {
                    WebResourceResponse r = serveTrack(p.substring(8), request);
                    if (r != null) return r;
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    /** Streams an MP3 from MediaStore with HTTP Range support so seeking works. */
    private WebResourceResponse serveTrack(String idStr, WebResourceRequest request) {
        try {
            long id = Long.parseLong(idStr);
            Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
            AssetFileDescriptor afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) return null;
            long total = afd.getLength();
            if (total < 0) total = afd.getParcelFileDescriptor().getStatSize();

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

            WebResourceResponse resp = new WebResourceResponse("audio/mpeg", null, body);
            resp.setResponseHeaders(h);
            if (partial) resp.setStatusCodeAndReasonPhrase(206, "Partial Content");
            else resp.setStatusCodeAndReasonPhrase(200, "OK");
            return resp;
        } catch (Exception e) {
            return null;
        }
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
