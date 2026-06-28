package com.example.screencompanion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptureService extends Service {
    private static final int NOTIFICATION_ID = 42;
    private static final String CHANNEL_ID = "screen_companion";
    private static final long FLOATING_DOUBLE_TAP_MS = 420L;

    private Handler mainHandler;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private ExecutorService networkExecutor;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int width;
    private int height;
    private int densityDpi;

    private WindowManager windowManager;
    private Button floatingButton;
    private TextView replyBubble;
    private WindowManager.LayoutParams floatingParams;
    private WindowManager.LayoutParams bubbleParams;
    private boolean periodicRunning = false;
    private Runnable periodicRunnable;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private String lastReply = "";
    private boolean projectionForegroundStarted = false;
    private long lastFloatingTapAt = 0L;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            markCaptureReady(false);
            releaseProjection(false);
            broadcastStatus("屏幕捕获已停止，需要重新授权。", null);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        mainHandler = new Handler(Looper.getMainLooper());
        captureThread = new HandlerThread("capture-thread");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        networkExecutor = Executors.newSingleThreadExecutor();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        try {
            if (Actions.ACTION_START_PROJECTION.equals(action)) {
                if (!startForegroundForProjection("屏幕捕获已准备")) {
                    markCaptureReady(false);
                    stopSelf();
                    return START_NOT_STICKY;
                }
                startProjection(intent);
            } else if (Actions.ACTION_CAPTURE_ONCE.equals(action)) {
                triggerCapture("manual");
            } else if (Actions.ACTION_SEND_TEXT.equals(action)) {
                String userText = intent.getStringExtra(Actions.EXTRA_USER_TEXT);
                handleUserText(userText);
            } else if (Actions.ACTION_START_PERIODIC.equals(action)) {
                startPeriodic();
            } else if (Actions.ACTION_STOP_PERIODIC.equals(action)) {
                stopPeriodic();
                updateNotification("陪看模式已停止", lastReply);
            } else if (Actions.ACTION_SHOW_FLOATING.equals(action)) {
                showFloatingButton();
            } else if (Actions.ACTION_HIDE_FLOATING.equals(action)) {
                hideFloatingButton();
            } else {
                updateNotification("给你看看运行中", lastReply == null || lastReply.isEmpty() ? "等待操作" : lastReply);
            }
        } catch (Exception e) {
            broadcastStatus("服务处理失败：" + e.getMessage(), null);
            updateNotification("服务处理失败", e.getMessage());
            busy.set(false);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopPeriodic();
        hideFloatingButton();
        releaseProjection(true);
        markCaptureReady(false);
        if (captureThread != null) captureThread.quitSafely();
        if (networkExecutor != null) networkExecutor.shutdownNow();
        super.onDestroy();
    }

    private void markCaptureReady(boolean ready) {
        getSharedPreferences(Actions.PREFS, MODE_PRIVATE).edit()
                .putBoolean(Actions.PREF_CAPTURE_READY, ready)
                .apply();
    }

    private void startProjection(Intent intent) {
        int resultCode = intent.getIntExtra(Actions.EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(Actions.EXTRA_RESULT_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(Actions.EXTRA_RESULT_DATA);
        }
        if (resultCode == 0 || data == null) {
            markCaptureReady(false);
            broadcastStatus("屏幕捕获授权数据为空，请重新授权。", null);
            return;
        }

        releaseProjection(false);
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            mediaProjection = mpm.getMediaProjection(resultCode, data);
            if (mediaProjection == null) {
                markCaptureReady(false);
                broadcastStatus("无法创建 MediaProjection，请重新授权。", null);
                return;
            }
            mediaProjection.registerCallback(projectionCallback, captureHandler);
            setupVirtualDisplay();
            markCaptureReady(true);
            updateNotification("屏幕捕获已授权", "悬浮头像双击即可分享当前屏幕。 ");
            broadcastStatus("屏幕捕获已授权。", null);
        } catch (Exception e) {
            markCaptureReady(false);
            releaseProjection(false);
            broadcastStatus("屏幕捕获启动失败：" + e.getMessage(), null);
            updateNotification("屏幕捕获启动失败", e.getMessage());
        }
    }

    private void setupVirtualDisplay() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        width = Math.max(1, metrics.widthPixels);
        height = Math.max(1, metrics.heightPixels);
        densityDpi = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "MomentCompanionCapture",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler
        );
    }

    private void triggerCapture(String source) {
        if (mediaProjection == null || imageReader == null) {
            markCaptureReady(false);
            broadcastStatus("还没有屏幕捕获授权。请先打开 App 点“授权屏幕捕获”。", null);
            updateNotification("需要屏幕捕获授权", "打开 App 后先授权。 ");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            broadcastStatus("Ta 还在看上一条，稍等一下。", null);
            return;
        }

        boolean hadOverlay = floatingButton != null && floatingButton.getVisibility() == View.VISIBLE;
        if (hadOverlay) floatingButton.setVisibility(View.INVISIBLE);
        hideReplyBubble();
        mainHandler.postDelayed(() -> captureHandler.post(() -> captureNow(0)), hadOverlay ? 250 : 0);
        if (hadOverlay) {
            mainHandler.postDelayed(() -> {
                if (floatingButton != null) floatingButton.setVisibility(View.VISIBLE);
            }, 1300);
        }
    }

    private void captureNow(int attempt) {
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                if (attempt < 4) {
                    captureHandler.postDelayed(() -> captureNow(attempt + 1), 200);
                    return;
                }
                busy.set(false);
                broadcastStatus("暂时没有拿到屏幕帧，请再试一次。", null);
                return;
            }
            Bitmap bitmap = imageToBitmap(image);
            Bitmap scaled = scaleForModel(bitmap, 1280);
            if (scaled != bitmap) bitmap.recycle();
            byte[] jpegBytes = bitmapToJpegBytes(scaled, 65);
            scaled.recycle();
            String base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
            callOllamaForScreenshot(base64, jpegBytes);
        } catch (Exception e) {
            busy.set(false);
            broadcastStatus("截屏失败：" + e.getMessage(), null);
        } finally {
            if (image != null) image.close();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int bitmapWidth = width + Math.max(0, rowPadding / Math.max(1, pixelStride));
        Bitmap padded = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        padded.recycle();
        return cropped;
    }

    private Bitmap scaleForModel(Bitmap src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxSide) return src;
        float ratio = maxSide / (float) longest;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * ratio)), Math.max(1, Math.round(h * ratio)), true);
    }

    private byte[] bitmapToJpegBytes(Bitmap bitmap, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    private void handleUserText(String userText) {
        if (userText == null || userText.trim().isEmpty()) {
            broadcastStatus("没有收到文字。", null);
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            broadcastStatus("Ta 还在回复上一条，稍等一下。", null);
            return;
        }
        callOllamaForText(userText.trim());
    }

    private void callOllamaForScreenshot(String imageBase64, byte[] jpegBytes) {
        networkExecutor.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
                boolean saveHistory = prefs.getBoolean(Actions.PREF_SAVE_HISTORY, Actions.DEFAULT_SAVE_HISTORY);
                boolean saveScreenshots = prefs.getBoolean(Actions.PREF_SAVE_SCREENSHOTS, Actions.DEFAULT_SAVE_SCREENSHOTS);

                String imagePath = null;
                if (saveHistory && saveScreenshots) {
                    try {
                        imagePath = ChatStore.saveScreenshot(this, jpegBytes);
                    } catch (Exception ignored) {
                    }
                }
                if (saveHistory) {
                    ChatStore.append(this, "user", "screenshot", "给你看看了当前屏幕。", imagePath);
                }

                String prompt = buildPrompt(prefs, "screenshot", null);
                JSONObject payload = buildPayload(prefs, prompt, imageBase64);
                String reply = invokeOllama(prefs, payload);
                handleModelReply(reply, saveHistory);
            } catch (Exception e) {
                String msg = "调用 Ollama 失败：" + e.getMessage();
                updateNotification("Ta 暂时没回应", msg);
                broadcastStatus(msg, null);
            } finally {
                busy.set(false);
            }
        });
    }

    private void callOllamaForText(String userText) {
        networkExecutor.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
                boolean saveHistory = prefs.getBoolean(Actions.PREF_SAVE_HISTORY, Actions.DEFAULT_SAVE_HISTORY);
                if (saveHistory) ChatStore.append(this, "user", "text", userText, null);

                String prompt = buildPrompt(prefs, "text", userText);
                JSONObject payload = buildPayload(prefs, prompt, null);
                String reply = invokeOllama(prefs, payload);
                handleModelReply(reply, saveHistory);
            } catch (Exception e) {
                String msg = "调用 Ollama 失败：" + e.getMessage();
                updateNotification("Ta 暂时没回应", msg);
                broadcastStatus(msg, null);
            } finally {
                busy.set(false);
            }
        });
    }

    private JSONObject buildPayload(SharedPreferences prefs, String prompt, String imageBase64) throws Exception {
        String model = prefs.getString(Actions.PREF_MODEL, Actions.DEFAULT_MODEL);
        JSONObject payload = new JSONObject();
        payload.put("model", model == null || model.trim().isEmpty() ? Actions.DEFAULT_MODEL : model.trim());
        payload.put("prompt", prompt);
        payload.put("stream", false);
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JSONArray images = new JSONArray();
            images.put(imageBase64);
            payload.put("images", images);
        }
        return payload;
    }

    private String buildPrompt(SharedPreferences prefs, String inputType, String userText) {
        String basePrompt = prefs.getString(Actions.PREF_PROMPT, Actions.DEFAULT_PROMPT);
        String companionName = prefs.getString(Actions.PREF_COMPANION_NAME, Actions.DEFAULT_COMPANION_NAME);
        String style = prefs.getString(Actions.PREF_STYLE, Actions.DEFAULT_STYLE);
        String recent = ChatStore.formatRecentForPrompt(this, 10);

        String current;
        if ("screenshot".equals(inputType)) {
            current = "用户刚刚短按了按钮，主动把当前手机屏幕分享给你看。请像亲近的陪伴对象一样回应这张图。不要把它写成页面分析，不要给操作建议。";
        } else {
            current = "用户刚刚通过长按按钮输入或语音转文字告诉你：" + userText + "\n请像亲近的陪伴对象一样回应这句话。";
        }

        return basePrompt +
                "\n\n你的名字：" + companionName +
                "\n关系风格：" + style +
                "\n\n最近对话：\n" + recent +
                "\n\n当前输入：\n" + current +
                "\n\n请只输出“" + companionName + "”会说的话，不要加引号，不要加前缀。";
    }

    private String invokeOllama(SharedPreferences prefs, JSONObject payload) throws Exception {
        String endpoint = prefs.getString(Actions.PREF_ENDPOINT, Actions.DEFAULT_ENDPOINT);
        String url = normalizeGenerateUrl(endpoint);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(120000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String responseText = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + ": " + responseText);
        }
        JSONObject response = new JSONObject(responseText);
        String reply = response.optString("response", "").trim();
        if (reply.isEmpty()) reply = "我看到了，只是这一下有点安静。";
        return cleanupReply(reply);
    }

    private String cleanupReply(String reply) {
        String r = reply == null ? "" : reply.trim();
        r = r.replaceFirst("^Ta[:：]", "").trim();
        r = r.replaceFirst("^小雨[:：]", "").trim();
        if (r.length() > 220) r = r.substring(0, 220).trim();
        return r;
    }

    private void handleModelReply(String reply, boolean saveHistory) {
        lastReply = reply;
        if (saveHistory) ChatStore.append(this, "companion", "text", reply, null);
        updateNotification("Ta 回复了", reply);
        broadcastStatus(null, reply);
        mainHandler.post(() -> showReplyBubble(reply));
    }

    private String normalizeGenerateUrl(String endpoint) {
        String e = endpoint == null ? Actions.DEFAULT_ENDPOINT : endpoint.trim();
        if (e.isEmpty()) e = Actions.DEFAULT_ENDPOINT;
        if (e.endsWith("/api/generate")) return e;
        while (e.endsWith("/")) e = e.substring(0, e.length() - 1);
        return e + "/api/generate";
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void startPeriodic() {
        if (mediaProjection == null || imageReader == null) {
            markCaptureReady(false);
            broadcastStatus("陪看模式需要先授权屏幕捕获。", null);
            updateNotification("需要屏幕捕获授权", "打开 App 后先授权。 ");
            return;
        }
        if (periodicRunning) return;
        periodicRunning = true;
        periodicRunnable = new Runnable() {
            @Override
            public void run() {
                if (!periodicRunning) return;
                triggerCapture("periodic");
                int seconds = getSharedPreferences(Actions.PREFS, MODE_PRIVATE)
                        .getInt(Actions.PREF_INTERVAL, Actions.DEFAULT_INTERVAL_SECONDS);
                mainHandler.postDelayed(this, Math.max(5, seconds) * 1000L);
            }
        };
        mainHandler.post(periodicRunnable);
        broadcastStatus("已开始陪看模式。", null);
        updateNotification("陪看模式运行中", "Ta 会按间隔看一眼屏幕。 ");
    }

    private void stopPeriodic() {
        periodicRunning = false;
        if (periodicRunnable != null) mainHandler.removeCallbacks(periodicRunnable);
    }

    private void showFloatingButton() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            broadcastStatus("没有悬浮窗权限，请先在 App 里开启。", null);
            return;
        }
        if (floatingButton != null) return;
        floatingButton = new Button(this);
        floatingButton.setText("双击\n给Ta看");
        floatingButton.setAllCaps(false);
        floatingButton.setAlpha(0.86f);
        floatingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        floatingParams.gravity = Gravity.TOP | Gravity.START;
        floatingParams.x = 40;
        floatingParams.y = 260;
        attachFloatingTouchBehavior(floatingButton, floatingParams);
        try {
            windowManager.addView(floatingButton, floatingParams);
            broadcastStatus("悬浮头像已显示。双击给 Ta 看，长按跟 Ta 说，拖动只移动位置。", null);
        } catch (Exception e) {
            floatingButton = null;
            broadcastStatus("悬浮头像显示失败：" + e.getMessage(), null);
        }
    }

    private void attachFloatingTouchBehavior(View view, WindowManager.LayoutParams params) {
        final float[] downRawX = new float[1];
        final float[] downRawY = new float[1];
        final int[] startX = new int[1];
        final int[] startY = new int[1];
        final boolean[] dragging = new boolean[1];
        final boolean[] longPressed = new boolean[1];
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final Runnable[] longPressRunnable = new Runnable[1];

        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX[0] = event.getRawX();
                    downRawY[0] = event.getRawY();
                    startX[0] = params.x;
                    startY[0] = params.y;
                    dragging[0] = false;
                    longPressed[0] = false;
                    longPressRunnable[0] = () -> {
                        if (!dragging[0]) {
                            longPressed[0] = true;
                            lastFloatingTapAt = 0L;
                            openVoiceInputFromFloating();
                        }
                    };
                    mainHandler.postDelayed(longPressRunnable[0], ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - downRawX[0]);
                    int dy = Math.round(event.getRawY() - downRawY[0]);
                    if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
                        dragging[0] = true;
                        if (longPressRunnable[0] != null) mainHandler.removeCallbacks(longPressRunnable[0]);
                        params.x = startX[0] + dx;
                        params.y = startY[0] + dy;
                        try {
                            if (floatingButton != null) windowManager.updateViewLayout(floatingButton, params);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (longPressRunnable[0] != null) mainHandler.removeCallbacks(longPressRunnable[0]);
                    if (!dragging[0] && !longPressed[0]) {
                        long now = System.currentTimeMillis();
                        if (now - lastFloatingTapAt <= FLOATING_DOUBLE_TAP_MS) {
                            lastFloatingTapAt = 0L;
                            triggerCapture("floating-double-tap");
                        } else {
                            lastFloatingTapAt = now;
                            broadcastStatus("再点一下，给 Ta 看当前屏幕。", null);
                        }
                    }
                    dragging[0] = false;
                    longPressed[0] = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (longPressRunnable[0] != null) mainHandler.removeCallbacks(longPressRunnable[0]);
                    dragging[0] = false;
                    longPressed[0] = false;
                    return true;
                default:
                    return true;
            }
        });
    }

    private void openVoiceInputFromFloating() {
        Intent i = new Intent(this, MainActivity.class);
        i.setAction(Actions.ACTION_OPEN_VOICE_INPUT);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(i);
        } catch (Exception e) {
            broadcastStatus("无法打开输入界面：" + e.getMessage(), null);
        }
    }

    private void showReplyBubble(String text) {
        if (floatingButton == null || text == null || text.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        hideReplyBubble();
        replyBubble = new TextView(this);
        replyBubble.setText(text);
        replyBubble.setTextSize(15);
        replyBubble.setTextColor(Color.WHITE);
        replyBubble.setBackgroundColor(0xCC333333);
        int pad = Math.round(getResources().getDisplayMetrics().density * 10);
        replyBubble.setPadding(pad, pad, pad, pad);
        bubbleParams = new WindowManager.LayoutParams(
                Math.round(getResources().getDisplayMetrics().density * 260),
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = floatingParams == null ? 40 : floatingParams.x;
        bubbleParams.y = floatingParams == null ? 360 : floatingParams.y + 90;
        try {
            windowManager.addView(replyBubble, bubbleParams);
            mainHandler.postDelayed(this::hideReplyBubble, 9000);
        } catch (Exception ignored) {
            replyBubble = null;
        }
    }

    private void hideReplyBubble() {
        if (replyBubble != null) {
            try {
                windowManager.removeView(replyBubble);
            } catch (Exception ignored) {
            }
            replyBubble = null;
        }
    }

    private void hideFloatingButton() {
        hideReplyBubble();
        if (floatingButton != null) {
            try {
                windowManager.removeView(floatingButton);
            } catch (Exception ignored) {
            }
            floatingButton = null;
            broadcastStatus("悬浮头像已隐藏。", null);
        }
    }

    private void releaseProjection(boolean stopProjection) {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            try {
                mediaProjection.unregisterCallback(projectionCallback);
            } catch (Exception ignored) {
            }
            if (stopProjection) {
                try {
                    mediaProjection.stop();
                } catch (Exception ignored) {
                }
            }
            mediaProjection = null;
        }
        if (stopProjection) markCaptureReady(false);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "给你看看", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("主动分享式陪伴服务");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent captureIntent = new Intent(this, MainActivity.class);
        captureIntent.setAction(Actions.ACTION_CAPTURE_ONCE);
        captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent capturePi = PendingIntent.getActivity(this, 2, captureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(title == null ? "给你看看" : title)
                .setContentText(text == null ? "运行中" : text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(openPi)
                .setOngoing(projectionForegroundStarted)
                .addAction(android.R.drawable.ic_menu_camera, "给 Ta 看一眼", capturePi)
                .setStyle(new Notification.BigTextStyle().bigText(text == null ? "运行中" : text));
        return builder.build();
    }

    private boolean startForegroundForProjection(String title) {
        Notification n = buildNotification(title, "屏幕捕获服务运行中");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            projectionForegroundStarted = true;
            return true;
        } catch (Exception e) {
            projectionForegroundStarted = false;
            broadcastStatus("前台屏幕捕获服务启动失败：" + e.getMessage(), null);
            try {
                startForeground(NOTIFICATION_ID, n);
                projectionForegroundStarted = true;
                return true;
            } catch (Exception ignored) {
                updateNotification("前台服务启动失败", e.getMessage());
                return false;
            }
        }
    }

    private void updateNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(title, text));
        } catch (Exception ignored) {
        }
    }

    private void broadcastStatus(String status, String text) {
        Intent i = new Intent(Actions.ACTION_RESULT);
        i.setPackage(getPackageName());
        if (status != null) i.putExtra(Actions.EXTRA_STATUS, status);
        if (text != null) i.putExtra(Actions.EXTRA_TEXT, text);
        try {
            sendBroadcast(i);
        } catch (Exception ignored) {
        }
    }
}
