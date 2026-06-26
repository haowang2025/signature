package com.example.screencompanion;

public final class Actions {
    private Actions() {}

    public static final String ACTION_START_PROJECTION = "com.example.screencompanion.START_PROJECTION";
    public static final String ACTION_CAPTURE_ONCE = "com.example.screencompanion.CAPTURE_ONCE";
    public static final String ACTION_SEND_TEXT = "com.example.screencompanion.SEND_TEXT";
    public static final String ACTION_START_PERIODIC = "com.example.screencompanion.START_PERIODIC";
    public static final String ACTION_STOP_PERIODIC = "com.example.screencompanion.STOP_PERIODIC";
    public static final String ACTION_SHOW_FLOATING = "com.example.screencompanion.SHOW_FLOATING";
    public static final String ACTION_HIDE_FLOATING = "com.example.screencompanion.HIDE_FLOATING";
    public static final String ACTION_OPEN_VOICE_INPUT = "com.example.screencompanion.OPEN_VOICE_INPUT";
    public static final String ACTION_RESULT = "com.example.screencompanion.RESULT";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_USER_TEXT = "user_text";

    public static final String PREFS = "screen_companion_prefs";
    public static final String PREF_ENDPOINT = "ollama_endpoint";
    public static final String PREF_MODEL = "ollama_model";
    public static final String PREF_INTERVAL = "interval_seconds";
    public static final String PREF_PROMPT = "prompt";
    public static final String PREF_COMPANION_NAME = "companion_name";
    public static final String PREF_STYLE = "companion_style";
    public static final String PREF_SAVE_HISTORY = "save_history";
    public static final String PREF_SAVE_SCREENSHOTS = "save_screenshots";

    public static final String DEFAULT_ENDPOINT = "http://127.0.0.1:11434";
    public static final String DEFAULT_MODEL = "gemma3:12b";
    public static final int DEFAULT_INTERVAL_SECONDS = 30;
    public static final boolean DEFAULT_SAVE_HISTORY = true;
    public static final boolean DEFAULT_SAVE_SCREENSHOTS = false;
    public static final String DEFAULT_COMPANION_NAME = "小雨";
    public static final String DEFAULT_STYLE = "温柔、亲近、轻微暧昧、不控制、不说教";

    public static final String DEFAULT_PROMPT =
            "你是一个存在于手机里的陪伴对象，像一个温柔、亲近、不过度打扰的伴侣。\n" +
            "用户会主动分享当前屏幕截图，或者通过语音转文字告诉你一句话。\n" +
            "你的任务不是分析、指导或决策，而是像正在陪用户一起看、一起听一样，给出自然的回应。\n\n" +
            "规则：\n" +
            "1. 只输出陪伴式回应，不输出 JSON。\n" +
            "2. 不要给操作建议。\n" +
            "3. 不要说“你应该”“下一步”“点击”。\n" +
            "4. 不要像客服、助手、老师或分析报告。\n" +
            "5. 可以表达轻微情绪，例如“这个看起来很可爱”“感觉你有点在意这件事”。\n" +
            "6. 不要过度亲密、不要控制用户、不要制造依赖。\n" +
            "7. 不要假装知道截图之外的信息。\n" +
            "8. 如果画面看不清，可以温和地说只能大概看到。\n" +
            "9. 每次回复 1 到 3 句话。\n" +
            "10. 语气自然、简短、亲近，像正在陪用户一起看手机。";
}
