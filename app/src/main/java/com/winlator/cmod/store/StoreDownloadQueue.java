package com.winlator.cmod.store;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class StoreDownloadQueue {

    public interface DownloadListener {
        void onProgress(String msg, int pct);
        void onComplete(String installDir);
        void onError(String msg);
        void onCancelled();
    }

    public static class DownloadEntry {
        public final String dlKey;
        public final String store;
        public final String title;
        public volatile int     percent;
        public volatile String  status;
        public volatile boolean active;

        DownloadEntry(String dlKey, String store, String title) {
            this.dlKey   = dlKey;
            this.store   = store;
            this.title   = title;
            this.percent = 0;
            this.status  = "Starting…";
            this.active  = true;
        }
    }

    private static final String CHANNEL_ID     = "store_downloads";
    private static final String ACTION_CANCEL  = "com.winlator.cmod.store.CANCEL_DOWNLOAD";
    private static final String EXTRA_DL_KEY   = "dl_key";

    private static final ConcurrentHashMap<String, DownloadEntry>   entries     = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DownloadListener> listeners  = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Runnable>        cancelActions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer>         lastPct     = new ConcurrentHashMap<>();

    private static volatile Context            appCtx         = null;
    private static volatile BroadcastReceiver  cancelReceiver = null;
    private static final AtomicInteger         activeCount    = new AtomicInteger(0);

    // ── Public API ────────────────────────────────────────────────────────────

    public static boolean isActive(String dlKey) {
        DownloadEntry e = entries.get(dlKey);
        return e != null && e.active;
    }

    public static void cancel(Context ctx, String dlKey) {
        Runnable action = cancelActions.get(dlKey);
        if (action != null) action.run();
    }

    public static void addListener(String dlKey, DownloadListener l) {
        listeners.put(dlKey, l);
    }

    public static void removeListener(String dlKey) {
        listeners.remove(dlKey);
    }

    public static List<DownloadEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    public static DownloadEntry getEntry(String dlKey) {
        return entries.get(dlKey);
    }

    /** Returns the first active entry matching any of the given keys, or null. */
    public static DownloadEntry findActiveEntry(String... dlKeys) {
        for (String key : dlKeys) {
            DownloadEntry e = entries.get(key);
            if (e != null && e.active) return e;
        }
        return null;
    }

    // ── GOG ──────────────────────────────────────────────────────────────────

    public static void startGog(Context ctx, GogGame game, String dlKey) {
        DownloadEntry entry = new DownloadEntry(dlKey, "GOG", game.title);
        entries.put(dlKey, entry);
        onDownloadStarted(ctx, dlKey);

        Runnable cancelAction = GogDownloadManager.startDownload(ctx, game, new GogDownloadManager.Callback() {
            @Override public void onProgress(String msg, int pct) {
                entry.status = msg; entry.percent = pct;
                notifyProgress(dlKey, msg, pct);
                updateNotification(dlKey, entry);
            }
            @Override public void onComplete(String exePath) {
                ctx.getSharedPreferences("bh_gog_prefs", 0).edit()
                        .putString("gog_exe_" + game.gameId, exePath)
                        .putString("gog_dir_" + game.gameId, new File(exePath).getParent())
                        .apply();
                finish(dlKey, entry, false, false, null, exePath);
            }
            @Override public void onError(String msg) {
                finish(dlKey, entry, false, true, msg, null);
            }
            @Override public void onCancelled() {
                finish(dlKey, entry, true, false, null, null);
            }
        });
        cancelActions.put(dlKey, cancelAction);
    }

    // ── Epic ─────────────────────────────────────────────────────────────────

    public static void startEpic(Context ctx, EpicGame game, String dlKey) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        DownloadEntry entry = new DownloadEntry(dlKey, "EPIC", game.title);
        entries.put(dlKey, entry);
        onDownloadStarted(ctx, dlKey);
        cancelActions.put(dlKey, () -> {
            cancelled.set(true);
            finish(dlKey, entry, true, false, null, null);
        });

        new Thread(() -> {
            try {
                String token = EpicCredentialStore.getValidAccessToken(ctx);
                if (token == null) { finish(dlKey, entry, cancelled.get(), true, "Login required", null); return; }
                if (cancelled.get()) { finish(dlKey, entry, true, false, null, null); return; }

                entry.status = "Fetching manifest…";
                notifyProgress(dlKey, "Fetching manifest…", 0);
                updateNotification(dlKey, entry);

                String manifestJson = EpicApiClient.getManifestApiJson(
                        token, game.namespace, game.catalogItemId, game.appName);
                if (manifestJson == null) { finish(dlKey, entry, cancelled.get(), true, "Failed to fetch manifest", null); return; }
                if (cancelled.get()) { finish(dlKey, entry, true, false, null, null); return; }

                String sanitized = game.title.replaceAll("[^a-zA-Z0-9 \\-_]", "").trim();
                if (sanitized.isEmpty()) sanitized = "epic_" + game.appName.hashCode();
                File installDir = new File(new File(ctx.getFilesDir(), "imagefs/epic_games"), sanitized);
                ctx.getSharedPreferences("bh_epic_prefs", 0).edit()
                        .putString("epic_dir_" + game.appName, installDir.getAbsolutePath())
                        .apply();

                final String finalToken = token;
                boolean ok = EpicDownloadManager.install(ctx, manifestJson, finalToken,
                        installDir.getAbsolutePath(), (msg, pct) -> {
                            if (cancelled.get()) return;
                            entry.status = msg; entry.percent = pct;
                            notifyProgress(dlKey, msg, pct);
                            updateNotification(dlKey, entry);
                        });

                if (cancelled.get()) {
                    deleteDir(installDir);
                    ctx.getSharedPreferences("bh_epic_prefs", 0).edit()
                            .remove("epic_dir_" + game.appName).apply();
                    finish(dlKey, entry, true, false, null, null);
                    return;
                }
                if (!ok) { finish(dlKey, entry, false, true, "Download failed", null); return; }

                List<File> exeFiles = new ArrayList<>();
                AmazonLaunchHelper.collectExe(installDir, exeFiles);
                if (exeFiles.isEmpty()) { finish(dlKey, entry, false, true, "No executable found", null); return; }

                String lower = game.title.toLowerCase();
                Collections.sort(exeFiles, (a, b) ->
                        AmazonLaunchHelper.scoreExe(b, lower) - AmazonLaunchHelper.scoreExe(a, lower));

                String exePath = exeFiles.get(0).getAbsolutePath();
                ctx.getSharedPreferences("bh_epic_prefs", 0).edit()
                        .putString("epic_exe_" + game.appName, exePath)
                        .apply();
                finish(dlKey, entry, false, false, null, exePath);
            } catch (Exception e) {
                finish(dlKey, entry, false, true, e.getMessage(), null);
            }
        }, "epic-dl-" + game.appName).start();
    }

    // ── Amazon ───────────────────────────────────────────────────────────────

    public static void startAmazon(Context ctx, AmazonGame game, String dlKey) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        DownloadEntry entry = new DownloadEntry(dlKey, "AMAZON", game.title);
        entries.put(dlKey, entry);
        onDownloadStarted(ctx, dlKey);
        cancelActions.put(dlKey, () -> {
            cancelled.set(true);
            finish(dlKey, entry, true, false, null, null);
        });

        new Thread(() -> {
            try {
                String token = AmazonCredentialStore.getValidAccessToken(ctx);
                if (token == null) { finish(dlKey, entry, cancelled.get(), true, "Login required", null); return; }
                if (cancelled.get()) { finish(dlKey, entry, true, false, null, null); return; }

                String sanitized = game.title.replaceAll("[^a-zA-Z0-9 \\-_]", "").trim();
                if (sanitized.isEmpty()) sanitized = "game_" + game.productId.hashCode();
                File installDir = new File(new File(ctx.getFilesDir(), "imagefs/Amazon"), sanitized);
                ctx.getSharedPreferences("bh_amazon_prefs", 0).edit()
                        .putString("amazon_dir_" + game.productId, installDir.getAbsolutePath())
                        .apply();

                boolean ok = AmazonDownloadManager.install(ctx, game, token, installDir,
                        (dl, total, file) -> {
                            if (cancelled.get()) return;
                            int pct = (total > 0) ? (int) (dl * 100L / total) : 0;
                            String name = (file != null && !file.isEmpty()) ? file : "Downloading…";
                            entry.status = name; entry.percent = pct;
                            notifyProgress(dlKey, name, pct);
                            updateNotification(dlKey, entry);
                        },
                        cancelled::get);

                if (cancelled.get()) {
                    deleteDir(installDir);
                    ctx.getSharedPreferences("bh_amazon_prefs", 0).edit()
                            .remove("amazon_dir_" + game.productId).apply();
                    finish(dlKey, entry, true, false, null, null);
                    return;
                }
                if (!ok) { finish(dlKey, entry, false, true, "Download failed", null); return; }

                List<File> exeFiles = new ArrayList<>();
                AmazonLaunchHelper.collectExe(installDir, exeFiles);
                if (exeFiles.isEmpty()) { finish(dlKey, entry, false, true, "No executable found", null); return; }

                String lower = game.title.toLowerCase();
                Collections.sort(exeFiles, (a, b) ->
                        AmazonLaunchHelper.scoreExe(b, lower) - AmazonLaunchHelper.scoreExe(a, lower));

                String exePath = exeFiles.get(0).getAbsolutePath();
                ctx.getSharedPreferences("bh_amazon_prefs", 0).edit()
                        .putString("amazon_exe_" + game.productId, exePath)
                        .apply();
                finish(dlKey, entry, false, false, null, exePath);
            } catch (Exception e) {
                finish(dlKey, entry, false, true, e.getMessage(), null);
            }
        }, "amazon-dl-" + game.productId).start();
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private static void ensureChannel(Context ctx) {
        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Game Downloads", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Progress for GOG, Epic, and Amazon game downloads");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private static int notifId(String dlKey) {
        return ("dl:" + dlKey).hashCode();
    }

    private static void updateNotification(String dlKey, DownloadEntry e) {
        if (!e.active) return;
        Context ctx = appCtx;
        if (ctx == null) return;
        // Throttle: skip update if percent unchanged
        Integer prev = lastPct.get(dlKey);
        if (prev != null && prev == e.percent) return;
        lastPct.put(dlKey, e.percent);

        Intent cancelIntent = new Intent(ACTION_CANCEL).putExtra(EXTRA_DL_KEY, dlKey);
        PendingIntent cancelPi = PendingIntent.getBroadcast(ctx, notifId(dlKey),
                cancelIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent tapIntent = new Intent(ctx, DownloadsActivity.class);
        PendingIntent tapPi = PendingIntent.getActivity(ctx, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(e.store + ": " + e.title)
                .setContentText(e.status)
                .setProgress(100, e.percent, e.percent == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(tapPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPi)
                .build();

        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(notifId(dlKey), notif);
    }

    private static void postFinalNotification(String dlKey, DownloadEntry e,
                                              boolean cancelled, boolean error) {
        Context ctx = appCtx;
        if (ctx == null) return;

        String title, text;
        int icon;
        if (cancelled) {
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE))
                    .cancel(notifId(dlKey));
            return;
        } else if (error) {
            title = e.store + ": Download failed";
            text  = e.title + " — " + e.status;
            icon  = android.R.drawable.stat_notify_error;
        } else {
            title = e.store + ": Download complete";
            text  = e.title;
            icon  = android.R.drawable.stat_sys_download_done;
        }

        Intent tapIntent = new Intent(ctx, DownloadsActivity.class);
        PendingIntent tapPi = PendingIntent.getActivity(ctx, 0, tapIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(tapPi)
                .build();

        ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(notifId(dlKey), notif);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private static synchronized void onDownloadStarted(Context ctx, String dlKey) {
        if (appCtx == null) appCtx = ctx.getApplicationContext();
        ensureChannel(appCtx);
        lastPct.put(dlKey, -1);
        if (activeCount.getAndIncrement() == 0) {
            cancelReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    String key = i.getStringExtra(EXTRA_DL_KEY);
                    if (key != null) cancel(c, key);
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_CANCEL);
            if (Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appCtx.registerReceiver(cancelReceiver, filter);
            }
        }
    }

    private static synchronized void onDownloadFinished() {
        if (activeCount.decrementAndGet() == 0 && cancelReceiver != null) {
            try { appCtx.unregisterReceiver(cancelReceiver); } catch (Exception ignored) {}
            cancelReceiver = null;
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static void notifyProgress(String dlKey, String msg, int pct) {
        DownloadListener l = listeners.get(dlKey);
        if (l != null) l.onProgress(msg, pct);
    }

    private static void finish(String dlKey, DownloadEntry entry,
                                boolean wasCancelled, boolean wasError,
                                String errorMsg, String completePath) {
        if (!entry.active) return;
        entry.active = false;
        cancelActions.remove(dlKey);
        lastPct.remove(dlKey);
        postFinalNotification(dlKey, entry, wasCancelled, wasError);
        onDownloadFinished();
        DownloadListener l = listeners.get(dlKey);
        if (wasCancelled) {
            entry.status = "Cancelled";
            if (l != null) l.onCancelled();
        } else if (wasError) {
            entry.status = "Error: " + errorMsg;
            if (l != null) l.onError(errorMsg);
        } else {
            entry.status = "Complete";
            entry.percent = 100;
            if (l != null) l.onComplete(completePath);
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> entries.remove(dlKey), 60_000L);
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f); else f.delete();
        }
        dir.delete();
    }
}
