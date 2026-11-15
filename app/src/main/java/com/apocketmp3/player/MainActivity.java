package com.apocketmp3.player;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Outline;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = 0xff000000, PANEL = 0xff0c1421, BLUE = 0xff55a7ff;
    private static final int WHITE = 0xfff3f7ff, MUTED = 0xff8c9db5;
    private static final int PICK = 41, PERMISSION = 42;
    private final ArrayList<Track> tracks = new ArrayList<>(), visible = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SongAdapter adapter = new SongAdapter();
    private Set<String> favorites;
    private SharedPreferences prefs;
    private PlaybackService service;
    private boolean bound, onlyFavorites, seeking, scanning, active;
    private int shownError;
    private String query = "", displayedUri = "";
    private TextView count, emptyTitle, emptyBody, nowTitle, nowArtist, time, total;
    private Button allTab, favoritesTab, scanButton;
    private LinearLayout empty;
    private IconView play, shuffle, repeat, like, previous, next, battery;
    private CoverView cover;
    private ArtworkBackground backdrop;
    private SeekBar seek;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            shownError = service.errorVersion;
            refreshPlayer();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            refreshPlayer();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            refreshPlayer();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("library", MODE_PRIVATE);
        favorites = new HashSet<>(prefs.getStringSet("favorites", Collections.emptySet()));
        onlyFavorites = prefs.getBoolean("favoritesTab", false);
        if (state != null) {
            onlyFavorites = state.getBoolean("favoritesTab");
            query = state.getString("query", "");
        }
        buildUi();
        filter();
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
        handler.post(tick);
        loadLibrary();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshBatteryStatus();
    }

    @Override protected void onStop() {
        active = false;
        handler.removeCallbacks(tick);
        if (bound) { unbindService(connection); bound = false; }
        service = null;
        super.onStop();
    }

    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("favoritesTab", onlyFavorites);
        out.putString("query", query);
        super.onSaveInstanceState(out);
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }

    private Button button(String title, boolean primary, View.OnClickListener action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false);
        button.setTextSize(13); button.setTextColor(primary ? BG : WHITE);
        button.setMinHeight(0); button.setMinimumHeight(0); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), shape(primary ? BLUE : PANEL, 22), null));
        button.setOnClickListener(action); return button;
    }

    private IconView icon(String type, String label, View.OnClickListener action) {
        IconView view = new IconView(this, type); view.setContentDescription(label);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, shape(WHITE, 24)));
        view.setOnClickListener(action); view.setFocusable(true); return view;
    }

    private boolean batteryExempt() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean backgroundRestricted() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        return Build.VERSION.SDK_INT >= 28 && manager != null && manager.isBackgroundRestricted();
    }

    private void refreshBatteryStatus() {
        if (battery == null) return;
        boolean unrestricted = batteryExempt() && !backgroundRestricted();
        battery.tint = unrestricted ? BLUE : MUTED;
        battery.setContentDescription("Background playback and battery settings. "
                + (unrestricted ? "Battery optimization exemption enabled" : "Review battery restrictions"));
        battery.invalidate();
    }

    private void showBatterySettings() {
        boolean exempt = batteryExempt();
        String message = exempt
                ? "Android battery optimization is already disabled for APocketMp3."
                : "Allow APocketMp3 to ignore battery optimization to help keep music playing with the screen off. This may use more battery.";
        if (backgroundRestricted()) message += "\n\nAndroid currently restricts this app's background activity. Open App settings and change Battery to Unrestricted or allow background activity.";
        else message += "\n\nIf your phone still stops music, open App settings and choose Battery → Unrestricted. On phones with extra battery controls, also allow background activity or auto-start.";
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Keep music playing")
                .setMessage(message)
                .setNeutralButton("App settings", (d, which) -> openAppSettings())
                .setNegativeButton("Close", null);
        if (!exempt) dialog.setPositiveButton("Allow", (d, which) -> {
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            if (!openSettings(request)
                    && !openSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppSettings();
        });
        dialog.show();
    }

    private boolean openSettings(Intent intent) {
        try { startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException exception) { return false; }
    }

    private void openAppSettings() {
        if (!openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())))) {
            Toast.makeText(this, "Open Android Settings → Apps → APocketMp3 → Battery and allow background activity.", Toast.LENGTH_LONG).show();
        }
    }

    private void buildUi() {
        LinearLayout root = column(); root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        root.setFocusableInTouchMode(true); root.requestFocus();
        LinearLayout top = column(); top.setPadding(dp(22), dp(14), dp(22), dp(10));
        root.addView(top);
        LinearLayout brand = row();
        TextView logo = text("●  APocketMp3", 14, BLUE, true); logo.setLetterSpacing(0.09f);
        brand.addView(logo, new LinearLayout.LayoutParams(0, dp(48), 1)); logo.setGravity(Gravity.CENTER_VERTICAL);
        battery = icon("battery", "Background playback and battery settings", v -> showBatterySettings());
        brand.addView(battery, new LinearLayout.LayoutParams(dp(48), dp(48)));
        IconView add = icon("plus", "Add local MP3 files", v -> pickFiles());
        brand.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(brand);
        TextView heading = text("Your music.", 26, WHITE, true);
        heading.setPadding(0, dp(4), 0, dp(8)); top.addView(heading);
        TextView subtitle = text("LOCAL MP3  /  NO STREAMING", 10, MUTED, false);
        subtitle.setLetterSpacing(0.13f); top.addView(subtitle);
        LinearLayout searchBox = row(); searchBox.setBackground(shape(PANEL, 14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(48)); searchParams.topMargin = dp(18);
        top.addView(searchBox, searchParams);
        IconView searchIcon = new IconView(this, "search"); searchIcon.tint = MUTED;
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBox.addView(searchIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        EditText search = new EditText(this); search.setSingleLine(true); search.setTextSize(14);
        search.setHint("Search your MP3s"); search.setTextColor(WHITE); search.setHintTextColor(MUTED);
        search.setBackgroundColor(Color.TRANSPARENT); search.setPadding(0, 0, dp(12), 0);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); filter(); }
            public void afterTextChanged(Editable e) { }
        });
        LinearLayout tabs = row();
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, dp(42)); tabsParams.topMargin = dp(14);
        top.addView(tabs, tabsParams);
        allTab = button("All songs", true, v -> selectFavoritesTab(false));
        favoritesTab = button("Favorites", false, v -> selectFavoritesTab(true));
        tabs.addView(allTab, new LinearLayout.LayoutParams(dp(100), dp(36)));
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(dp(106), dp(36)); favoriteParams.leftMargin = dp(8);
        tabs.addView(favoritesTab, favoriteParams);
        View space = new View(this); tabs.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
        IconView refresh = icon("refresh", "Scan or refresh MP3 library", v -> scan());
        tabs.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(42)));
        count = text("", 11, MUTED, false); count.setPadding(dp(2), dp(10), 0, 0); top.addView(count);
        FrameLayout library = new FrameLayout(this); root.addView(library, new LinearLayout.LayoutParams(-1, 0, 1));
        ListView list = new ListView(this); list.setDivider(null); list.setClipToPadding(false);
        list.setPadding(dp(14), 0, dp(14), dp(10)); list.setAdapter(adapter);
        library.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = column(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(28), 0, dp(28), 0);
        library.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        emptyTitle = text("", 19, WHITE, true); emptyTitle.setGravity(Gravity.CENTER); empty.addView(emptyTitle);
        emptyBody = text("", 13, MUTED, false); emptyBody.setGravity(Gravity.CENTER); emptyBody.setPadding(0, dp(8), 0, dp(16)); empty.addView(emptyBody);
        LinearLayout actions = row(); actions.setGravity(Gravity.CENTER); empty.addView(actions);
        scanButton = button("Scan phone", true, v -> scan()); actions.addView(scanButton, new LinearLayout.LayoutParams(-2, dp(42)));
        Button pick = button("Pick MP3s", false, v -> pickFiles());
        LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(-2, dp(42)); pickParams.leftMargin = dp(8); actions.addView(pick, pickParams);
        buildPlayer(root);
    }

    private void buildPlayer(LinearLayout root) {
        LinearLayout player = column(); player.setPadding(dp(20), dp(14), dp(20), dp(10));
        // A background Drawable has no layout size and cannot consume the list's height.
        backdrop = new ArtworkBackground(this, dp(20));
        player.setBackground(backdrop); player.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(dp(12), 0, dp(12), dp(8)); root.addView(player, params);
        TextView label = text("NOW PLAYING", 9, BLUE, true); label.setLetterSpacing(0.16f); label.setPadding(0, 0, 0, dp(10)); player.addView(label);
        LinearLayout info = row(); player.addView(info);
        cover = new CoverView(this); info.addView(cover, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout names = column(); names.setPadding(dp(12), 0, dp(8), 0);
        info.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        nowTitle = text("Pick your first track", 15, WHITE, true); nowTitle.setSingleLine(true); nowTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowArtist = text("Your music stays on your phone", 11, MUTED, false); nowArtist.setSingleLine(true); nowArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        names.addView(nowTitle); names.addView(nowArtist);
        like = icon("heart", "Favorite current track", v -> { if (service != null && service.current() != null) toggleFavorite(service.current()); });
        info.addView(like, new LinearLayout.LayoutParams(dp(48), dp(48)));
        seek = new SeekBar(this); seek.setMax(1000); seek.setPadding(dp(4), dp(8), dp(4), 0);
        seek.setProgressTintList(ColorStateList.valueOf(BLUE)); seek.setThumbTintList(ColorStateList.valueOf(BLUE));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0xff26374e));
        seek.setContentDescription("Track playback position"); player.addView(seek, new LinearLayout.LayoutParams(-1, dp(32)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                if (user && service != null) time.setText(format(progress));
            }
            public void onStopTrackingTouch(SeekBar bar) { seeking = false; if (service != null) service.seek(bar.getProgress()); }
        });
        LinearLayout times = row(); player.addView(times);
        time = text("0:00", 10, MUTED, false); total = text("0:00", 10, MUTED, false); total.setGravity(Gravity.END);
        times.addView(time, new LinearLayout.LayoutParams(0, -2, 1)); times.addView(total, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER); player.addView(controls, new LinearLayout.LayoutParams(-1, dp(62)));
        shuffle = icon("shuffle", "Turn shuffle on", v -> { if (service != null) { service.toggleShuffle(); refreshPlayer(); } });
        previous = icon("previous", "Previous track", v -> { if (service != null) service.previous(); });
        play = icon("play", "Play", v -> {
            if (service == null) return;
            if (service.current() == null) { if (!visible.isEmpty()) service.play(visible, 0); }
            else service.toggle();
            refreshPlayer();
        });
        play.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44000000), shape(BLUE, 30), null)); play.tint = BG;
        next = icon("next", "Next track", v -> { if (service != null) service.next(); });
        repeat = icon("repeat", "Repeat off", v -> { if (service != null) { service.cycleRepeat(); refreshPlayer(); } });
        controls.addView(shuffle, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(play, new LinearLayout.LayoutParams(dp(56), dp(56)));
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(repeat, new LinearLayout.LayoutParams(0, dp(48), 1));
        refreshPlayer();
    }

    private void refreshPlayer() {
        if (play == null) return;
        Track current = service == null ? null : service.current();
        boolean hasTrack = current != null;
        boolean busy = service != null && service.isLoading();
        boolean playing = service != null && service.isPlaying();
        play.type = playing ? "pause" : "play";
        play.setEnabled(service != null && !busy && (hasTrack || !visible.isEmpty()));
        play.setAlpha(play.isEnabled() ? 1 : 0.4f);
        play.setContentDescription(busy ? "Opening MP3" : playing ? "Pause" : "Play"); play.invalidate();
        previous.setEnabled(hasTrack); next.setEnabled(hasTrack); like.setEnabled(hasTrack);
        previous.setAlpha(hasTrack ? 1 : 0.3f); next.setAlpha(hasTrack ? 1 : 0.3f); like.setAlpha(hasTrack ? 1 : 0.3f);
        if (hasTrack) {
            nowTitle.setText(current.title); nowArtist.setText(busy ? "Opening MP3…" : current.artist);
        } else { nowTitle.setText("Pick your first track"); nowArtist.setText("Your music stays on your phone"); }
        cover.setTrack(current); backdrop.setTrack(current);
        like.tint = hasTrack && favorites.contains(current.uri) ? BLUE : MUTED;
        like.filled = hasTrack && favorites.contains(current.uri); like.invalidate();
        like.setContentDescription(like.filled ? "Remove current track from favorites" : "Favorite current track");
        boolean shuffled = service != null && service.isShuffled();
        shuffle.tint = shuffled ? BLUE : MUTED; shuffle.invalidate();
        shuffle.setContentDescription(shuffled ? "Turn shuffle off" : "Turn shuffle on");
        int mode = service == null ? 0 : service.repeatMode();
        repeat.tint = mode == 0 ? MUTED : BLUE; repeat.type = mode == 2 ? "repeatOne" : "repeat"; repeat.invalidate();
        repeat.setContentDescription(mode == 0 ? "Repeat off. Tap to repeat all" : mode == 1 ? "Repeat all. Tap to repeat one" : "Repeat one. Tap to turn off");
        int duration = hasTrack ? service.duration() : 0;
        seek.setEnabled(duration > 0 && !busy);
        if (!seeking) { seek.setMax(Math.max(1, duration)); seek.setProgress(hasTrack ? service.position() : 0); time.setText(format(seek.getProgress())); }
        total.setText(format(duration));
        String uri = hasTrack ? current.uri : "";
        if (!uri.equals(displayedUri)) { displayedUri = uri; adapter.notifyDataSetChanged(); }
        if (service != null && service.errorVersion != shownError) { shownError = service.errorVersion; toast(service.error); }
    }

    private static String format(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private String permission() { return Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE; }
    private boolean canScan() { return checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED; }

    private void scan() {
        if (canScan()) loadLibrary();
        else requestPermissions(new String[]{permission()}, PERMISSION);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) loadLibrary();
            else { toast("You can still use Pick MP3s without library access."); filter(); }
        }
    }

    private void loadLibrary() {
        if (scanning || isDestroyed()) return;
        scanning = true; filter();
        worker.execute(() -> {
            LinkedHashMap<String, Track> found = new LinkedHashMap<>();
            String warning = null;
            if (canScan()) {
                Uri source = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String[] columns = {MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION};
                try (Cursor cursor = getContentResolver().query(source, columns, "LOWER(" + MediaStore.Audio.Media.DISPLAY_NAME + ") LIKE ?", new String[]{"%.mp3"}, null)) {
                    if (cursor != null) while (cursor.moveToNext()) {
                        String uri = ContentUris.withAppendedId(source, cursor.getLong(0)).toString();
                        String title = cursor.getString(2);
                        if (title == null || title.isEmpty()) title = stripExtension(cursor.getString(1));
                        found.put(uri, new Track(uri, title, cursor.getString(3), cursor.getLong(4)));
                    }
                } catch (Exception exception) { warning = "Could not scan the library. You can use Pick MP3s."; }
            }
            try {
                JSONArray imported = new JSONArray(prefs.getString("imports", "[]"));
                for (int i = 0; i < imported.length(); i++) {
                    JSONObject item = imported.getJSONObject(i);
                    Track track = new Track(item.getString("uri"), item.optString("title"), item.optString("artist"), item.optLong("duration"));
                    found.put(track.uri, track);
                }
            } catch (Exception ignored) { }
            ArrayList<Track> result = new ArrayList<>(found.values());
            Collections.sort(result, (a, b) -> a.title.compareToIgnoreCase(b.title));
            String message = warning;
            handler.post(() -> {
                if (isDestroyed()) return;
                tracks.clear(); tracks.addAll(result); scanning = false; filter();
                if (message != null) toast(message);
            });
        });
    }

    private static String stripExtension(String name) {
        if (name == null) return "Untitled MP3";
        return name.toLowerCase(Locale.ROOT).endsWith(".mp3") ? name.substring(0, name.length() - 4) : name;
    }

    private void selectFavoritesTab(boolean selected) {
        onlyFavorites = selected;
        prefs.edit().putBoolean("favoritesTab", selected).apply();
        filter();
    }

    private void filter() {
        visible.clear(); String needle = query.trim().toLowerCase(Locale.ROOT);
        for (Track track : tracks) {
            if (onlyFavorites && !favorites.contains(track.uri)) continue;
            if (!needle.isEmpty() && !(track.title + " " + track.artist).toLowerCase(Locale.ROOT).contains(needle)) continue;
            visible.add(track);
        }
        adapter.notifyDataSetChanged();
        if (count == null) return;
        count.setText(scanning ? "Finding local MP3s…" : visible.size() + (visible.size() == 1 ? " TRACK" : " TRACKS") + "  ·  ON YOUR DEVICE");
        allTab.setTextColor(onlyFavorites ? MUTED : BG); allTab.setBackgroundTintList(ColorStateList.valueOf(onlyFavorites ? PANEL : BLUE));
        favoritesTab.setTextColor(onlyFavorites ? BG : MUTED); favoritesTab.setBackgroundTintList(ColorStateList.valueOf(onlyFavorites ? BLUE : PANEL));
        if (scanning) { emptyTitle.setText("Finding your music"); emptyBody.setText("Looking for MP3 files on your phone."); }
        else if (!needle.isEmpty()) { emptyTitle.setText("No matching MP3s"); emptyBody.setText("Try a different title or artist."); }
        else if (onlyFavorites) { emptyTitle.setText("Keep your favorites close"); emptyBody.setText("Tap the heart beside a song to save it here."); }
        else { emptyTitle.setText("A little space for your music"); emptyBody.setText("Scan your phone or pick local MP3 files.\nNo account. No streaming."); }
        scanButton.setText(canScan() ? "Scan again" : "Scan phone");
        refreshPlayer();
    }

    private void toggleFavorite(Track track) {
        if (!favorites.add(track.uri)) favorites.remove(track.uri);
        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply(); filter();
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/mpeg");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(intent, PICK); }
        catch (android.content.ActivityNotFoundException e) { toast("No file picker is available on this device."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri uri : uris) {
            try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException exception) { toast("This file provider cannot keep access after a restart."); }
        }
        toast("Adding local MP3 files…");
        worker.execute(() -> {
            int added = 0, skipped = 0;
            try {
                JSONArray stored = new JSONArray(prefs.getString("imports", "[]"));
                Set<String> known = new HashSet<>();
                for (int i = 0; i < stored.length(); i++) known.add(stored.getJSONObject(i).getString("uri"));
                for (Uri uri : uris) {
                    if (known.contains(uri.toString())) continue;
                    String name = null;
                    try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
                    } catch (Exception exception) { skipped++; continue; }
                    if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".mp3")) { skipped++; continue; }
                    MediaMetadataRetriever reader = new MediaMetadataRetriever();
                    try {
                        reader.setDataSource(this, uri);
                        String title = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                        String artist = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                        String length = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        long duration = length == null ? 0 : Long.parseLong(length);
                        JSONObject item = new JSONObject();
                        item.put("uri", uri.toString()); item.put("title", title == null || title.isEmpty() ? stripExtension(name) : title);
                        item.put("artist", artist == null ? "Unknown artist" : artist); item.put("duration", duration);
                        stored.put(item); known.add(uri.toString()); added++;
                    } catch (Exception exception) { skipped++; }
                    finally { try { reader.release(); } catch (Exception ignored) { } }
                }
                prefs.edit().putString("imports", stored.toString()).apply();
            } catch (Exception exception) { skipped++; }
            String message = "Added " + added + (added == 1 ? " MP3" : " MP3s") + (skipped > 0 ? "; " + skipped + " unreadable or non-MP3 files skipped" : "");
            handler.post(() -> { if (!isDestroyed()) { toast(message); loadLibrary(); } });
        });
    }

    private void toast(String message) { if (message != null) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private void details(Track track) {
        ArrayList<String> options = new ArrayList<>(); options.add("File information");
        if (track.uri.startsWith("content://") && !track.uri.startsWith(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString() + "/")) options.add("Remove from library");
        new AlertDialog.Builder(this).setTitle(track.title).setItems(options.toArray(new String[0]), (dialog, which) -> {
            if (which == 0) new AlertDialog.Builder(this).setTitle(track.title).setMessage(track.artist + "\n" + format(track.duration) + "\n\n" + track.uri).setPositiveButton("Done", null).show();
            else removeImport(track);
        }).show();
    }

    private void removeImport(Track track) {
        worker.execute(() -> {
            try {
                JSONArray before = new JSONArray(prefs.getString("imports", "[]")), after = new JSONArray();
                for (int i = 0; i < before.length(); i++) {
                    JSONObject item = before.getJSONObject(i);
                    if (!track.uri.equals(item.getString("uri"))) after.put(item);
                }
                prefs.edit().putString("imports", after.toString()).apply();
                // The current player may still be reading it; release its grant after playback ends on a future launch.
            } catch (Exception ignored) { }
            handler.post(() -> { if (!isDestroyed()) loadLibrary(); });
        });
    }

    private final class SongAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Track getItem(int index) { return visible.get(index); }
        @Override public long getItemId(int index) { return index; }
        @Override public View getView(int index, View reused, ViewGroup parent) {
            LinearLayout song;
            SongHolder holder;
            if (reused == null) {
                song = row(); song.setPadding(dp(8), dp(9), dp(4), dp(9));
                holder = new SongHolder(); holder.art = new CoverView(MainActivity.this);
                song.addView(holder.art, new LinearLayout.LayoutParams(dp(44), dp(44)));
                LinearLayout labels = column(); labels.setPadding(dp(12), 0, dp(4), 0);
                holder.title = text("", 14, WHITE, true); holder.title.setSingleLine(true); holder.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                holder.artist = text("", 11, MUTED, false); holder.artist.setSingleLine(true); holder.artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(holder.title); labels.addView(holder.artist);
                song.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                holder.heart = icon("heart", "Favorite song", null);
                song.addView(holder.heart, new LinearLayout.LayoutParams(dp(48), dp(48)));
                song.setTag(holder);
            } else { song = (LinearLayout) reused; holder = (SongHolder) song.getTag(); }
            Track track = getItem(index);
            holder.art.setTrack(track);
            holder.title.setText(track.title); holder.title.setTextColor(track.uri.equals(displayedUri) ? BLUE : WHITE);
            holder.artist.setText(track.artist + "  ·  " + format(track.duration));
            holder.heart.filled = favorites.contains(track.uri); holder.heart.tint = holder.heart.filled ? BLUE : MUTED;
            holder.heart.setContentDescription((holder.heart.filled ? "Remove from favorites: " : "Favorite: ") + track.title);
            holder.heart.setOnClickListener(v -> toggleFavorite(track)); holder.heart.invalidate();
            song.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2255a7ff), shape(track.uri.equals(displayedUri) ? PANEL : Color.TRANSPARENT, 12), null));
            song.setOnClickListener(v -> {
                if (service != null) { int selected = visible.indexOf(track); if (selected >= 0) service.play(visible, selected); refreshPlayer(); }
            });
            song.setOnLongClickListener(v -> { details(track); return true; });
            song.setContentDescription(track.title + ", " + track.artist + ", " + format(track.duration));
            return song;
        }
    }

    private static final class SongHolder { CoverView art; TextView title, artist; IconView heart; }

    private static final class CoverView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF destination = new RectF();
        private final Path clip = new Path();
        private String artworkUri = "";
        private Bitmap bitmap;
        private int seed;
        CoverView(Context context) {
            super(context);
            paint.setFilterBitmap(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void setTrack(Track track) {
            String uri = track == null ? "" : track.uri;
            if (uri.equals(artworkUri)) return;
            artworkUri = uri; bitmap = null; seed = track == null ? 0 : track.title.hashCode(); invalidate();
            if (track != null) ArtworkLoader.get(getContext()).load(uri, picture -> {
                // Recycled rows and rapid skips must never display an older song's picture.
                if (uri.equals(artworkUri)) { bitmap = picture; invalidate(); }
            });
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), radius = Math.min(w, h) / 2;
            if (w <= 0 || h <= 0) return;
            if (bitmap != null) {
                canvas.save(); clip.reset();
                clip.addRoundRect(0, 0, w, h, radius * 0.3f, radius * 0.3f, Path.Direction.CW); canvas.clipPath(clip);
                float scale = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
                float width = bitmap.getWidth() * scale, height = bitmap.getHeight() * scale;
                destination.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2);
                paint.setShader(null); canvas.drawBitmap(bitmap, null, destination, paint); canvas.restore();
                return;
            }
            int[] colors = {0xff164d8c, 0xff225da2, 0xff173b71, 0xff2875b6, 0xff234374};
            paint.setShader(new LinearGradient(0, 0, w, h, colors[Math.floorMod(seed, colors.length)], 0xff030916, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0, 0, w, h, radius * 0.3f, radius * 0.3f, paint); paint.setShader(null);
            paint.setColor(0xff02050b); canvas.drawCircle(w / 2, h / 2, radius * 0.74f, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1, w / 80)); paint.setColor(0xff213a5b);
            canvas.drawCircle(w / 2, h / 2, radius * 0.57f, paint); canvas.drawCircle(w / 2, h / 2, radius * 0.45f, paint);
            paint.setStyle(Paint.Style.FILL); paint.setColor(BLUE); canvas.drawCircle(w / 2, h / 2, radius * 0.21f, paint);
            paint.setColor(BG); canvas.drawCircle(w / 2, h / 2, radius * 0.06f, paint);
        }
    }

    private static final class ArtworkBackground extends Drawable {
        private final ArtworkLoader loader;
        private final float cornerRadius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clip = new Path();
        private final RectF destination = new RectF();
        private String artworkUri = "";
        private Bitmap bitmap;

        ArtworkBackground(Context context, float cornerRadius) {
            loader = ArtworkLoader.get(context); this.cornerRadius = cornerRadius;
        }

        void setTrack(Track track) {
            String uri = track == null ? "" : track.uri;
            if (uri.equals(artworkUri)) return;
            artworkUri = uri; bitmap = null; invalidateSelf();
            if (track != null) loader.load(uri, picture -> {
                if (uri.equals(artworkUri)) { bitmap = picture; invalidateSelf(); }
            });
        }

        @Override public void draw(Canvas canvas) {
            float width = getBounds().width(), height = getBounds().height();
            if (width <= 0 || height <= 0) return;
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            clip.reset(); clip.addRoundRect(0, 0, width, height, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.clipPath(clip);
            if (bitmap != null) {
                float scale = Math.max(width / bitmap.getWidth(), height / bitmap.getHeight());
                float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
                destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2);
                canvas.drawBitmap(bitmap, null, destination, paint);
            }
            paint.setShader(new LinearGradient(0, 0, 0, height, bitmap == null ? 0xff112440 : 0x85000818,
                    bitmap == null ? 0xff03070e : 0xe8000308, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, paint); paint.setShader(null); canvas.restore();
        }

        @Override public void getOutline(Outline outline) { outline.setRoundRect(getBounds(), cornerRadius); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class IconView extends View {
        String type; int tint = WHITE; boolean filled;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(Context context, String type) { super(context); this.type = type; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth(), getHeight()) * 0.46f;
            canvas.save(); canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2); canvas.scale(size / 24, size / 24);
            paint.setColor(tint); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            switch (type) {
                case "play":
                    paint.setStyle(Paint.Style.FILL); path.moveTo(7, 3); path.lineTo(21, 12); path.lineTo(7, 21); path.close(); canvas.drawPath(path, paint); break;
                case "pause":
                    paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(6, 3, 10, 21, 1, 1, paint); canvas.drawRoundRect(14, 3, 18, 21, 1, 1, paint); break;
                case "previous": case "next":
                    if (type.equals("previous")) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                    paint.setStyle(Paint.Style.FILL); path.moveTo(5, 4); path.lineTo(17, 12); path.lineTo(5, 20); path.close(); canvas.drawPath(path, paint); canvas.drawRoundRect(18, 4, 21, 20, 1, 1, paint); break;
                case "heart":
                    path.moveTo(12, 21); path.cubicTo(-3, 11, 1, 2, 7, 3); path.cubicTo(10, 3, 12, 6, 12, 6); path.cubicTo(12, 6, 14, 3, 17, 3); path.cubicTo(23, 2, 27, 11, 12, 21); path.close();
                    if (filled) paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint); break;
                case "plus": canvas.drawLine(12, 4, 12, 20, paint); canvas.drawLine(4, 12, 20, 12, paint); break;
                case "battery":
                    canvas.drawRoundRect(2, 6, 20, 18, 2, 2, paint); canvas.drawLine(23, 10, 23, 14, paint);
                    path.moveTo(12, 8); path.lineTo(8, 12); path.lineTo(13, 12); path.lineTo(10, 16); canvas.drawPath(path, paint); break;
                case "search": canvas.drawCircle(10, 10, 6, paint); canvas.drawLine(15, 15, 21, 21, paint); break;
                case "shuffle":
                    path.moveTo(3, 6); path.lineTo(6, 6); path.cubicTo(11, 6, 13, 18, 18, 18); path.lineTo(22, 18); path.moveTo(18, 14); path.lineTo(22, 18); path.lineTo(18, 22);
                    path.moveTo(3, 18); path.lineTo(6, 18); path.cubicTo(11, 18, 13, 6, 18, 6); path.lineTo(22, 6); path.moveTo(18, 2); path.lineTo(22, 6); path.lineTo(18, 10); canvas.drawPath(path, paint); break;
                case "refresh":
                    canvas.drawArc(4, 4, 20, 20, 40, 280, false, paint); path.moveTo(20, 3); path.lineTo(20, 9); path.lineTo(14, 9); canvas.drawPath(path, paint); break;
                case "repeat": case "repeatOne":
                    path.moveTo(4, 10); path.lineTo(4, 8); path.quadTo(4, 5, 7, 5); path.lineTo(21, 5); path.moveTo(17, 1); path.lineTo(21, 5); path.lineTo(17, 9);
                    path.moveTo(20, 14); path.lineTo(20, 16); path.quadTo(20, 19, 17, 19); path.lineTo(3, 19); path.moveTo(7, 15); path.lineTo(3, 19); path.lineTo(7, 23); canvas.drawPath(path, paint);
                    if (type.equals("repeatOne")) { paint.setStyle(Paint.Style.FILL); paint.setTextSize(9); paint.setTypeface(Typeface.DEFAULT_BOLD); canvas.drawText("1", 10, 15, paint); } break;
            }
            canvas.restore();
        }
    }
}
