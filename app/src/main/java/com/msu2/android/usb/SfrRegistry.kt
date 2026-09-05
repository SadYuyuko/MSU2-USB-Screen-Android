package com.msu2.android.usb

/** MSN 数据字典解析 */
class SfrEntry(
    val name: ByteArray,
    val unit: ByteArray,
    val family: ByteArray,
    val data: ByteArray
) {
    /** family 高 5 位 */
    fun familyType(): Int = if (family.isNotEmpty()) (family[0].toInt() and 0xFF) / 32 else -1
    fun familyLen(): Int = if (family.isNotEmpty()) (family[0].toInt() and 0xFF) % 32 else 0
    fun nameString(): String = String(name, Charsets.US_ASCII)
    override fun toString(): String =
        "名称:${nameString()} 单位:${String(unit, Charsets.US_ASCII)} 类型:${familyType()} 长度:${familyLen()} 数据:${data.joinToString(" ") { "%02X".format(it) }}"
}

object SfrRegistry {

    private const val SFR_BASE = 0x0100

    /** 读取并解析数据字典 */
    suspend fun read(serial: Msu2Serial): List<SfrEntry> {
        val sfr = ByteArray(256)
        for (i in 0 until 256) sfr[i] = serial.readU8(SFR_BASE + i).toByte()
        return parse(sfr)
    }

    private fun parse(sfr: ByteArray): List<SfrEntry> {
        val entries = ArrayList<SfrEntry>()
        val dataUse = java.io.ByteArrayOutputStream()
        var dataType = 0
        var dataLen = 0
        var name = ByteArray(0)
        var unit = ByteArray(0)
        var family = ByteArray(0)

        for (i in sfr.indices) {
            val b = sfr[i].toInt() and 0xFF
            if (b != 0 && dataType < 3) {
                dataUse.write(b)
            } else if (dataType < 3) {
                if (dataUse.size() == 0) break
                when (dataType) {
                    0 -> { name = dataUse.toByteArray(); dataType = 1 }
                    1 -> { unit = dataUse.toByteArray(); dataType = 2 }
                    2 -> {
                        family = dataUse.toByteArray()
                        dataType = 3
                        // 仅类型 0 到 3 更新长度
                        when ((family[0].toInt() and 0xFF) / 32) {
                            0, 2 -> dataLen = 2
                            1 -> dataLen = 1
                            3 -> dataLen = (family[0].toInt() and 0xFF) % 32
                        }
                    }
                }
                dataUse.reset()
                continue
            }
            if (dataLen > 0 && dataType == 3) {
                dataUse.write(b)
                dataLen -= 1
            }
            if (dataLen == 0 && dataType == 3) {
                entries.add(SfrEntry(name, unit, family, dataUse.toByteArray()))
                dataType = 0
                dataUse.reset()
            }
        }
        return entries
    }

    /** 按名称读取数据 */
    suspend fun readData(serial: Msu2Serial, entries: List<SfrEntry>, name: ByteArray): Any? {
        for (e in entries) {
            if (e.name.contentEquals(name)) {
                return when (e.familyType()) {
                    0 -> {
                        val add = (e.data[0].toInt() and 0xFF) * 256 + (e.data[1].toInt() and 0xFF)
                        (0 until e.familyLen()).map { serial.readU8(add + it) }
                    }
                    1 -> serial.readU16(e.data[0].toInt() and 0xFF)
                    3, 4 -> e.data
                    else -> e.data
                }
            }
        }
        return null
    }

    /** 按名称写入数据 */
    suspend fun writeData(serial: Msu2Serial, entries: List<SfrEntry>, name: ByteArray, value: Int): Boolean {
        for (e in entries) {
            if (e.name.contentEquals(name)) {
                when (e.familyType()) {
                    0 -> {
                        val add = (e.data[0].toInt() and 0xFF) * 256 + (e.data[1].toInt() and 0xFF)
                        serial.ack(Msu2Protocol.writeU8(add, value))
                        return true
                    }
                    1 -> {
                        serial.ack(Msu2Protocol.writeU16(e.data[0].toInt() and 0xFF, value))
                        return true
                    }
                }
            }
        }
        return false
    }
}