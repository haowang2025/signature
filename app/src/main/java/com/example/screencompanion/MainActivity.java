package com.example.screencompanion;

import android.Manifest;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Executor;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private EditText endpointEdit;
    private EditText modelEdit;
    private EditText intervalEdit;
    private EditText companionNameEdit;
    private EditText styleEdit;
    private EditText promptEdit;
    private EditText voiceEdit;
    private CheckBox saveHistoryCheck;
    private CheckBox saveScreenshotsCheck;
    private TextView resultText;
    private TextView historyText;

    private final BroadcastReceiver resultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Actions.ACTION_RESULT.equals(intent.getAction())) return;
            String text = intent.getStringExtra(Actions.EXTRA_TEXT);
            String status = intent.getStringExtra(Actions.EXTRA_STATUS);
            if (text != null && !text.isEmpty()) {
                resultText.setText(text);
            } else if (status != null && !status.isEmpty()) {
                resultText.setText(status);
            }
            refreshHistory();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadPrefs();
        refreshHistory();
        requestNotificationPermissionIfNeeded();
        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(Actions.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resultReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(resultReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        savePrefs();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Actions.ACTION_OPEN_VOICE_INPUT.equals(action)) {
            voiceEdit.postDelayed(this::openVoiceInput, 250);
        } else if (Actions.ACTION_CAPTURE_ONCE.equals(action)) {
            resultText.postDelayed(this::captureOrAskAuthorization, 250);
        }
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(24));
        scrollView.addView(root);

        root.addView(text("给你看看", 26, true));
        root.addView(text("主动分享式陪伴 App。短按把当前屏幕给 Ta 看一眼，长按把想说的话告诉 Ta。", 14, false));

        endpointEdit = edit("Ollama 地址，例如 http://127.0.0.1:11434", false);
        modelEdit = edit("模型，例如 gemma3:12b", false);
        intervalEdit = edit("陪看模式间隔秒数", false);
        intervalEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        companionNameEdit = edit("陪伴对象名字，例如 小雨", false);
        styleEdit = edit("关系风格，例如 温柔、亲近、轻微暧昧", true);
        styleEdit.setMinLines(2);
        promptEdit = edit("固定人设 Prompt", true);
        promptEdit.setMinLines(8);

        root.addView(label("Ollama 地址"));
        root.addView(endpointEdit);
        root.addView(label("模型"));
        root.addView(modelEdit);
        root.addView(label("陪伴对象名字"));
        root.addView(companionNameEdit);
        root.addView(label("关系风格"));
        root.addView(styleEdit);
        root.addView(label("人设 Prompt"));
        root.addView(promptEdit);

        saveHistoryCheck = new CheckBox(this);
        saveHistoryCheck.setText("保存文字聊天记录");
        saveHistoryCheck.setPadding(0, dp(12), 0, 0);
        root.addView(saveHistoryCheck);

        saveScreenshotsCheck = new CheckBox(this);
        saveScreenshotsCheck.setText("保存分享过的截图（默认关闭，涉及隐私）");
        root.addView(saveScreenshotsCheck);

        Button grantButton = button("1. 授权屏幕捕获");
        grantButton.setOnClickListener(v -> requestScreenCapture());
        root.addView(grantButton);

        Button coreButton = button("短按：给你看看｜长按：跟你说");
        coreButton.setTextSize(17);
        coreButton.setMinHeight(dp(64));
        coreButton.setOnClickListener(v -> captureOrAskAuthorization());
        coreButton.setOnLongClickListener(v -> {
            openVoiceInput();
            return true;
        });
        root.addView(coreButton);

        root.addView(label("我想说"));
        voiceEdit = edit("长按按钮后这里会获得焦点。可以点输入法麦克风，把语音转成文字。", true);
        voiceEdit.setMinLines(3);
        root.addView(voiceEdit);

        Button sendTextButton = button("发送给 Ta");
        sendTextButton.setOnClickListener(v -> sendUserText());
        root.addView(sendTextButton);

        LinearLayout floatRow = new LinearLayout(this);
        floatRow.setOrientation(LinearLayout.HORIZONTAL);
        Button showFloat = button("显示悬浮头像");
        Button hideFloat = button("隐藏悬浮头像");
        showFloat.setOnClickListener(v -> ensureOverlayThenShow());
        hideFloat.setOnClickListener(v -> sendServiceAction(Actions.ACTION_HIDE_FLOATING));
        floatRow.addView(showFloat, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        floatRow.addView(hideFloat, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(floatRow);

        root.addView(label("陪看模式（默认不建议一直开）"));
        root.addView(text("陪看模式会按间隔自动把屏幕分享给 Ta。更推荐用户想分享时手动短按。", 13, false));
        root.addView(label("间隔，秒"));
        root.addView(intervalEdit);
        LinearLayout periodicRow = new LinearLayout(this);
        periodicRow.setOrientation(LinearLayout.HORIZONTAL);
        periodicRow.setGravity(Gravity.CENTER);
        Button startPeriodic = button("开始陪看");
        Button stopPeriodic = button("停止陪看");
        startPeriodic.setOnClickListener(v -> startPeriodicOrAskAuthorization());
        stopPeriodic.setOnClickListener(v -> sendServiceAction(Actions.ACTION_STOP_PERIODIC));
        periodicRow.addView(startPeriodic, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        periodicRow.addView(stopPeriodic, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(periodicRow);

        Button tileButton = button("添加快捷设置 Tile：给 Ta 看一眼");
        tileButton.setOnClickListener(v -> requestTile());
        root.addView(tileButton);

        root.addView(label("Ta 的回复"));
        resultText = text("第一次使用请先授权屏幕捕获。之后短按按钮即可分享当前屏幕。", 16, false);
        resultText.setPadding(0, dp(12), 0, dp(12));
        root.addView(resultText);

        root.addView(label("最近聊天"));
        historyText = text("", 14, false);
        historyText.setPadding(dp(8), dp(8), dp(8), dp(8));
        root.addView(historyText);

        Button clearButton = button("清空聊天记录");
        clearButton.setOnClickListener(v -> {
            ChatStore.clear(this);
            refreshHistory();
            Toast.makeText(this, "已清空文字聊天记录。", Toast.LENGTH_SHORT).show();
        });
        root.addView(clearButton);

        setContentView(scrollView);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setPadding(0, dp(6), 0, dp(6));
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView label(String s) {
        TextView tv = text(s, 13, true);
        tv.setPadding(0, dp(14), 0, dp(4));
        return tv;
    }

    private EditText edit(String hint, boolean multiLine) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(!multiLine);
        e.setInputType(multiLine ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        return b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(Actions.PREFS, MODE_PRIVATE);
        endpointEdit.setText(p.getString(Actions.PREF_ENDPOINT, Actions.DEFAULT_ENDPOINT));
        modelEdit.setText(p.getString(Actions.PREF_MODEL, Actions.DEFAULT_MODEL));
        intervalEdit.setText(String.valueOf(p.getInt(Actions.PREF_INTERVAL, Actions.DEFAULT_INTERVAL_SECONDS)));
        companionNameEdit.setText(p.getString(Actions.PREF_COMPANION_NAME, Actions.DEFAULT_COMPANION_NAME));
        styleEdit.setText(p.getString(Actions.PREF_STYLE, Actions.DEFAULT_STYLE));
        promptEdit.setText(p.getString(Actions.PREF_PROMPT, Actions.DEFAULT_PROMPT));
        saveHistoryCheck.setChecked(p.getBoolean(Actions.PREF_SAVE_HISTORY, Actions.DEFAULT_SAVE_HISTORY));
        saveScreenshotsCheck.setChecked(p.getBoolean(Actions.PREF_SAVE_SCREENSHOTS, Actions.DEFAULT_SAVE_SCREENSHOTS));
    }

    private void savePrefs() {
        int interval = Actions.DEFAULT_INTERVAL_SECONDS;
        try {
            interval = Math.max(5, Integer.parseInt(intervalEdit.getText().toString().trim()));
        } catch (Exception ignored) {
        }
        getSharedPreferences(Actions.PREFS, MODE_PRIVATE).edit()
                .putString(Actions.PREF_ENDPOINT, endpointEdit.getText().toString().trim())
                .putString(Actions.PREF_MODEL, modelEdit.getText().toString().trim())
                .putInt(Actions.PREF_INTERVAL, interval)
                .putString(Actions.PREF_COMPANION_NAME, companionNameEdit.getText().toString().trim())
                .putString(Actions.PREF_STYLE, styleEdit.getText().toString())
                .putString(Actions.PREF_PROMPT, promptEdit.getText().toString())
                .putBoolean(Actions.PREF_SAVE_HISTORY, saveHistoryCheck.isChecked())
                .putBoolean(Actions.PREF_SAVE_SCREENSHOTS, saveScreenshotsCheck.isChecked())
                .apply();
    }

    private boolean isCaptureReady() {
        return getSharedPreferences(Actions.PREFS, MODE_PRIVATE)
                .getBoolean(Actions.PREF_CAPTURE_READY, false);
    }

    private void captureOrAskAuthorization() {
        savePrefs();
        if (!isCaptureReady()) {
            resultText.setText("还没有屏幕捕获授权。正在打开授权弹窗……");
            requestScreenCapture();
            return;
        }
        sendServiceAction(Actions.ACTION_CAPTURE_ONCE);
        resultText.setText("已分享给 Ta，等待回复……");
    }

    private void startPeriodicOrAskAuthorization() {
        savePrefs();
        if (!isCaptureReady()) {
            resultText.setText("陪看模式需要先授权屏幕捕获。正在打开授权弹窗……");
            requestScreenCapture();
            return;
        }
        sendServiceAction(Actions.ACTION_START_PERIODIC);
    }

    private void requestScreenCapture() {
        savePrefs();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            resultText.setText("无法打开屏幕捕获授权：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent i = new Intent(this, CaptureService.class);
                i.setAction(Actions.ACTION_START_PROJECTION);
                i.putExtra(Actions.EXTRA_RESULT_CODE, resultCode);
                i.putExtra(Actions.EXTRA_RESULT_DATA, data);
                startProjectionServiceSafely(i);
                resultText.setText("屏幕捕获已授权。现在可以短按“给你看看”，或打开悬浮头像。 ");
            } else {
                getSharedPreferences(Actions.PREFS, MODE_PRIVATE).edit()
                        .putBoolean(Actions.PREF_CAPTURE_READY, false)
                        .apply();
                resultText.setText("你取消了屏幕捕获授权。 ");
            }
        }
    }

    private void sendUserText() {
        savePrefs();
        String text = voiceEdit.getText().toString().trim();
        if (text.isEmpty()) {
            openVoiceInput();
            Toast.makeText(this, "先说点什么，或者用输入法麦克风转文字。", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, CaptureService.class);
        i.setAction(Actions.ACTION_SEND_TEXT);
        i.putExtra(Actions.EXTRA_USER_TEXT, text);
        startServiceSafely(i);
        voiceEdit.setText("");
        resultText.setText("已发给 Ta，等待回复……");
    }

    private void sendServiceAction(String action) {
        Intent i = new Intent(this, CaptureService.class);
        i.setAction(action);
        startServiceSafely(i);
    }

    private void startProjectionServiceSafely(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            resultText.setText("启动屏幕捕获服务失败：" + e.getMessage());
            getSharedPreferences(Actions.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(Actions.PREF_CAPTURE_READY, false)
                    .apply();
        }
    }

    private void startServiceSafely(Intent i) {
        try {
            startService(i);
        } catch (Exception e) {
            resultText.setText("启动服务失败：" + e.getMessage());
        }
    }

    private void ensureOverlayThenShow() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            Toast.makeText(this, "请允许显示在其他应用上层，然后返回再点一次。", Toast.LENGTH_LONG).show();
            return;
        }
        sendServiceAction(Actions.ACTION_SHOW_FLOATING);
    }

    private void openVoiceInput() {
        voiceEdit.requestFocus();
        voiceEdit.setSelection(voiceEdit.getText().length());
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(voiceEdit, InputMethodManager.SHOW_IMPLICIT);
        Toast.makeText(this, "已打开输入法。请使用键盘上的麦克风转文字。", Toast.LENGTH_SHORT).show();
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                StatusBarManager sbm = getSystemService(StatusBarManager.class);
                ComponentName cn = new ComponentName(this, CompanionTileService.class);
                Executor executor = getMainExecutor();
                sbm.requestAddTileService(cn, "给 Ta 看一眼", Icon.createWithResource(this, R.drawable.ic_tile), executor, result -> {
                    Toast.makeText(this, "Tile 请求结果：" + result, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Toast.makeText(this, "无法添加 Tile：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "请手动在快捷设置里添加“给 Ta 看一眼”Tile。", Toast.LENGTH_LONG).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void refreshHistory() {
        if (historyText != null) historyText.setText(ChatStore.renderForUi(this, 30));
    }
}
