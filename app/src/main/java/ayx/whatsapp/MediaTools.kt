package ayx.whatsapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object MediaTools {

    // ---------- public: PHOTO(S) -> silent MP4 ----------
    fun photosToVideo(ctx: Context, uris: List<Uri>, out: File, durationMs: Long, lyrics: List<Pair<Long, String>>, onProgress: (Float) -> Unit): Boolean {
        val W = 720; val H = 1280; val FPS = 30; val BITRATE = 6_000_000
        val bmps = uris.mapNotNull { runCatching { loadScaledCropped(ctx, it, W, H) }.getOrNull() }
        if (bmps.isEmpty()) return false
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglBase? = null
        var quad: GlQuad? = null
        try {
            val format = MediaFormat.createVideoFormat("video/avc", W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                try {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                } catch (_: Exception) {}
            }
            encoder = MediaCodec.createEncoderByType("video/avc")
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            egl = EglBase(encoder.createInputSurface())
            encoder.start()
            egl.makeCurrent()
            quad = GlQuad()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            val totalFrames = (durationMs * FPS / 1000L).toInt().coerceAtLeast(FPS)
            val perImage = (totalFrames / bmps.size).coerceAtLeast(1)
            var frame = 0

            fun drain(endOfStream: Boolean) {
                if (endOfStream) encoder!!.signalEndOfInputStream()
                while (true) {
                    val outIdx = encoder!!.dequeueOutputBuffer(info, 10000)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) { if (!endOfStream) break }
                    else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw RuntimeException("format changed twice")
                        trackIndex = muxer!!.addTrack(encoder!!.outputFormat)
                        muxer!!.start(); muxerStarted = true
                    } else if (outIdx >= 0) {
                        val encoded = encoder!!.getOutputBuffer(outIdx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size != 0 && muxerStarted) {
                            encoded.position(info.offset); encoded.limit(info.offset + info.size)
                            muxer!!.writeSampleData(trackIndex, encoded, info)
                        }
                        encoder!!.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }

            var lastKey = ""
            var composite: Bitmap? = null
            while (frame < totalFrames) {
                drain(false)
                val tMs = frame.toLong() * 1000L / FPS
                val imgIdx = (frame / perImage).coerceIn(0, bmps.size - 1)
                val lyric = lyrics.lastOrNull { it.first <= tMs }?.second ?: ""
                val key = imgIdx.toString() + "|" + lyric
                if (key != lastKey) {
                    composite?.recycle()
                    composite = drawComposite(bmps[imgIdx], lyric, W, H)
                    quad!!.uploadBitmap(composite!!)
                    lastKey = key
                }
                GLES20.glViewport(0, 0, W, H)
                quad!!.draw()
                egl!!.setPresentationTime(frame.toLong() * 1_000_000_000L / FPS)
                egl!!.swapBuffers()
                frame++
                if (frame % 5 == 0) onProgress((frame.toFloat() / totalFrames).coerceIn(0f, 1f))
            }
            composite?.recycle()
            drain(true)
            onProgress(1f)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { quad?.release() } catch (_: Exception) {}
            try { egl?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
            bmps.forEach { runCatching { it.recycle() } }
        }
    }

    // ---------- public: download song URL + trim to [startMs,endMs] -> AAC .m4a ----------
    fun downloadAndTrimAudio(url: String, out: File, startMs: Long, endMs: Long): Boolean {
        val tmp = File(out.parentFile, "song_dl_${System.currentTimeMillis()}.m4a")
        try {
            // download
            URL(url).openStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            return trimAudioFile(tmp.absolutePath, out, startMs, endMs)
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun trimAudioFile(srcPath: String, out: File, startMs: Long, endMs: Long): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(srcPath)
            var audioTrack = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { audioTrack = i; fmt = f; break }
            }
            if (audioTrack < 0 || fmt == null) return false
            extractor.selectTrack(audioTrack)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(fmt)
            muxer.start()
            val maxSize = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 256 * 1024
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var firstPts = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                if (pts >= startUs) {
                    if (firstPts < 0) firstPts = pts
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = pts - firstPts
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(outTrack, buffer, info)
                }
                if (!extractor.advance()) break
            }
            return firstPts >= 0
        } catch (e: Exception) {
            return false
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    // ---------- public: mux silent video + audio -> final MP4 (video kept, audio replaced) ----------
    // ---------- generate a silent AAC track (so every status video has audio -> WhatsApp plays it) ----------
    fun makeSilentAac(durationMs: Long, out: File): Boolean {
        val SR = 44100; val CH = 1
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", SR, CH).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1; var started = false
            val info = MediaCodec.BufferInfo()
            val totalUs = durationMs * 1000L
            val frameBytes = 2048 * CH * 2   // ~1024 samples/frame * 2 bytes
            val silence = ByteArray(frameBytes)
            var ptsUs = 0L
            val usPerFrame = (1024L * 1_000_000L) / SR
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10000)
                    if (inIx >= 0) {
                        if (ptsUs >= totalUs) {
                            codec.queueInputBuffer(inIx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val ib = codec.getInputBuffer(inIx)!!; ib.clear(); ib.put(silence); ib.clear()
                            codec.queueInputBuffer(inIx, 0, frameBytes, ptsUs, 0)
                            ptsUs += usPerFrame
                        }
                    }
                }
                val outIx = codec.dequeueOutputBuffer(info, 10000)
                if (outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                } else if (outIx >= 0) {
                    val ob = codec.getOutputBuffer(outIx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) { ob.position(info.offset); ob.limit(info.offset + info.size); muxer.writeSampleData(track, ob, info) }
                    codec.releaseOutputBuffer(outIx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch (e: Exception) { return false }
        finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    // ---------- qt-faststart: move moov atom to front so WhatsApp can play the video ----------
    fun faststart(inFile: File, outFile: File): Boolean {
        try {
            val data = inFile.readBytes()
            if (data.size < 16) return false
            val starts = ArrayList<Int>(); val sizes = ArrayList<Int>(); val types = ArrayList<String>()
            var pos = 0
            while (pos + 8 <= data.size) {
                var size = u32(data, pos)
                val type = String(data, pos + 4, 4, Charsets.US_ASCII)
                if (size == 1L) size = u64(data, pos + 8) else if (size == 0L) size = (data.size - pos).toLong()
                if (size < 8 || pos + size > data.size) break
                starts.add(pos); sizes.add(size.toInt()); types.add(type)
                pos += size.toInt()
            }
            val moovIdx = types.indexOf("moov"); val mdatIdx = types.indexOf("mdat")
            if (moovIdx < 0 || mdatIdx < 0) return false
            if (moovIdx < mdatIdx) { inFile.copyTo(outFile, overwrite = true); return true }
            val moovBytes = data.copyOfRange(starts[moovIdx], starts[moovIdx] + sizes[moovIdx])
            patchChunkOffsets(moovBytes, sizes[moovIdx].toLong())
            val ftypIdx = types.indexOf("ftyp")
            FileOutputStream(outFile).use { os ->
                if (ftypIdx >= 0) os.write(data, starts[ftypIdx], sizes[ftypIdx])
                os.write(moovBytes)
                for (i in starts.indices) { if (i == ftypIdx || i == moovIdx) continue; os.write(data, starts[i], sizes[i]) }
            }
            return true
        } catch (e: Exception) { return false }
    }

    private fun u32(d: ByteArray, p: Int): Long =
        ((d[p].toLong() and 0xFF) shl 24) or ((d[p + 1].toLong() and 0xFF) shl 16) or ((d[p + 2].toLong() and 0xFF) shl 8) or (d[p + 3].toLong() and 0xFF)
    private fun u64(d: ByteArray, p: Int): Long { var v = 0L; for (i in 0 until 8) v = (v shl 8) or (d[p + i].toLong() and 0xFF); return v }
    private fun putU32(d: ByteArray, p: Int, v: Long) { d[p] = ((v shr 24) and 0xFF).toByte(); d[p + 1] = ((v shr 16) and 0xFF).toByte(); d[p + 2] = ((v shr 8) and 0xFF).toByte(); d[p + 3] = (v and 0xFF).toByte() }

    private fun patchChunkOffsets(moov: ByteArray, delta: Long) {
        fun scan(start: Int, end: Int) {
            var p = start
            while (p + 8 <= end) {
                val size = u32(moov, p)
                if (size < 8 || p + size > end) break
                val type = String(moov, p + 4, 4, Charsets.US_ASCII)
                when (type) {
                    "stco" -> {
                        val cnt = u32(moov, p + 12).toInt(); var ep = p + 16
                        var i = 0
                        while (i < cnt && ep + 4 <= end) { putU32(moov, ep, u32(moov, ep) + delta); ep += 4; i++ }
                    }
                    "co64" -> {
                        val cnt = u32(moov, p + 12).toInt(); var ep = p + 16
                        var i = 0
                        while (i < cnt && ep + 8 <= end) { val no = u64(moov, ep) + delta; for (k in 0 until 8) moov[ep + 7 - k] = ((no shr (k * 8)) and 0xFF).toByte(); ep += 8; i++ }
                    }
                    "moov", "trak", "mdia", "minf", "stbl", "udta", "edts" -> scan(p + 8, (p + size).toInt())
                }
                p += size.toInt()
            }
        }
        scan(0, moov.size)
    }

    // ---------- validate final MP4 (duration + video track present) ----------
    fun isValidMp4(f: File): Boolean {
        if (!f.exists() || f.length() < 1024) return false
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.absolutePath)
            val dur = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val hasVideo = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            dur > 0L && hasVideo
        } catch (e: Exception) { false } finally { try { mmr.release() } catch (_: Exception) {} }
    }

    fun muxVideoAudio(videoFile: File, audioFile: File, out: File): Boolean {
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            vEx = MediaExtractor(); vEx.setDataSource(videoFile.absolutePath)
            aEx = MediaExtractor(); aEx.setDataSource(audioFile.absolutePath)
            var vTrack = -1; var vFmt: MediaFormat? = null
            for (i in 0 until vEx.trackCount) { val f = vEx.getTrackFormat(i); if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { vTrack = i; vFmt = f; break } }
            var aTrack = -1; var aFmt: MediaFormat? = null
            for (i in 0 until aEx.trackCount) { val f = aEx.getTrackFormat(i); if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { aTrack = i; aFmt = f; break } }
            if (vTrack < 0 || vFmt == null) return false
            vEx.selectTrack(vTrack)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outV = muxer.addTrack(vFmt)
            val outA = if (aTrack >= 0 && aFmt != null) muxer.addTrack(aFmt) else -1
            if (aTrack >= 0) aEx.selectTrack(aTrack)
            muxer.start()
            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // video (defines final duration)
            var videoDurUs = 0L
            while (true) {
                val sz = vEx.readSampleData(buf, 0); if (sz < 0) break
                info.offset = 0; info.size = sz; info.presentationTimeUs = vEx.sampleTime
                info.flags = if (vEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outV, buf, info)
                videoDurUs = vEx.sampleTime
                if (!vEx.advance()) break
            }
            // audio: loop until it covers the video duration
            if (outA >= 0) {
                var offsetUs = 0L
                var guard = 0
                loop@ while (offsetUs < videoDurUs && guard < 50) {
                    aEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var lastPts = 0L
                    while (true) {
                        val sz = aEx.readSampleData(buf, 0); if (sz < 0) break
                        val pts = aEx.sampleTime + offsetUs
                        if (pts > videoDurUs) break@loop
                        info.offset = 0; info.size = sz; info.presentationTimeUs = pts
                        info.flags = if (aEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        muxer.writeSampleData(outA, buf, info)
                        lastPts = aEx.sampleTime
                        if (!aEx.advance()) break
                    }
                    if (lastPts <= 0L) break
                    offsetUs += lastPts + 23000L
                    guard++
                }
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { vEx?.release() } catch (_: Exception) {}
            try { aEx?.release() } catch (_: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch (_: Exception) {}
        }
    }

    // ---------- helpers ----------
    private fun loadScaledCropped(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        val big = maxOf(opts.outWidth, opts.outHeight)
        while (big / sample > 2000) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            ?: throw RuntimeException("decode failed")
        // center-crop into w x h
        val target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(android.graphics.Color.BLACK)
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dw = src.width * scale; val dh = src.height * scale
        val left = (w - dw) / 2f; val top = (h - dh) / 2f
        val m = Matrix().apply { setScale(scale, scale); postTranslate(left, top) }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        runCatching { src.recycle() }
        return target
    }

    private fun drawComposite(base: Bitmap, lyric: String, w: Int, h: Int): Bitmap {
        val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
        if (lyric.isNotBlank()) {
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = h * 0.048f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(10f, 0f, 3f, android.graphics.Color.argb(200, 0, 0, 0))
            }
            val lines = wrapText(lyric, paint, w * 0.88f)
            var y = h * 0.80f
            for (line in lines.takeLast(3)) { canvas.drawText(line, w / 2f, y, paint); y += paint.textSize * 1.35f }
        }
        return bmp
    }

    private fun wrapText(text: String, paint: Paint, maxW: Float): List<String> {
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (word in words) {
            val test = if (cur.isEmpty()) word else cur.toString() + " " + word
            if (paint.measureText(test) <= maxW) { cur = StringBuilder(test) }
            else { if (cur.isNotEmpty()) lines.add(cur.toString()); cur = StringBuilder(word) }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }

    // ---------- EGL base bound to a MediaCodec input Surface ----------
    private class EglBase(surface: Surface) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        init {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)
            val attrib = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142, 1, /* EGL_RECORDABLE_ANDROID */ EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            EGL14.eglChooseConfig(display, attrib, 0, configs, 0, 1, numConfig, 0)
            val ctxAttrib = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0)
            val surfAttrib = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, surfAttrib, 0)
        }
        fun makeCurrent() { EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context) }
        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, eglSurface)
        fun setPresentationTime(ns: Long) { EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns) }
        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, eglSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    // ---------- GL textured fullscreen quad ----------
    private class GlQuad {
        private val vertShader = "attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex; void main(){ gl_Position=aPos; vTex=aTex; }"
        private val fragShader = "precision mediump float; varying vec2 vTex; uniform sampler2D uTex; void main(){ gl_FragColor=texture2D(uTex,vTex); }"
        private val program: Int
        private val texId: Int
        private val posBuf: FloatBuffer
        private val texBuf: FloatBuffer
        init {
            val verts = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
            val texc = floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f)
            posBuf = ByteBuffer.allocateDirect(verts.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(verts); position(0) }
            texBuf = ByteBuffer.allocateDirect(texc.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texc); position(0) }
            val vs = compile(GLES20.GL_VERTEX_SHADER, vertShader)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragShader)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); texId = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        private fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
        }
        fun uploadBitmap(bmp: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        }
        fun draw() {
            GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aTex = GLES20.glGetAttribLocation(program, "aTex")
            GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
            GLES20.glEnableVertexAttribArray(aTex); GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texBuf)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aTex)
        }
        fun release() { runCatching { GLES20.glDeleteProgram(program) }; runCatching { GLES20.glDeleteTextures(1, intArrayOf(texId), 0) } }
    }
}
