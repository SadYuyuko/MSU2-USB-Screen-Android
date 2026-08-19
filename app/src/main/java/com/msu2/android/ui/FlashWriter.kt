package com.msu2.android.ui

import com.msu2.android.usb.Msu2Protocol
import com.msu2.android.usb.Msu2Serial
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/** 将素材 .bin 烧录到 MSU2 Flash：照片类先擦除后写（03 03），字库类直接写（03 01）。 */
class FlashWriter {

    /** MSU2 Flash 总容量 1024KB，每页 256B，共 4096 页。 */
    companion object {
        const val FLASH_TOTAL_PAGES = 4096
    }

    /** 烧录指定数据：data 素材字节，page 起始页，zk 字库类(不擦除)或图片类(先擦除)。 */
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
            // 擦除按 4KB 扇区（16 页）进行，等待超时须按页数放大，避免擦除期间 USB 缓冲填满超时
            val eraseTimeoutMs = maxOf(10_000L, totalPages * 100L)
            serial.ack(Msu2Protocol.eraseFlashPage(page, totalPages), waitMs = eraseTimeoutMs, requireResponse = true)
            // 擦除完成后稍作稳定
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