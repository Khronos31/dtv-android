package dev.khronos31.mirakc

import java.io.ByteArrayOutputStream

/**
 * Builds a player-usable MPEG-TS from one ISDB-T service.
 *
 * 12-seg video/audio is MULTI2-scrambled (B-CAS). The tuner pipeline
 * descrambles it, but that can fail (no card, no reader, unpaired card), and
 * then a 12-seg-only stream is black video + captions. 1-seg H.264 on the same
 * transponder is in the clear. When the requested service's video is still
 * observed scrambled, the filter substitutes that 1-seg program's elementary
 * streams while keeping the requested program number in PAT.
 */
internal class TsServiceFilter(private val programNumber: Int) {
    private val remainder = ByteArrayOutputStream()
    private val output = ByteArrayOutputStream(32 * 1024)
    private val pending = ByteArrayOutputStream()
    private val patSection = PsiAssembler()
    private val pmtAssemblers = HashMap<Int, PsiAssembler>()
    private val programs = HashMap<Int, Int>()
    private val parsedPmts = HashMap<Int, ParsedPmt>()
    private var pmtPid = -1
    private var tsId = 0
    private var patVersion = 0
    private val keepPids = HashSet<Int>().apply { add(0) }
    private val mediaPids = HashSet<Int>()
    private val primedPids = HashSet<Int>()
    private var patContinuity = 0
    private var pmtContinuity = 0
    private var decided = false
    private var passthrough = false
    private val scrambledPackets = HashMap<Int, Int>()
    private val clearPackets = HashMap<Int, Int>()

    fun push(data: ByteArray, offset: Int, length: Int): ByteArray {
        if (length <= 0) return EMPTY
        output.reset()
        remainder.write(data, offset, length)
        val buf = remainder.toByteArray()
        remainder.reset()
        var i = 0
        while (i < buf.size) {
            if (buf[i] != SYNC) {
                val next = indexOfSync(buf, i + 1)
                if (next < 0) break
                i = next
                continue
            }
            if (i + PACKET > buf.size) {
                remainder.write(buf, i, buf.size - i)
                break
            }
            if (passthrough) {
                output.write(buf, i, PACKET)
            } else {
                filterPacket(buf, i)
            }
            i += PACKET
        }
        return if (output.size() == 0) EMPTY else output.toByteArray()
    }

    private fun filterPacket(buf: ByteArray, offset: Int) {
        val b1 = buf[offset + 1].toInt() and 0xFF
        if (b1 and 0x80 != 0) return
        val pusi = b1 and 0x40 != 0
        val pid = ((b1 and 0x1F) shl 8) or (buf[offset + 2].toInt() and 0xFF)
        if (!decided) {
            val tsc = (buf[offset + 3].toInt() shr 6) and 0x3
            if (tsc != 0) scrambledPackets[pid] = (scrambledPackets[pid] ?: 0) + 1
            else clearPackets[pid] = (clearPackets[pid] ?: 0) + 1
            if (pid == 0) handlePat(buf, offset)
            if (pid in programs.values) handleAnyPmt(pid, buf, offset)
            if (pending.size() < MAX_PENDING) pending.write(buf, offset, PACKET)
            maybeDecide()
            return
        }
        when {
            pid == 0 -> {
                output.write(buildPatPacket(tsId, patVersion, programNumber, pmtPid, patContinuity))
                patContinuity = (patContinuity + 1) and 0x0F
            }
            pid == pmtPid -> { }
            keepPids.contains(pid) -> {
                if (pid in mediaPids && pid !in primedPids) {
                    if (!pusi) return
                    primedPids.add(pid)
                }
                output.write(buf, offset, PACKET)
            }
        }
    }

    private fun handlePat(buf: ByteArray, offset: Int) {
        val section = patSection.offer(buf, offset) ?: return
        if (section.isEmpty() || section[0] != 0.toByte()) return
        val parsed = parsePatPrograms(section) ?: return
        tsId = ((section[3].toInt() and 0xFF) shl 8) or (section[4].toInt() and 0xFF)
        patVersion = (section[5].toInt() and 0x3E) shr 1
        programs.clear()
        for ((program, pid) in parsed) {
            if (program == 0) continue
            programs[program] = pid
            pmtAssemblers.getOrPut(pid) { PsiAssembler() }
        }
        pmtPid = programs[programNumber] ?: -1
        if (pmtPid < 0 && programs.isNotEmpty()) passthrough = true
    }

    private fun handleAnyPmt(pid: Int, buf: ByteArray, offset: Int) {
        val assembler = pmtAssemblers[pid] ?: return
        val section = assembler.offer(buf, offset) ?: return
        val pmt = parsePmt(section) ?: return
        val program = programs.entries.firstOrNull { it.value == pid }?.key ?: return
        parsedPmts[program] = pmt
    }

    private fun maybeDecide() {
        if (decided || pmtPid < 0) return
        val requested = parsedPmts[programNumber] ?: return
        val oneSeg = parsedPmts.values.firstOrNull { it.hasClearAvc }
        val videoScrambled = requested.playable.any { es ->
            es.streamType in VIDEO_TYPES && (scrambledPackets[es.pid] ?: 0) >= 8
        }
        val videoObserved = requested.playable.any { es ->
            es.streamType in VIDEO_TYPES &&
                (scrambledPackets[es.pid] ?: 0) + (clearPackets[es.pid] ?: 0) >= 8
        }
        if (!videoObserved && pending.size() < DECIDE_AFTER) return
        if (videoScrambled && oneSeg == null && pending.size() < MAX_PENDING - PACKET) return
        val chosen = if (videoScrambled && oneSeg != null) oneSeg else requested
        applyChosen(chosen)
        decided = true
        val buffered = pending.toByteArray()
        pending.reset()
        var i = 0
        while (i + PACKET <= buffered.size) {
            emitDecided(buffered, i)
            i += PACKET
        }
    }

    private fun applyChosen(pmt: ParsedPmt) {
        keepPids.clear()
        mediaPids.clear()
        keepPids.add(0)
        keepPids.add(pmt.pcrPid)
        for (es in pmt.playable) {
            keepPids.add(es.pid)
            mediaPids.add(es.pid)
        }
        if (pmtPid < 0) pmtPid = programs[programNumber] ?: 0x1F0
        output.write(buildPatPacket(tsId, patVersion, programNumber, pmtPid, patContinuity))
        patContinuity = (patContinuity + 1) and 0x0F
        output.write(packPsiSection(rebuildPmt(pmt, programNumber, pmt.pcrPid), pmtPid, pmtContinuity))
        pmtContinuity = (pmtContinuity + 1) and 0x0F
    }

    private fun emitDecided(buf: ByteArray, offset: Int) {
        val b1 = buf[offset + 1].toInt() and 0xFF
        if (b1 and 0x80 != 0) return
        val pusi = b1 and 0x40 != 0
        val pid = ((b1 and 0x1F) shl 8) or (buf[offset + 2].toInt() and 0xFF)
        if (pid == 0 || pid == pmtPid) return
        if (!keepPids.contains(pid)) return
        if (pid in mediaPids && pid !in primedPids) {
            if (!pusi) return
            primedPids.add(pid)
        }
        output.write(buf, offset, PACKET)
    }

    private fun parsePatPrograms(section: ByteArray): List<Pair<Int, Int>>? {
        if (section.size < 12) return null
        val sectionLen = ((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF)
        val end = (3 + sectionLen - 4).coerceAtMost(section.size)
        val result = ArrayList<Pair<Int, Int>>()
        var i = 8
        while (i + 3 < end) {
            val program = ((section[i].toInt() and 0xFF) shl 8) or (section[i + 1].toInt() and 0xFF)
            val pid = ((section[i + 2].toInt() and 0x1F) shl 8) or (section[i + 3].toInt() and 0xFF)
            result.add(program to pid)
            i += 4
        }
        return result
    }

    private fun parsePmt(section: ByteArray): ParsedPmt? {
        if (section.size < 12 || section[0] != 0x02.toByte()) return null
        val sectionLen = ((section[1].toInt() and 0x0F) shl 8) or (section[2].toInt() and 0xFF)
        val infoLen = ((section[10].toInt() and 0x0F) shl 8) or (section[11].toInt() and 0xFF)
        val headerEnd = 12 + infoLen
        val end = (3 + sectionLen - 4).coerceAtMost(section.size)
        if (headerEnd > end) return null
        val pcr = ((section[8].toInt() and 0x1F) shl 8) or (section[9].toInt() and 0xFF)
        var hasCa = hasCaDescriptor(section, 12, infoLen)
        var hasClearAvc = false
        val playable = ArrayList<EsStream>()
        var i = headerEnd
        while (i + 4 < end) {
            val streamType = section[i].toInt() and 0xFF
            val elemPid = ((section[i + 1].toInt() and 0x1F) shl 8) or (section[i + 2].toInt() and 0xFF)
            val esLen = ((section[i + 3].toInt() and 0x0F) shl 8) or (section[i + 4].toInt() and 0xFF)
            val next = i + 5 + esLen
            if (next > end) break
            if (hasCaDescriptor(section, i + 5, esLen)) hasCa = true
            if (streamType in PLAYABLE_STREAM_TYPES) {
                val copy = section.copyOfRange(i, next)
                playable.add(EsStream(streamType, elemPid, copy))
                if (streamType == 0x1B) hasClearAvc = true
            }
            i = next
        }
        return ParsedPmt(pcr, hasCa, hasClearAvc && !hasCa, playable)
    }

    private fun rebuildPmt(pmt: ParsedPmt, program: Int, pcrPid: Int): ByteArray {
        val esSize = pmt.playable.sumOf { it.bytes.size }
        val header = ByteArray(9)
        header[0] = (program shr 8).toByte()
        header[1] = program.toByte()
        header[2] = 0xC1.toByte()
        header[3] = 0
        header[4] = 0
        header[5] = (0xE0 or ((pcrPid shr 8) and 0x1F)).toByte()
        header[6] = pcrPid.toByte()
        header[7] = 0xF0.toByte()
        header[8] = 0
        val newSectionLen = header.size + esSize + 4
        val out = ByteArray(3 + newSectionLen)
        out[0] = 0x02
        out[1] = (0xB0 or ((newSectionLen shr 8) and 0x0F)).toByte()
        out[2] = newSectionLen.toByte()
        System.arraycopy(header, 0, out, 3, header.size)
        var pos = 3 + header.size
        for (es in pmt.playable) {
            System.arraycopy(es.bytes, 0, out, pos, es.bytes.size)
            pos += es.bytes.size
        }
        val crc = mpegCrc32(out, 0, out.size - 4)
        out[out.size - 4] = (crc shr 24).toByte()
        out[out.size - 3] = (crc shr 16).toByte()
        out[out.size - 2] = (crc shr 8).toByte()
        out[out.size - 1] = crc.toByte()
        return out
    }

    private fun buildPatPacket(
        tsId: Int,
        version: Int,
        program: Int,
        pmtPid: Int,
        continuity: Int
    ): ByteArray {
        val section = ByteArray(16)
        section[0] = 0x00
        section[1] = 0xB0.toByte()
        section[2] = 13
        section[3] = (tsId shr 8).toByte()
        section[4] = tsId.toByte()
        section[5] = (0xC1 or ((version and 0x1F) shl 1)).toByte()
        section[6] = 0
        section[7] = 0
        section[8] = (program shr 8).toByte()
        section[9] = program.toByte()
        section[10] = (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte()
        section[11] = pmtPid.toByte()
        val crc = mpegCrc32(section, 0, 12)
        section[12] = (crc shr 24).toByte()
        section[13] = (crc shr 16).toByte()
        section[14] = (crc shr 8).toByte()
        section[15] = crc.toByte()
        return packPsiSection(section, 0, continuity)
    }

    private fun packPsiSection(section: ByteArray, pid: Int, continuity: Int): ByteArray {
        val packet = ByteArray(PACKET) { 0xFF.toByte() }
        packet[0] = SYNC
        packet[1] = (0x40 or ((pid shr 8) and 0x1F)).toByte()
        packet[2] = pid.toByte()
        packet[3] = (0x10 or (continuity and 0x0F)).toByte()
        packet[4] = 0x00
        System.arraycopy(section, 0, packet, 5, minOf(section.size, 183))
        return packet
    }

    private class ParsedPmt(
        val pcrPid: Int,
        val hasCa: Boolean,
        val hasClearAvc: Boolean,
        val playable: List<EsStream>
    )

    private class EsStream(val streamType: Int, val pid: Int, val bytes: ByteArray)

    companion object {
        private const val PACKET = 188
        private const val SYNC: Byte = 0x47
        private const val MAX_PENDING = 188 * 8000
        private const val DECIDE_AFTER = 188 * 1500
        private val EMPTY = ByteArray(0)
        private val PLAYABLE_STREAM_TYPES = setOf(
            0x01, 0x02, 0x03, 0x04, 0x0F, 0x11, 0x1B, 0x24, 0x81, 0x06
        )
        private val VIDEO_TYPES = setOf(0x01, 0x02, 0x1B, 0x24)

        private fun indexOfSync(buf: ByteArray, from: Int): Int {
            for (i in from until buf.size) if (buf[i] == SYNC) return i
            return -1
        }

        private fun hasCaDescriptor(data: ByteArray, from: Int, length: Int): Boolean {
            var i = from
            val end = (from + length).coerceAtMost(data.size)
            while (i + 1 < end) {
                val tag = data[i].toInt() and 0xFF
                val len = data[i + 1].toInt() and 0xFF
                if (tag == 0x09) return true
                i += 2 + len
            }
            return false
        }

        internal fun mpegCrc32(data: ByteArray, offset: Int, length: Int): Int {
            var crc = -1
            for (i in offset until offset + length) {
                crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
                repeat(8) {
                    crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
                }
            }
            return crc
        }
    }
}

private class PsiAssembler {
    private val buffer = ByteArrayOutputStream()
    private var needed = -1

    fun offer(packet: ByteArray, offset: Int): ByteArray? {
        val b1 = packet[offset + 1].toInt() and 0xFF
        val pusi = b1 and 0x40 != 0
        val afc = (packet[offset + 3].toInt() shr 4) and 0x3
        var payload = offset + 4
        if (afc == 2 || afc == 3) {
            val afl = packet[offset + 4].toInt() and 0xFF
            payload = offset + 5 + afl
        }
        val end = offset + 188
        if (payload >= end) return null
        if (pusi) {
            val pointer = packet[payload].toInt() and 0xFF
            payload++
            payload = (payload + pointer).coerceAtMost(end)
            buffer.reset()
            needed = -1
            if (payload >= end) return null
        } else if (buffer.size() == 0) {
            return null
        }
        if (payload < end) buffer.write(packet, payload, end - payload)
        val data = buffer.toByteArray()
        if (needed < 0 && data.size >= 3) {
            needed = 3 + (((data[1].toInt() and 0x0F) shl 8) or (data[2].toInt() and 0xFF))
        }
        if (needed > 0 && data.size >= needed) {
            val complete = needed
            buffer.reset()
            needed = -1
            return data.copyOf(complete.coerceAtMost(data.size))
        }
        return null
    }
}
