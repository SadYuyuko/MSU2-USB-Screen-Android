package com.msu2.android.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 手机状态采集：CPU / 内存 / 电量 / 存储（对应 PC 版 psutil 取数）。
 */
object StatusProvider {

    private const val TAG = "StatusProvider"

    /**
     * CPU 占用率（%）：读 /proc/stat 汇总行两次（间隔 ~300ms）算差值。
     * @return 0~100；/proc/stat 不可用时返回 -1（调用方给出提示）。
     */
    suspend fun cpuUsage(): Int = withContext(Dispatchers.IO) {
        val t1 = readCpuStat()
        delay(300)
        val t2 = readCpuStat()
        val dTotal = t2.total - t1.total
        val dIdle = (t2.idle - t1.idle).coerceAtLeast(0)
        if (t1.total > 0 && t2.total > 0 && dTotal > 0) {
            return@withContext ((dTotal - dIdle) * 100.0 / dTotal).toInt().coerceIn(0, 100)
        }
        Log.w(TAG, "/proc/stat 不可用或无变化: t1=$t1 t2=$t2")
        -1
    }

    private data class CpuStat(val total: Long, val idle: Long)

    /** 读取 /proc/stat 汇总行 "cpu ..."（= 所有核心之和，即整体占用）。 */
    private fun readCpuStat(): CpuStat {
        var total = 0L
        var idle = 0L
        try {
            BufferedReader(FileReader("/proc/stat")).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (!line.startsWith("cpu")) continue
                    if (line.length > 3 && line[3] == ' ') {
                        val parts = line.trim().split(Regex("\\s+")).drop(1)
                        if (parts.size >= 5) {
                            total = parts[0].toLongOrNull() ?: 0L
                            total += parts[1].toLongOrNull() ?: 0L
                            total += parts[2].toLongOrNull() ?: 0L
                            idle = parts[3].toLongOrNull() ?: 0L
                            total += parts[4].toLongOrNull() ?: 0L
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readCpuStat 异常: ${e.message}")
        }
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

    /** 内部存储占用率（%），对应 V1.6 的 FRQ（磁盘使用率）。 */
    fun storageUsage(context: Context): Int {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            if (total <= 0) 0 else (((total - free) * 100) / total).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            0
        }
    }
}