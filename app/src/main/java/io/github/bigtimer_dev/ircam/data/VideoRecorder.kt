package io.github.bigtimer_dev.ircam.data

import android.content.Context
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface

/**
 * H.264/MP4 recorder fed by drawing on the encoder's input surface. Frames are drawn by the
 * caller (the same renderer as snapshots), so the video shows the overlay too.
 */
class VideoRecorder(
    context: Context,
    val uri: Uri,
    private val width: Int,
    private val height: Int,
    fps: Int = 25,
) {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "rw")!!
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: Surface
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxing = false
    private var firstPts = -1L
    private val info = MediaCodec.BufferInfo()
    var frames = 0
        private set
    val startedAt = System.currentTimeMillis()

    init {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    /** Draw one frame. Called from the processing thread. */
    fun frame(draw: (Canvas) -> Unit) {
        val c = try { surface.lockHardwareCanvas() } catch (e: Exception) { surface.lockCanvas(null) }
        try { draw(c) } finally { surface.unlockCanvasAndPost(c) }
        frames++
        drain(false)
    }

    private fun drain(end: Boolean) {
        if (end) codec.signalEndOfInputStream()
        var tries = 0
        while (tries++ < 500) {
            val idx = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        info.presentationTimeUs -= firstPts
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Finish the file. Returns false when nothing was written. */
    fun stop(): Boolean {
        var ok = false
        try {
            drain(true)
            ok = muxing
        } catch (_: Exception) {
        } finally {
            runCatching { codec.stop() }
            codec.release()
            surface.release()
            runCatching { if (muxing) muxer.stop() }
            runCatching { muxer.release() }
            pfd.close()
        }
        return ok && frames > 0
    }
}
