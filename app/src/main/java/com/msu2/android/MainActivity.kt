package com.msu2.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hoho.android.usbserial.driver.SerialTimeoutException
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.msu2.android.databinding.ActivityMainBinding
import com.msu2.android.services.MirrorService
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
import java.time.LocalTime
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "Msu2Screen"
        private const val VID = 0x1A86
        private const val PID = 0xFE0C
        private const val ACTION_USB_PERMISSION = "com.msu2.android.USB_PERMISSION"
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
    @Volatile private var projectionGranted = false
    @Volatile private var flashing = false
    @Volatile private var projectionDeferred: CompletableDeferred<Boolean>? = null
    @Volatile private var permissionContinuation: kotlin.coroutines.Continuation<Boolean>? = null
    private var progressStart = -1
    private var progressDone = false

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

    // ---------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------

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
            getString(R.string.state_3), getString(R.string.state_4), getString(R.string.state_5)
        )

        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { disconnect() }
        binding.btnSwitchState.setOnClickListener { keyEvent = true }
        binding.btnFlash.setOnClickListener { showFlashDialog() }

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

        // 注册 USB 相关广播（整个 Activity 生命周期内保持注册，
        // 避免 USB 权限对话框导致 onPause 时错过权限结果广播）
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

    /** 沉浸式：内容延伸到状态栏/导航栏后，为根布局加上系统栏 insets + 12dp 边距。 */
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

    // ---------------------------------------------------------------
    // 连接 / 断开
    // ---------------------------------------------------------------

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
            // 不能调用 disconnectInternal()（它会 cancelAndJoin 当前连接任务，造成自等待死锁）
            connected = false
            keyEvent = false
            flashing = false
            stopMirrorSession()
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

    private suspend fun disconnectInternal() {
        connected = false
        keyEvent = false
        flashing = false
        resetStateLabel()
        stopMirrorSession()
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

    // ---------------------------------------------------------------
    // 状态机
    // ---------------------------------------------------------------

    private suspend fun CoroutineScope.runKeyPoll(s: Msu2Serial) {
        val adc1 = s.readAdc(9)
        val adc2 = s.readAdc(9)
        val adcDet = (adc1 + adc2) / 2.0 - 125.0
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

    private suspend fun CoroutineScope.runDisplayLoop(s: Msu2Serial) {
        var state = 0
        var stateChanged = true
        var gifNum = 0
        while (isActive && connected) {
            // 烧录期间暂停显示轮询，避免与 Flash 写操作争用串口并刷屏错误日志
            if (flashing) {
                delay(50)
                continue
            }
            if (keyEvent) {
                keyEvent = false
                val prev = state
                state = (state + 1) % 6
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
                    0 -> { // GIF 动图
                        if (stateChanged) gifNum = 0
                        s.ack(Msu2Protocol.lcdPhoto(0, 0, 240, 240, gifNum * 450))
                        gifNum = (gifNum + 1) % 6
                    }
                    1 -> showPhoneStatus(s, Msu2Protocol.BLUE, stateChanged)
                    2 -> showPhoneStatus(s, Msu2Protocol.RED, stateChanged)
                    3 -> { // 照片
                        if (stateChanged) {
                            s.ack(Msu2Protocol.lcdPhoto(0, 0, 240, 240, Msu2Protocol.PAGE_C3))
                        }
                        delay(300)
                    }
                    4 -> showClock(s, stateChanged)
                    5 -> showMirror(s)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("显示异常：${e.message}")
                delay(500)
            }
            stateChanged = false
            delay(30)
        }
    }

    /** 手机状态（蓝/红），对应 Python show_PC_state。 */
    private suspend fun showPhoneStatus(s: Msu2Serial, fc: Int, stateChanged: Boolean) {
        val photoAdd = Msu2Protocol.PAGE_DEMO1
        val numAdd = Msu2Protocol.PAGE_N48X66
        val bc = Msu2Protocol.BLACK
        if (stateChanged) {
            s.ack(Msu2Protocol.lcdPhotoWb(0, 0, 240, 240, photoAdd, fc, bc))
        }
        var cpu = StatusProvider.cpuUsage()
        val mem = StatusProvider.memoryUsage(this)
        val bat = StatusProvider.batteryPercent(this)

        // CPU（y=24）
        if (cpu >= 100) { s.ack(Msu2Protocol.lcdPhotoWb(120, 24, 24, 66, 20 + numAdd, fc, bc)); cpu %= 100 }
        else s.ack(Msu2Protocol.lcdPhotoWb(120, 24, 24, 66, 21 + numAdd, fc, bc))
        s.ack(Msu2Protocol.lcdPhotoWb(144, 24, 48, 66, (cpu / 10) * 2 + numAdd, fc, bc))
        s.ack(Msu2Protocol.lcdPhotoWb(192, 24, 48, 66, (cpu % 10) * 2 + numAdd, fc, bc))

        // 内存（y=87）
        var memV = mem
        if (memV >= 100) { s.ack(Msu2Protocol.lcdPhotoWb(120, 87, 24, 66, 20 + numAdd, fc, bc)); memV %= 100 }
        else s.ack(Msu2Protocol.lcdPhotoWb(120, 87, 24, 66, 21 + numAdd, fc, bc))
        s.ack(Msu2Protocol.lcdPhotoWb(144, 87, 48, 66, (memV / 10) * 2 + numAdd, fc, bc))
        s.ack(Msu2Protocol.lcdPhotoWb(192, 87, 48, 66, (memV % 10) * 2 + numAdd, fc, bc))

        // 电量（y=150）
        var batV = bat
        if (batV >= 100) { s.ack(Msu2Protocol.lcdPhotoWb(120, 150, 24, 66, 20 + numAdd, fc, bc)); batV %= 100 }
        else s.ack(Msu2Protocol.lcdPhotoWb(120, 150, 24, 66, 21 + numAdd, fc, bc))
        s.ack(Msu2Protocol.lcdPhotoWb(144, 150, 48, 66, (batV / 10) * 2 + numAdd, fc, bc))
        s.ack(Msu2Protocol.lcdPhotoWb(192, 150, 48, 66, (batV % 10) * 2 + numAdd, fc, bc))
    }

    /** 时钟，对应 Python show_PC_time。 */
    private suspend fun showClock(s: Msu2Serial, stateChanged: Boolean) {
        val fc = Msu2Protocol.YELLOW
        val photoAdd = Msu2Protocol.PAGE_C6
        val numAdd = Msu2Protocol.PAGE_ASC64
        if (stateChanged) {
            s.ack(Msu2Protocol.lcdPhoto(0, 0, 240, 240, photoAdd))
            s.ack(Msu2Protocol.lcdAscii32x64Mix(56 + 8, 32, ':', fc, photoAdd, numAdd))
            s.ack(Msu2Protocol.lcdAscii32x64Mix(136 + 8, 32, ':', fc, photoAdd, numAdd))
        }
        val now = LocalTime.now()
        val h = now.hour
        val m = now.minute
        val sec = now.second
        s.ack(Msu2Protocol.lcdAscii32x64Mix(0 + 8, 32, digitChar(h / 10), fc, photoAdd, numAdd))
        s.ack(Msu2Protocol.lcdAscii32x64Mix(32 + 8, 32, digitChar(h % 10), fc, photoAdd, numAdd))
        s.ack(Msu2Protocol.lcdAscii32x64Mix(80 + 8, 32, digitChar(m / 10), fc, photoAdd, numAdd))
        s.ack(Msu2Protocol.lcdAscii32x64Mix(112 + 8, 32, digitChar(m % 10), fc, photoAdd, numAdd))
        s.ack(Msu2Protocol.lcdAscii32x64Mix(160 + 8, 32, digitChar(sec / 10), fc, photoAdd, numAdd))
        s.ack(Msu2Protocol.lcdAscii32x64Mix(192 + 8, 32, digitChar(sec % 10), fc, photoAdd, numAdd))
        delay(200)
    }

    /** 屏幕镜像。 */
    private suspend fun showMirror(s: Msu2Serial) {
        if (!projectionGranted) {
            val ok = requestProjection()
            if (!ok) {
                log("未获得屏幕捕获授权")
                delay(1500)
                return
            }
            projectionGranted = true
            delay(400)
        }
        val frame = MirrorService.MirrorBus.latest
        if (frame != null) {
            s.sendScreen(Msu2Protocol.lcdLoadAddr(frame.x, frame.y, frame.w, frame.h) + frame.data)
        } else {
            delay(80)
        }
    }

    private suspend fun requestProjection(): Boolean {
        val result = withTimeoutOrNull(60000) {
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

    // ---------------------------------------------------------------
    // 烧录素材
    // ---------------------------------------------------------------

    private fun showFlashDialog() {
        flashFileLauncher.launch(arrayOf("application/octet-stream", "application/x-binary", "application/*", "*/*"))
    }

    private fun startFlash(uri: Uri) {
        val s = serial
        if (s == null) {
            log("未连接设备，无法烧录")
            return
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.flash_params_title)

        // 起始页输入
        val pageInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "起始页（0 - 499）"
        }
        // 类型选择：图片（先擦除）/ 字库（不擦除）
        val rbPhoto = RadioButton(this).apply { text = getString(R.string.flash_type_photo) }
        val rbZk = RadioButton(this).apply { text = getString(R.string.flash_type_zk) }
        val rbGroup = RadioGroup(this).apply {
            addView(rbPhoto)
            addView(rbZk)
            check(rbPhoto.id)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pageInput)
            addView(rbGroup)
        }
        builder.setView(container)

        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            val page = pageInput.text.toString().toIntOrNull() ?: 0
            val zk = rbGroup.checkedRadioButtonId == rbZk.id
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
                    // 清空设备残留数据，避免影响后续指令
                    runCatching { s.drain() }
                    if (e is SerialTimeoutException) {
                        log("提示：MSU2 未在预期时间内响应。若设备已卡死，请重新插拔 MSU2 后重试")
                    }
                } finally {
                    flashing = false
                }
            }
        }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.show()
    }

    // ---------------------------------------------------------------
    // UI
    // ---------------------------------------------------------------

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
            binding.logScroll.post { binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /** 命令行风格进度条：单行实时覆盖刷新。 */
    private fun renderProgress(done: Int, total: Int) {
        val pct = if (total <= 0) 100 else done * 100 / total
        val width = 20
        val filled = pct * width / 100
        val bar = "█".repeat(filled) + "░".repeat(width - filled)
        val line = "烧录 [$bar] $pct% ($done/$total 页)"
        removeProgressLine()
        progressStart = binding.tvLog.text.length
        binding.tvLog.append(line)
        binding.tvLog.append("\n")
        progressDone = done >= total
        binding.logScroll.post { binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** 删除当前进度行，使普通日志/下一进度行能覆盖它；烧录完成后保留 100% 进度行。 */
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