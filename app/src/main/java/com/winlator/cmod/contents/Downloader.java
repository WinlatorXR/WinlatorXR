package com.winlator.cmod.contents;

import android.app.Activity;
import android.content.Context;

import com.winlator.cmod.R;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.core.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Downloader {

    public static boolean downloadFile(String address, File file) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            // download the file
            InputStream input = url.openStream();

            // Output stream
            OutputStream output = new FileOutputStream(file.getAbsolutePath());

            byte[] data = new byte[1024];

            int count;
            while ((count = input.read(data)) != -1) {
                output.write(data, 0, count);
            }

            // flushing output
            output.flush();

            // closing streams
            output.close();
            input.close();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean downloadFileWithProgress(String address, File file, PreloaderDialog preloaderDialog) {
        Context context = preloaderDialog.getContext();
        preloaderDialog.showOnUiThread(R.string.downloading_file);

        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            long totalBytes = connection.getContentLengthLong();
            long downloadedBytes = 0;
            long lastDisplayedMB = -1;

            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(file.getAbsolutePath())) {

                byte[] data = new byte[8192];
                int count;

                while ((count = input.read(data)) != -1) {
                    output.write(data, 0, count);
                    downloadedBytes += count;

                    long downloadedMB = downloadedBytes / (1024 * 1024);
                    if (downloadedMB != lastDisplayedMB) {
                        lastDisplayedMB = downloadedMB;

                        final String progress;
                        if (totalBytes > 0) {
                            long totalMB = totalBytes / (1024 * 1024);
                            progress = context.getString(R.string.downloading_file) + "\n" + downloadedMB + " MB / " + totalMB + " MB";
                        } else {
                            progress = context.getString(R.string.downloading_file) + "\n" + downloadedMB + " MB";
                        }

                        preloaderDialog.updateText(progress);
                    }
                }
            }
            preloaderDialog.close();
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            preloaderDialog.close();
            return false;
        }
    }

    // Size of the file at address without downloading it, or -1 when the server does not say.
    public static long getContentLength(String address) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            long length = connection.getContentLengthLong();
            connection.disconnect();
            return length;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    // Shows the download size and free space first, so nothing is fetched without the user agreeing.
    public static void confirmDownload(Activity activity, PreloaderDialog preloaderDialog, String url, Runnable onConfirm) {
        preloaderDialog.showOnUiThread(R.string.checking_download_size);
        Executors.newSingleThreadExecutor().execute(() -> {
            long size = getContentLength(url);
            long free = activity.getCacheDir().getUsableSpace();
            activity.runOnUiThread(() -> {
                preloaderDialog.close();
                if (activity.isFinishing()) return;
                if (size > 0 && size > free) {
                    ContentDialog.alert(activity, activity.getString(R.string.download_not_enough_space,
                            StringUtils.formatBytes(size), StringUtils.formatBytes(free)), null);
                    return;
                }
                String sizeText = size > 0 ? StringUtils.formatBytes(size) : activity.getString(R.string.download_size_unknown);
                ContentDialog.confirm(activity, activity.getString(R.string.download_size_confirm,
                        sizeText, StringUtils.formatBytes(free)), onConfirm);
            });
        });
    }

    public static String downloadString(String address) {
        try {
            URL url = new URL(address);
            URLConnection connection = url.openConnection();
            connection.connect();

            InputStream input = url.openStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            StringBuilder sb = new StringBuilder();
            String line = null;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static ArrayList<String> extractContaining(String input, String regex) {
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(input);

        ArrayList<String> urls = new ArrayList<>();
        while (matcher.find()) {
            urls.add(matcher.group());
        }
        return urls;
    }

    public static ArrayList<String> getGithubZipLinks(String releases) {
        ArrayList<String> output = new ArrayList<>();
        String page = Downloader.downloadString(releases);
        if (page == null)
            return output;
        ArrayList<String> urls = Downloader.extractContaining(page, "https?://[^\\s\"]*expanded_assets[^\\s\"]*");
        for (String url : urls) {
            String assets = Downloader.downloadString(url);
            if (assets == null)
                continue;
            ArrayList<String> files = Downloader.extractContaining(assets, "download/[^\\s\"']+?\\.zip");
            for (String file : files) {
                output.add(releases + file);
            }
        }
        return output;
    }
}