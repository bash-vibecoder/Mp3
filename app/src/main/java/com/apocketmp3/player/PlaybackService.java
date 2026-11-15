package com.apocketmp3.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class PlaybackService extends Service {
    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 1;
    private final Binder binder = new LocalBinder();
    private final PlaybackQueue queue = new PlaybackQueue();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences playbackPrefs;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private NotificationManager notifications;
    private PowerManager.WakeLock preparingWakeLock;
    private Bitmap artwork;
    private String artworkUri = "";
    private boolean destroyed;
    private boolean ready, playing, loading, resumeAfterFocus, foreground;
    private boolean seekingPosition;
    private int resumePosition, rememberedDuration, activeSeekPosition;
    public String error;
    public int errorVersion;

    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            if (!playing) return;
            saveBookmark();
            handler.postDelayed(this, 5_000L);
        }
    };

    public final class LocalBinder extends Binder {
        public PlaybackService service() { return PlaybackService.this; }
    }

    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { pause(); }
    };

    @Override public void onCreate() {
        super.onCreate();
        playbackPrefs = getSharedPreferences("playback", MODE_PRIVATE);
        queue.shuffle = playbackPrefs.getBoolean("shuffle", false);
        int repeat = playbackPrefs.getInt("repeat", 0);
        queue.repeat = repeat >= 0 && repeat <= 2 ? repeat : 0;
        restorePlayback();
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Music playback", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Controls for your local MP3 files");
        notifications.createNotificationChannel(channel);
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        preparingWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "APocketMp3:prepare");
        preparingWakeLock.setReferenceCounted(false);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        if (player != null && ready) player.setVolume(0.2f, 0.2f);
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        if (player != null && ready) player.setVolume(1f, 1f);
                        if (resumeAfterFocus) { resumeAfterFocus = false; resume(); }
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        resumeAfterFocus = playing;
                        pauseInternal(false);
                    } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
                        pause();
                    }
                }).build();
        session = new MediaSession(this, "APocketMp3");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seek((int) Math.min(Integer.MAX_VALUE, position)); }
            @Override public void onStop() { stopPlayback(); }
        });
        session.setSessionActivity(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, filter);
        // Display the remembered song without creating a player or starting audio.
        if (current() != null) { loadArtwork(); updateState(); }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public boolean onUnbind(Intent intent) {
        saveBookmark();
        return super.onUnbind(intent);
    }

    @Override public void onTaskRemoved(Intent intent) {
        saveBookmark();
        super.onTaskRemoved(intent);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        String action = intent == null ? null : intent.getAction();
        if ("toggle".equals(action)) toggle();
        else if ("next".equals(action)) next();
        else if ("previous".equals(action)) previous();
        else if ("stop".equals(action)) stopPlayback();
        return START_NOT_STICKY;
    }

    public Track current() { return queue.current(); }
    public boolean isPlaying() { return playing; }
    public boolean isLoading() { return loading; }
    public int position() {
        if (ready && player != null && !seekingPosition) {
            try { return Math.max(0, player.getCurrentPosition()); }
            catch (IllegalStateException ignored) { }
        }
        return resumePosition;
    }
    public int duration() { return rememberedDuration; }

    private void restorePlayback() {
        String saved = playbackPrefs.getString("queue", null);
        if (saved == null) return;
        try {
            JSONArray items = new JSONArray(saved);
            ArrayList<Track> restored = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String uri = item.getString("uri");
                if (!uri.startsWith("content://") && !uri.startsWith("file://"))
                    throw new JSONException("Invalid local track URI");
                restored.add(new Track(uri, item.optString("title"), item.optString("artist"), item.optLong("duration")));
            }
            queue.set(restored, playbackPrefs.getInt("index", -1));
            rememberedDuration = Math.max(0, playbackPrefs.getInt("duration", trackDuration()));
            resumePosition = Math.max(0, playbackPrefs.getInt("position", 0));
            if (rememberedDuration > 0 && resumePosition >= rememberedDuration) resumePosition = 0;
        } catch (JSONException | IllegalArgumentException exception) {
            playbackPrefs.edit().remove("queue").remove("index").remove("position").remove("duration").apply();
        }
    }

    private int trackDuration() {
        return current() == null ? 0 : (int) Math.min(Integer.MAX_VALUE, current().duration);
    }

    private void saveQueue() {
        JSONArray items = new JSONArray();
        try {
            for (Track track : queue.snapshot()) {
                JSONObject item = new JSONObject();
                item.put("uri", track.uri); item.put("title", track.title);
                item.put("artist", track.artist); item.put("duration", track.duration);
                items.put(item);
            }
            // Save the new queue and its bookmark together, before preparing audio.
            playbackPrefs.edit().putString("queue", items.toString()).putInt("index", queue.currentIndex())
                    .putInt("position", 0).putInt("duration", trackDuration()).apply();
        } catch (JSONException ignored) { }
    }

    private void saveBookmark() {
        if (current() == null) return;
        resumePosition = position();
        playbackPrefs.edit().putInt("index", queue.currentIndex()).putInt("position", resumePosition)
                .putInt("duration", rememberedDuration).apply();
    }

    public boolean isShuffled() { return queue.shuffle; }
    public int repeatMode() { return queue.repeat; }

    public void toggleShuffle() {
        queue.shuffle = !queue.shuffle;
        playbackPrefs.edit().putBoolean("shuffle", queue.shuffle).apply();
    }

    public void cycleRepeat() {
        queue.repeat = (queue.repeat + 1) % 3;
        playbackPrefs.edit().putInt("repeat", queue.repeat).apply();
    }

    public void play(List<Track> tracks, int selected) {
        queue.set(tracks, selected);
        saveQueue();
        begin(0);
    }

    private void begin(int startPosition) {
        // MediaPlayer holds its own wake lock only while playing. Cover the handoff
        // between tracks and asynchronous preparation without keeping a paused app awake.
        preparingWakeLock.acquire(60_000L);
        releasePlayer();
        resumePosition = Math.max(0, startPosition);
        rememberedDuration = trackDuration();
        saveBookmark();
        resumeAfterFocus = false;
        error = null;
        loading = true;
        loadArtwork();
        session.setActive(true);
        if (!promote()) return;
        MediaPlayer candidate = new MediaPlayer();
        player = candidate;
        try {
            candidate.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            candidate.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            candidate.setDataSource(this, Uri.parse(current().uri));
            candidate.setOnPreparedListener(prepared -> {
                if (prepared != player) return;
                ready = true;
                rememberedDuration = Math.max(0, prepared.getDuration());
                if (rememberedDuration > 0 && resumePosition >= rememberedDuration) resumePosition = 0;
                if (resumePosition > 0) { seek(resumePosition); return; }
                loading = false;
                resume();
            });
            candidate.setOnSeekCompleteListener(prepared -> {
                if (prepared != player) return;
                try {
                    // Rapid seeks keep the newest requested bookmark until it completes.
                    if (resumePosition != activeSeekPosition) {
                        activeSeekPosition = resumePosition;
                        prepared.seekTo(activeSeekPosition, MediaPlayer.SEEK_CLOSEST);
                        return;
                    }
                    seekingPosition = false;
                    saveBookmark();
                    if (loading) { loading = false; resume(); }
                    else update();
                } catch (IllegalStateException exception) {
                    fail("Cannot seek in this MP3. Try playing it again.");
                }
            });
            candidate.setOnCompletionListener(completed -> {
                if (completed != player) return;
                playing = false;
                if (queue.next(true)) begin(0);
                else {
                    handler.removeCallbacks(checkpoint);
                    seek(0);
                    audio.abandonAudioFocusRequest(focus);
                    update();
                    demote(false);
                }
            });
            candidate.setOnErrorListener((failed, what, extra) -> {
                if (failed == player) fail("This MP3 cannot be played. Try another file.");
                return true;
            });
            candidate.prepareAsync();
            update();
        } catch (Exception exception) {
            fail("Cannot open this MP3. Add it again if it was moved or deleted.");
        }
    }

    private boolean promote() {
        try {
            if (!foreground) startForegroundService(new Intent(this, PlaybackService.class));
            startForeground(NOTIFICATION_ID, notification());
            foreground = true;
            return true;
        } catch (IllegalStateException | SecurityException exception) {
            // fail() also stops a start request that succeeded before promotion was
            // rejected, so Android is not left waiting for startForeground().
            fail("Android blocked background playback. Open APocketMp3 and check Battery settings.");
            return false;
        }
    }

    private void loadArtwork() {
        String uri = current().uri;
        if (uri.equals(artworkUri)) return;
        artworkUri = uri;
        artwork = null;
        ArtworkLoader.get(this).load(uri, bitmap -> {
            if (destroyed || !uri.equals(artworkUri)) return;
            artwork = bitmap;
            if (session.isActive()) update();
        });
    }

    private void demote(boolean remove) {
        stopForeground(remove ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
        foreground = false;
    }

    public void resume() {
        if (current() == null || loading) return;
        if (!ready || player == null) { begin(resumePosition); return; }
        if (!promote()) return;
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail("Audio is busy. Try playing again when the other audio stops.");
            return;
        }
        try {
            player.start();
            releasePreparingWakeLock();
            playing = true;
            saveBookmark();
            handler.removeCallbacks(checkpoint);
            handler.postDelayed(checkpoint, 5_000L);
            session.setActive(true);
            update();
        } catch (IllegalStateException exception) {
            fail("This MP3 cannot be played. Try another file.");
        }
    }

    public void toggle() { if (playing) pause(); else resume(); }

    public void pause() {
        resumeAfterFocus = false;
        pauseInternal(true);
    }

    private void pauseInternal(boolean abandonFocus) {
        saveBookmark();
        handler.removeCallbacks(checkpoint);
        // Cancel an unfinished prepare so a headphone disconnect cannot start audio later.
        if (loading) releasePlayer();
        releasePreparingWakeLock();
        if (player != null && ready && playing) player.pause();
        playing = false;
        if (abandonFocus) audio.abandonAudioFocusRequest(focus);
        update();
        if (abandonFocus) demote(false);
    }

    public void next() { if (queue.next(false)) begin(0); }
    public void previous() {
        if (queue.previous()) begin(0);
        else seek(0);
    }

    public void seek(int position) {
        if (current() == null) return;
        resumePosition = Math.max(0, duration() > 0 ? Math.min(position, duration()) : position);
        try {
            if (player != null && ready && !seekingPosition) {
                seekingPosition = true;
                activeSeekPosition = resumePosition;
                player.seekTo(activeSeekPosition, MediaPlayer.SEEK_CLOSEST);
            }
            saveBookmark();
            // A seek in the restored, paused UI must not create a notification.
            if (session.isActive()) update();
        } catch (IllegalStateException exception) {
            fail("Cannot seek in this MP3. Try playing it again.");
        }
    }

    private void fail(String message) {
        saveBookmark();
        releasePlayer();
        releasePreparingWakeLock();
        resumeAfterFocus = false;
        error = message;
        errorVersion++;
        audio.abandonAudioFocusRequest(focus);
        update();
        demote(false);
        stopSelf();
    }

    private void stopPlayback() {
        saveBookmark();
        releasePlayer();
        releasePreparingWakeLock();
        resumeAfterFocus = false;
        audio.abandonAudioFocusRequest(focus);
        session.setActive(false);
        updateState();
        demote(true);
        notifications.cancel(NOTIFICATION_ID);
        stopSelf();
    }

    private void releasePlayer() {
        handler.removeCallbacks(checkpoint);
        if (player != null) { player.release(); player = null; }
        ready = playing = loading = seekingPosition = false;
    }

    private void releasePreparingWakeLock() {
        if (preparingWakeLock != null && preparingWakeLock.isHeld()) preparingWakeLock.release();
    }

    private PendingIntent action(String action, int code) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        Track track = current();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_music)
                .setLargeIcon(artwork)
                .setContentTitle(track == null ? "APocketMp3" : track.title)
                .setContentText(error != null ? error : loading ? "Opening MP3…" : track == null ? "Local music" : track.artist)
                .setContentIntent(session.getController().getSessionActivity())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true).setOngoing(playing || loading || resumeAfterFocus)
                .setDeleteIntent(action("stop", 4))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", action("previous", 1)).build())
                .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play, playing ? "Pause" : "Play", action("toggle", 2)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action("next", 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }

    private void updateState() {
        Track track = current();
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, track == null ? "APocketMp3" : track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, track == null ? "" : track.artist)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        int state = loading ? PlaybackState.STATE_BUFFERING : playing ? PlaybackState.STATE_PLAYING
                : ready ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, position(), playing ? 1f : 0f, SystemClock.elapsedRealtime()).build());
    }

    private void update() {
        updateState();
        if (current() != null && session.isActive()) notifications.notify(NOTIFICATION_ID, notification());
    }

    @Override public void onDestroy() {
        saveBookmark();
        destroyed = true;
        releasePlayer();
        releasePreparingWakeLock();
        audio.abandonAudioFocusRequest(focus);
        session.release();
        unregisterReceiver(noisy);
        notifications.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }
}
