package com.msu2.android.ui

import com.msu2.android.usb.Msu2Protocol
import com.msu2.android.usb.Msu2Serial
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/**
 * 将用户选取的 .bin 素材烧录到 MSU2 Flash。
 * 照片类用 Python Write_Flash_Photo_fast 流程（先擦除，03 03 写页）；
 * 字库类（ASC64）用 Write_Flash_ZK 流程（不擦除，03 01 写页，末 6 字节非点阵）。
 */
class FlashWriter {

    /** MSU2 Flash 总容量 1024KB，每页 256B，共 4096 页。 */
    companion object {
        const val FLASH_TOTAL_PAGES = 4096
    }

    /**
     * 烧录指定数据。
     * @param data 素材原始字节
     * @param page 起始 Flash 页
     * @param zk true=字库类（不擦除，末 6 字节非点阵）；false=图片类（先擦除）
     * @param onLog 日志回调
     * @param onProgress 进度回调（已完成页数, 总页数）
     */
    suspend fun flash(
        serial: Msu2Serial,
        data: ByteArray,
        page: Int,
        zk: Boolean,
        onLog: (String) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        var fsize = data.size
        if (zk) fsize -= 6 // 字库文件最后 6 字节不是点阵信息
        onLog("素材大小 ${data.size} B，有效数据 ${fsize} B")

        val totalPages = if (fsize % 256 != 0) fsize / 256 + 1 else fsize / 256
        if (page < 0 || page + totalPages > FLASH_TOTAL_PAGES) {
            throw IllegalStateException(
                "起始页越界：$page + $totalPages 页超过 Flash 容量（共 $FLASH_TOTAL_PAGES 页）"
            )
        }
        var written = 0

        if (!zk) {
            // 照片：先擦除区域，再快速写入
            onLog("擦除 $totalPages 页 (起始页 $page)")
            // 擦除按 4KB 扇区进行（每 16 页一扇区，每扇区约 50~400ms），
            // 必须等设备真正擦完（收到擦除完成响应）才开始写页。等待超时按页数放大，
            // 否则设备忙于擦除、不服务 USB，写页突发数据会因接收缓冲填满而 bulk 写入超时（rc=-1）。
            val eraseTimeoutMs = maxOf(10_000L, totalPages * 100L)
            serial.ack(Msu2Protocol.eraseFlashPage(page, totalPages), waitMs = eraseTimeoutMs, requireResponse = true)
            // 擦除完成后稍作稳定，确保设备状态机回到空闲
            delay(200)
        }

        var off = 0
        var p = page
        while (off + 256 <= fsize) {
            val cmd = buildPageCommands(data, off, 256) +
                Msu2Protocol.writeFlashPage(p, 1, zk)
            serial.ack(cmd, waitMs = 3000)
            off += 256
            p += 1
            written += 1
            onProgress(written, totalPages)
        }
        if (off < fsize) {
            val remain = fsize - off
            val padded = ByteArray(256) { 0xFF.toByte() }
            data.copyInto(padded, 0, off, off + remain)
            val cmd = buildPageCommands(padded, 0, 256) +
                Msu2Protocol.writeFlashPage(p, 1, zk)
            serial.ack(cmd, waitMs = 3000)
            written += 1
            onProgress(written, totalPages)
        }
        onLog("烧写完成（共 ${p - page} 页）")
    }

    /** 生成 256B 数据的 64 条 04 写缓存指令。 */
    private fun buildPageCommands(data: ByteArray, off: Int, size: Int): ByteArray {
        val out = ByteArrayOutputStream(64 * 6)
        for (i in 0 until 64) {
            val b = off + i * 4
            out.write(
                Msu2Protocol.flashDataWrite(
                    i,
                    data[b].toInt() and 0xFF,
                    data[b + 1].toInt() and 0xFF,
                    data[b + 2].toInt() and 0xFF,
                    data[b + 3].toInt() and 0xFF
                )
            )
        }
        return out.toByteArray()
    }
}