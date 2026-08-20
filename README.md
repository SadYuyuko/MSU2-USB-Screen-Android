# MSU2 USB小屏幕Android端程序

[下载](https://github.com/SadYuyuko/MSU2-USB-Screen-Android/releases/download/2.0.2/MSU2-USB-Screen_2.0.2_release.apk) 基于Windows版 (`MSU2_MINI_DemoV1.6.py`) 移植，适用于 **MSU2 MINI 160×80 0.96寸小屏幕**  
通过**OTG**转接口(线)连接USB小屏幕(VID`0x1A86`/PID`0xFE0C`，CDC-ACM虚拟串口，波特率19200)

## 功能

| 状态 | 说明 |
|---|---|
| 0 | GIF 动图（36帧，Flash页0/100/…/3500） |
| 1 | 手机状态·蓝色（CPU/内存/电量/存储，数码管显示） |
| 2 | 手机状态·红色 内容同上 |
| 3 | 照片（PH1，3926页） |
| 4 | 时钟（CLK_BG背景+ASC64 ASCII字库） |
| 5 | 屏幕镜像（MediaProjection截屏 → 压缩编码发送，竖屏/横屏旋转自适应） |
| 6 | 网速（TrafficStats差值 → 文字+线条图） |

**烧录**：

| 类型 | 说明 |
|---|---|
| GIF（36帧 160x80 自动处理） | 按帧解析、按帧率自动取36帧并缩放至160×80，烧录到页0，耗时约7min |
| 图片（jpg/png 160x80 烧录至指定页） | 缩放至160×80，烧录到时钟背景(3826)/照片(3926)/自定义页，耗时约15s |
| 固件（.bin 原始数据） | 原始数据，按指定页与类型烧录（图片先擦除/字库不擦除） |

注：
 - 由于Android 8.0后限制`/proc/stat`读取，高版本Android核心显示为00
 - ADB执行`appops set com.msu2.android PROJECT_MEDIA allow`可避免每次切到投屏功能时都出现授权弹窗  

## 构建

1. 安装 **Android Studio**（版本建议 Hedgehog 2023.1.1 及以上）。
2. `File → Open` 选择本目录，等待 Gradle 同步
   （首次会自动下载 Gradle 与依赖；`usb-serial-for-android` 来自 JitPack）。
3. **重要：Gradle JDK 必须为 JDK 17 或 21**。
   `File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK`
   选择 17/21（建议直接用 Android Studio 自带的 JBR）。
   若选了 JDK 25+，Gradle 的内嵌 Kotlin 编译器无法解析其版本号，同步会报错。
4. `Build → Build APK(s)` 或直接 `Run` 到真机。
   - minSdk 26（Android 8.0） ／ targetSdk 34（Android 14）
   - 需联网同步依赖。

## 联调

1. 用**OTG线**连接USB小屏幕到手机。
2. 首次启动App授予通知权限（用于屏幕镜像前台服务通知），同意系统 USB 授权弹窗。
3. 日志区显示“设备连接完成，版本 xx”及数据字典即成功。
4. 默认进入 GIF 动图状态；点击上一个/下一个键或小屏幕触控切换状态，旋转键180°翻转副屏显示。
6. 切到投屏时系统会弹出**屏幕捕获授权**，同意后手机画面即同步到小屏幕
   （竖屏自动旋转适配，横屏1:1直显；帧率受设备固件消化速度限制）。
7. 连接期间由前台服务保活，App退到后台时常驻通知保活。

> 若Flash中素材被改动/损坏导致某些状态空白，请重新烧录对应固件。

## 工程结构

```
app/src/main/java/com/msu2/android/
├── MainActivity.kt            # USB 连接/权限、状态机、UI/日志、烧录(GIF/图片/固件)
├── usb/Msu2Protocol.kt        # 协议命令编码（SFR/ADC/Flash/LCD/RGB565/屏幕编码）
├── usb/Msu2Serial.kt          # CDC-ACM打开、握手、串行化IO、屏幕数据分块发送
├── usb/SfrRegistry.kt         # MSN 数据字典解析
├── ui/StatusProvider.kt       # CPU(/proc/stat)/内存/电量/存储/网速采集
├── ui/FlashWriter.kt          # 素材烧录（擦除/写页）
├── services/MirrorService.kt  # MediaProjection 前台服务（竖屏/横屏自适应）
└── services/UsbService.kt     # 连接期间前台保活（后台刷新）
```

## 协议说明

- 所有指令为**6字节包**`[CMD][SUB][D0][D1][D2]`。
- 握手：设备广播`00'MSN'xx` → 主机回复`00'MSNCN'` → 设备确认。
- SFR读写`CMD=0x00`；ADC读写`CMD=0x08`（CH9为按键）；
  Flash操作`CMD=0x03`；Flash数据`CMD=0x04`；LCD指令`CMD=0x02`。
- 屏幕数据（投屏）编码：256字节/页，每页`02 04主色`+`04索引d0 d1 d2 d3`差异像素+`02 03 08`提交。
- 详见`usb/Msu2Protocol.kt`，对齐`MSU2_MINI_DemoV1.6`源码。

## 截图

<img width="440" height="1000" alt="0" src="https://github.com/user-attachments/assets/44df9698-6165-49f0-bb7a-e23f4c89ebf3" />  

<img width="440" height="278" alt="1" src="https://github.com/user-attachments/assets/0b0dc756-fe7c-4541-973a-6370942b4aaf" />  


<img width="440" height="586" alt="2" src="https://github.com/user-attachments/assets/1d3c229e-849b-4ffa-b066-c7f1503cd9dd" />  
