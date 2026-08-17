package com.msu2.android.services

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.msu2.android.R
import com.msu2.android.usb.Msu2Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 屏幕镜像前台服务（MediaProjection）。
 *
 * Android 14 要求：先 startForeground(mediaProjection 类型)，再 getMediaProjection()，
 * 再 createVirtualDisplay()。每次进入镜像状态需重新获得用户授权。
 */
class MirrorService : Service() {

    companion object {
        private const val TAG = "MirrorService"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "com.msu2.android.START_MIRROR"
        private const val ACTION_STOP = "com.msu2.android.STOP_MIRROR"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val FRAME_INTERVAL_MS = 120L

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, MirrorService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorService::class.java))
        }
    }

    /** 帧总线：镜像服务产出的最新帧，由状态机消费。 */
    object MirrorBus {
        class Frame(val data: ByteArray, val x: Int, val y: Int, val w: Int, val h: Int)
        @Volatile var latest: Frame? = null
        @Volatile var onError: ((String) -> Unit)? = null
        @Volatile var onStop: (() -> Unit)? = null
        fun reset() { latest = null }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var projectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMirror()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startProjection(intent)
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            MirrorBus.onError?.invoke("未获得屏幕捕获授权")
            stopSelf()
            return
        }

        scope.launch {
            try {
                // 1) 先启动前台服务（mediaProjection 类型）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIF_ID, buildNotification())
                }
                // 2) 再获取投影 token
                val mp = projectionManager!!.getMediaProjection(resultCode, resultData)
                    ?: throw IllegalStateException("getMediaProjection 返回空")
                mediaProjection = mp
                // 3) 创建虚拟显示
                startCapture(mp)
            } catch (e: Exception) {
                Log.e(TAG, "startProjection failed", e)
                MirrorBus.onError?.invoke("投影启动失败: ${e.message}")
                stopSelf()
            }
        }
    }

    private fun startCapture(mp: MediaProjection) {
        val (w, h) = computeFitSize()
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = ir
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system/user")
                MirrorBus.onStop?.invoke()
                stopMirror()
            }
        }, mainHandler)

        val vd = mp.createVirtualDisplay(
            "MSU2Mirror",
            w, h,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface,
            null, null
        )
        virtualDisplay = vd

        MirrorBus.reset()
        captureJob = scope.launch {
            while (isActive && virtualDisplay != null) {
                acquireAndPublish()
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    /** 等比缩放屏幕到 240x240 内的尺寸（保持纵横比）。 */
    private fun computeFitSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        if (sw <= 0 || sh <= 0) return 240 to 240
        val scale = min(240f / sw, 240f / sh)
        val w = (sw * scale).toInt().coerceAtLeast(1)
        val h = (sh * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    private fun acquireAndPublish() {
        val ir = imageReader ?: return
        val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowBytes = w * pixelStride
            val row = ByteArray(rowBytes)
            val ints = IntArray(w * h)
            var idx = 0
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                buffer.get(row, 0, rowBytes)
                var o = 0
                for (x in 0 until w) {
                    ints[idx++] = 0xFF000000.toInt() or
                        ((row[o].toInt() and 0xFF) shl 16) or
                        ((row[o + 1].toInt() and 0xFF) shl 8) or
                        (row[o + 2].toInt() and 0xFF)
                    o += pixelStride
                }
            }
            val rgb = ByteArray(w * h * 2)
            Msu2Protocol.rgb565Bytes(ints, w, h, w, rgb)
            val data = Msu2Protocol.encodeScreenData(rgb, w, h)
            MirrorBus.latest = MirrorBus.Frame(data, (240 - w) / 2, (240 - h) / 2, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "acquire frame failed", e)
        } finally {
            img.close()
        }
    }

    private fun stopMirror() {
        captureJob?.cancel()
        captureJob = null
        releaseDisplay()
        val mp = mediaProjection
        mediaProjection = null
        try { mp?.stop() } catch (_: Exception) {}
        MirrorBus.latest = null
        stopSelf()
    }

    private fun releaseDisplay() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "屏幕镜像", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mirror_foreground_notify))
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        scope.cancel()
        captureJob?.cancel()
        captureJob = null
        releaseDisplay()
        val mp = mediaProjection
        mediaProjection = null
        try { mp?.stop() } catch (_: Exception) {}
        MirrorBus.latest = null
        super.onDestroy()
    }
}