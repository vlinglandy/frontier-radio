package com.aireadio.app.playback

import java.io.File
import java.io.RandomAccessFile
import java.io.Closeable

object PcmWave {
    class Writer(file: File, private val rate: Int, private val channels: Int = 1) : Closeable {
        private val out = RandomAccessFile(file, "rw").apply { setLength(0) }
        private var bytes = 0L
        init { out.write(ByteArray(44)) }
        fun samples(samples: FloatArray) {
            val pcm = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val value = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
                pcm[i * 2] = value.toByte(); pcm[i * 2 + 1] = (value shr 8).toByte()
            }
            raw(pcm)
        }
        fun raw(pcm: ByteArray) { out.write(pcm); bytes += pcm.size }
        fun silence(ms: Int) = raw(ByteArray(rate * channels * 2 * ms / 1000))
        override fun close() {
            require(bytes < Int.MAX_VALUE)
            fun int(v: Int) { repeat(4) { out.write(v ushr (it * 8) and 255) } }
            fun short(v: Int) { out.write(v and 255); out.write(v ushr 8 and 255) }
            out.seek(0); out.writeBytes("RIFF"); int(36 + bytes.toInt()); out.writeBytes("WAVEfmt ")
            int(16); short(1); short(channels); int(rate); int(rate * channels * 2); short(channels * 2); short(16)
            out.writeBytes("data"); int(bytes.toInt()); out.close()
        }
    }
    data class Pcm(val rate: Int, val channels: Int, val bytes: ByteArray)
    fun read(file: File): Pcm = RandomAccessFile(file, "r").use { input ->
        fun int(): Int = Integer.reverseBytes(input.readInt())
        fun short(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
        fun tag(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        require(tag() == "RIFF"); int(); require(tag() == "WAVE")
        var rate = 0; var channels = 0
        while (input.filePointer + 8 <= input.length()) {
            val name = tag(); val size = int(); val end = input.filePointer + size + (size and 1)
            require(size >= 0 && end <= input.length() + 1)
            if (name == "fmt ") { require(short() == 1) { "系统语音不是 PCM 音频" }; channels = short(); rate = int(); int(); short(); require(short() == 16) }
            if (name == "data") { require(rate > 0 && channels in 1..2); return@use Pcm(rate, channels, ByteArray(size).also(input::readFully)) }
            input.seek(end)
        }
        error("语音文件没有音频数据")
    }
}
