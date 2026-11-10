package com.glasseslab.android

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Escritor WAV PCM 16 bits little-endian, thread-safe. Calcula RMS para la evidencia. */
class WavWriter(val file: File, private val sampleRate: Int, private val channels: Int) {
  private val raf = RandomAccessFile(file, "rw")
  private var dataBytes = 0L
  private var sumSquares = 0.0
  private var sampleCount = 0L
  private var closed = false

  init {
    raf.setLength(0)
    raf.write(ByteArray(44)) // cabecera provisional
  }

  @Synchronized
  fun write(data: ByteArray, offset: Int, size: Int) {
    if (closed || size <= 0) return
    raf.write(data, offset, size)
    dataBytes += size
    val shorts = ByteBuffer.wrap(data, offset, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    while (shorts.hasRemaining()) {
      val v = shorts.get() / 32768.0
      sumSquares += v * v
      sampleCount++
    }
  }

  val seconds: Double
    @Synchronized get() = dataBytes.toDouble() / (sampleRate * channels * 2)

  val rms: Double
    @Synchronized get() = if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0

  @Synchronized
  fun close() {
    if (closed) return
    closed = true
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt((36 + dataBytes).toInt())
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1) // PCM
    header.putShort(channels.toShort())
    header.putInt(sampleRate)
    header.putInt(sampleRate * channels * 2)
    header.putShort((channels * 2).toShort())
    header.putShort(16)
    header.put("data".toByteArray())
    header.putInt(dataBytes.toInt())
    raf.seek(0)
    raf.write(header.array())
    raf.close()
  }
}
