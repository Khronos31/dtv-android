package dev.khronos31.mirakc

import java.io.ByteArrayOutputStream

internal class TsSiParser(
    private val channel: ChannelDefinition,
    private val store: EpgStore
) {
    private val remainder = ByteArrayOutputStream()
    private val sections = HashMap<Int, ByteArrayOutputStream>()
    private val remoteKeys = HashMap<Int, Int>()
    private var packets = 0
    @Volatile var servicesSeen = 0
        private set
    @Volatile var programsSeen = 0
        private set

    fun feed(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        remainder.write(data, offset, length)
        val buf = remainder.toByteArray()
        remainder.reset()
        var i = 0
        while (i < buf.size) {
            if (buf[i] != 0x47.toByte()) {
                val next = buf.indexOfFirstFrom(i + 1) { it == 0x47.toByte() }
                if (next < 0) break
                i = next
                continue
            }
            if (i + 188 > buf.size) {
                remainder.write(buf, i, buf.size - i)
                break
            }
            consumePacket(buf, i)
            i += 188
        }
    }

    private fun consumePacket(buf: ByteArray, offset: Int) {
        packets++
        val b1 = buf[offset + 1].toInt() and 0xFF
        if (b1 and 0x80 != 0) return
        val pusi = b1 and 0x40 != 0
        val pid = ((b1 and 0x1F) shl 8) or (buf[offset + 2].toInt() and 0xFF)
        if (pid != PID_NIT && pid != PID_SDT && pid != PID_EIT) return
        val afc = (buf[offset + 3].toInt() shr 4) and 0x3
        var payload = offset + 4
        if (afc == 2 || afc == 3) {
            val afl = buf[offset + 4].toInt() and 0xFF
            payload = offset + 5 + afl
        }
        if (payload >= offset + 188) return
        val end = offset + 188
        if (pusi) {
            if (payload >= end) return
            val pointer = buf[payload].toInt() and 0xFF
            payload++
            val prevEnd = (payload + pointer).coerceAtMost(end)
            if (pointer > 0) appendSection(pid, buf, payload, prevEnd, finish = true)
            payload = prevEnd
            startSection(pid, buf, payload, end)
        } else {
            appendSection(pid, buf, payload, end, finish = false)
        }
    }

    private fun startSection(pid: Int, buf: ByteArray, from: Int, to: Int) {
        val stream = ByteArrayOutputStream()
        sections[pid] = stream
        if (from < to) stream.write(buf, from, to - from)
        flushIfComplete(pid)
    }

    private fun appendSection(pid: Int, buf: ByteArray, from: Int, to: Int, finish: Boolean) {
        val stream = sections[pid] ?: return
        if (from < to) stream.write(buf, from, to - from)
        if (finish) {
            parseSection(pid, stream.toByteArray())
            sections.remove(pid)
        } else {
            flushIfComplete(pid)
        }
    }

    private fun flushIfComplete(pid: Int) {
        val stream = sections[pid] ?: return
        val bytes = stream.toByteArray()
        if (bytes.size < 3) return
        val length = ((bytes[1].toInt() and 0x0F) shl 8) or (bytes[2].toInt() and 0xFF)
        val total = 3 + length
        if (bytes.size < total) return
        parseSection(pid, bytes.copyOf(total))
        sections.remove(pid)
        if (bytes.size > total) {
            val rest = ByteArrayOutputStream()
            rest.write(bytes, total, bytes.size - total)
            sections[pid] = rest
            flushIfComplete(pid)
        }
    }

    private fun parseSection(pid: Int, section: ByteArray) {
        if (section.size < 8) return
        val tableId = section[0].toInt() and 0xFF
        when {
            pid == PID_NIT && tableId == 0x40 -> parseNit(section)
            pid == PID_SDT && (tableId == 0x42 || tableId == 0x46) -> parseSdt(section)
            pid == PID_EIT && tableId in 0x4E..0x6F -> parseEit(section)
        }
    }

    private fun parseNit(section: ByteArray) {
        var i = 10
        val networkDescriptorsLength = ((section[i].toInt() and 0x0F) shl 8) or (section[i + 1].toInt() and 0xFF)
        i += 2 + networkDescriptorsLength
        if (i + 2 > section.size) return
        val tsLoopLength = ((section[i].toInt() and 0x0F) shl 8) or (section[i + 1].toInt() and 0xFF)
        i += 2
        val tsEnd = (i + tsLoopLength).coerceAtMost(section.size - 4)
        while (i + 6 <= tsEnd) {
            i += 4
            val descLen = ((section[i].toInt() and 0x0F) shl 8) or (section[i + 1].toInt() and 0xFF)
            i += 2
            val descEnd = (i + descLen).coerceAtMost(tsEnd)
            var d = i
            while (d + 2 <= descEnd) {
                val tag = section[d].toInt() and 0xFF
                val len = section[d + 1].toInt() and 0xFF
                if (tag == 0xCD && len >= 1 && d + 2 < descEnd) {
                    remoteKeys[0] = section[d + 2].toInt() and 0xFF
                }
                d += 2 + len
            }
            i = descEnd
        }
    }

    private fun parseSdt(section: ByteArray) {
        if (section.size < 12) return
        val originalNetworkId = ((section[8].toInt() and 0xFF) shl 8) or (section[9].toInt() and 0xFF)
        var i = 11
        val end = section.size - 4
        while (i + 5 <= end) {
            val serviceId = ((section[i].toInt() and 0xFF) shl 8) or (section[i + 1].toInt() and 0xFF)
            i += 3
            val descLen = ((section[i].toInt() and 0x0F) shl 8) or (section[i + 1].toInt() and 0xFF)
            i += 2
            val descEnd = (i + descLen).coerceAtMost(end)
            var name = ""
            var type = 1
            var d = i
            while (d + 2 <= descEnd) {
                val tag = section[d].toInt() and 0xFF
                val len = section[d + 1].toInt() and 0xFF
                if (tag == 0x48 && len >= 3 && d + 2 + len <= section.size) {
                    type = section[d + 2].toInt() and 0xFF
                    val providerLen = section[d + 3].toInt() and 0xFF
                    val namePos = d + 4 + providerLen
                    if (namePos < d + 2 + len) {
                        val nameLen = section[namePos].toInt() and 0xFF
                        name = AribString.decode(section, namePos + 1, nameLen.coerceAtMost(d + 2 + len - namePos - 1))
                    }
                }
                d += 2 + len
            }
            i = descEnd
            if (type == 0xA4) continue
            if (name.isBlank()) continue
            store.upsertService(
                EpgService(
                    networkId = originalNetworkId,
                    serviceId = serviceId,
                    name = name,
                    type = type,
                    channel = channel.channel.removePrefix("T"),
                    channelType = channel.type,
                    remoteControlKeyId = remoteKeys[0]
                )
            )
            servicesSeen++
        }
    }

    private fun parseEit(section: ByteArray) {
        if (section.size < 18) return
        val serviceId = ((section[3].toInt() and 0xFF) shl 8) or (section[4].toInt() and 0xFF)
        val transportNetworkId = ((section[10].toInt() and 0xFF) shl 8) or (section[11].toInt() and 0xFF)
        var i = 14
        val end = section.size - 4
        while (i + 12 <= end) {
            val eventId = ((section[i].toInt() and 0xFF) shl 8) or (section[i + 1].toInt() and 0xFF)
            val startAt = mjdToUnixMs(section, i + 2)
            val duration = bcdDurationMs(section, i + 7)
            val freeCa = (section[i + 10].toInt() and 0x10) != 0
            val descLen = ((section[i + 10].toInt() and 0x0F) shl 8) or (section[i + 11].toInt() and 0xFF)
            if (startAt == null) {
                i += 12 + descLen
                continue
            }
            i += 12
            val descEnd = (i + descLen).coerceAtMost(end)
            var name: String? = null
            var description: String? = null
            val genres = mutableListOf<IntArray>()
            var d = i
            while (d + 2 <= descEnd) {
                val tag = section[d].toInt() and 0xFF
                val len = section[d + 1].toInt() and 0xFF
                val payload = d + 2
                if (payload + len > section.size) break
                when (tag) {
                    0x4D -> if (len >= 4) {
                        val nameLen = section[payload + 3].toInt() and 0xFF
                        name = AribString.decode(section, payload + 4, nameLen)
                        val textPos = payload + 4 + nameLen
                        if (textPos < payload + len) {
                            val textLen = section[textPos].toInt() and 0xFF
                            description = AribString.decode(section, textPos + 1, textLen)
                        }
                    }
                    0x4E -> if (len >= 7) {
                        val itemLen = section[payload + 5].toInt() and 0xFF
                        var p = payload + 6
                        val itemEnd = (p + itemLen).coerceAtMost(payload + len)
                        val extra = StringBuilder()
                        while (p + 1 <= itemEnd) {
                            val descLenItem = section[p].toInt() and 0xFF
                            p += 1 + descLenItem
                            if (p >= itemEnd) break
                            val textLen = section[p].toInt() and 0xFF
                            extra.append(AribString.decode(section, p + 1, textLen))
                            p += 1 + textLen
                        }
                        if (extra.isNotEmpty()) {
                            description = listOfNotNull(description, extra.toString()).joinToString("\n")
                        }
                    }
                    0x54 -> {
                        var g = payload
                        val gEnd = payload + len
                        while (g + 2 <= gEnd) {
                            val nibble = section[g].toInt() and 0xFF
                            val user = section[g + 1].toInt() and 0xFF
                            genres += intArrayOf(nibble shr 4, nibble and 0x0F, user shr 4, user and 0x0F)
                            g += 2
                        }
                    }
                }
                d += 2 + len
            }
            i = descEnd
            if (duration <= 0L) continue
            store.upsertProgram(
                EpgProgram(
                    networkId = transportNetworkId,
                    serviceId = serviceId,
                    eventId = eventId,
                    startAt = startAt,
                    duration = duration,
                    isFree = !freeCa,
                    name = name,
                    description = description,
                    genres = genres
                )
            )
            programsSeen++
            val tableId = section[0].toInt() and 0xFF
            if (tableId == 0x4E) {
                store.markOnAir(transportNetworkId, serviceId)
            }
        }
    }

    companion object {
        private const val PID_NIT = 0x0010
        private const val PID_SDT = 0x0011
        private const val PID_EIT = 0x0012

        private fun mjdToUnixMs(buf: ByteArray, offset: Int): Long? {
            if (offset + 5 > buf.size) return null
            val mjd = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
            if (mjd == 0xFFFF) return null
            val hour = bcd(buf[offset + 2])
            val minute = bcd(buf[offset + 3])
            val second = bcd(buf[offset + 4])
            if (hour > 23 || minute > 59 || second > 59) return null
            val unixSeconds = (mjd - 40587L) * 86400L + hour * 3600L + minute * 60L + second
            return (unixSeconds - 9 * 3600L) * 1000L
        }

        private fun bcdDurationMs(buf: ByteArray, offset: Int): Long {
            if (offset + 3 > buf.size) return 0
            val hour = bcd(buf[offset])
            val minute = bcd(buf[offset + 1])
            val second = bcd(buf[offset + 2])
            return ((hour * 3600L) + (minute * 60L) + second) * 1000L
        }

        private fun bcd(value: Byte): Int {
            val v = value.toInt() and 0xFF
            return ((v shr 4) * 10) + (v and 0x0F)
        }
    }
}

private fun ByteArray.indexOfFirstFrom(start: Int, predicate: (Byte) -> Boolean): Int {
    for (i in start until size) if (predicate(this[i])) return i
    return -1
}
