package dev.khronos31.mirakc

import java.nio.charset.Charset

/**
 * ARIB STD-B24 8-unit character decoder covering the G-sets used in
 * terrestrial SDT/EIT (kanji, alphanumeric, hiragana, katakana).
 */
internal object AribString {
    private val shiftJis: Charset = charset("Shift_JIS")

    fun decode(bytes: ByteArray, offset: Int, length: Int): String {
        if (length <= 0 || offset < 0 || offset + length > bytes.size) return ""
        var gl = 0
        var gr = 2
        val g = intArrayOf(KANJI, ALNUM, HIRA, KATA)
        val out = StringBuilder(length)
        var i = offset
        val end = offset + length
        fun invoke(set: Int, first: Int): Int {
            var used = 1
            val second = if (set == KANJI) {
                if (i + 1 >= end) return 1
                used = 2
                bytes[i + 1].toInt() and 0xFF
            } else {
                0
            }
            val ch = when (set) {
                KANJI -> jisKanji(first and 0x7F, second and 0x7F)
                ALNUM -> alnum(first and 0x7F)
                HIRA -> kana(first and 0x7F, hiragana = true)
                KATA -> kana(first and 0x7F, hiragana = false)
                else -> ""
            }
            out.append(ch)
            return used
        }
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b == 0x0F -> { gl = 0; i++ }
                b == 0x0E -> { gl = 1; i++ }
                b == 0x19 -> {
                    i++
                    if (i < end) {
                        val extra = invoke(g[2], bytes[i].toInt() and 0xFF)
                        i += extra
                    }
                }
                b == 0x1D -> {
                    i++
                    if (i < end) {
                        val extra = invoke(g[3], bytes[i].toInt() and 0xFF)
                        i += extra
                    }
                }
                b == 0x1B -> {
                    i++
                    if (i >= end) break
                    when (bytes[i].toInt() and 0xFF) {
                        0x24 -> {
                            i++
                            if (i >= end) break
                            val n = bytes[i].toInt() and 0xFF
                            when (n) {
                                0x40, 0x42 -> { g[0] = KANJI; i++ }
                                0x29, 0x2A, 0x2B -> {
                                    val idx = n - 0x28
                                    i++
                                    if (i < end) {
                                        val f = bytes[i].toInt() and 0xFF
                                        g[idx] = charsetOf(f)
                                        i++
                                    }
                                }
                                else -> i++
                            }
                        }
                        0x28, 0x29, 0x2A, 0x2B -> {
                            val idx = (bytes[i].toInt() and 0xFF) - 0x28
                            i++
                            if (i < end) {
                                g[idx] = charsetOf(bytes[i].toInt() and 0xFF)
                                i++
                            }
                        }
                        0x6E -> { gl = 2; i++ }
                        0x6F -> { gl = 3; i++ }
                        0x7C -> { gr = 3; i++ }
                        0x7D -> { gr = 2; i++ }
                        0x7E -> { gr = 1; i++ }
                        else -> i++
                    }
                }
                b == 0x0A || b == 0x0D -> { out.append('\n'); i++ }
                b == 0x20 -> { out.append(' '); i++ }
                b in 0x21..0x7E -> {
                    val extra = invoke(g[gl], b)
                    i += extra
                }
                b in 0xA1..0xFE -> {
                    val extra = invoke(g[gr], b)
                    i += extra
                }
                else -> i++
            }
        }
        return out.toString().trim()
    }

    private const val KANJI = 0
    private const val ALNUM = 1
    private const val HIRA = 2
    private const val KATA = 3

    private fun charsetOf(finalByte: Int): Int = when (finalByte) {
        0x40, 0x42 -> KANJI
        0x4A, 0x4B -> ALNUM
        0x30, 0x49 -> HIRA
        0x31 -> KATA
        else -> ALNUM
    }

    private fun jisKanji(row: Int, cell: Int): String {
        val ku = row - 0x20
        val ten = cell - 0x20
        if (ku !in 1..94 || ten !in 1..94) return ""
        val s1 = if (ku <= 62) (ku + 257) / 2 else (ku + 385) / 2
        val s2 = if (ku % 2 == 1) ten + 63 + if (ten >= 63) 1 else 0 else ten + 158
        return try {
            String(byteArrayOf(s1.toByte(), s2.toByte()), shiftJis)
        } catch (_: Exception) {
            ""
        }
    }

    private fun alnum(c: Int): String {
        if (c in 0x21..0x7E) return (c).toChar().toString()
        return ""
    }

    private fun kana(c: Int, hiragana: Boolean): String {
        val base = if (hiragana) 0x3041 else 0x30A1
        val index = c - 0x21
        if (index !in 0..82) return if (c == 0x21) " " else ""
        return Character.toChars(base + index).concatToString()
    }
}
