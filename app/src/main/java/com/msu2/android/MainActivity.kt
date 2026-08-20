package com.msu2.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.msu2.android.databinding.ActivityMainBinding
import com.msu2.android.services.MirrorService
import com.msu2.android.services.UsbService
import com.msu2.android.ui.FlashWriter
import com.msu2.android.ui.StatusProvider
import com.msu2.android.usb.Msu2Protocol
import com.msu2.android.usb.Msu2Serial
import com.msu2.android.usb.SfrRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import kotlin.coroutines.resume
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Msu2Screen"
        private const val VID = 0x1A86
        private const val PID = 0xFE0C
        private const val ACTION_USB_PERMISSION = "com.msu2.android.USB_PERMISSION"
        private const val REPO_URL = "https://github.com/SadYuyuko/MSU2-USB-Screen-Android"
        private const val RELEASES_URL = "$REPO_URL/releases"
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/SadYuyuko/MSU2-USB-Screen-Android/releases/latest"
        /** 投屏授权等待上限，避免超时误判为拒绝。 */
        private const val PROJECTION_WAIT_MS = 180000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var stateNames: Array<String>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var connectJob: Job? = null
    @Volatile private var serial: Msu2Serial? = null
    @Volatile private var connected = false
    @Volatile private var currentDeviceId = -1

    @Volatile private var keyEvent = false
    @Volatile private var keyEventPrev = false
    @Volatile private var projectionGranted = false
    @Volatile private var flashing = false
    @Volatile private var lcdState = 0
    /** 旋转指令已下发，待显示循环强制重绘当前页（对齐 Python LCD_State 后 State_change=1）。 */
    @Volatile private var rotatePending = false
    @Volatile private var projectionDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var permissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null
    private var mirrorInfoLogged = false
    private var cpuWarned = false
    private var lastProgressRender = 0L
    private var progressStart = -1
    private var progressDone = false

    // 网速页状态（对应 MG 版 show_netspeed）
    private var netSpeedLastTime = 0L
    private var netSpeedLastRx = 0L
    private var netSpeedLastTx = 0L
    private val netSpeedPlot = ArrayDeque<Pair<Double, Double>>()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                MirrorService.start(this, result.resultCode, result.data!!)
                projectionDeferred?.complete(true)
            } else {
                projectionDeferred?.complete(false)
            }
        }

    private val flashFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) startFlash(uri)
        }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val cont = permissionContinuation
                    permissionContinuation = null
                    if (cont != null) {
                        if (cont.context.isActive) cont.resume(granted)
                    } else if (granted) {
                        launchConnectFromIntent(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE))
                    } else {
                        log(getString(R.string.permission_denied))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (dev != null && !connected) launchConnectFromIntent(dev)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (dev != null && dev.deviceId == currentDeviceId) onDeviceDetached()
                }
            }
        }
    }

    // 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        stateNames = arrayOf(
            getString(R.string.state_0), getString(R.string.state_1), getString(R.string.state_2),
            getString(R.string.state_3), getString(R.string.state_4), getString(R.string.state_5),
            getString(R.string.state_6)
        )

        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnRotate.setOnClickListener { rotateDisplay() }
        binding.btnStatePrev.setOnClickListener { keyEventPrev = true }
        binding.btnStateNext.setOnClickListener { keyEvent = true }
        binding.btnFlash.setOnClickListener { showFlashDialog() }
        binding.btnMenu.setOnClickListener { showOverflowMenu(it) }

        MirrorService.MirrorBus.onError = { msg ->
            log("镜像: $msg")
            projectionGranted = false
        }
        MirrorService.MirrorBus.onStop = {
            log("镜像已停止")
            projectionGranted = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        // 整个 Activity 生命周期内保持注册 USB 广播，避免权限弹窗导致 onPause 错过结果
        registerUsbReceiver()

        // 检查是否已连接设备
        val existing = findDevice()
        if (existing != null && usbManager.hasPermission(existing)) {
            log("检测到已连接的 MSU2 设备，自动连接…")
            launchConnect(existing)
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    /** 沉浸式：根布局应用系统栏 insets + 12dp 边距。 */
    private fun applyWindowInsets() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad)
            insets
        }
    }

    override fun onDestroy() {
        scope.cancel()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        MirrorService.MirrorBus.onError = null
        MirrorService.MirrorBus.onStop = null
        super.onDestroy()
    }

    // 连接 / 断开

    private fun connect() {
        if (connected) return
        val device = findDevice()
        if (device == null) {
            log(getString(R.string.no_device))
            return
        }
        launchConnect(device)
    }

    private fun launchConnectFromIntent(device: UsbDevice?) {
        if (device == null || connected) return
        launchConnect(device)
    }

    private fun launchConnect(device: UsbDevice) {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { doConnect(device) }
    }

    private suspend fun doConnect(device: UsbDevice) {
        try {
            updateStatus(getString(R.string.status_connecting))
            val granted = withTimeoutOrNull(60000) { requestUsbPermission(device) }
            if (granted == null) {
                log("等待 USB 权限超时，请在弹出的权限框中点击“允许”")
                updateStatus(getString(R.string.status_disconnected))
                return
            }
            if (!granted) {
                log(getString(R.string.permission_denied))
                updateStatus(getString(R.string.status_disconnected))
                return
            }

            val s = Msu2Serial(usbManager, device)
            s.open()
            val version = s.handshake()
            serial = s
            connected = true
            currentDeviceId = device.deviceId

            log("设备连接完成，版本 $version")
            updateStatus("${getString(R.string.status_connected)}（MSN v$version）")

            // 同步显示方向 + 启动保活服务（后台不被冻结）
            try {
                s.ack(Msu2Protocol.lcdState(lcdState))
            } catch (_: Exception) {}
            UsbService.start(this)

            // 读取数据字典（对应 Python Read_M_SFR_Data）
            try {
                val entries = SfrRegistry.read(s)
                log("数据总数：${entries.size}")
                entries.forEach { log("  $it") }
                val status = SfrRegistry.readData(s, entries, "MSN_Status".toByteArray(Charsets.US_ASCII))
                val uid = SfrRegistry.readData(s, entries, "MSN_UID".toByteArray(Charsets.US_ASCII))
                log("MSN_Status=$status  MSN_UID=$uid")
            } catch (e: Exception) {
                log("读取 MSN 数据字典失败：${e.message}")
            }

            updateStateLabel(0)
            coroutineScope {
                launch { runKeyPoll(s) }
                runDisplayLoop(s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(getString(R.string.connect_failed, e.message ?: "未知错误"))
            // 勿调 disconnectInternal()：cancelAndJoin 当前任务会自等死锁
            connected = false
            keyEvent = false
            keyEventPrev = false
            flashing = false
            stopMirrorSession()
            UsbService.stop(this)
            serial?.close()
            serial = null
            currentDeviceId = -1
            connectJob = null
            updateStatus(getString(R.string.status_disconnected))
            resetStateLabel()
        }
    }

    private suspend fun requestUsbPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            permissionContinuation = cont
            cont.invokeOnCancellation {
                if (permissionContinuation === cont) permissionContinuation = null
            }
            val pending = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_MUTABLE
            )
            try {
                usbManager.requestPermission(device, pending)
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
    }

    private fun disconnect() {
        scope.launch {
            disconnectInternal()
            updateStatus(getString(R.string.status_disconnected))
            log("已断开连接")
        }
    }

    /** 旋转键：硬件切换显示方向 180°。 */
    private fun rotateDisplay() {
        val s = serial
        if (s == null) {
            log(getString(R.string.no_device))
            return
        }
        lcdState = if (lcdState == 0) 1 else 0
        scope.launch {
            try {
                s.ack(Msu2Protocol.lcdState(lcdState))
                rotatePending = true
                log(getString(R.string.rotate_done))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("旋转失败：${e.message}")
            }
        }
    }

    private suspend fun disconnectInternal() {
        connected = false
        keyEvent = false
        keyEventPrev = false
        flashing = false
        resetStateLabel()
        stopMirrorSession()
        UsbService.stop(this)
        connectJob?.cancelAndJoin()
        connectJob = null
        serial?.close()
        serial = null
        currentDeviceId = -1
    }

    private fun onDeviceDetached() {
        scope.launch {
            log(getString(R.string.device_detached))
            disconnectInternal()
            updateStatus(getString(R.string.status_disconnected))
        }
    }

    private fun findDevice(): UsbDevice? {
        val devices = usbManager.deviceList
        devices.values.firstOrNull { it.vendorId == VID && it.productId == PID }?.let { return it }
        return devices.values.firstOrNull {
            runCatching { UsbSerialProber.getDefaultProber().probeDevice(it)?.ports?.isNotEmpty() == true }.getOrDefault(false)
        }
    }

    // 状态机

    private suspend fun CoroutineScope.runKeyPoll(s: Msu2Serial) {
        // 等设备完成开机第一帧绘制后再采样，避免读到 0
        delay(300)
        val adc1 = s.readAdc(9)
        val adc2 = s.readAdc(9)
        val adc3 = s.readAdc(9)
        val adcDet = maxOf(adc1, adc2, adc3) - 125.0
        log("按键 ADC 阈值：%.0f".format(adcDet))
        var keyOn = false
        while (isActive && connected) {
            delay(200)
            if (flashing) continue
            try {
                val v = s.readAdc(9)
                if (v < adcDet) keyOn = true
                else if (keyOn) {
                    keyOn = false
                    keyEvent = true
                } else keyOn = false
            } catch (_: Exception) {}
        }
    }

    /** 是否有待处理的状态切换请求（上一个/下一个）。 */
    private fun switchPending(): Boolean = keyEvent || keyEventPrev

    /** 分段延时：每 50ms 检查切换请求，有请求立即返回。 */
    private suspend fun delayInterruptible(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            if (switchPending()) return
            val step = minOf(50L, remaining)
            delay(step)
            remaining -= step
        }
    }

    private suspend fun CoroutineScope.runDisplayLoop(s: Msu2Serial) {
        var state = 0
        var stateChanged = true
        var gifNum = 0
        while (isActive && connected) {
            // 烧录期间暂停显示轮询，避免争用串口
            if (flashing) {
                delay(50)
                continue
            }
            var delta = 0
            if (rotatePending) {
                rotatePending = false
                stateChanged = true
            }
            if (keyEventPrev) { keyEventPrev = false; delta = -1 }
            else if (keyEvent) { keyEvent = false; delta = 1 }
            if (delta != 0) {
                val prev = state
                state = ((state + delta) % 7 + 7) % 7
                stateChanged = true
                if (prev == 5 || state == 5) {
                    if (prev == 5) stopMirrorSession()
                    if (state == 5) projectionGranted = false
                }
                log("状态切换 -> ${stateNames[state]}")
                updateStateLabel(state)
            }
            try {
                when (state) {
                    0 -> { // GIF 动图（36 帧，页 0,100,...,3500）
                        if (stateChanged) gifNum = 0
                        s.ack(Msu2Protocol.lcdPhoto(0, 0, Msu2Protocol.SCREEN_W, Msu2Protocol.SCREEN_H, gifNum * Msu2Protocol.GIF_FRAME_PAGES))
                        gifNum = (gifNum + 1) % Msu2Protocol.GIF_FRAME_COUNT
                    }
                    1 -> showPhoneStatus(s, Msu2Protocol.BLUE, stateChanged)
                    2 -> showPhoneStatus(s, Msu2Protocol.RED, stateChanged)
                    3 -> { // 照片
                        if (stateChanged) {
                            s.ack(Msu2Protocol.lcdPhoto(0, 0, Msu2Protocol.SCREEN_W, Msu2Protocol.SCREEN_H, Msu2Protocol.PAGE_PH1))
                        }
                        delayInterruptible(300)
                    }
                    4 -> showClock(s, stateChanged)
                    5 -> showMirror(s)
                    6 -> showNetSpeed(s, stateChanged)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("显示异常：${e.message}")
                if (e is SerialTimeoutException) {
                    log("提示：MSU2 可能已卡死（不再处理指令）。请拔下设备断电 15 秒后重插，再重新连接。")
                }
                delayInterruptible(500)
            }
            stateChanged = false
            delay(30)
        }
    }

    /** 手机状态（蓝/红），对齐 MSU2_MINI_DemoV1.6 show_PC_state（MP1 背景 + N24X33 数码管）。 */
    private suspend fun showPhoneStatus(s: Msu2Serial, fc: Int, stateChanged: Boolean) {
        val numAdd = Msu2Protocol.PAGE_N24X33
        val bc = Msu2Protocol.BLACK
        if (stateChanged) {
            s.ack(Msu2Protocol.lcdPhotoWb(0, 0, Msu2Protocol.SCREEN_W, Msu2Protocol.SCREEN_H, Msu2Protocol.PAGE_MP1, fc, bc))
        }
        if (switchPending()) return
        var cpu = StatusProvider.cpuUsage()
        if (cpu < 0) {
            if (!cpuWarned) {
                cpuWarned = true
                log("提示：无法读取 CPU 占用率（/proc/stat 不可用或无变化）")
            }
            cpu = 0
        }
        val mem = StatusProvider.memoryUsage(this)
        val bat = StatusProvider.batteryPercent(this)
        val frq = StatusProvider.storageUsage(this)

        drawN24(s, fc, bc, numAdd, 24, 0, cpu)      // CPU 左上
        if (switchPending()) return
        drawN24(s, fc, bc, numAdd, 104, 0, mem)     // 内存 右上
        if (switchPending()) return
        drawN24(s, fc, bc, numAdd, 104, 47, bat)    // 电量 右下
        if (switchPending()) return
        drawN24(s, fc, bc, numAdd, 24, 47, frq)     // 存储 左下
    }

    /** 一组 N24X33 数码管（百位“1”/空白 + 十位 + 个位），对齐 V1.6。 */
    private suspend fun drawN24(s: Msu2Serial, fc: Int, bc: Int, numAdd: Int, x: Int, y: Int, value: Int) {
        var v = value
        if (v >= 100) { s.ack(Msu2Protocol.lcdPhotoWb(x, y, 8, 33, 10 + numAdd, fc, bc)); v %= 100 }
        else s.ack(Msu2Protocol.lcdPhotoWb(x, y, 8, 33, 11 + numAdd, fc, bc))
        if (switchPending()) return
        s.ack(Msu2Protocol.lcdPhotoWb(x + 8, y, 24, 33, v / 10 + numAdd, fc, bc))
        if (switchPending()) return
        s.ack(Msu2Protocol.lcdPhotoWb(x + 32, y, 24, 33, v % 10 + numAdd, fc, bc))
    }

    /** 时钟（HH:MM），对齐 V1.6 show_PC_time（CLK_BG 背景 + ASC64 字库，y=8）。 */
    private suspend fun showClock(s: Msu2Serial, stateChanged: Boolean) {
        val fc = Msu2Protocol.YELLOW
        val photoAdd = Msu2Protocol.PAGE_CLK_BG
        val numAdd = Msu2Protocol.PAGE_ASC64
        if (stateChanged) {
            s.ack(Msu2Protocol.lcdPhoto(0, 0, Msu2Protocol.SCREEN_W, Msu2Protocol.SCREEN_H, photoAdd))
            if (switchPending()) return
            s.ack(Msu2Protocol.lcdAscii32x64Mix(56 + 8, 8, ':', fc, photoAdd, numAdd))
        }
        val now = LocalTime.now()
        val h = now.hour
        val m = now.minute
        s.ack(Msu2Protocol.lcdAscii32x64Mix(0 + 8, 8, digitChar(h / 10), fc, photoAdd, numAdd))
        if (switchPending()) return
        s.ack(Msu2Protocol.lcdAscii32x64Mix(32 + 8, 8, digitChar(h % 10), fc, photoAdd, numAdd))
        if (switchPending()) return
        s.ack(Msu2Protocol.lcdAscii32x64Mix(80 + 8, 8, digitChar(m / 10), fc, photoAdd, numAdd))
        if (switchPending()) return
        s.ack(Msu2Protocol.lcdAscii32x64Mix(112 + 8, 8, digitChar(m % 10), fc, photoAdd, numAdd))
        delayInterruptible(200)
    }

    /** 网速：TrafficStats 差值算速率，绘制 160x80 文字+线条图直写显存（对齐 MG 版）。 */
    private suspend fun showNetSpeed(s: Msu2Serial, stateChanged: Boolean) {
        if (stateChanged) {
            val (rx, tx) = StatusProvider.netCounters()
            netSpeedLastTime = SystemClock.elapsedRealtime()
            netSpeedLastRx = rx
            netSpeedLastTx = tx
            netSpeedPlot.clear()
            repeat(120) { netSpeedPlot.addLast(0.0 to 0.0) }
        }
        val (curRx, curTx) = StatusProvider.netCounters()
        val now = SystemClock.elapsedRealtime()
        val dt = (now - netSpeedLastTime) / 1000.0
        val sent = if (curTx >= 0 && curTx >= netSpeedLastTx && dt > 0) (curTx - netSpeedLastTx) / dt else 0.0
        val recv = if (curRx >= 0 && curRx >= netSpeedLastRx && dt > 0) (curRx - netSpeedLastRx) / dt else 0.0
        netSpeedLastTime = now
        netSpeedLastRx = curRx
        netSpeedLastTx = curTx
        if (netSpeedPlot.isNotEmpty()) netSpeedPlot.removeFirst()
        netSpeedPlot.addLast(sent to recv)

        val w = Msu2Protocol.SCREEN_W
        val h = Msu2Protocol.SCREEN_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val textPaint = Paint().apply {
            isAntiAlias = true
            color = Color.rgb(255, 128, 0)
            textSize = 20f
        }
        canvas.drawText("上传  ${formatSpeed(sent).padStart(8)}", 0f, 18f, textPaint)
        canvas.drawText("下载  ${formatSpeed(recv).padStart(8)}", 0f, 58f, textPaint)
        drawNetLines(canvas, netSpeedPlot.map { it.first }, 39, Color.rgb(235, 139, 139))
        drawNetLines(canvas, netSpeedPlot.map { it.second }, 79, Color.rgb(146, 211, 217))
        val rgb = ByteArray(w * h * 2)
        bitmapToRgb565(bmp, rgb)
        bmp.recycle()
        val data = Msu2Protocol.encodeScreenData(rgb, w, h)
        s.sendScreen(Msu2Protocol.lcdLoadAddr(0, 0, w, h) + data) { switchPending() }
        delayInterruptible(1000)
    }

    /** 网速线条图：每点 2px、高 20、最小量程 100KB/s、取最近 80 点，线下填充颜色。 */
    private fun drawNetLines(canvas: Canvas, values: List<Double>, baselineY: Int, color: Int) {
        val maxValue = maxOf(1024.0 * 100.0, values.maxOrNull() ?: 0.0)
        val recent = values.takeLast(80)
        if (recent.isEmpty()) return
        val linePaint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            alpha = 60
        }
        val line = Path()
        val fill = Path()
        var lastX = 0f
        recent.forEachIndexed { i, v ->
            val x = (i * 2).toFloat()
            val y = baselineY.toFloat() - (if (maxValue > 0) (v / maxValue * 20.0) else 0.0).toFloat()
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, baselineY.toFloat())
            }
            line.lineTo(x, y)
            fill.lineTo(x, y)
            lastX = x
        }
        fill.lineTo(lastX, baselineY.toFloat())
        fill.close()
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(line, linePaint)
    }

    /** 网速格式化（对齐 MG 版 sizeof_fmt）。 */
    private fun formatSpeed(num: Double): String {
        val base = 1024.0
        if (abs(num) < base) return String.format("%3.1fKiB", num / base)
        var n = num
        for (unit in arrayOf("", "Ki", "Mi", "Gi", "Ti", "Pi", "Ei", "Zi")) {
            if (abs(n) < base) return String.format("%3.1f%sB", n, unit)
            n /= base
        }
        return String.format("%.1fYiB", n)
    }

    /** 屏幕镜像。 */
    private suspend fun showMirror(s: Msu2Serial) {
        if (!projectionGranted) {
            val ok = requestProjection()
            if (!ok) {
                log("未获得屏幕捕获授权")
                delayInterruptible(1500)
                return
            }
            projectionGranted = true
            delayInterruptible(400)
        }
        val frame = MirrorService.MirrorBus.latest
        if (frame != null) {
            // 首次拿到帧时打印真实尺寸/编码字节数，便于确认投屏捕获与编码
            if (!mirrorInfoLogged) {
                mirrorInfoLogged = true
                log("镜像帧：${frame.w}x${frame.h} 编码${frame.data.size}B")
            }
            // 投屏帧发送过程中每块之间检查切换请求，用户随时可切走（在指令边界安全中止）
            s.sendScreen(Msu2Protocol.lcdLoadAddr(frame.x, frame.y, frame.w, frame.h) + frame.data) {
                switchPending()
            }
        } else {
            delay(80)
        }
    }

    private suspend fun requestProjection(): Boolean {
        val result = withTimeoutOrNull(PROJECTION_WAIT_MS) {
            withContext(Dispatchers.Main) {
                val d = projectionDeferred
                if (d != null && !d.isCompleted) {
                    d.await()
                } else {
                    val nd = CompletableDeferred<Boolean>()
                    projectionDeferred = nd
                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    nd.await()
                }
            }
        }
        return result ?: false
    }

    private fun stopMirrorSession() {
        projectionGranted = false
        projectionDeferred = null
        MirrorService.stop(this)
        MirrorService.MirrorBus.latest = null
    }

    private fun digitChar(d: Int): Char = ((d % 10) + 48).toChar()

    // 烧录素材

    private enum class FlashKind { GIF, PHOTO, BIN }

    @Volatile private var flashKind: FlashKind = FlashKind.BIN

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    /** 构建 Material 风格纵向单选组（项间距 8dp；外框内边距由容器统一设置）。 */
    private fun materialRadioGroup(options: List<String>): RadioGroup {
        return RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            options.forEachIndexed { i, text ->
                val rb = com.google.android.material.radiobutton.MaterialRadioButton(this@MainActivity).apply {
                    this.text = text
                    textSize = 16f
                }
                val lp = RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (i < options.size - 1) lp.bottomMargin = dp(8f)
                rb.layoutParams = lp
                addView(rb)
            }
            if (childCount > 0) check(getChildAt(0).id)
        }
    }

    /** 单选组中被选中项的下标（按添加顺序）。 */
    private fun RadioGroup.checkedIndex(): Int =
        (0 until childCount).firstOrNull { getChildAt(it).id == checkedRadioButtonId } ?: 0

    private fun showFlashDialog() {
        // 从上到下：GIF / 图片 / 固件
        val group = materialRadioGroup(
            listOf(
                getString(R.string.flash_kind_gif),
                getString(R.string.flash_kind_photo),
                getString(R.string.flash_kind_bin)
            )
        )
        // Material 对话框内容内边距：左右 24dp、上下 8dp
        group.setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flash_title)
            .setView(group)
            .setPositiveButton(R.string.flash_confirm) { _, _ ->
                flashKind = when (group.checkedIndex()) {
                    0 -> FlashKind.GIF
                    1 -> FlashKind.PHOTO
                    else -> FlashKind.BIN
                }
                val mime = when (flashKind) {
                    FlashKind.GIF -> arrayOf("image/gif")
                    FlashKind.PHOTO -> arrayOf("image/*")
                    FlashKind.BIN -> arrayOf("application/octet-stream", "application/x-binary", "*/*")
                }
                flashFileLauncher.launch(mime)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startFlash(uri: Uri) {
        val s = serial
        if (s == null) {
            log("未连接设备，无法烧录")
            return
        }
        when (flashKind) {
            FlashKind.GIF -> flashGif(s, uri)
            FlashKind.PHOTO -> showImageTargetDialog(s, uri)
            FlashKind.BIN -> showBinFlashDialog(s, uri)
        }
    }

    /** 烧录 GIF：解码动画 GIF 前 36 帧，缩放裁剪到 160x80，按帧烧录到页 0/100/.../3500。 */
    private fun flashGif(s: Msu2Serial, uri: Uri) {
        scope.launch {
            flashing = true
            try {
                progressDone = false
                progressStart = -1
                log("解析 GIF（≤36 帧，160x80）…")
                val frames = decodeGifFrames(uri)
                if (frames.isEmpty()) throw IllegalStateException("GIF 无有效帧")
                log("GIF 共 ${frames.size} 帧，转换中…")
                val data = ByteArray(frames.size * 25600)
                frames.forEachIndexed { i, bmp ->
                    val rgb = ByteArray(25600)
                    bitmapToRgb565(bmp, rgb)
                    System.arraycopy(rgb, 0, data, i * 25600, rgb.size)
                    bmp.recycle()
                }
                log("开始烧录 ${frames.size} 帧到 Flash 页 0（共 ${data.size / 256} 页，耗时较长）…")
                FlashWriter().flash(
                    s, data, 0, false,
                    onLog = { log(it) },
                    onProgress = { done, total -> runOnUiThread { renderProgress(done, total) } }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("GIF 烧录失败：${e.message}")
                runCatching { s.drain() }
                if (e is SerialTimeoutException) {
                    log("提示：MSU2 未在预期时间内响应。若设备已卡死，请重新插拔 MSU2 后重试")
                }
            } finally {
                flashing = false
            }
        }
    }

    /** 解码动画 GIF：按帧解析帧时间表，按帧率均匀取 36 帧，每帧缩放裁剪到 160x80。 */
    private fun decodeGifFrames(uri: Uri): List<Bitmap> {
        val movie = contentResolver.openInputStream(uri)?.use { Movie.decodeStream(it) }
            ?: throw IllegalStateException("无法解码 GIF（请确认是动画 GIF）")
        val w = movie.width()
        val h = movie.height()
        if (w <= 0 || h <= 0) throw IllegalStateException("GIF 尺寸无效")

        // 解析出的每帧起始时间表（毫秒）
        val frameTimes = parseGifFrameTimes(uri)
        val selected: LongArray = if (frameTimes != null && frameTimes.isNotEmpty()) {
            // 按帧率均匀取 36 帧：帧数>=36 均匀抽帧；<36 自动重复补足
            LongArray(36) { i -> frameTimes[(i * frameTimes.size) / 36] }
        } else {
            // 解析失败回退：按总时长均匀采样
            val duration = movie.duration()
            LongArray(36) { i -> if (duration > 0) duration.toLong() * i / 36 else 0L }
        }

        val frames = ArrayList<Bitmap>(36)
        for (t in selected) {
            movie.setTime(t.toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            movie.draw(canvas, 0f, 0f)
            frames.add(resizeTo160x80(bmp))
            bmp.recycle()
        }
        return frames
    }

    /** 解析 GIF 每帧起始时间（ms）：图像描述符(0x2C)为一帧，其前图形控制扩展(0x21 0xF9)给延时(10ms 单位)。 */
    private fun parseGifFrameTimes(uri: Uri): LongArray? {
        return try {
            val data = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            if (data.size < 13) return null
            if (data[0] != 'G'.code.toByte() || data[1] != 'I'.code.toByte() || data[2] != 'F'.code.toByte()) return null
            var p = 6
            val flags = data[p + 4].toInt() and 0xFF
            p += 7
            if ((flags and 0x80) != 0) p += 6 shl (flags and 0x07) // 全局调色板
            val times = ArrayList<Long>()
            var total = 0L
            var prevDelay = 0L
            while (p < data.size) {
                val block = data[p].toInt() and 0xFF
                p++
                when (block) {
                    0x2C -> { // 图像帧
                        if (p + 8 >= data.size) return null
                        times.add(total)
                        total += prevDelay
                        prevDelay = 0
                        val packed = data[p + 8].toInt() and 0xFF
                        p += 9
                        if ((packed and 0x80) != 0) p += 6 shl (packed and 0x07) // 局部调色板
                        if (p >= data.size) return null
                        p++ // LZW 最小码长
                        p = skipSubBlocks(data, p)
                    }
                    0x21 -> { // 扩展
                        if (p >= data.size) break
                        val label = data[p].toInt() and 0xFF
                        p++
                        if (label == 0xF9) { // 图形控制扩展：延时=该帧显示时长
                            if (p + 5 >= data.size) return null
                            prevDelay = (((data[p + 3].toInt() and 0xFF) shl 8) or (data[p + 2].toInt() and 0xFF)) * 10L
                            p += 6
                        } else {
                            p = skipSubBlocks(data, p)
                        }
                    }
                    0x3B -> break // 结束
                    else -> return null
                }
            }
            if (times.isEmpty()) null else times.toLongArray()
        } catch (e: Exception) {
            null
        }
    }

    /** 跳过子块序列（每块 1 字节长度 + 数据，0 结束）。 */
    private fun skipSubBlocks(data: ByteArray, start: Int): Int {
        var p = start
        while (p < data.size) {
            val len = data[p].toInt() and 0xFF
            p++
            if (len == 0) return p
            p += len
        }
        return p
    }

    /** 图片类：弹框选择目标页（时钟背景/照片/自定义页+输入框），点确认后缩放 160x80 烧录。 */
    private fun showImageTargetDialog(s: Msu2Serial, uri: Uri) {
        val rbClock = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            text = getString(R.string.flash_target_clock)
            textSize = 16f
        }
        val rbPhoto = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            text = getString(R.string.flash_target_photo)
            textSize = 16f
        }
        val rbCustom = com.google.android.material.radiobutton.MaterialRadioButton(this).apply {
            text = getString(R.string.flash_target_custom)
            textSize = 16f
        }
        val pageInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "页号"
            width = dp(96f)
            isEnabled = false
        }
        // 手动管理互斥（避免 RadioGroup 对嵌套单选按钮注册不可靠）
        fun select(rb: android.widget.RadioButton) {
            rbClock.isChecked = rb === rbClock
            rbPhoto.isChecked = rb === rbPhoto
            rbCustom.isChecked = rb === rbCustom
            pageInput.isEnabled = rb === rbCustom
        }
        rbClock.setOnCheckedChangeListener { _, c -> if (c) select(rbClock) }
        rbPhoto.setOnCheckedChangeListener { _, c -> if (c) select(rbPhoto) }
        rbCustom.setOnCheckedChangeListener { _, c -> if (c) select(rbCustom) }
        rbClock.isChecked = true

        // 自定义页：单选按钮 + 输入框在它右边
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(rbCustom)
            addView(pageInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8f) })
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
            addView(rbClock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(rbPhoto, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
            addView(customRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flash_target_title)
            .setView(container)
            .setPositiveButton(R.string.flash_confirm) { _, _ ->
                val page = when {
                    rbPhoto.isChecked -> 3926
                    rbCustom.isChecked -> pageInput.text.toString().toIntOrNull() ?: 0
                    else -> 3826
                }
                flashImageTo(s, uri, page)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun flashImageTo(s: Msu2Serial, uri: Uri, page: Int) {
        scope.launch {
            flashing = true
            try {
                progressDone = false
                progressStart = -1
                log("解析图片并缩放至 160x80 …")
                val bmp = decodeImage(uri)
                val resized = resizeTo160x80(bmp)
                bmp.recycle()
                val rgb = ByteArray(25600)
                bitmapToRgb565(resized, rgb)
                resized.recycle()
                log("烧录图片到 Flash 页 $page …")
                FlashWriter().flash(
                    s, rgb, page, false,
                    onLog = { log(it) },
                    onProgress = { done, total -> runOnUiThread { renderProgress(done, total) } }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("图片烧录失败：${e.message}")
                runCatching { s.drain() }
                if (e is SerialTimeoutException) {
                    log("提示：MSU2 未在预期时间内响应。若设备已卡死，请重新插拔 MSU2 后重试")
                }
            } finally {
                flashing = false
            }
        }
    }

    /** 固件/Bin 类：保持原有“起始页 + 类型（图片/字库）”流程。 */
    private fun showBinFlashDialog(s: Msu2Serial, uri: Uri) {
        val pageInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "起始页（0 - 4095）"
        }
        val rbGroup = materialRadioGroup(
            listOf(
                getString(R.string.flash_type_photo),
                getString(R.string.flash_type_zk)
            )
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), dp(8f))
            addView(pageInput)
            addView(rbGroup, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flash_params_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val page = pageInput.text.toString().toIntOrNull() ?: 0
                val zk = rbGroup.checkedIndex() == 1
                scope.launch {
                    flashing = true
                    try {
                        progressDone = false
                        progressStart = -1
                        log("开始烧录 $uri …")
                        val data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("无法读取文件")
                        FlashWriter().flash(
                            s, data, page, zk,
                            onLog = { log(it) },
                            onProgress = { done, total ->
                                runOnUiThread { renderProgress(done, total) }
                            }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("烧录失败：${e.message}")
                        runCatching { s.drain() }
                        if (e is SerialTimeoutException) {
                            log("提示：MSU2 未在预期时间内响应。若设备已卡死，请重新插拔 MSU2 后重试")
                        }
                    } finally {
                        flashing = false
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 解码图片（先采样边界防 OOM，再解码缩略）。 */
    private fun decodeImage(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 320 || bounds.outHeight / sample > 320) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("无法解析图片")
    }

    /** 按 V1.6 方式缩放并中心裁剪到 160x80。 */
    private fun resizeTo160x80(src: Bitmap): Bitmap {
        val sw = src.width
        val sh = src.height
        return if (sw >= sh * 2) {
            val nw = (80 * sw / sh).coerceAtLeast(160)
            val tmp = Bitmap.createScaledBitmap(src, nw, 80, true)
            Bitmap.createBitmap(tmp, (nw - 160) / 2, 0, 160, 80).also { tmp.recycle() }
        } else {
            val nh = (160 * sh / sw).coerceAtLeast(80)
            val tmp = Bitmap.createScaledBitmap(src, 160, nh, true)
            Bitmap.createBitmap(tmp, 0, (nh - 80) / 2, 160, 80).also { tmp.recycle() }
        }
    }

    /** 将 160x80 位图转成 RGB565 字节（对齐设备编码）。 */
    private fun bitmapToRgb565(bmp: Bitmap, out: ByteArray) {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        Msu2Protocol.rgb565Bytes(pixels, w, h, w, out)
    }

    // 菜单 / 关于 / 更新

    private fun showOverflowMenu(anchor: View) {
        val d = resources.displayMetrics.density

        val menuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = MaterialShapeDrawable().apply {
                setTint(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorSurface, Color.WHITE))
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, 4 * d)
                    .build()
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        val popup = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = 4 * d
        }

        menuView.addView(menuRow(getString(R.string.menu_about)) {
            popup.dismiss()
            showAboutDialog()
        })
        menuView.addView(menuRow(getString(R.string.menu_update)) {
            popup.dismiss()
            checkUpdate()
        })

        // 面板宽 = 内容宽 × 1.5
        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popup.setWidth((menuView.measuredWidth * 1.5).toInt())

        // 面板右缘对齐按钮右缘
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    /** 菜单行：文字居中，带按压反馈。 */
    private fun menuRow(label: String, onClick: () -> Unit): TextView {
        val d = resources.displayMetrics.density
        val ripple = TypedValue()
        val rippleRes =
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) ripple.resourceId else 0
        return TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
            height = (48 * d).toInt()
            gravity = Gravity.CENTER
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            if (rippleRes != 0) setBackgroundResource(rippleRes)
            setOnClickListener { onClick() }
        }
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_about)
            .setView(selectableBodyView(getString(R.string.about_content)))
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    /** 可长按复制、超长可滚动的弹窗内容视图。 */
    private fun selectableBodyView(content: String, maxHeightDp: Int = 360): TextView {
        val d = resources.displayMetrics.density
        return TextView(this).apply {
            text = content
            // setTextIsSelectable 后可滚动+长按复制，勿再覆盖 movementMethod
            setTextIsSelectable(true)
            setMaxHeight((maxHeightDp * d).toInt())
            setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), 0)
        }
    }

    private fun checkUpdate() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_update)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
            runOnUiThread {
                dialog.dismiss()
                if (result == null) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.menu_update)
                        .setMessage(R.string.update_failed)
                        .setPositiveButton(R.string.btn_open) { _, _ -> openUrl(RELEASES_URL) }
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show()
                } else {
                    val msg = buildString {
                        append(getString(R.string.update_available, result.tag))
                        if (!result.body.isNullOrBlank()) {
                            append("\n\n")
                            append(getString(R.string.update_changelog))
                            append("\n")
                            append(result.body)
                        }
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.menu_update)
                        .setView(selectableBodyView(msg))
                        .setPositiveButton(R.string.btn_open) { _, _ -> openUrl(result.url) }
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo? = runCatching {
        fetchFromHtmlPage() ?: fetchFromApi()
    }.getOrNull()

    /** 优先抓 releases 页面（HTML 不受 API 限流、国内更稳）。 */
    private fun fetchFromHtmlPage(): ReleaseInfo? = runCatching {
        val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Msu2Screen-Android")
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val tag = Regex("""/releases/tag/([^"&]+)""").find(body)?.groupValues?.get(1)
                if (tag != null) {
                    ReleaseInfo(tag, "$RELEASES_URL/tag/$tag", extractReleaseNotes(body))
                } else null
            } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun fetchFromApi(): ReleaseInfo? = runCatching {
        val conn = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "Msu2Screen-Android")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                ReleaseInfo(
                    tag = json.optString("tag_name", "未知版本"),
                    url = json.optString("html_url", RELEASES_URL),
                    body = json.optString("body", "").trim().ifEmpty { null }
                )
            } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 提取 releases 页面首个 markdown-body 作为更新日志。 */
    private fun extractReleaseNotes(html: String): String? {
        var idx = html.indexOf("markdown-body")
        while (idx >= 0) {
            val tagEnd = html.indexOf('>', idx)
            if (tagEnd >= 0) {
                var depth = 1
                var i = tagEnd + 1
                while (i < html.length) {
                    val open = html.indexOf("<div", i)
                    val close = html.indexOf("</div>", i)
                    if (close == -1) break
                    if (open != -1 && open < close) {
                        depth++
                        i = open + 4
                    } else {
                        depth--
                        if (depth == 0) {
                            val raw = html.substring(tagEnd + 1, close)
                            val text = stripHtml(raw)
                            if (text.isNotBlank()) return text
                            break
                        }
                        i = close + 5
                    }
                }
            }
            idx = html.indexOf("markdown-body", idx + 1)
        }
        return null
    }

    private fun stripHtml(html: String): String {
        var s = html
        // 块级标签换行，保留列表/段落结构
        s = s.replace(Regex("</?(p|div|li|h[1-6]|ul|ol|pre|br|blockquote|hr)[^>]*>"), "\n")
        s = s.replace(Regex("<[^>]+>"), "")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        return s.trim().replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { log("无法打开链接：${it.message}") }
    }

    private data class ReleaseInfo(val tag: String, val url: String, val body: String? = null)

    // UI

    private fun log(msg: String) {
        runOnUiThread {
            if (binding.tvLog.text.length > 20000) {
                binding.tvLog.text = ""
                progressStart = -1
                progressDone = false
            }
            removeProgressLine()
            binding.tvLog.append(msg)
            binding.tvLog.append("\n")
            scrollLogIfAtBottom()
        }
    }

    /** 仅当日志原本就在底部时才自动滚到底，避免把正在向上翻看的用户拽回去。 */
    private fun scrollLogIfAtBottom() {
        if (!binding.logScroll.canScrollVertically(1)) {
            binding.logScroll.post { binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** 命令行风格进度条：单行实时覆盖刷新，限流避免高频更新导致闪烁。 */
    private fun renderProgress(done: Int, total: Int) {
        val doneFinal = done >= total
        val now = SystemClock.elapsedRealtime()
        // 未完成时最多约 5 次/秒刷新；完成时刻必刷
        if (!doneFinal && now - lastProgressRender < 200) return
        lastProgressRender = now

        val pct = if (total <= 0) 100 else done * 100 / total
        val width = 20
        val filled = pct * width / 100
        val bar = "█".repeat(filled) + "░".repeat(width - filled)
        val line = "烧录 [$bar] $pct% ($done/$total 页)"
        removeProgressLine()
        progressStart = binding.tvLog.text.length
        binding.tvLog.append(line)
        binding.tvLog.append("\n")
        progressDone = doneFinal
        scrollLogIfAtBottom()
    }

    /** 删除当前进度行，烧录完成后保留 100% 行。 */
    private fun removeProgressLine() {
        if (progressStart < 0 || progressDone) return
        val text = binding.tvLog.text
        if (text is Editable && progressStart <= text.length) {
            text.delete(progressStart, text.length)
        }
        progressStart = -1
    }

    private fun updateStatus(status: String) {
        runOnUiThread { binding.tvStatus.text = status }
    }

    private fun updateStateLabel(state: Int) {
        runOnUiThread { binding.tvState.text = stateNames[state] }
    }

    private fun resetStateLabel() {
        runOnUiThread { binding.tvState.text = "" }
    }
}