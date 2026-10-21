package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.util.List;

/**
 * Home's Now playing card (config.xml now_playing): whatever is playing on the TV, from Android's media sessions,
 * which only an enabled notification listener may read. The listener itself does nothing with notifications; it is
 * the key Android asks for. Enable it once over adb:
 * {@code cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.NowPlaying$Listener}.
 * Nothing leaves the TV.
 */
final class NowPlaying {

    /** The track as Home shows it; null when nothing plays. */
    static final class Track {
        final String title, artist, app;
        final Bitmap art;
        final boolean playing;

        Track(String title, String artist, String app, Bitmap art, boolean playing) {
            this.title = title;
            this.artist = artist;
            this.app = app;
            this.art = art;
            this.playing = playing;
        }
    }

    interface Callback {
        void onTrack(Track track);
    }

    /** The listener Android binds; it keeps nothing. */
    public static final class Listener extends NotificationListenerService {
    }

    private final Context context;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessions;
    private MediaController controller;
    private final ComponentName listener;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::follow;
    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            report();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            report();
        }

        @Override
        public void onSessionDestroyed() {
            follow(null);
        }
    };

    NowPlaying(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
        listener = new ComponentName(context, Listener.class);
    }

    /** Starts following the sessions; false when the listener is not enabled yet. */
    boolean start() {
        sessions = context.getSystemService(MediaSessionManager.class);
        if (sessions == null) return false;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main);
            follow(sessions.getActiveSessions(listener));
            return true;
        } catch (SecurityException e) {
            sessions = null;
            return false;   // not enabled as a notification listener
        }
    }

    void stop() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        sessions = null;
        if (controller != null) controller.unregisterCallback(controllerCallback);
        controller = null;
    }

    /** Picks the session that is playing, else the first that has a track loaded and is paused. */
    private void follow(List<MediaController> active) {
        if (sessions == null) return;
        if (active == null) {
            try {
                active = sessions.getActiveSessions(listener);
            } catch (SecurityException e) {
                active = null;
            }
        }
        MediaController pick = null;
        if (active != null) {
            for (MediaController c : active) {
                if (isState(c, PlaybackState.STATE_PLAYING)) {
                    pick = c;
                    break;
                }
                if (pick == null && isState(c, PlaybackState.STATE_PAUSED) && c.getMetadata() != null) pick = c;
            }
        }
        if (controller != null && (pick == null || !pick.getSessionToken().equals(controller.getSessionToken()))) {
            controller.unregisterCallback(controllerCallback);
            controller = null;
        }
        if (pick != null && controller == null) {
            controller = pick;
            controller.registerCallback(controllerCallback, main);
        }
        report();
    }

    private static boolean isState(MediaController c, int state) {
        PlaybackState s = c.getPlaybackState();
        return s != null && s.getState() == state;
    }

    private void report() {
        MediaController c = controller;
        MediaMetadata m = c != null ? c.getMetadata() : null;
        PlaybackState s = c != null ? c.getPlaybackState() : null;
        String title = m != null ? text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE) : "";
        if (title.isEmpty() || s == null || (s.getState() != PlaybackState.STATE_PLAYING
                && s.getState() != PlaybackState.STATE_PAUSED)) {
            callback.onTrack(null);
            return;
        }
        String artist = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) artist = text(m, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_ALBUM);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        callback.onTrack(new Track(title, artist, appName(c.getPackageName()), art,
                s.getState() == PlaybackState.STATE_PLAYING));
    }

    private static String text(MediaMetadata m, String first, String second) {
        CharSequence t = m.getText(first);
        if (t == null || t.length() == 0) t = m.getText(second);
        return t == null ? "" : t.toString().trim();
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
