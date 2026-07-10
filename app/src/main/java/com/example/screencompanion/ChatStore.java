package com.example.screencompanion;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ChatStore {
    private static final String TAG = "ChatStore";
    private static final String CHAT_FILE = "chat_history.jsonl";
    private static final String SCREENSHOT_DIR = "shared_screens";
    private static final int MAX_HISTORY_LINES = 1000;

    private ChatStore() {}

    public static File historyFile(Context context) {
        return new File(context.getFilesDir(), CHAT_FILE);
    }

    public static File screenshotDir(Context context) {
        File dir = new File(context.getFilesDir(), SCREENSHOT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create screenshot directory: " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static String saveScreenshot(Context context, byte[] jpegBytes) throws Exception {
        File dir = screenshotDir(context);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalStateException("截图目录不可用");
        }
        String name = "screen_" + System.currentTimeMillis() + ".jpg";
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(jpegBytes);
        }
        return file.getAbsolutePath();
    }

    public static synchronized void append(Context context, String role, String type, String text, String imagePath) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", "msg_" + System.currentTimeMillis());
            obj.put("time", nowIsoLike());
            obj.put("role", role);
            obj.put("type", type);
            obj.put("text", text == null ? JSONObject.NULL : text);
            obj.put("image_path", imagePath == null ? JSONObject.NULL : imagePath);

            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(historyFile(context), true), StandardCharsets.UTF_8)) {
                writer.write(obj.toString());
                writer.write("\n");
            }
            trimHistoryIfNeeded(context);
        } catch (Exception e) {
            Log.e(TAG, "Unable to append chat history", e);
        }
    }

    private static void trimHistoryIfNeeded(Context context) {
        List<String> lines = new ArrayList<>();
        File file = historyFile(context);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to inspect chat history size", e);
            return;
        }

        if (lines.size() <= MAX_HISTORY_LINES) return;
        int start = lines.size() - MAX_HISTORY_LINES;
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            for (int i = start; i < lines.size(); i++) {
                writer.write(lines.get(i));
                writer.write("\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to trim chat history", e);
        }
    }

    public static synchronized List<JSONObject> readAll(Context context) {
        ArrayList<JSONObject> result = new ArrayList<>();
        File file = historyFile(context);
        if (!file.exists()) return result;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    result.add(new JSONObject(line));
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed chat history line", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to read chat history", e);
        }
        return result;
    }

    public static synchronized List<JSONObject> recent(Context context, int max) {
        List<JSONObject> all = readAll(context);
        int start = Math.max(0, all.size() - Math.max(0, max));
        return new ArrayList<>(all.subList(start, all.size()));
    }

    public static String formatRecentForPrompt(Context context, int max) {
        List<JSONObject> recent = recent(context, max);
        if (recent.isEmpty()) return "暂无最近对话。";
        StringBuilder sb = new StringBuilder();
        for (JSONObject obj : recent) {
            String role = obj.optString("role", "");
            String type = obj.optString("type", "text");
            String text = obj.optString("text", "");
            if (JSONObject.NULL.toString().equals(text)) text = "";

            if ("user".equals(role)) {
                sb.append("用户：");
                if ("screenshot".equals(type)) {
                    sb.append(text.isEmpty() ? "分享了一张屏幕。" : text);
                } else {
                    sb.append(text);
                }
            } else {
                sb.append("你：").append(text);
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public static String renderForUi(Context context, int max) {
        List<JSONObject> recent = recent(context, max);
        if (recent.isEmpty()) return "还没有聊天记录。短按“给你看看”分享屏幕，或长按说一句话。";
        StringBuilder sb = new StringBuilder();
        for (JSONObject obj : recent) {
            String role = obj.optString("role", "");
            String type = obj.optString("type", "text");
            String text = obj.optString("text", "");
            if (JSONObject.NULL.toString().equals(text)) text = "";
            if ("user".equals(role)) {
                sb.append("我：");
                if ("screenshot".equals(type)) sb.append(text.isEmpty() ? "给你看看了当前屏幕" : text);
                else sb.append(text);
            } else {
                sb.append("Ta：").append(text);
            }
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    public static synchronized boolean clearHistory(Context context) {
        File file = historyFile(context);
        return !file.exists() || file.delete();
    }

    public static synchronized int screenshotCount(Context context) {
        File[] files = screenshotDir(context).listFiles();
        return files == null ? 0 : files.length;
    }

    public static synchronized long screenshotBytes(Context context) {
        File[] files = screenshotDir(context).listFiles();
        if (files == null) return 0L;
        long total = 0L;
        for (File file : files) {
            if (file.isFile()) total += file.length();
        }
        return total;
    }

    public static synchronized boolean clearScreenshots(Context context) {
        File dir = screenshotDir(context);
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && !file.delete()) {
                    success = false;
                    Log.w(TAG, "Unable to delete screenshot: " + file.getAbsolutePath());
                }
            }
        }
        return success;
    }

    public static synchronized boolean clearAll(Context context) {
        return clearHistory(context) & clearScreenshots(context);
    }

    @Deprecated
    public static synchronized void clear(Context context) {
        clearHistory(context);
    }

    private static String nowIsoLike() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
    }
}
