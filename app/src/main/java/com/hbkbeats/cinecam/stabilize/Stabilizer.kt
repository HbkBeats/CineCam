package com.hbkbeats.cinecam.stabilize

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.MatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/** Smoothing strength and the zoom (crop) that buys room to shift the picture. First guesses, to be tuned. */
enum class StabMode(val label: String, val sigmaSec: Double, val zoom: Float) {
    STANDARD("Standard", 0.20, 1.05f),
    HIGH("High", 0.45, 1.10f),
    ACTION("Action", 0.90, 1.18f)
}

/** Per-frame picture shifts (fractions of the frame) ready to hand to the video renderer. */
class Plan(
    val zoom: Float,
    private val stepSec: Double,
    private val cx: FloatArray,
    private val cy: FloatArray,
    val bitrate: Int,
    val summary: String
) {
    fun matrixAt(presentationTimeUs: Long): Matrix {
        val t = presentationTimeUs / 1_000_000.0
        val pos = (t / stepSec).coerceIn(0.0, (cx.size - 1).toDouble())
        val i0 = min(pos.toInt(), cx.size - 2)
        val f = (pos - i0).toFloat()
        val x = cx[i0] * (1f - f) + cx[i0 + 1] * f
        val y = cy[i0] * (1f - f) + cy[i0 + 1] * f
        val m = Matrix()
        m.postScale(zoom, zoom)
        // The renderer works in -1..1 coordinates with Y pointing up; our Y points down.
        m.postTranslate(2f * x, -2f * y)
        return m
    }
}

object Stabilizer {

    private const val SIZE = 192
    private const val RADIUS = 8
    private const val STEP = 0.01 // seconds between path samples

    private class Fit(val rss: Double, val ax: DoubleArray, val ay: DoubleArray)

    /**
     * Step 1 (slow, background thread): measures how the picture really moved in a few spots of the clip,
     * lines that up with the recorded gyro to find the time offset and gains, then builds the smoothed correction.
     */
    fun plan(context: Context, uri: Uri, gyro: GyroData, mode: StabMode, progress: (String) -> Unit): Plan {
        if (gyro.tNs.size < 200) throw IllegalStateException("Not enough gyro data was recorded")

        // Gyro angle (integrated rate) per axis
        val n = gyro.tNs.size
        val t0 = gyro.tNs[0]
        val tg = DoubleArray(n) { (gyro.tNs[it] - t0) * 1e-9 }
        val src = arrayOf(gyro.x, gyro.y, gyro.z)
        val cum = Array(3) { DoubleArray(n) }
        for (a in 0..2) {
            for (i in 1 until n) {
                cum[a][i] = cum[a][i - 1] + 0.5 * (src[a][i] + src[a][i - 1]) * (tg[i] - tg[i - 1])
            }
        }

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IllegalStateException("Cannot read the video length")
            val durSec = durMs / 1000.0
            val frameCount = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull() ?: (durMs * 30 / 1000)
            if (durSec < 2.0 || frameCount < 50) throw IllegalStateException("Clip is too short (need 2 s or more)")
            val period = durSec / frameCount
            val bitrate = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 20_000_000).coerceIn(8_000_000, 60_000_000)

            // Measure real picture motion at ~50 spots (two frames apart)
            val gap = 2
            val firstK = 8
            val lastK = (frameCount - gap - 8).toInt()
            val ks = (0 until 50)
                .map { firstK + ((lastK - firstK) * it / 49.0).roundToInt() }
                .distinct()
            val tAList = ArrayList<Double>()
            val tBList = ArrayList<Double>()
            val dxList = ArrayList<Double>()
            val dyList = ArrayList<Double>()
            for ((idx, k) in ks.withIndex()) {
                progress("Stabilizing: analysing clip ${idx + 1}/${ks.size}")
                val a = lumaFrame(mmr, k * period) ?: continue
                val b = lumaFrame(mmr, (k + gap) * period) ?: continue
                val s = ShiftEstimator.estimate(a, b, SIZE, SIZE, RADIUS)
                if (abs(s[0]) >= RADIUS - 0.4f || abs(s[1]) >= RADIUS - 0.4f) continue
                tAList.add(k * period)
                tBList.add((k + gap) * period)
                dxList.add(s[0].toDouble())
                dyList.add(s[1].toDouble())
            }
            if (tAList.size < 15) throw IllegalStateException("Could not measure enough picture motion in the clip")
            val tA = tAList.toDoubleArray()
            val tB = tBList.toDoubleArray()
            val dx = dxList.toDoubleArray()
            val dy = dyList.toDoubleArray()

            var tss = 0.0
            for (i in dx.indices) tss += dx[i] * dx[i] + dy[i] * dy[i]
            val motionRms = sqrt(tss / (2.0 * dx.size))
            if (motionRms < 0.25) {
                throw IllegalStateException("There was too little camera movement to line up the gyro. Try a clip with some walking or panning.")
            }

            // Find the time offset between video time and gyro time
            progress("Stabilizing: syncing gyro to video")
            var best: Fit? = null
            var bestT0 = 0.0
            var t = -0.3
            while (t <= 3.0) {
                val f = fitAt(t, tA, tB, dx, dy, tg, cum)
                val b = best
                if (b == null || f.rss < b.rss) { best = f; bestT0 = t }
                t += 0.005
            }
            val coarse = bestT0
            t = coarse - 0.005
            while (t <= coarse + 0.005) {
                val f = fitAt(t, tA, tB, dx, dy, tg, cum)
                val b = best
                if (b == null || f.rss < b.rss) { best = f; bestT0 = t }
                t += 0.0005
            }
            val fit = best ?: throw IllegalStateException("Sync failed")
            val r2 = 1.0 - fit.rss / tss
            if (r2 < 0.5) {
                throw IllegalStateException("Could not line up gyro and video (match ${(r2 * 100).roundToInt()}%). Try a textured scene.")
            }

            // Camera path in picture space (fractions of the frame), then its smoothed version
            val m = floor(durSec / STEP).toInt() + 2
            val px = DoubleArray(m)
            val py = DoubleArray(m)
            for (j in 0 until m) {
                val tt = j * STEP + bestT0
                var sx = 0.0
                var sy = 0.0
                for (c in 0..2) {
                    val th = interp(tt, tg, cum[c])
                    sx += fit.ax[c] * th
                    sy += fit.ay[c] * th
                }
                px[j] = sx / SIZE
                py[j] = sy / SIZE
            }
            val smx = smooth(px, mode.sigmaSec, STEP)
            val smy = smooth(py, mode.sigmaSec, STEP)

            // Shift the picture from where it is (path) to where the smooth path says; limited by the zoom margin
            val limit = (mode.zoom - 1.0) / 2.0 * 0.9
            val cx = FloatArray(m)
            val cy = FloatArray(m)
            var peak = 0.0
            for (j in 0 until m) {
                val ex = smx[j] - px[j]
                val ey = smy[j] - py[j]
                peak = max(peak, max(abs(ex), abs(ey)))
                cx[j] = (limit * tanh(ex / limit)).toFloat()
                cy[j] = (limit * tanh(ey / limit)).toFloat()
            }
            val summary = "gyro match ${(r2 * 100).roundToInt()}%, offset ${(bestT0 * 1000).roundToInt()} ms, " +
                "peak correction ${"%.1f".format(peak * 100)}% of frame, crop ${((1.0 - 1.0 / mode.zoom) * 100).roundToInt()}%"
            return Plan(mode.zoom, STEP, cx, cy, bitrate, summary)
        } finally {
            mmr.release()
        }
    }

    /** Step 2 (must be called on the main thread): renders the stabilized video to a cache file. */
    suspend fun export(context: Context, src: Uri, plan: Plan, onProgress: (Int) -> Unit): File =
        suspendCancellableCoroutine { cont ->
            val outFile = File(context.cacheDir, "stab_${System.currentTimeMillis()}.mp4")
            val effect = object : MatrixTransformation {
                override fun getMatrix(presentationTimeUs: Long): Matrix = plan.matrixAt(presentationTimeUs)
            }
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(src))
                .setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(effect)))
                .build()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(plan.bitrate).build()
                )
                .build()
            val handler = Handler(Looper.getMainLooper())
            var finished = false
            lateinit var transformer: Transformer
            val poll = object : Runnable {
                override fun run() {
                    if (finished) return
                    val holder = ProgressHolder()
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress)
                    }
                    handler.postDelayed(this, 500)
                }
            }
            transformer = Transformer.Builder(context)
                .setEncoderFactory(encoderFactory)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        if (cont.isActive) cont.resume(outFile)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        finished = true
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { handler.post { finished = true; transformer.cancel() } }
            transformer.start(edited, outFile.absolutePath)
            handler.postDelayed(poll, 500)
        }

    /** Copies the rendered file into Movies/CineCam and removes the temporary copy. */
    fun saveToGallery(context: Context, file: File, mode: StabMode): Boolean {
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "CineCam_stab_${mode.label}_$stamp.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CineCam")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            file.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- helpers ----------

    private fun lumaFrame(mmr: MediaMetadataRetriever, tSec: Double): IntArray? {
        var bmp = mmr.getScaledFrameAtTime(
            (tSec * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, SIZE, SIZE
        ) ?: return null
        if (bmp.width != SIZE || bmp.height != SIZE) {
            bmp = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        }
        val px = IntArray(SIZE * SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return px
    }

    private fun interp(t: Double, tg: DoubleArray, v: DoubleArray): Double {
        val last = tg.size - 1
        if (t <= tg[0]) return v[0]
        if (t >= tg[last]) return v[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (tg[mid] <= t) lo = mid else hi = mid
        }
        val f = (t - tg[lo]) / (tg[hi] - tg[lo])
        return v[lo] + f * (v[hi] - v[lo])
    }

    /** Least-squares fit of picture shift against gyro rotation for one candidate time offset. */
    private fun fitAt(
        t0: Double, tA: DoubleArray, tB: DoubleArray, dx: DoubleArray, dy: DoubleArray,
        tg: DoubleArray, cum: Array<DoubleArray>
    ): Fit {
        val n = tA.size
        val th = Array(n) { i ->
            DoubleArray(3) { a -> interp(tB[i] + t0, tg, cum[a]) - interp(tA[i] + t0, tg, cum[a]) }
        }
        val ata = Array(3) { DoubleArray(3) }
        val atx = DoubleArray(3)
        val aty = DoubleArray(3)
        for (i in 0 until n) {
            for (r in 0..2) {
                atx[r] += th[i][r] * dx[i]
                aty[r] += th[i][r] * dy[i]
                for (c in 0..2) ata[r][c] += th[i][r] * th[i][c]
            }
        }
        val tr = (ata[0][0] + ata[1][1] + ata[2][2]) / 3.0
        for (r in 0..2) ata[r][r] += 1e-3 * tr + 1e-12
        val ax = solve3(ata, atx)
        val ay = solve3(ata, aty)
        var rss = 0.0
        for (i in 0 until n) {
            var ex = 0.0
            var ey = 0.0
            for (c in 0..2) {
                ex += ax[c] * th[i][c]
                ey += ay[c] * th[i][c]
            }
            rss += (dx[i] - ex) * (dx[i] - ex) + (dy[i] - ey) * (dy[i] - ey)
        }
        return Fit(rss, ax, ay)
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = Array(3) { r -> doubleArrayOf(a[r][0], a[r][1], a[r][2], b[r]) }
        for (col in 0..2) {
            var piv = col
            for (r in col + 1..2) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            val tmp = m[col]
            m[col] = m[piv]
            m[piv] = tmp
            val d = m[col][col]
            if (abs(d) < 1e-18) return DoubleArray(3)
            for (r in 0..2) {
                if (r != col) {
                    val f = m[r][col] / d
                    for (c in col..3) m[r][c] -= f * m[col][c]
                }
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    /** Zero-lag Gaussian smoothing; ends are mirrored point-wise so straight drifts are not bent. */
    private fun smooth(p: DoubleArray, sigma: Double, step: Double): DoubleArray {
        val half = ceil(3.0 * sigma / step).toInt()
        val w = DoubleArray(2 * half + 1) { exp(-0.5 * ((it - half) * step / sigma).pow(2)) }
        val sum = w.sum()
        val last = p.size - 1
        return DoubleArray(p.size) { i ->
            var acc = 0.0
            for (k in -half..half) {
                val idx = i + k
                val v = when {
                    idx < 0 -> 2 * p[0] - p[min(-idx, last)]
                    idx > last -> 2 * p[last] - p[max(2 * last - idx, 0)]
                    else -> p[idx]
                }
                acc += w[k + half] * v
            }
            acc / sum
        }
    }
}
