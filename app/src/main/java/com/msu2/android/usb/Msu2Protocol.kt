package com.msu2.android.usb

/**
 * MSU2 副屏串口协议（逐字节对齐 Python 版 MSU2_DemoV1.0.py）。
 *
 * 所有指令均为 6 字节数据包：[CMD][SUB][D0][D1][D2][D3]。
 * LCD 常用颜色为 RGB565（如 0xF800 红、0x07E0 绿、0x001F 蓝、0xFFFF 白、0x0000 黑）。
 */
object Msu2Protocol {

    // 颜色（RGB565）
    const val RED = 0xF800
    const val GREEN = 0x07E0
    const val BLUE = 0x001F
    const val WHITE = 0xFFFF
    const val BLACK = 0x0000
    const val YELLOW = 0xFFE0

    /** Flash 页布局（页 = 256B，出厂已烧录） */
    const val PAGE_IMG1 = 0        // 1.bin    240x240 彩色
    const val PAGE_IMG2 = 450
    const val PAGE_IMG3 = 900
    const val PAGE_IMG4 = 1350
    const val PAGE_IMG5 = 1800
    const val PAGE_IMG6 = 2250
    const val PAGE_C3 = 2700
    const val PAGE_C6 = 3150
    const val PAGE_DEMO1 = 3600    // 240x240 单色背景
    const val PAGE_N48X66 = 3629   // 48x66 数码管数字
    const val PAGE_ASC64 = 3651    // 32x64 ASCII 字库
    const val PAGE_LOGO = 3779
    const val PAGE_J1 = 3791

    // ---------------------------------------------------------------
    // SFR（寄存器）读写   CMD=0x00, SUB=b'0'(0x30)
    // ---------------------------------------------------------------

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

    // ---------------------------------------------------------------
    // ADC 读取   CMD=0x08（用于读取按键 CH9）
    // ---------------------------------------------------------------

    /** 08 CH 00 00 00 00 */
    fun readAdc(ch: Int): ByteArray =
        byteArrayOf(0x08, (ch and 0xFF).toByte(), 0x00, 0x00, 0x00, 0x00)

    // ---------------------------------------------------------------
    // Flash 操作   CMD=0x03
    // ---------------------------------------------------------------

    /** 读 Flash 字节。 03 00 AH AL 00 00 */
    fun readFlashByte(add: Int): ByteArray =
        byteArrayOf(
            0x03, 0x00,
            (add / (256 * 256) and 0xFF).toByte(),
            ((add % 65536) / 256).toByte(),
            ((add % 65536) % 256).toByte(),
            0x00
        )

    /** 擦除指定区域（add 与 size 均按 16bit 取低字节）。 03 02 AH AL SH SL */
    fun eraseFlashPage(add: Int, size: Int): ByteArray =
        byteArrayOf(
            0x03, 0x02,
            ((add % 65536) / 256).toByte(),
            ((add % 65536) % 256).toByte(),
            ((size % 65536) / 256).toByte(),
            ((size % 65536) % 256).toByte()
        )

    /**
     * 写 Flash 页。erase=true 对应 Python Write_Flash_Page（03 01，自动擦除）；
     * erase=false 对应 Write_Flash_Page_fast（03 03，区域需已擦除）。
     * 03 01/03 BH AH AL PN
     */
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

    // ---------------------------------------------------------------
    // LCD 指令   CMD=0x02
    // ---------------------------------------------------------------

    /** 设置起始坐标。 02 00 XH XL YH YL */
    fun lcdSetXY(x: Int, y: Int): ByteArray =
        byteArrayOf(0x02, 0x00, (x ushr 8).toByte(), (x and 0xFF).toByte(), (y ushr 8).toByte(), (y and 0xFF).toByte())

    /** 设置显示尺寸。 02 01 XH XL YH YL */
    fun lcdSetSize(w: Int, h: Int): ByteArray =
        byteArrayOf(0x02, 0x01, (w ushr 8).toByte(), (w and 0xFF).toByte(), (h ushr 8).toByte(), (h and 0xFF).toByte())

    /** 设置颜色（FC 前景，BC 背景/字库页）。 02 02 FCH FCL BCH BCL */
    fun lcdSetColor(fc: Int, bc: Int): ByteArray =
        byteArrayOf(0x02, 0x02, (fc ushr 8).toByte(), (fc and 0xFF).toByte(), (bc ushr 8).toByte(), (bc and 0xFF).toByte())

    /**
     * LCD 显示指令。 02 03 op d0 d1 d2 d3
     * op=0 彩色图片(page)  op=1 单色图片wb(page)  op=2 ASCII(ch,font page)
     * op=4 单色混合(page)  op=5 ASCII混合(ch,font page)  op=7 载入显存地址  op=8 显示RAM(size)
     * op=9 提交刷新
     */
    fun lcdDisplay(op: Int, d0: Int, d1: Int, d2: Int, d3: Int): ByteArray =
        byteArrayOf(
            0x02, 0x03, (op and 0xFF).toByte(),
            (d0 and 0xFF).toByte(), (d1 and 0xFF).toByte(),
            (d2 and 0xFF).toByte(), (d3 and 0xFF).toByte()
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

    // ---------------------------------------------------------------
    // 复合指令
    // ---------------------------------------------------------------

    /** 载入显存写入地址（Python LCD_ADD）。 */
    fun lcdLoadAddr(x: Int, y: Int, w: Int, h: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdDisplay(7, 0, 0, 0, 0)

    /**
     * 显示 Flash 中的彩色图片（Python LCD_Photo）。
     * @param pageAdd 图片所在 Flash 页
     */
    fun lcdPhoto(x: Int, y: Int, w: Int, h: Int, pageAdd: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdDisplay(0, pageAdd / 256, pageAdd % 256, 0, 0)

    /** 显示 Flash 中的单色图片（Python LCD_Photo_wb）。 */
    fun lcdPhotoWb(x: Int, y: Int, w: Int, h: Int, pageAdd: Int, fc: Int, bc: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetSize(w, h) + lcdSetColor(fc, bc) + lcdDisplay(1, pageAdd / 256, pageAdd % 256, 0, 0)

    /**
     * 显示 32x64 ASCII 字符（Python LCD_ASCII_32X64_MIX，bg=字库背景页）。
     * 注意：该指令为 02 03 05 CH NH NL，仅 6 字节、无尾部 0。
     */
    fun lcdAscii32x64Mix(x: Int, y: Int, ch: Char, fc: Int, bgPage: Int, numPage: Int): ByteArray =
        lcdSetXY(x, y) + lcdSetColor(fc, bgPage) +
            byteArrayOf(0x02, 0x03, 0x05, (ch.code and 0xFF).toByte(), (numPage / 256).toByte(), (numPage % 256).toByte())

    // ---------------------------------------------------------------
    // RGB565 / 屏幕数据编码
    // ---------------------------------------------------------------

    /**
     * 将像素转换为 16bit RGB565 小端字节流（逐字节对齐 Python Screen_Date_get）。
     * 高5位R + 中6位G + 低5位B。
     */
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

    /**
     * 将 RGB565 字节流编码为屏幕显示指令（逐字节对齐 Python Screen_Date_Process）。
     * 含主色压缩（02 04）+ 差异像素（04）+ 分页提交（02 03 08）。
     */
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
            out.write(lcdDisplay(8, 1, 0, 0, 0))
        } else {
            // 末页不足 256B：64 组全量发送（含 0xFF 补齐），不压缩（对齐 Python 末页分支）
            for (i in 0 until 64) {
                val b = pos + i * 4
                out.write(flashDataWrite(i, byteAt(b), byteAt(b + 1), byteAt(b + 2), byteAt(b + 3)))
            }
            out.write(lcdDisplay(8, 0, remain and 0xFF, 0, 0))
        }
    }
}