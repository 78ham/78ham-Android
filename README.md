# 78HAM Android Client

78HAM 对讲机客户端 — 基于 Android 的业余无线电网络对讲应用。

## 功能特性

- **PTT 对讲** — 按住说话，支持息屏启麦
- **语音编码** — 支持 G711 编码格式
- **频道/房间** — 支持多房间切换
- **PTT 按键自定义** — 可映射实体按键为 PTT
- **开机自启** — 支持开机后台自动连接
- **前台服务** — 保持后台稳定运行
- **业余无线电协议** — 支持 APRS、AX.25、MDC1200 协议解析

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
├── audio/                  # 音频录制、播放、G711 编解码
├── data/                   # 配置管理、设置存储
├── network/                # API 客户端、UDP 通信、NRL21 协议
├── protocol/               # APRS、AX.25、MDC1200 协议处理
├── ptt/                    # PTT 按键控制
├── service/                # 对讲服务、开机启动
└── ui/                     # 登录、主界面、设置界面
```

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

## 使用说明

1. 启动应用后进入登录界面
2. 使用在 78HAM 网站注册的用户名和密码登录
3. 填写服务器地址和端口
4. 登录后进入主界面，选择房间即可开始对讲
5. 按住 PTT 按钮说话，松开结束

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
