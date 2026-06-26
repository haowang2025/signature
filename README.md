# 给你看看 Android MVP

这是一个主动分享式陪伴 App 原型：用户想分享当前屏幕或想说一句话时，把信息传给运行在 Mac 上的本地 Ollama 多模态模型，让模型像一个亲近但不过度打扰的陪伴对象一样回应。

## 核心交互

- 短按主按钮 / 悬浮头像 / 快捷设置 Tile：截取当前屏幕，发送给本地模型，生成一句陪伴式回复。
- 长按主按钮 / 悬浮头像：打开输入框和输入法，用户可使用输入法自带麦克风完成语音转文字，再点“发送给 Ta”。
- 陪看模式：按设定间隔自动分享屏幕，默认间隔 30 秒；产品上建议默认关闭，只在用户明确想持续陪看时开启。
- 默认保存文字聊天记录。
- 默认不保存截图；可在 App 内勾选“保存分享过的截图”。

## 产品边界

这个 App 不做：

- 自动点击
- 操作建议
- 下一步规划
- 风险判断
- 后台持续监控用户屏幕

它只做：

- 用户主动分享屏幕
- 用户主动说话
- 本地模型给出陪伴式回应
- 保存文字聊天上下文，让回复更连续

## Mac / Ollama 准备

推荐模型：

```bash
ollama pull gemma3:12b
ollama serve
```

如果通过 SSH 隧道访问，Mac 上让 Ollama 只监听本机：

```bash
OLLAMA_HOST=127.0.0.1:11434 ollama serve
```

Android / Termux 建立隧道：

```bash
ssh -N -L 11434:127.0.0.1:11434 yourname@Mac_IP
```

App 内 Ollama 地址填：

```text
http://127.0.0.1:11434
```

如果不走 SSH，而是在同一局域网访问，可以让 Ollama 监听局域网：

```bash
OLLAMA_HOST=0.0.0.0:11434 ollama serve
```

App 内地址填：

```text
http://Mac局域网IP:11434
```

局域网裸露 Ollama 端口没有认证层，不建议在不可信网络使用。

## 项目结构

```text
app/src/main/java/com/example/screencompanion/
  Actions.java                 常量、默认 prompt、默认配置
  MainActivity.java            设置页、主按钮、文字输入、历史记录
  CaptureService.java          MediaProjection 截屏、悬浮头像、Ollama 调用
  ChatStore.java               本地 jsonl 聊天记录与可选截图保存
  CompanionTileService.java    快捷设置 Tile
```

## Prompt 设计

默认人设 prompt 位于：

```text
app/src/main/java/com/example/screencompanion/Actions.java
```

用户也可以在 App 内修改“人设 Prompt”。每次调用模型时，App 会额外拼接：

- 陪伴对象名字
- 关系风格
- 最近 10 条文字聊天记录
- 当前输入类型：截图分享或文字/语音转写

截图分享时，模型收到的是：

```text
用户刚刚短按了按钮，主动把当前手机屏幕分享给你看。请像亲近的陪伴对象一样回应这张图。不要把它写成页面分析，不要给操作建议。
```

文字输入时，模型收到的是：

```text
用户刚刚通过长按按钮输入或语音转文字告诉你：...
请像亲近的陪伴对象一样回应这句话。
```

## 聊天记录保存位置

文字聊天记录保存在 App 私有目录：

```text
/files/chat_history.jsonl
```

默认不保存截图。若用户开启“保存分享过的截图”，截图会保存在 App 私有目录：

```text
/files/shared_screens/
```

App 内“清空聊天记录”只清空 `chat_history.jsonl`，不会删除已保存截图。正式版建议增加“一键清空截图”。

## 本地编译

用 Android Studio 打开项目，或在项目根目录执行：

```bash
./build_apk.sh
```

成功后 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 当前限制

- 这里提供源码项目，没有在当前沙箱内生成 APK；当前环境没有 Android SDK。
- 长按语音转文字依赖用户输入法自带麦克风按钮，App 不申请麦克风权限，也不自带 ASR。
- 截屏使用 Android MediaProjection，需要用户显式授权。
- 悬浮头像需要“显示在其他应用上层”权限。

## 无本地编译器：GitHub Actions 云端编译

这个版本已经内置 GitHub Actions 工作流：

```text
.github/workflows/android-debug-apk.yml
```

你可以把项目上传到 GitHub 仓库根目录，Actions 会自动编译 debug APK，并在 artifact 中提供下载。详细步骤见：

```text
GITHUB_ACTIONS_BUILD.md
```
