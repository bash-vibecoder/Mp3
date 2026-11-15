package com.apocketmp3.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Local embedded artwork only. Decode away from playback and cache at most 4 MiB. */
final class ArtworkLoader {
    interface Callback { void loaded(Bitmap bitmap); }
    private static ArtworkLoader instance;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>());
    private final HashMap<String, ArrayList<Callback>> pending = new HashMap<>();
    private final LruCache<String, Result> cache = new LruCache<String, Result>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String uri, Result result) {
            return result.bitmap == null ? 1024 : result.bitmap.getAllocationByteCount();
        }
    };

    private static final class Result {
        final Bitmap bitmap;
        Result(Bitmap bitmap) { this.bitmap = bitmap; }
    }

    private ArtworkLoader(Context context) { this.context = context.getApplicationContext(); }

    static ArtworkLoader get(Context context) {
        if (instance == null) instance = new ArtworkLoader(context);
        return instance;
    }

    // Called on the main thread by views and the playback service.
    void load(String uri, Callback callback) {
        Result cached = cache.get(uri);
        if (cached != null) { callback.loaded(cached.bitmap); return; }
        ArrayList<Callback> listeners = pending.get(uri);
        if (listeners != null) { listeners.add(callback); return; }
        listeners = new ArrayList<>(); listeners.add(callback); pending.put(uri, listeners);
        worker.execute(() -> {
            Bitmap bitmap = read(uri);
            main.post(() -> {
                cache.put(uri, new Result(bitmap));
                ArrayList<Callback> waiting = pending.remove(uri);
                if (waiting != null) for (Callback listener : waiting) listener.loaded(bitmap);
            });
        });
    }

    private Bitmap read(String uri) {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        try {
            reader.setDataSource(context, Uri.parse(uri));
            byte[] picture = reader.getEmbeddedPicture();
            if (picture == null || picture.length > 20 * 1024 * 1024) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 384)
                options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(picture, 0, picture.length, options);
        } catch (Exception | OutOfMemoryError exception) {
            return null;
        } finally {
            try { reader.release(); } catch (Exception ignored) { }
        }
    }
}
