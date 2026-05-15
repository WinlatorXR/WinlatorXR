package com.winlator.cmod.store;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class SplashInstallActivity extends Activity {

    private static final int LATEST_VERSION = 21;

    private ProgressBar progressBar;
    private TextView statusText;
    private TextView percentText;
    private Button proceedButton;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Already installed: skip straight to MainActivity, zero UI shown
        if (isAlreadyInstalled()) {
            launchMainActivity();
            return;
        }

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int layoutId = getResources().getIdentifier(
                "activity_splash_install", "layout", getPackageName());
        setContentView(layoutId);

        ImageView logo = findViewById(getResources().getIdentifier(
                "splash_logo", "id", getPackageName()));
        int iconId = getResources().getIdentifier(
                "ic_launcher_foreground", "mipmap", getPackageName());
        if (logo != null && iconId != 0) logo.setImageResource(iconId);

        statusText   = findViewById(getResources().getIdentifier("splash_status_text",    "id", getPackageName()));
        progressBar  = findViewById(getResources().getIdentifier("splash_progress_bar",   "id", getPackageName()));
        percentText  = findViewById(getResources().getIdentifier("splash_percent_text",   "id", getPackageName()));
        proceedButton = findViewById(getResources().getIdentifier("splash_proceed_button", "id", getPackageName()));

        if (proceedButton != null) {
            proceedButton.setVisibility(View.GONE);
            proceedButton.setOnClickListener(v -> onProceedClicked());
        }

        Executors.newSingleThreadExecutor().execute(this::runInstall);
    }

    private boolean isAlreadyInstalled() {
        try {
            Class<?> cls = Class.forName("com.winlator.cmod.xenvironment.ImageFs");
            Object imageFs = cls.getMethod("find", Context.class).invoke(null, this);
            boolean valid = (boolean) cls.getMethod("isValid").invoke(imageFs);
            int version   = (int)     cls.getMethod("getVersion").invoke(imageFs);
            return valid && version >= LATEST_VERSION;
        } catch (Exception e) {
            return false;
        }
    }

    private void runInstall() {
        try {
            // --- resolve internal classes ---
            Class<?> imageFsClass  = Class.forName("com.winlator.cmod.xenvironment.ImageFs");
            Class<?> fileUtilsClass = Class.forName("com.winlator.cmod.core.FileUtils");
            Class<?> tarClass      = Class.forName("com.winlator.cmod.core.TarCompressorUtils");
            Class<?> tarTypeClass  = Class.forName("com.winlator.cmod.core.TarCompressorUtils$Type");
            Class<?> listenerClass = Class.forName("com.winlator.cmod.core.OnExtractFileListener");
            Object   xzType        = tarTypeClass.getField("XZ").get(null);

            Object imageFs = imageFsClass.getMethod("find", Context.class).invoke(null, this);
            File rootDir   = (File) imageFsClass.getMethod("getRootDir").invoke(imageFs);

            // --- wipe old rootfs (preserve home dir) ---
            clearRootDir(rootDir);
            setProgress("Installing system files", 0);

            // --- calculate expected uncompressed size (compression ratio ≈ 100/22) ---
            long rawSize       = (long) fileUtilsClass.getMethod("getSize", Context.class, String.class)
                    .invoke(null, this, "imagefs.txz");
            final long contentLength = (long) (rawSize * (100.0f / 22));
            AtomicLong totalSizeRef  = new AtomicLong();

            // --- extract imagefs.txz with live progress via reflection proxy ---
            Object progressListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class[]{listenerClass},
                    (proxy, method, args) -> {
                        File file  = (File) args[0];
                        long size  = (Long) args[1];
                        if (size > 0) {
                            long total = totalSizeRef.addAndGet(size);
                            int pct = (int) Math.min(((float) total / contentLength) * 100, 99);
                            handler.post(() -> setProgress("Installing system files", pct));
                        }
                        return file;
                    }
            );

            Method extractWithListener = tarClass.getMethod("extract",
                    tarTypeClass, Context.class, String.class, File.class, listenerClass);
            boolean success = (boolean) extractWithListener.invoke(
                    null, xzType, this, "imagefs.txz", rootDir, progressListener);

            if (!success) {
                handler.post(() -> Toast.makeText(this,
                        "Installation failed — please reinstall the app.", Toast.LENGTH_LONG).show());
                return;
            }

            // --- install Wine txz files ---
            handler.post(() -> setProgress("Extracting Wine", 99));
            Method extractSimple = tarClass.getMethod("extract",
                    tarTypeClass, Context.class, String.class, File.class);
            int wineArrayId = getResources().getIdentifier(
                    "wine_entries", "array", getPackageName());
            String[] wineVersions = getResources().getStringArray(wineArrayId);
            for (String ver : wineVersions) {
                File outFile = new File(rootDir, "/opt/" + ver);
                outFile.mkdirs();
                extractSimple.invoke(null, xzType, this, ver + ".txz", outFile);
            }

            // --- install bundled graphics drivers ---
            handler.post(() -> setProgress("Installing drivers", 99));
            Class<?> adrenoClass = Class.forName("com.winlator.cmod.contents.AdrenotoolsManager");
            Object   adrenoMgr   = adrenoClass.getConstructor(Context.class).newInstance(this);
            Method   extractDrv  = adrenoClass.getMethod("extractDriverFromResources", String.class);
            int driversArrayId = getResources().getIdentifier(
                    "wrapper_graphics_driver_version_entries", "array", getPackageName());
            String[] drivers = getResources().getStringArray(driversArrayId);
            for (String drv : drivers) extractDrv.invoke(adrenoMgr, drv);

            // --- stamp version file ---
            imageFsClass.getMethod("createImgVersionFile", int.class)
                    .invoke(imageFs, LATEST_VERSION);

            // --- libSDL2 symlink ---
            File libDir = (File) imageFsClass.getMethod("getLibDir").invoke(imageFs);
            fileUtilsClass.getMethod("symlink", String.class, String.class)
                    .invoke(null, "libSDL2-2.0.so",
                            new File(libDir, "libSDL2-2.0.so.0").getAbsolutePath());

            // --- reset container imgVersion fields (best-effort, no-op on fresh install) ---
            try {
                Method reset = Class.forName("com.winlator.cmod.xenvironment.ImageFsInstaller")
                        .getDeclaredMethod("resetContainerImgVersions", Context.class);
                reset.setAccessible(true);
                reset.invoke(null, this);
            } catch (Exception ignored) {}

            handler.post(() -> {
                setProgress("Installation complete", 100);
                if (proceedButton != null) proceedButton.setVisibility(View.VISIBLE);
            });

        } catch (Exception e) {
            handler.post(() -> {
                Toast.makeText(this,
                        "Setup error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                launchMainActivity();
            });
        }
    }

    private void setProgress(String message, int pct) {
        if (statusText  != null) statusText.setText(message);
        if (progressBar != null) progressBar.setProgress(pct);
        if (percentText != null) percentText.setText(pct + "%");
    }

    private void onProceedClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !Environment.isExternalStorageManager()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
        launchMainActivity();
    }

    private void launchMainActivity() {
        try {
            Intent intent = new Intent(this,
                    Class.forName("com.winlator.cmod.MainActivity"));
            startActivity(intent);
        } catch (Exception ignored) {}
        finish();
    }

    private void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory() && "home".equals(f.getName())) continue;
                    deleteRecursive(f);
                }
            }
        } else {
            rootDir.mkdirs();
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
