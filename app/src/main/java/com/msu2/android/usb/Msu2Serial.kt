package com.msu2.android.usb

import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * MSU2 设备串口封装：打开 CDC-ACM 端口、握手、串行化读写。
 * 所有串口访问通过 [mutex] 串行化，保证多协程（状态机 / 镜像 / 烧录）不交叉。
 */
class Msu2Serial(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    companion object {
        private const val TAG = "Msu2Serial"
        private const val BAUD = 19200
        private const val BUF_SIZE = 4096
        private const val READ_TIMEOUT = 200
        private const val WRITE_TIMEOUT = 3000
    }

    private val mutex = Mutex()
    private var port: UsbSerialPort? = null
    private val readBuffer = java.io.ByteArrayOutputStream()

    val isConnected: Boolean get() = port != null

    /** 打开端口并初始化（需已获得 USB 权限）。 */
    suspend fun open() {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
            ?: throw IOException("未找到适用于该设备的串口驱动（需 CDC-ACM）")
        val p = driver.ports[0]
        val connection = usbManager.openDevice(device)
            ?: throw IOException("无法打开 USB 设备")
        p.open(connection)
        p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        try { p.setDTR(true) } catch (_: Exception) {}
        try { p.setRTS(true) } catch (_: Exception) {}
        port = p
        readBuffer.reset()
        Log.i(TAG, "serial opened, ${p.portNumber}")
    }

    /** 关闭端口。 */
    suspend fun close() {
        mutex.withLock {
            val p = port ?: return
            try { p.close() } catch (_: Exception) {}
            port = null
        }
    }

    // ---------------------------------------------------------------
    // 握手
    // ---------------------------------------------------------------

    /**
     * 与设备握手：等待广播 "00 MSNxx" -> 回复 "00 MSNCN" -> 等待设备确认。
     * @return 版本号（如 01 -> 1）
     */
    suspend fun handshake(): Int = mutex.withLock {
        // 重试：设备可能因打开串口/上电需要一点时间才开始广播
        var version = -1
        var lastBytes = ""
        for (attempt in 1..3) {
            version = waitBroadcast()
            if (version >= 0) break
            lastBytes = hex(readBuffer.toByteArray())
            readBuffer.reset()
            Log.w(TAG, "握手第 $attempt 次未收到广播, 收到: $lastBytes")
            delay(300)
        }
        if (version < 0) {
            throw IOException("未收到 MSN 设备广播（共收到 $lastBytes）")
        }
        writeRaw(byteArrayOf(0, 'M'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'N'.code.toByte()))
        val confirmed = waitPattern(byteArrayOf(0, 'M'.code.toByte(), 'S'.code.toByte(), 'N'.code.toByte(), 'C'.code.toByte(), 'N'.code.toByte()), 3000)
        if (!confirmed) throw IOException("握手确认失败")
        drainAll()
        version
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02X ".format(it.toInt() and 0xFF) }.trim()

    private suspend fun waitBroadcast(): Int {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            readChunk(READ_TIMEOUT)
            val buf = readBuffer.toByteArray()
            for (i in 0 until buf.size - 5) {
                if (buf[i] == 0.toByte() &&
                    buf[i + 1] == 'M'.code.toByte() &&
                    buf[i + 2] == 'S'.code.toByte() &&
                    buf[i + 3] == 'N'.code.toByte() &&
                    buf[i + 4] in '0'.code.toByte()..'9'.code.toByte() &&
                    buf[i + 5] in '0'.code.toByte()..'9'.code.toByte()
                ) {
                    val ver = (buf[i + 4] - '0'.code.toByte()) * 10 + (buf[i + 5] - '0'.code.toByte())
                    readBuffer.reset()
                    return ver.toInt()
                }
            }
        }
        return -1
    }

    private suspend fun waitPattern(pattern: ByteArray, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            readChunk(READ_TIMEOUT)
            val buf = readBuffer.toByteArray()
            for (i in 0 until buf.size - pattern.size + 1) {
                var match = true
                for (j in pattern.indices) if (buf[i + j] != pattern[j]) { match = false; break }
                if (match) { readBuffer.reset(); return true }
            }
        }
        return false
    }

    // ---------------------------------------------------------------
    // 指令读写（与 Python 语义对齐）
    // ---------------------------------------------------------------

    /** 读 8bit 寄存器，返回 recv[5]。 */
    suspend fun readU8(add: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readU8(add))
        val resp = readResponse()
        if (resp.size >= 6) resp[5].toInt() and 0xFF else 0
    }

    /** 读 16bit 寄存器，返回 recv[4]*256+recv[5]。 */
    suspend fun readU16(add: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readU16(add))
        val resp = readResponse()
        if (resp.size >= 6) ((resp[4].toInt() and 0xFF) * 256 + (resp[5].toInt() and 0xFF)) else 0
    }

    /** 读 ADC 通道，返回 recv[4]*256+recv[5]（按键为 CH9）。 */
    suspend fun readAdc(ch: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readAdc(ch))
        val resp = readResponse()
        if (resp.size >= 6) ((resp[4].toInt() and 0xFF) * 256 + (resp[5].toInt() and 0xFF)) else 0
    }

    /** 读 Flash 字节，返回 recv[5]。 */
    suspend fun readFlashByte(add: Int): Int = mutex.withLock {
        writeRaw(Msu2Protocol.readFlashByte(add))
        val resp = readResponse()
        if (resp.size >= 6) resp[5].toInt() and 0xFF else 0
    }

    /**
     * 发送需要确认的指令（写寄存器 / Flash / LCD），等待设备响应后清空缓冲。
     * @param waitMs 等待设备响应（≥1 字节）的超时。Flash 擦除按 4KB 扇区进行，
     *               数百页耗时可达数秒甚至十几秒，必须按擦除量给出足够等待时间，
     *               否则设备仍在擦除（不服务 USB）时发送写页突发数据会因接收
     *               缓冲填满导致 bulk 写入超时（rc=-1）。
     * @param requireResponse true 时若超时未收到任何响应则抛出 IOException
     */
    suspend fun ack(cmd: ByteArray, waitMs: Long = 1000, requireResponse: Boolean = false) {
        mutex.withLock {
            writeRaw(cmd)
            val got = readUntilData(waitMs)
            if (requireResponse && !got) throw IOException("设备无响应（等待 ${waitMs}ms）")
            drainAll()
        }
    }

    /** 发送无需确认的指令（批量数据）。 */
    suspend fun writeRawBytes(cmd: ByteArray) {
        mutex.withLock { writeRaw(cmd) }
    }

    /** 发送屏幕显存数据（LCD_ADD + 编码数据），随后清空设备返回的响应。 */
    suspend fun sendScreen(cmd: ByteArray) {
        mutex.withLock {
            writeRaw(cmd)
            drainAll()
        }
    }

    /** 丢弃当前所有缓冲数据。 */
    suspend fun drain() {
        mutex.withLock { drainAll() }
    }

    // ---------------------------------------------------------------
    // 底层读写
    // ---------------------------------------------------------------

    private fun writeRaw(data: ByteArray, timeoutMs: Int = WRITE_TIMEOUT) {
        val p = port ?: throw IOException("串口未打开")
        p.write(data, timeoutMs)
    }

    /** 等待并返回 6 字节响应（取缓冲中最后 6 字节，避免误读残留广播）。 */
    private suspend fun readResponse(): ByteArray {
        val deadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < deadline) {
            readChunk(READ_TIMEOUT)
            val buf = readBuffer.toByteArray()
            if (buf.size >= 6) {
                val resp = buf.copyOfRange(buf.size - 6, buf.size)
                readBuffer.reset()
                return resp
            }
        }
        readBuffer.reset()
        return ByteArray(0)
    }

    /** 等待至少收到 1 字节，返回是否收到。 */
    private suspend fun readUntilData(timeoutMs: Long = 1000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = readChunk(READ_TIMEOUT)
            if (n > 0) return true
        }
        return false
    }

    /** 读取并丢弃所有缓冲数据，直到设备静默（有硬性时间上限，防止设备持续广播时挂死）。 */
    private suspend fun drainAll() {
        val deadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < deadline) {
            val n = readChunk(30)
            if (n == 0) {
                // 连续静默 3 次（90ms）认为已排空；同时受 300ms 硬上限保护
                if (System.currentTimeMillis() + 90 >= deadline) break
            }
        }
        readBuffer.reset()
    }

    private suspend fun readChunk(timeoutMs: Int): Int {
        val p = port ?: return 0
        val buf = ByteArray(BUF_SIZE)
        val n = try { p.read(buf, timeoutMs) } catch (_: Exception) { 0 }
        if (n > 0) readBuffer.write(buf, 0, n)
        return n
    }
}