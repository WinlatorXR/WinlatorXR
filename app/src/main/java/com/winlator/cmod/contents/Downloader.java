package com.winlator.cmod.contents;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
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