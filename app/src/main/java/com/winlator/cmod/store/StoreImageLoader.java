package com.winlator.cmod.store;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared cover-art loader for the storefront grids.
 * Built with low-RAM XR devices in mind (Quest 2, Pico) for libraries in the thousands:
 */
public final class StoreImageLoader {

    private StoreImageLoader() {}

    /** Byte-bounded bitmap cache, capped at 1/8 of the app heap (a few MB on Quest-class devices). */
    private static final LruCache<String, Bitmap> CACHE;
    static {
        int cacheKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        CACHE = new LruCache<String, Bitmap>(cacheKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getAllocationByteCount() / 1024);  // size in KB
            }
        };
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "store-cover-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Load {@code url} into {@code iv}, downsampled to the view's size. */
    public static void load(ImageView iv, String url) {
        load(iv, url, null);
    }

    /**
     * Load {@code url} into {@code iv}, downsampled to the view's size.
     *
     * @param userAgent optional User-Agent header (GOG's image CDN expects one); may be null.
     */
    public static void load(ImageView iv, String url, String userAgent) {
        if (iv == null) return;
        if (url == null || url.isEmpty()) {
            iv.setTag(null);
            iv.setImageDrawable(null);
            return;
        }
        iv.setTag(url);

        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);  // clear any recycled cover while the new one loads

        // Target the largest decoded width we actually display. getWidth() is 0 on a
        // first (not-yet-laid-out) bind, so fall back to the column width.
        int targetW = iv.getWidth();
        if (targetW <= 0) {
            targetW = iv.getResources().getDisplayMetrics().widthPixels / StoreGridUi.COLUMNS;
        }
        final int reqWidth = targetW;
        final String finalUrl = url;
        final String ua = userAgent;
        POOL.submit(() -> {
            byte[] data = fetch(finalUrl, ua);
            if (data == null) return;
            Bitmap bmp = decodeSampled(data, reqWidth);
            if (bmp == null) return;
            CACHE.put(finalUrl, bmp);
            UI.post(() -> {
                if (finalUrl.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    /** Download the raw bytes for a cover. Returns null on any failure. */
    public static byte[] fetch(String url, String userAgent) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
            if (conn.getResponseCode() != 200) return null;
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) != -1) bos.write(buf, 0, read);
                return bos.toByteArray();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Decode {@code data} downsampled so the result is no smaller than {@code reqWidth}
     * wide, as RGB_565. Shared by every store (Steam calls this directly).
     */
    public static Bitmap decodeSampled(byte[] data, int reqWidth) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);

            int sample = 1;
            if (reqWidth > 0) {
                while (o.outWidth / (sample * 2) >= reqWidth) sample *= 2;
            }

            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;  // opaque covers: no alpha needed, half the RAM
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }
}
