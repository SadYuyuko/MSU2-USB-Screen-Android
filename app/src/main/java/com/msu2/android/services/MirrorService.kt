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

/** 屏幕镜像前台服务（MediaProjection；Android 14 需先 startForeground 再 getMediaProjection）。 */
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
    @Volatile private var lastLoggedSize = -1

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
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by system/user")
                MirrorBus.onStop?.invoke()
                stopMirror()
            }
        }, mainHandler)
        val (w, h) = computeFitSize()
        createCapture(mp, w, h)
        MirrorBus.reset()
        captureJob = scope.launch {
            while (isActive && virtualDisplay != null) {
                val needed = computeFitSize()
                val cur = imageReader?.let { it.width to it.height }
                if (cur != null && cur != needed) {
                    Log.i(TAG, "方向变化，重建捕获 $cur -> $needed")
                    createCapture(mp, needed.first, needed.second)
                } else {
                    acquireAndPublish()
                }
                delay(FRAME_INTERVAL_MS)
            }
        }
    }

    private fun createCapture(mp: MediaProjection, w: Int, h: Int) {
        releaseDisplay()
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        imageReader = ir
        val vd = mp.createVirtualDisplay(
            "MSU2Mirror",
            w, h,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface,
            null, null
        )
        virtualDisplay = vd
    }

    /** 投屏捕获尺寸：竖屏 80x160，横屏 160x80。 */
    private fun computeFitSize(): Pair<Int, Int> =
        if (isLandscape()) Msu2Protocol.SCREEN_W to Msu2Protocol.SCREEN_H
        else Msu2Protocol.MIRROR_W to Msu2Protocol.MIRROR_H

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

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
            val rw: Int
            val rh: Int
            val frame: IntArray
            if (w > h) {
                // 手机横屏：整屏 1:1 直显，避免镜像
                rw = w
                rh = h
                frame = ints
            } else {
                // 手机竖屏：软件旋转 90° 成 160x80
                rw = h
                rh = w
                frame = IntArray(rw * rh)
                for (ny in 0 until rh) {
                    val srcCol = w - 1 - ny
                    for (nx in 0 until rw) frame[ny * rw + nx] = ints[nx * w + srcCol]
                }
            }
            val rgb = ByteArray(rw * rh * 2)
            Msu2Protocol.rgb565Bytes(frame, rw, rh, rw, rgb)
            val data = Msu2Protocol.encodeScreenData(rgb, rw, rh)
            if (data.size != lastLoggedSize) {
                lastLoggedSize = data.size
                Log.i(TAG, "frame ${w}x$h ${if (w > h) "横屏直显→" else "竖屏旋转→"}${rw}x$rh 编码${data.size}B")
            }
            MirrorBus.latest = MirrorBus.Frame(data, 0, 0, rw, rh)
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