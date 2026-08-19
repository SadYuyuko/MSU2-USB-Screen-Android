package com.msu2.android.usb

/** MSU2 副屏串口协议（6 字节包 [CMD][SUB][D0][D1][D2]，逐字节对齐 Python 源码）。 */
object Msu2Protocol {

    // 颜色（RGB565）
    const val RED = 0xF800
    const val GREEN = 0x07E0
    const val BLUE = 0x001F
    const val WHITE = 0xFFFF
    const val BLACK = 0x0000
    const val YELLOW = 0xFFE0

    // MSU2 MINI（V1.6 固件）：160x80 屏幕
    const val SCREEN_W = 160
    const val SCREEN_H = 80

    /** 投屏竖屏（旋转 90° 后）逻辑尺寸：80 宽 × 160 高。 */
    const val MIRROR_W = 80
    const val MIRROR_H = 160

    /** V1.6 Flash 页布局（页 = 256B，出厂已烧录，与 MSU2_MINI_DemoV1.6 一致） */
    const val PAGE_ASC64 = 3651       // 32x64 ASCII 字库
    const val PAGE_CLK_BG = 3826      // 时钟背景 160x80 彩色，100 页
    const val PAGE_PH1 = 3926         // 照片 160x80 彩色，100 页
    const val PAGE_N24X33 = 4026      // 24x33 数码管字库（页+0..9 数字，+10 百位“1”，+11 空白）
    const val PAGE_MP1 = 4038         // 手机状态单色背景 160x80

    /** GIF 动图：160x80 彩色，每帧 100 页，共 36 帧（页 0,100,...,3500） */
    const val GIF_FRAME_PAGES = 100
    const val GIF_FRAME_COUNT = 36

    // SFR（寄存器）读写   CMD=0x00

    /** 读 8bit 寄存器，add 为 16bit 地址。 00 30 00 AH AL 00 */
    fun readU8(add: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0x00, (add ushr 8).toByte(), (add and 0xFF).toByte(), 0x00)

    /** 读 16bit 寄存器，add 为 8bit 地址。 00 30 20 AL 00 00 */
    fun readU16(add: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0x20, (add and 0xFF).toByte(), 0x00, 0x00)

    /** 写 8bit 寄存器。 00 30 80 AH AL DL */
    fun writeU8(add: Int, data: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0x80.toByte(), (add ushr 8).toByte(), (add and 0xFF).toByte(), (data and 0xFF).toByte())

    /** 写 16bit 寄存器。 00 30 A0 AL DH DL */
    fun writeU16(add: Int, data: Int): ByteArray =
        byteArrayOf(0x00, 0x30, 0xA0.toByte(), (add and 0xFF).toByte(), (data ushr 8).toByte(), (data and 0xFF).toByte())

    // ADC 读取   CMD=0x08（用于读取按键 CH9）

    /** 08 CH 00 00 00 00 */
    fun readAdc(ch: Int): ByteArray =
        byteArrayOf(0x08, (ch and 0xFF).toByte(), 0x00, 0x00, 0x00, 0x00)

    // Flash 操作   CMD=0x03

    /** 擦除指定区域（add 与 size 均按 16bit 取低字节）。 03 02 AH AL SH SL */
    fun eraseFlashPage(add: Int, size: Int): ByteArray =
        byteArrayOf(
            0x03, 0x02,
            ((add % 65536) / 256).toByte(),
            ((add % 65536) % 256).toByte(),
            ((size % 65536) / 256).toByte(),
            ((size % 65536) % 256).toByte()
        )

    /** 写 Flash 页（erase=true 自动擦除 03 01，false 快速写 03 03）。 03 01/03 BH AH AL PN */
    fun writeFlashPage(pageAdd: Int, pageNum: Int, erase: Boolean): ByteArray =
        byteArrayOf(
            0x03, if (erase) 0x01 else 0x03,
            (pageAdd / (256 * 256) and 0xFF).toByte(),
            ((pageAdd % 65536) / 256).toByte(),
            ((pageAdd % 65536) % 256).toByte(),
            (pageNum and 0xFF).toByte()
        )

    /** 写 4 字节到 256B RAM 缓存（用于 Flash 页数据 / LCD 显存）。 04 IDX D0 D1 D2 D3 */
    fun flashDataWrite(idx: Int, d0: Int, d1: Int, d2: Int, d3: Int): ByteArray =
        byteArrayOf(
            0x04, (idx and 0xFF).toByte(),
            (d0 and 0xFF).toByte(), (d1 and 0xFF).toByte(),
            (d2 and 0xFF).toByte(), (d3 and 0xFF).toByte()
        )

    // LCD 指令   CMD=0x02

    /** 设置起始坐标。 02 00 XH XL YH YL */
    fun lcdSetXY(x: Int, y: Int): ByteArray =
        byteArrayOf(0x02, 0x00, (x ushr 8).toByte(), (x and 0xFF).toByte(), (y ushr 8).toByte(), (y and 0xFF).toByte())

    /** 设置显示尺寸。 02 01 XH XL YH YL */
    fun lcdSetSize(w: Int, h: Int): ByteArray =
        byteArrayOf(0x02, 0x01, (w ushr 8).toByte(), (w and 0xFF).toByte(), (h ushr 8).toByte(), (h and 0xFF).toByte())

    /** 设置颜色（FC 前景，BC 背景/字库页）。 02 02 FCH FCL BCH BCL */
    fun lcdSetColor(fc: Int, bc: Int): ByteArray =
        byteArrayOf(0x02, 0x02, (fc ushr 8).toByte(), (fc and 0xFF).toByte(), (bc ushr 8).toByte(), (bc and 0xFF).toByte())

    /** LCD 显示指令（02 03 op d0 d1 d2；op: 0 彩图 1 单色 2 ASCII 7 载入显存 8 提交 9 刷新）。 */
    fun lcdDisplay(op: Int, d0: Int, d1: Int, d2: Int): ByteArray =
        byteArrayOf(
            0x02, 0x03, (op and 0xFF).toByte(),
            (d0 and 0xFF).toByte(), (d1 and 0xFF).toByte(),
            (d2 and 0xFF).toByte()
        )

    /** 设置压缩模式主色（02 04 RR GG BB AA，color 按 32bit 大端）。 */
    fun lcdSetColorRam(color: Int): ByteArray =
        byteArrayOf(
            0x02, 0x04,
            ((color ushr 24) and 0xFF).toByte(),
            ((color ushr 16) and 0xFF).toByte(),
            ((color ushr 8) and 0xFF).toByte(),
            (color and 0xFF).toByte()
        )

    // 复合指令

    /** 载入显存写入地址（Python LCD_ADD）。 */
    fun lcdLoadAddr(x: Int, y: Int, w: Int, h: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdDisplay(7, 0, 0, 0)

    /** 显示 Flash 中的彩色图片（Python LCD_Photo，pageAdd 为图片所在 Flash 页）。 */
    fun lcdPhoto(x: Int, y: Int, w: Int, h: Int, pageAdd: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdDisplay(0, pageAdd / 256, pageAdd % 256, 0)

    /** 显示 Flash 中的单色图片（Python LCD_Photo_wb）。 */
    fun lcdPhotoWb(x: Int, y: Int, w: Int, h: Int, pageAdd: Int, fc: Int, bc: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdSetColor(fc, bc) + lcdDisplay(1, pageAdd / 256, pageAdd % 256, 0)

    /** 显示 32x64 ASCII 字符（02 03 05 CH NH NL，6 字节无尾部 0）。 */
    fun lcdAscii32x64Mix(x: Int, y: Int, ch: Char, fc: Int, bgPage: Int, numPage: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetColor(fc, bgPage) +
            byteArrayOf(0x02, 0x03, 0x05, (ch.code and 0xFF).toByte(), (numPage / 256).toByte(), (numPage % 256).toByte())

    // RGB565 / 屏幕数据编码

    /** 将像素转成 RGB565 字节流（高5R+中6G+低5B，对齐 Python Screen_Date_get）。 */
    fun rgb565Bytes(pixels: IntArray, width: Int, height: Int, stride: Int, out: ByteArray) {
        var o = 0
        for (y in 0 until height) {
            val row = y * stride
            for (x in 0 until width) {
                val p = pixels[row + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                out[o++] = (((r shr 3) shl 3) or (g shr 5)).toByte()          // (r//8)*8 + g//32
                out[o++] = ((((g and 0x1F) shr 2) shl 5) or (b shr 3)).toByte() // ((g%32)//4)*32 + b//8
            }
        }
    }

    /** 将 RGB565 编码为屏幕指令（主色 02 04 + 差异 04 + 分页提交 02 03 08）。 */
    fun encodeScreenData(rgb565: ByteArray, xSize: Int, ySize: Int): ByteArray {
        val total = xSize * ySize * 2
        val out = java.io.ByteArrayOutputStream(total * 7 / 6)
        var pos = 0
        while (pos < total - total % 256) {
            encodePage(rgb565, pos, out, fullPage = true, remain = 0)
            pos += 256
        }
        if (total % 256 != 0) {
            encodePage(rgb565, pos, out, fullPage = false, remain = total % 256)
        }
        return out.toByteArray()
    }

    private fun encodePage(rgb565: ByteArray, pos: Int, out: java.io.ByteArrayOutputStream, fullPage: Boolean, remain: Int) {
        // 组值：4 字节（大端 32bit）；不足部分按 Python 用 0xFF 补齐
        fun byteAt(idx: Int): Int = if (idx < rgb565.size) rgb565[idx].toInt() and 0xFF else 0xFF
        fun groupValue(i: Int): Long {
            val b = pos + i * 4
            return (byteAt(b).toLong() shl 24) or (byteAt(b + 1).toLong() shl 16) or
                    (byteAt(b + 2).toLong() shl 8) or byteAt(b + 3).toLong()
        }
        if (fullPage) {
            // 满页：压缩主色 + 差异像素（对齐 Python Screen_Date_Process 满页分支）
            var best = 0L
            var bestCount = -1
            val counts = HashMap<Long, Int>()
            for (i in 0 until 64) {
                val v = groupValue(i)
                val c = (counts[v] ?: 0) + 1
                counts[v] = c
                if (c > bestCount) { bestCount = c; best = v }
            }
            val colorRam = best
            out.write(lcdSetColorRam(colorRam.toInt()))
            for (i in 0 until 64) {
                if (groupValue(i) != colorRam) {
                    val b = pos + i * 4
                    out.write(flashDataWrite(i, byteAt(b), byteAt(b + 1), byteAt(b + 2), byteAt(b + 3)))
                }
            }
            out.write(lcdDisplay(8, 1, 0, 0))
        } else {
            // 末页不足 256B：64 组全量发送（含 0xFF 补齐），不压缩（对齐 Python 末页分支）
            for (i in 0 until 64) {
                val b = pos + i * 4
                out.write(flashDataWrite(i, byteAt(b), byteAt(b + 1), byteAt(b + 2), byteAt(b + 3)))
            }
            out.write(lcdDisplay(8, 0, remain and 0xFF, 0))
        }
    }
}