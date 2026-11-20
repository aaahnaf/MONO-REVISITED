package com.mono.player;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
    name = "MusicLibrary",
    permissions = {
        @Permission(alias = "audio", strings = { Manifest.permission.READ_MEDIA_AUDIO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MusicLibraryPlugin extends Plugin {

    private static final String[] EXTS = { ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ogg", ".oga", ".opus", ".wma", ".amr", ".mka" };
    private static volatile boolean indexStarted = false;

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

    /** Every music file Android knows about (all formats), minus ringtones / notification / alarm sounds. */
    private JSArray queryTracks(Set<String> knownPaths) {
        String[] proj = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.IS_RINGTONE,
            MediaStore.Audio.Media.IS_NOTIFICATION,
            MediaStore.Audio.Media.IS_ALARM
        };
        Uri base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        JSArray out = new JSArray();
        try (Cursor c = getContext().getContentResolver().query(base, proj, null, null, null)) {
            if (c != null) {
                while (c.moveToNext()) {
                    if (knownPaths != null) {
                        String d = c.getString(7);
                        if (d != null) knownPaths.add(d);
                    }
                    if (c.getInt(8) == 1 || c.getInt(9) == 1 || c.getInt(10) == 1) continue; // system sounds
                    long dur = c.getLong(6);
                    if (dur > 0 && dur < 10000) continue; // clips under 10 s
                    long id = c.getLong(0);
                    JSObject o = new JSObject();
                    o.put("id", id);
                    o.put("title", c.getString(1));
                    o.put("artist", c.getString(2));
                    o.put("album", c.getString(3));
                    o.put("albumId", c.getLong(4));
                    o.put("track", c.getInt(5));
                    o.put("duration", dur);
                    o.put("path", ContentUris.withAppendedId(base, id).toString());
                    out.put(o);
                }
            }
        }
        return out;
    }

    private static boolean isAudioName(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        for (String e : EXTS) if (l.endsWith(e)) return true;
        return false;
    }

    /** Finds audio files on shared storage (skips folders with a .nomedia file, like Android does). */
    private void walk(File dir, int depth, List<String> out, int[] budget) {
        if (depth > 7 || budget[0] <= 0) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.getName().equals(".nomedia")) return;
        }
        for (File f : fs) {
            if (budget[0]-- <= 0) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && n.equals("Android")) continue;
                walk(f, depth + 1, out, budget);
            } else if (isAudioName(n)) {
                out.add(f.getAbsolutePath());
            }
        }
    }

    private void loadTracks(PluginCall call) {
        try {
            final Set<String> known = new HashSet<>();
            final JSArray out = queryTracks(known);
            JSObject res = new JSObject();
            res.put("tracks", out);
            call.resolve(res);   // answer immediately; indexing happens in the background

            if (!indexStarted) {
                indexStarted = true;
                final int before = out.length();
                new Thread(() -> indexMissing(known, before), "mono-index").start();
            }
        } catch (Exception e) {
            call.reject("Scan failed: " + e.getMessage());
        }
    }

    /** Adds audio files Android has not indexed yet (e.g. just copied over USB / adb) and tells the page. */
    private void indexMissing(Set<String> known, int before) {
        try {
            List<String> files = new ArrayList<>();
            walk(Environment.getExternalStorageDirectory(), 0, files, new int[] { 40000 });
            List<String> missing = new ArrayList<>();
            for (String f : files) if (!known.contains(f)) missing.add(f);
            if (missing.isEmpty()) return;
            if (missing.size() > 3000) missing = missing.subList(0, 3000);
            final CountDownLatch latch = new CountDownLatch(missing.size());
            MediaScannerConnection.scanFile(getContext(), missing.toArray(new String[0]), null,
                (path, uri) -> latch.countDown());
            latch.await(60, TimeUnit.SECONDS);
            if (queryTracks(null).length() != before) notifyListeners("changed", new JSObject());
        } catch (Exception ignored) { }
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
