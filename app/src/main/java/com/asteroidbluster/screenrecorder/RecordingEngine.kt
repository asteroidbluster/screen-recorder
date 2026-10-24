package com.asteroidbluster.screenrecorder

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Which audio goes into the recording. */
enum class AudioMode {
    /** Microphone only (works on every Android version this app supports). */
    EXTERNAL,

    /** App/game audio only, via AudioPlaybackCapture (Android 10+). */
    INTERNAL,

    /** Microphone and app audio mixed together. */
    BOTH
}

data class RecordingConfig(
    val audioMode: AudioMode,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val videoBitRate: Int,
    val frameRate: Int
)

/**
 * The recording pipeline, built on MediaCodec + MediaMuxer instead of
 * MediaRecorder — MediaRecorder cannot take internal audio at all, and
 * certainly cannot mix two audio sources.
 *
 * Video: encoder input surface fed by a VirtualDisplay.
 * Audio: one AudioRecord per source (mic and/or playback capture). In BOTH
 * mode a mixer thread sums the two PCM streams with clipping and feeds a
 * single AAC encoder. Everything is muxed into one MP4.
 *
 * A/V sync approach: video timestamps are normalised to the first frame,
 * audio timestamps count audio frames from zero. Both start together, so
 * drift stays within a frame or two for typical recordings.
 */
class RecordingEngine(
    private val outputFd: ParcelFileDescriptor,
    private val onStopped: (success: Boolean) -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 2
        private const val AUDIO_BIT_RATE = 128_000
        private const val CHUNK_SAMPLES = 2048
    }

    private val lock = Any()
    private val stopping = AtomicBoolean(false)
    private val errored = AtomicBoolean(false)

    // Live controls from the floating ball. Pause drops video frames and
    // drains (discards) audio chunks while paused, then re-bases video
    // timestamps on resume so the saved clip has no gap in it.
    private val paused = AtomicBoolean(false)
    private val micMuted = AtomicBoolean(false)
    private val audioMuted = AtomicBoolean(false)
    @Volatile
    private var pauseStartMonoNs = 0L
    private val pausedTotalUs = java.util.concurrent.atomic.AtomicLong(0)

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val pending = ArrayList<PendingSample>()

    private var firstVideoPtsUs: Long = -1

    private val audioInputIndices = LinkedBlockingQueue<Int>()
    private val micQueue = LinkedBlockingQueue<ShortArray>()
    private val internalQueue = LinkedBlockingQueue<ShortArray>()

    private var micRecord: AudioRecord? = null
    private var internalRecord: AudioRecord? = null
    private var captureThreads = ArrayList<Thread>()
    private var feederThread: Thread? = null
    private var samplesFed: Long = 0

    private val videoEosLatch = CountDownLatch(1)
    private val audioEosLatch = CountDownLatch(1)

    private data class PendingSample(
        val track: Int,
        val bytes: ByteArray,
        val info: MediaCodec.BufferInfo
    )

    @Throws(IOException::class)
    fun start(projection: MediaProjection, config: RecordingConfig) {
        stopping.set(false)

        muxer = MediaMuxer(outputFd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // ---- Video encoder (surface input) ----
        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, config.videoBitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val vEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        vEncoder.setCallback(VideoCallback())
        vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = vEncoder.createInputSurface()
        if (Build.VERSION.SDK_INT >= 30) {
            // Cap frame production at the chosen rate. Without this the
            // compositor can feed the encoder at the display's refresh
            // rate (120 Hz on this phone) even for a 60 fps recording,
            // doubling encode and composition load for no benefit.
            inputSurface.setFrameRate(
                config.frameRate.toFloat(),
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
            )
        }
        vEncoder.start()
        videoEncoder = vEncoder

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenRecorder",
            config.width, config.height,
            config.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        // ---- Audio encoder ----
        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        }
        val aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        aEncoder.setCallback(AudioCallback())
        aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aEncoder.start()
        audioEncoder = aEncoder

        // ---- Audio capture ----
        val bufferSize = run {
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (min > 0) min * 2 else 32768
        }
        val pcmFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        if (config.audioMode == AudioMode.EXTERNAL || config.audioMode == AudioMode.BOTH) {
            micRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(pcmFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
                .also { startCaptureThread(it, micQueue) }
        }
        if (config.audioMode == AudioMode.INTERNAL || config.audioMode == AudioMode.BOTH) {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()
            internalRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(captureConfig)
                .setAudioFormat(pcmFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()
                .also { startCaptureThread(it, internalQueue) }
        }

        // ---- Feeder: PCM -> AAC encoder input ----
        feederThread = Thread({
            val singleQueue =
                if (config.audioMode == AudioMode.INTERNAL) internalQueue else micQueue
            while (!stopping.get()) {
                // Paused: drain (discard) audio chunks without feeding
                // them, so the queues can't grow and the audio clock
                // stands still until resume.
                if (paused.get()) {
                    try {
                        micQueue.poll(10, TimeUnit.MILLISECONDS)
                        internalQueue.poll(10, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                val chunk: ShortArray = try {
                    if (config.audioMode == AudioMode.BOTH) {
                        val mic = micQueue.poll(100, TimeUnit.MILLISECONDS)
                        val internal = internalQueue.poll(100, TimeUnit.MILLISECONDS)
                        if (mic == null && internal == null) continue
                        // Never drop the side that did deliver: a silent
                        // game means an empty internal queue, not a reason
                        // to throw the mic chunk away. Mix against zeros.
                        var micPart = mic ?: ShortArray(internal!!.size)
                        var internalPart = internal ?: ShortArray(mic!!.size)
                        if (micMuted.get()) micPart = ShortArray(micPart.size)
                        if (audioMuted.get()) {
                            ShortArray(min(micPart.size, internalPart.size))
                        } else {
                            mix(micPart, internalPart)
                        }
                    } else {
                        val raw = singleQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        val muted = audioMuted.get() ||
                            (config.audioMode == AudioMode.EXTERNAL && micMuted.get())
                        if (muted) ShortArray(raw.size) else raw
                    }
                } catch (e: InterruptedException) {
                    break
                }
                val index = try {
                    audioInputIndices.poll(100, TimeUnit.MILLISECONDS) ?: continue
                } catch (e: InterruptedException) {
                    break
                }
                val encoder = audioEncoder ?: break
                try {
                    val buffer = encoder.getInputBuffer(index) ?: continue
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.clear()
                    buffer.asShortBuffer().put(chunk)
                    // samplesFed counts SHORTS (stereo = 2 per frame), so
                    // divide by sample rate x channels. Dividing by the
                    // sample rate alone stamped audio at 2x speed — the
                    // v1.1.0 fast-playback bug, proven by ffprobe: audio
                    // tracks ran ~2x as long as their video tracks.
                    val pts = samplesFed * 1_000_000L / (SAMPLE_RATE * CHANNELS)
                    samplesFed += chunk.size
                    encoder.queueInputBuffer(index, 0, chunk.size * 2, pts, 0)
                } catch (e: IllegalStateException) {
                    break
                }
            }
            // End of stream for the audio encoder.
            try {
                val index = audioInputIndices.poll(1, TimeUnit.SECONDS)
                if (index != null) {
                    audioEncoder?.queueInputBuffer(
                        index, 0, 0,
                        samplesFed * 1_000_000L / (SAMPLE_RATE * CHANNELS),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            } catch (ignored: Exception) {
            }
        }, "audio-feeder").also { it.start() }
    }

    /** Pause/resume from the floating ball. No gap is left in the clip. */
    fun setPaused(p: Boolean) {
        if (paused.getAndSet(p) == p) return
        if (p) {
            pauseStartMonoNs = System.nanoTime()
        } else if (firstVideoPtsUs >= 0) {
            // Only re-base once at least one frame exists; before the
            // first frame there is nothing to shift.
            pausedTotalUs.addAndGet((System.nanoTime() - pauseStartMonoNs) / 1000)
        }
    }

    /** Mic on/off from the floating ball (feeds silence while muted). */
    fun setMicMuted(m: Boolean) {
        micMuted.set(m)
    }

    /** Mute all audio from the floating ball (feeds silence). */
    fun setAudioMuted(m: Boolean) {
        audioMuted.set(m)
    }

    private fun mix(a: ShortArray, b: ShortArray): ShortArray {
        val len = min(a.size, b.size)
        return ShortArray(len) { i ->
            (a[i].toInt() + b[i].toInt()).coerceIn(-32768, 32767).toShort()
        }
    }

    private fun startCaptureThread(record: AudioRecord, queue: LinkedBlockingQueue<ShortArray>) {
        val thread = Thread({
            try {
                record.startRecording()
                val buffer = ShortArray(CHUNK_SAMPLES)
                while (!stopping.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        queue.offer(buffer.copyOf(read))
                    } else if (read < 0) {
                        break
                    }
                }
            } catch (ignored: Exception) {
            }
        }, "audio-capture")
        captureThreads.add(thread)
        thread.start()
    }

    /** Stops everything on a worker thread, then reports success. */
    fun stopAsync(onDone: (Boolean) -> Unit) {
        Thread({
            stopping.set(true)
            try {
                captureThreads.forEach { it.join(500) }
                micRecord?.stop()
                internalRecord?.stop()
            } catch (ignored: Exception) {
            }
            try {
                micRecord?.release()
                internalRecord?.release()
            } catch (ignored: Exception) {
            }
            micRecord = null
            internalRecord = null

            try {
                feederThread?.join(1500)
            } catch (ignored: Exception) {
            }
            try {
                videoEncoder?.signalEndOfInputStream()
            } catch (ignored: Exception) {
            }
            // Bounded waits: a stuck latch must never wedge the stop path.
            try {
                videoEosLatch.await(3, TimeUnit.SECONDS)
            } catch (ignored: Exception) {
            }
            try {
                audioEosLatch.await(3, TimeUnit.SECONDS)
            } catch (ignored: Exception) {
            }

            var success = false
            synchronized(lock) {
                try {
                    if (muxerStarted) {
                        muxer?.stop()
                        success = !errored.get()
                    }
                } catch (ignored: Exception) {
                }
                try {
                    muxer?.release()
                } catch (ignored: Exception) {
                }
            }
            try {
                videoEncoder?.stop()
                videoEncoder?.release()
            } catch (ignored: Exception) {
            }
            try {
                audioEncoder?.stop()
                audioEncoder?.release()
            } catch (ignored: Exception) {
            }
            videoEncoder = null
            audioEncoder = null
            try {
                virtualDisplay?.release()
            } catch (ignored: Exception) {
            }
            virtualDisplay = null
            onDone(success)
            onStopped(success)
        }, "engine-stop").start()
    }

    private fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (track < 0) return
        synchronized(lock) {
            if (!muxerStarted) {
                val bytes = ByteArray(info.size)
                val pos = buffer.position()
                buffer.position(info.offset)
                buffer.get(bytes, 0, info.size)
                buffer.position(pos)
                val copy = MediaCodec.BufferInfo()
                copy.set(0, bytes.size, info.presentationTimeUs, info.flags)
                pending.add(PendingSample(track, bytes, copy))
                return
            }
            muxer?.writeSampleData(track, buffer, info)
        }
    }

    private fun onTrackAdded(format: MediaFormat, isVideo: Boolean) {
        synchronized(lock) {
            if (isVideo) {
                videoTrack = muxer?.addTrack(format) ?: -1
            } else {
                audioTrack = muxer?.addTrack(format) ?: -1
            }
            if (videoTrack != -1 && audioTrack != -1 && !muxerStarted) {
                muxer?.start()
                muxerStarted = true
                for (sample in pending) {
                    muxer?.writeSampleData(
                        sample.track,
                        ByteBuffer.wrap(sample.bytes),
                        sample.info
                    )
                }
                pending.clear()
            }
        }
    }

    private inner class VideoCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input: nothing to feed.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    videoEosLatch.countDown()
                } else if (info.size > 0) {
                    if (firstVideoPtsUs < 0) firstVideoPtsUs = info.presentationTimeUs
                    // While paused, drop frames; on resume the
                    // accumulated pause duration is subtracted so the
                    // clip plays back with no gap.
                    if (!paused.get()) {
                        val adjusted = MediaCodec.BufferInfo()
                        adjusted.set(
                            info.offset, info.size,
                            (info.presentationTimeUs - firstVideoPtsUs -
                                pausedTotalUs.get()).coerceAtLeast(0),
                            info.flags
                        )
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) writeSample(videoTrack, buffer, adjusted)
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (ignored: Exception) {
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            onTrackAdded(format, true)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            errored.set(true)
            videoEosLatch.countDown()
        }
    }

    private inner class AudioCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            audioInputIndices.offer(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    audioEosLatch.countDown()
                } else if (info.size > 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) writeSample(audioTrack, buffer, info)
                }
            } catch (ignored: Exception) {
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (ignored: Exception) {
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            onTrackAdded(format, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            errored.set(true)
            audioEosLatch.countDown()
        }
    }
}
