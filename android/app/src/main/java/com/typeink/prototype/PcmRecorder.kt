package com.typeink.prototype

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

class PcmRecorder {
    interface Listener {
        fun onAudioChunk(bytes: ByteArray)

        fun onError(message: String)

        fun onAmplitude(level: Float) = Unit
    }

    @Volatile
    private var running = false

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    fun start(listener: Listener) {
        if (running) return

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBufferSize <= 0) {
            listener.onError("设备不支持当前录音参数")
            return
        }

        val bufferSize = maxOf(minBufferSize, CHUNK_BYTES * 4)
        val record =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            listener.onError("录音器初始化失败")
            return
        }

        audioRecord = record
        running = true

        workerThread =
            thread(name = "typeink-pcm-recorder") {
                var carry = ByteArray(0)
                val buffer = ByteArray(bufferSize)

                try {
                    record.startRecording()
                    while (running) {
                        val readCount = record.read(buffer, 0, buffer.size)
                        if (readCount > 0) {
                            listener.onAmplitude(calculateAmplitude(buffer, readCount))
                            val merged = ByteArray(carry.size + readCount)
                            System.arraycopy(carry, 0, merged, 0, carry.size)
                            System.arraycopy(buffer, 0, merged, carry.size, readCount)

                            var offset = 0
                            while (merged.size - offset >= CHUNK_BYTES) {
                                listener.onAudioChunk(merged.copyOfRange(offset, offset + CHUNK_BYTES))
                                offset += CHUNK_BYTES
                            }
                            carry =
                                if (offset < merged.size) {
                                    merged.copyOfRange(offset, merged.size)
                                } else {
                                    ByteArray(0)
                                }
                        } else if (readCount < 0) {
                            listener.onError("录音读取失败：$readCount")
                            break
                        }
                    }
                } catch (error: Exception) {
                    listener.onError(error.message ?: "录音异常")
                } finally {
                    running = false
                    try {
                        record.stop()
                    } catch (_: Exception) {
                    }
                    record.release()
                    if (audioRecord === record) {
                        audioRecord = null
                    }
                }
            }
    }

    fun stop() {
        running = false
        workerThread?.join(400)
        workerThread = null
        audioRecord = null
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 640
    }

    private fun calculateAmplitude(
        buffer: ByteArray,
        readCount: Int,
    ): Float {
        var sum = 0.0
        var sampleCount = 0
        var index = 0
        while (index + 1 < readCount) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xFF)).toShort().toInt()
            sum += sample * sample.toDouble()
            sampleCount++
            index += 2
        }
        if (sampleCount == 0) return 0f
        val rms = kotlin.math.sqrt(sum / sampleCount)
        return (rms / 12000.0).toFloat().coerceIn(0f, 1f)
    }
}
