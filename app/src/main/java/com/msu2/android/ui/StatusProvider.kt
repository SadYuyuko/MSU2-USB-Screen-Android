package com.msu2.android.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手机状态采集：CPU / 内存 / 电量（对应 PC 版 psutil 取数）。
 */
object StatusProvider {

    /** CPU 占用率（%）。两次采样 /proc/stat 计算，约 500ms 间隔。 */
    suspend fun cpuUsage(): Int = withContext(Dispatchers.IO) {
        val t1 = readCpuStat()
        delay(500)
        val t2 = readCpuStat()
        val idle1 = t1.idle
        val idle2 = t2.idle
        val total1 = t1.total
        val total2 = t2.total
        val dTotal = (total2 - total1).coerceAtLeast(1)
        val dIdle = (idle2 - idle1).coerceAtLeast(0)
        ((dTotal - dIdle) * 100.0 / dTotal).toInt().coerceIn(0, 100)
    }

    private data class CpuStat(val total: Long, val idle: Long)

    private fun readCpuStat(): CpuStat {
        var total = 0L
        var idle = 0L
        try {
            File("/proc/stat").forEachLine { line ->
                if (line.startsWith("cpu ")) {
                    val parts = line.split(Regex("\\s+")).drop(1)
                    if (parts.size >= 5) {
                        val user = parts[0].toLongOrNull() ?: 0L
                        val nice = parts[1].toLongOrNull() ?: 0L
                        val sys = parts[2].toLongOrNull() ?: 0L
                        val idleV = parts[3].toLongOrNull() ?: 0L
                        val iowait = if (parts.size > 4) parts[4].toLongOrNull() ?: 0L else 0L
                        total = user + nice + sys + idleV + iowait
                        idle = idleV
                    }
                    return@forEachLine
                }
            }
        } catch (_: Exception) {}
        return CpuStat(total, idle)
    }

    /** 内存占用率（%）。 */
    fun memoryUsage(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val total = mem.totalMem
        val avail = mem.availMem
        if (total <= 0) return 0
        return (((total - avail) * 100) / total).toInt().coerceIn(0, 100)
    }

    /** 电池电量（%）。 */
    fun batteryPercent(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) level * 100 / scale else 0
        }
    }
}