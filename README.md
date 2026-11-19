# 78HAM Android Client

78HAM 对讲机客户端 — 基于 Android 的业余无线电网络对讲应用，兼容手台设备。

基于项目：
- [hicaoc/nrllink](https://github.com/hicaoc/nrllink) — NRLLink 服务端
- [hicaoc/nrllink-mp](https://github.com/hicaoc/nrllink-mp) — NRLLink 微信小程序客户端

## 功能特性

- **PTT 对讲** — 按住说话，支持息屏启麦
- **实体按键适配** — 兼容手台设备，目前支持和对讲 D12，其他设备暂无
- **语音编码** — 支持 G711 / Opus 编码格式
- **频道/房间** — 支持多服务器多房间：每个服务器可加入一个房间（可发可收），并可同时收听其他服务器的多个房间（只收不发）
- **CW（摩尔斯电码）** — 文本转 CW 音频发送，速度 5–35 WPM 可调
- **DTMF** — 双音多频发送，用于中继台控制命令
- **SSTV** — 图片转 Robot36 慢扫描电视信号发送（320×240，36 秒）
- **文字播报（TTS）** — 输入文字合成为语音发射
- **录音发送** — 本机录音最长 60 秒，录完直接发射
- **尾音（Roger Beep）** — 松开 PTT 自动追加结束提示音（经典/K 字/双音/Quindar）
- **定时播报** — 定时用 TTS 自动播报设定文字（每分钟/每 5/15 分钟/每天定时）
- **PTT 按键自定义** — 可映射实体按键为 PTT
- **开机自启** — 支持开机后台自动连接
- **前台服务** — 保持后台稳定运行

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **最低 SDK**: Android 6.0 (API 23)
- **目标 SDK**: Android 13 (API 33)
- **构建工具**: Gradle + AGP 9.2.0

## 项目结构

```
app/src/main/java/com/ham78/app/
├── HamApplication.kt      # Application 入口
├── audio/                  # 音频录制、播放、G711/Opus 编解码、信号源（CW/DTMF/SSTV/尾音/TTS）
├── data/                   # 配置管理、设置存储、定时播报任务
├── location/               # 位置获取
├── network/                # API 客户端、UDP 通信、NRL21 协议、多服务器/多房间管理
├── ptt/                    # PTT 按键控制
├── service/                # 对讲服务、开机启动
└── ui/                     # 登录、主界面、设置界面、发送工具面板
```

## 使用说明

1. 启动应用后进入登录界面
2. 使用在 nrl 小程序注册的用户名和密码登录
3. 填写你注册的服务器地址
4. 登录后进入主界面，选择房间即可开始对讲
5. 按住 PTT 按钮说话，松开结束

## 物理按键适配列表

应用启动时会自动识别设备型号，匹配对应的按键方案。

| 设备 | PTT | 方向键 | 确认键 | 按键方式 |
|------|-----|--------|--------|----------|
| 和对讲 D12 | ✓ | ✓ | ✓ | 物理 KeyCode |

> 注：部分手台的 PTT 按键不走物理 KeyCode，而是通过系统广播发送（如 `android.intent.action.PTT.down` / `PTT.up`），应用已自动适配。

### 提交新设备适配

如果你的手台按键无法使用，请通过 ADB 获取按键数据并提交给我们：

**方法一：获取物理 KeyCode**
```bash
adb shell getevent -lt | grep -i "EV_KEY"
```

**方法二：获取虚拟广播按键**（适用于无物理 KeyCode 输出的设备）
```bash
adb logcat -s PttController:* ActivityManager:* | grep -iE "ptt|key"
```

**方法三：导出音频状态变化**（对比 PTT 前后）
```bash
# 1. 先导出空闲状态
adb shell dumpsys audio > audio_before.txt
# 2. 按住 PTT 不放，等 2 秒后导出
adb shell dumpsys audio > audio_during.txt
# 3. 松开 PTT，等 2 秒后导出
adb shell dumpsys audio > audio_after.txt
```

**方法四：导出完整日志**
```bash
adb shell logcat -d > logcat_full.txt
```

**方法五：导出设备信息**
```bash
adb shell getprop ro.product.model
adb shell getprop ro.product.manufacturer
adb shell getprop ro.hardware
```

将以上结果截图或复制，提交到 [Issues](https://github.com/78ham/78ham-Android/issues)。

## 编译运行

### 环境要求

- Android Studio Ladybug 或更高版本
- JDK 8+
- Android SDK 35

### 步骤

1. **克隆项目**
   ```bash
   git clone https://github.com/78ham/78ham-Android.git
   ```

2. **用 Android Studio 打开项目**
   - 选择 `File > Open`，选择项目根目录

3. **等待 Gradle 同步完成**

4. **连接设备或启动模拟器，点击 Run**

### 命令行编译

```bash
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`。

## 权限说明

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 录制语音 |
| `INTERNET` | 网络通信 |
| `FOREGROUND_SERVICE` | 后台对讲服务 |
| `WAKE_LOCK` | 息屏保持连接 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `POST_NOTIFICATIONS` | 通知提醒 |

## License

This project is private. All rights reserved.
