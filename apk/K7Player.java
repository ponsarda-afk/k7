package fr.kawaiiapp.k7audiob;

import android.content.ComponentName;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pont entre la page K7 et Android :
 *  - lecteur natif (Media3) piloté depuis la page, qui continue écran éteint ;
 *  - lecture du dossier Audiobook directement sur le téléphone (aucune copie) ;
 *  - lecture d'un morceau de fichier (tags, pochettes, chapitres) et durée des pistes.
 */
@CapacitorPlugin(
    name = "K7Player",
    permissions = {
        @Permission(alias = "media", strings = {"android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES"}),
        @Permission(alias = "legacy", strings = {"android.permission.READ_EXTERNAL_STORAGE"})
    }
)
public class K7Player extends Plugin {

    private interface Job { void run(MediaController c); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Job> waiting = new ArrayList<>();
    private ListenableFuture<MediaController> future;
    private MediaController ctl;
    private SharedPreferences prefs;
    private String bookId = null;
    private long lastSaved = 0;

    private static final String AUDIO_EXT = "mp3|m4a|m4b|ogg|oga|opus|flac|wav|aac|webm|mka";
    private static final String IMG_EXT = "jpg|jpeg|png|webp";

    @Override
    public void load() {
        prefs = getContext().getSharedPreferences("k7player", 0);
    }

    /* ---------------- connexion au service de lecture ---------------- */

    private void withCtl(Job job) {
        main.post(() -> {
            if (ctl != null) { job.run(ctl); return; }
            waiting.add(job);
            if (future == null) connect();
        });
    }

    private void connect() {
        SessionToken token = new SessionToken(getContext(), new ComponentName(getContext(), K7PlaybackService.class));
        future = new MediaController.Builder(getContext(), token).buildAsync();
        future.addListener(() -> {
            try {
                ctl = future.get();
                ctl.addListener(listener);
                main.post(ticker);
                List<Job> jobs = new ArrayList<>(waiting);
                waiting.clear();
                for (Job j : jobs) j.run(ctl);
            } catch (Exception e) {
                future = null;
                List<Job> jobs = new ArrayList<>(waiting);
                waiting.clear();
                for (Job j : jobs) j.run(null);
            }
        }, ContextCompat.getMainExecutor(getContext()));
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (ctl != null && ctl.isPlaying()) {
                notifyListeners("time", state(ctl));
                savePos(false);
            }
            main.postDelayed(this, 1000);
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onEvents(Player p, Player.Events ev) {
            if (ev.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                Player.EVENT_PLAYBACK_PARAMETERS_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) {
                if (ctl != null) notifyListeners("state", state(ctl));
            }
            if (ev.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && !p.getPlayWhenReady()) savePos(true);
            if (ev.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && p.getPlaybackState() == Player.STATE_ENDED) {
                notifyListeners("ended", new JSObject());
            }
        }

        @Override
        public void onMediaItemTransition(MediaItem item, int reason) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && ctl != null) {
                JSObject o = new JSObject();
                o.put("index", ctl.getCurrentMediaItemIndex());
                notifyListeners("advance", o);
                savePos(true);
            }
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            JSObject o = new JSObject();
            o.put("message", e.getErrorCodeName() + " " + e.getMessage());
            notifyListeners("error", o);
        }
    };

    private JSObject state(MediaController c) {
        JSObject o = new JSObject();
        o.put("index", c.getCurrentMediaItemIndex());
        o.put("position", c.getCurrentPosition() / 1000.0);
        long d = c.getDuration();
        o.put("duration", d == C.TIME_UNSET ? 0 : d / 1000.0);
        o.put("playWhenReady", c.getPlayWhenReady());
        o.put("playing", c.isPlaying());
        o.put("state", c.getPlaybackState());
        o.put("rate", c.getPlaybackParameters().speed);
        o.put("count", c.getMediaItemCount());
        o.put("book", bookId);
        return o;
    }

    // sauvegarde de secours côté Android : si la page est figée ou tuée, la position n'est pas perdue
    private void savePos(boolean force) {
        if (ctl == null || bookId == null || ctl.getMediaItemCount() == 0) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSaved < 4000) return;
        lastSaved = now;
        try {
            JSONObject o = new JSONObject();
            o.put("file", ctl.getCurrentMediaItemIndex());
            o.put("t", Math.round(ctl.getCurrentPosition() / 100.0) / 10.0);
            o.put("last", now);
            prefs.edit().putString("p:" + bookId, o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /* ---------------- commandes du lecteur ---------------- */

    @PluginMethod
    public void load(PluginCall call) {
        JSArray uris = call.getArray("uris");
        if (uris == null || uris.length() == 0) { call.reject("aucun fichier"); return; }
        final List<MediaItem> items = new ArrayList<>();
        try {
            String title = call.getString("title", "K7");
            String artist = call.getString("artist", "");
            String art = call.getString("art", null);
            byte[] artBytes = null;
            if (art != null && !art.isEmpty()) {
                try { artBytes = Base64.decode(art, Base64.DEFAULT); } catch (Exception ignored) { }
            }
            List<String> names = new ArrayList<>();
            JSArray chap = call.getArray("names");
            for (int i = 0; i < uris.length(); i++) {
                String path = uris.getString(i);
                String sub = (chap != null && i < chap.length()) ? chap.getString(i) : title;
                MediaMetadata.Builder md = new MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(sub)
                    .setDisplayTitle(title);
                if (artBytes != null) md.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
                Uri u = path.startsWith("/") ? Uri.fromFile(new File(path)) : Uri.parse(path);
                items.add(new MediaItem.Builder().setUri(u).setMediaId(path).setMediaMetadata(md.build()).build());
            }
        } catch (Exception e) { call.reject("liste invalide : " + e.getMessage()); return; }
        final int index = (int) num(call, "index", 0);
        final long pos = Math.round(num(call, "position", 0) * 1000);
        final float rate = (float) num(call, "rate", 1);
        final float vol = (float) num(call, "volume", 1);
        final String id = call.getString("bookId", null);
        withCtl(c -> {
            if (c == null) { call.reject("lecteur indisponible"); return; }
            savePos(true);
            bookId = id;
            c.setMediaItems(items, Math.max(0, Math.min(index, items.size() - 1)), Math.max(0, pos));
            c.setPlaybackSpeed(rate);
            c.setVolume(vol);
            c.prepare();
            call.resolve(state(c));
        });
    }

    @PluginMethod
    public void play(PluginCall call) { withCtl(c -> { if (c != null) { if (c.getPlaybackState() == Player.STATE_IDLE) c.prepare(); c.play(); } done(call, c); }); }

    @PluginMethod
    public void pause(PluginCall call) { withCtl(c -> { if (c != null) c.pause(); savePos(true); done(call, c); }); }

    @PluginMethod
    public void seek(PluginCall call) {
        final Integer idx = call.getData().opt("index") instanceof Number ? (int) num(call, "index", 0) : null;
        final long ms = Math.round(num(call, "position", 0) * 1000);
        withCtl(c -> {
            if (c != null && c.getMediaItemCount() > 0) {
                if (idx != null && idx != c.getCurrentMediaItemIndex()) c.seekTo(Math.max(0, Math.min(idx, c.getMediaItemCount() - 1)), Math.max(0, ms));
                else c.seekTo(Math.max(0, ms));
            }
            done(call, c);
        });
    }

    @PluginMethod
    public void setRate(PluginCall call) { final float r = (float) num(call, "rate", 1); withCtl(c -> { if (c != null) c.setPlaybackSpeed(r); done(call, c); }); }

    @PluginMethod
    public void setVolume(PluginCall call) { final float v = (float) num(call, "volume", 1); withCtl(c -> { if (c != null) c.setVolume(Math.max(0f, Math.min(1f, v))); done(call, c); }); }

    @PluginMethod
    public void stop(PluginCall call) { withCtl(c -> { if (c != null) { savePos(true); c.stop(); c.clearMediaItems(); } bookId = null; done(call, c); }); }

    @PluginMethod
    public void getState(PluginCall call) { withCtl(c -> done(call, c)); }

    private static double num(PluginCall call, String k, double def) {
        Object v = call.getData().opt(k);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    private void done(PluginCall call, MediaController c) {
        if (c == null) call.reject("lecteur indisponible"); else call.resolve(state(c));
    }

    @PluginMethod
    public void lastPositions(PluginCall call) {
        JSObject out = new JSObject();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith("p:")) continue;
            try { out.put(e.getKey().substring(2), new JSObject(String.valueOf(e.getValue()))); } catch (Exception ignored) { }
        }
        call.resolve(out);
    }

    /* ---------------- accès aux fichiers du téléphone ---------------- */

    private String alias() { return Build.VERSION.SDK_INT >= 33 ? "media" : "legacy"; }

    @PluginMethod
    public void requestFiles(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) { granted(call, true); return; }
        requestPermissionForAlias(alias(), call, "filesCallback");
    }

    @PermissionCallback
    private void filesCallback(PluginCall call) {
        granted(call, getPermissionState(alias()) == PermissionState.GRANTED);
    }

    private void granted(PluginCall call, boolean ok) {
        JSObject o = new JSObject();
        o.put("granted", ok);
        call.resolve(o);
    }

    private File resolve(String p) {
        if (p == null || p.isEmpty()) p = "Audiobook";
        return p.startsWith("/") ? new File(p) : new File(Environment.getExternalStorageDirectory(), p);
    }

    @PluginMethod
    public void listTree(PluginCall call) {
        final File root = resolve(call.getString("path"));
        new Thread(() -> {
            if (!root.isDirectory()) { call.reject("Dossier introuvable : " + root.getAbsolutePath()); return; }
            JSArray out = new JSArray();
            walk(root, root.getName(), out, 0);
            JSObject o = new JSObject();
            o.put("root", root.getAbsolutePath());
            o.put("files", out);
            call.resolve(o);
        }).start();
    }

    private void walk(File dir, String rel, JSArray out, int depth) {
        if (depth > 6) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        for (File f : kids) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) { walk(f, rel + "/" + n, out, depth + 1); continue; }
            String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            if (!ext.matches(AUDIO_EXT) && !ext.matches(IMG_EXT)) continue;
            JSObject o = new JSObject();
            o.put("path", f.getAbsolutePath());
            o.put("rel", rel + "/" + n);
            o.put("name", n);
            o.put("size", f.length());
            o.put("mtime", f.lastModified());
            out.put(o);
        }
    }

    @PluginMethod
    public void readRange(PluginCall call) {
        final String path = call.getString("path");
        final long off = (long) num(call, "offset", 0);
        final int len = (int) num(call, "length", 0);
        new Thread(() -> {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                long size = raf.length();
                long start = Math.max(0, Math.min(off, size));
                int n = (int) Math.max(0, Math.min((long) len, size - start));
                byte[] buf = new byte[n];
                raf.seek(start);
                raf.readFully(buf);
                JSObject o = new JSObject();
                o.put("data", Base64.encodeToString(buf, Base64.NO_WRAP));
                o.put("size", size);
                call.resolve(o);
            } catch (Exception e) {
                call.reject("Lecture impossible : " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void probe(PluginCall call) {
        final String path = call.getString("path");
        new Thread(() -> {
            double dur = 0;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(path);
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) dur = Long.parseLong(d) / 1000.0;
            } catch (Exception ignored) {
            } finally {
                try { r.release(); } catch (Exception ignored) { }
            }
            JSObject o = new JSObject();
            o.put("duration", dur);
            call.resolve(o);
        }).start();
    }

    @PluginMethod
    public void saveDownload(PluginCall call) {
        final String name = call.getString("name", "k7-sauvegarde.json");
        final String data = call.getString("data", "");
        new Thread(() -> {
            try {
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) throw new Exception("création refusée");
                    try (OutputStream os = getContext().getContentResolver().openOutputStream(u)) { os.write(bytes); }
                } else {
                    File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
                    try (OutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                }
                call.resolve();
            } catch (Exception e) {
                call.reject(e.getMessage());
            }
        }).start();
    }

    @Override
    protected void handleOnDestroy() {
        main.removeCallbacks(ticker);
        savePos(true);
        if (ctl != null) { ctl.removeListener(listener); }
        if (future != null) MediaController.releaseFuture(future);
        ctl = null;
        future = null;
    }
}
