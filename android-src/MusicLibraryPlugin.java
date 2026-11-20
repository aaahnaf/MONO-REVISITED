package com.mono.player;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;

@CapacitorPlugin(
    name = "MusicLibrary",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MusicLibraryPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "audio" : "storage";
    }

    private String permName() {
        return Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_AUDIO
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @PluginMethod
    public void getTracks(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "permCallback");
            return;
        }
        loadTracks(call);
    }

    @PermissionCallback
    private void permCallback(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            loadTracks(call);
        } else {
            boolean canAskAgain = ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), permName());
            call.reject("Permission denied", canAskAgain ? "denied" : "permanently_denied");
        }
    }

    private void loadTracks(PluginCall call) {
        try {
            String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION
            };
            String sel = "(" + MediaStore.Audio.Media.MIME_TYPE + " = 'audio/mpeg' OR "
                + MediaStore.Audio.Media.DISPLAY_NAME + " LIKE '%.mp3')";
            Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            JSArray out = new JSArray();
            try (Cursor c = getContext().getContentResolver().query(base, proj, sel, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        JSObject o = new JSObject();
                        o.put("id", id);
                        o.put("title", c.getString(1));
                        o.put("artist", c.getString(2));
                        o.put("album", c.getString(3));
                        o.put("albumId", c.getLong(4));
                        o.put("track", c.getInt(5));
                        o.put("duration", c.getLong(6));
                        o.put("path", ContentUris.withAppendedId(base, id).toString());
                        out.put(o);
                    }
                }
            }
            JSObject res = new JSObject();
            res.put("tracks", out);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("Scan failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getArt(PluginCall call) {
        String path = call.getString("path");
        String albumId = call.getString("albumId", "0");
        JSObject res = new JSObject();
        if (path == null) { call.resolve(res); return; }
        try {
            File f = new File(getContext().getCacheDir(), "art_" + albumId + ".jpg");
            if (!f.exists() || f.length() == 0) {
                Uri uri = Uri.parse(path);
                byte[] pic = null;
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(getContext(), uri);
                    pic = r.getEmbeddedPicture();
                } catch (Exception ignored) {
                } finally {
                    try { r.release(); } catch (Exception ignored) {}
                }
                if (pic != null) {
                    try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(pic); }
                } else if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        Bitmap bm = getContext().getContentResolver().loadThumbnail(uri, new Size(512, 512), null);
                        try (FileOutputStream fo = new FileOutputStream(f)) {
                            bm.compress(Bitmap.CompressFormat.JPEG, 90, fo);
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (f.exists() && f.length() > 0) res.put("path", f.getAbsolutePath());
        } catch (Exception ignored) {}
        call.resolve(res);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", getContext().getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }
}
