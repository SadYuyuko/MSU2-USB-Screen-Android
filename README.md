# MSU2 USB小屏幕Android客户端  
[下载](https://github.com/SadYuyuko/MSU2-USB-Screen-Android/releases/download/1.1/MSU2-USB-Screen_1.1.apk) 基于Windows版Python Demo (`MSU2_DemoV1.0.py`) 移植  
通过**USB OTG**连接MSU2小屏幕 (VID`0x1A86`/PID`0xFE0C`，CDC-ACM虚拟串口，波特率19200)  

## 功能

| 状态 | 说明 |
|---|---|
| 0 | GIF 动图（Flash 页 0/450/900/1350/1800/2250） |
| 1 | 手机状态 · 蓝色（CPU / 内存 / 电量，数码管显示） |
| 2 | 手机状态 · 红色 |
| 3 | 照片（C3） |
| 4 | 时钟（C6 背景 + ASC64 ASCII 字库） |
| 5 | 屏幕镜像（MediaProjection 截屏 → 等比缩放 → RGB565 → 压缩编码发送） |

**烧录**功能：将 `.bin` 素材烧录到设备 Flash  
（照片类：先擦除后写入；ASC64 字库类：直接写入）

## 构建

1. 安装 **Android Studio**（版本建议 Hedgehog 2023.1.1 及以上）。
2. `File → Open` 选择本目录，等待 Gradle 同步
   （首次会自动下载 Gradle 与依赖；`usb-serial-for-android` 来自 JitPack）。
3. **重要：Gradle JDK 必须为 JDK 17 或 21**。
   `File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK`
   选择 17/21（建议直接用 Android Studio 自带的 JBR）。
   若选了 JDK 25+，Gradle 8.9 的内嵌 Kotlin 编译器无法解析其版本号，
   同步会直接报 `IllegalArgumentException: 25.0.2`（本项目无需升级 Gradle/AGP）。
4. `Build → Build APK(s)` 或直接 `Run` 到真机。
   - minSdk 26（Android 8.0） ／ targetSdk 34（Android 14）
   - 需联网同步依赖。

## 联调

1. 手机开启「开发者选项」→ 打开 **USB 调试**（仅调试用，非必需）。
2. 用 **OTG 线** 连接 MSU2 副屏到手机。
3. 首次启动 App：授予「通知」权限（用于屏幕镜像前台服务通知）。
4. 点击「连接」，同意系统 USB 授权弹窗。
5. 日志区显示“设备连接完成，版本 xx”及数据字典即成功。
6. 默认进入 GIF 动图状态；点击「切换状态」或按副屏上实体按键切换状态。
7. 切到「屏幕镜像」时系统会弹出**屏幕捕获授权**，同意后手机画面即同步到副屏。

> 若 Flash 中素材被改动/损坏导致某些状态空白，请重新烧录对应 `.bin`。

## 工程结构

```
app/src/main/java/com/msu2/android/
├── MainActivity.kt            # USB 连接/权限、6 状态状态机、UI 与日志
├── usb/Msu2Protocol.kt        # 协议命令编码（SFR/ADC/Flash/LCD/RGB565/屏幕编码）
├── usb/Msu2Serial.kt          # CDC-ACM 打开、握手、串行化 IO
├── usb/SfrRegistry.kt         # MSN 数据字典解析
├── ui/StatusProvider.kt       # CPU(/proc/stat)/内存/电量采集
├── ui/FlashWriter.kt          # 素材烧录
└── services/MirrorService.kt  # MediaProjection 前台服务（Android 14 兼容）
```

## 协议说明

- 所有指令为 6 字节包 `[CMD][SUB][D0][D1][D2][D3]`。
- 握手：设备广播 `00 'MSN' xx` → 主机回复 `00 'MSNCN'` → 设备确认。
- SFR 读写 `CMD=0x00`；ADC 读取 `CMD=0x08`（CH9 为按键）；
  Flash 操作 `CMD=0x03`；Flash 数据 `CMD=0x04`；LCD 指令 `CMD=0x02`。
- 详见 `usb/Msu2Protocol.kt`，全部逐字节对齐 Python 源码。

## 截图

<img width="540" height="1230" alt="1" src="https://github.com/user-attachments/assets/bd49422d-32e8-4127-a0da-39e9d00ed1cb" />

