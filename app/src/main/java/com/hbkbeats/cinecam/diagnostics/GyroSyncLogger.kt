package com.hbkbeats.cinecam.diagnostics

import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.hbkbeats.cinecam.stabilize.GyroData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic for Stage 4: while a video records, logs
 *  - gyroscope samples (timestamp, rad/s) at up to 200 Hz
 *  - per-frame timestamps plus a cheap image-shift estimate between frames
 * and saves a CSV to Download/CineCam. Claude then correlates gyro rotation with
 * image motion to measure the real gyro/frame time offset on this phone.
 * No images are stored, only numbers.
 */
class GyroSyncLogger(private val context: Context) : SensorEventListener, ImageAnalysis.Analyzer {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var logging = false
    private val gyroRows = StringBuilder()
    private val frameRows = StringBuilder()
    private var gyroCount = 0
    private var frameCount = 0
    private var csvOn = true
    private var gn = 0
    private var gt = LongArray(8192)
    private var gx = FloatArray(8192)
    private var gy = FloatArray(8192)
    private var gz = FloatArray(8192)
    private var prev: IntArray? = null
    private var prevTs = 0L
    @Volatile private var imgW = 0
    @Volatile private var imgH = 0
    @Volatile private var imgRotation = 0

    /** Returns false if the phone has no gyroscope or it could not be started. */
    fun start(csv: Boolean = true): Boolean {
        csvOn = csv
        gn = 0
        synchronized(frameRows) {
            frameRows.setLength(0)
            frameCount = 0
            prev = null
            prevTs = 0L
        }
        gyroRows.setLength(0)
        gyroCount = 0
        val g = gyro ?: return false
        logging = true
        return sm.registerListener(this, g, SAMPLING_US)
    }

    /** Stops logging and writes the CSV. Returns a short status message. */
    fun stop(header: String, tag: String): String {
        logging = false
        sm.unregisterListener(this)

        val frameText = synchronized(frameRows) { frameRows.toString() }
        val frames = synchronized(frameRows) { frameCount }

        val sb = StringBuilder()
        sb.append("# ").append(header)
            .append(",analysis=").append(imgW).append('x').append(imgH)
            .append(",step=").append(STEP)
            .append(",rotation=").append(imgRotation).append('\n')
        sb.append("# G,timestamp_ns,gx,gy,gz (rad/s)\n")
        sb.append("# F,timestamp_ns,dx_px,dy_px,arrival_minus_timestamp_ns,prev_timestamp_ns\n")
        sb.append(gyroRows)
        sb.append(frameText)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "CineCam_gyro_${tag}_$stamp.csv"
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CineCam")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                "Gyro log could not be created"
            } else {
                resolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
                "Gyro log saved to Download/CineCam ($gyroCount gyro samples, $frames frames)"
            }
        } catch (e: Exception) {
            "Gyro log failed: ${e.message}"
        }
    }

    /** Copy of the gyro samples recorded so far. */
    fun snapshot(): GyroData = GyroData(gt.copyOf(gn), gx.copyOf(gn), gy.copyOf(gn), gz.copyOf(gn))

    /** Stops gyro logging without writing a CSV and returns the samples. */
    fun stopQuiet(): GyroData {
        logging = false
        sm.unregisterListener(this)
        return snapshot()
    }

    // Gyroscope samples (runs on the main thread)
    override fun onSensorChanged(event: SensorEvent) {
        if (!logging) return
        if (gn == gt.size) {
            val cap = gt.size * 2
            gt = gt.copyOf(cap); gx = gx.copyOf(cap); gy = gy.copyOf(cap); gz = gz.copyOf(cap)
        }
        gt[gn] = event.timestamp
        gx[gn] = event.values[0]
        gy[gn] = event.values[1]
        gz[gn] = event.values[2]
        gn++
        if (csvOn) {
            gyroRows.append("G,").append(event.timestamp).append(',')
                .append(event.values[0]).append(',')
                .append(event.values[1]).append(',')
                .append(event.values[2]).append('\n')
        }
        gyroCount++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Camera frames (runs on a background thread)
    override fun analyze(image: ImageProxy) {
        try {
            if (!logging) return
            val arrival = SystemClock.elapsedRealtimeNanos()
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val w = image.width
            val h = image.height
            val cw = w / STEP
            val ch = h / STEP
            if (cw < 4 * RADIUS || ch < 4 * RADIUS) return

            // Downsample the brightness (Y) plane to a tiny grid.
            val cur = IntArray(cw * ch)
            for (y in 0 until ch) {
                val base = y * STEP * rowStride
                for (x in 0 until cw) {
                    cur[y * cw + x] = buf.get(base + x * STEP).toInt() and 0xFF
                }
            }
            imgW = w
            imgH = h
            imgRotation = image.imageInfo.rotationDegrees
            val ts = image.imageInfo.timestamp

            synchronized(frameRows) {
                val p = prev
                if (p != null && p.size == cur.size) {
                    val s = estimateShift(p, cur, cw, ch)
                    frameRows.append("F,").append(ts).append(',')
                        .append(s[0] * STEP).append(',')
                        .append(s[1] * STEP).append(',')
                        .append(arrival - ts).append(',')
                        .append(prevTs).append('\n')
                    frameCount++
                }
                prev = cur
                prevTs = ts
            }
        } finally {
            image.close()
        }
    }

    /**
     * Brute-force block match: finds (dx, dy) so that content at p(x-dx, y-dy)
     * appears at c(x, y), with sub-pixel refinement. Positive dx = scene moved right in the image.
     */
    private fun estimateShift(p: IntArray, c: IntArray, w: Int, h: Int): FloatArray {
        val r = RADIUS
        val n = 2 * r + 1
        val sad = LongArray(n * n)
        var best = Long.MAX_VALUE
        var bestIdx = 0
        for (j in 0 until n) {
            val dy = j - r
            for (i in 0 until n) {
                val dx = i - r
                var s = 0L
                for (y in r until h - r) {
                    val cRow = y * w
                    val pRow = (y - dy) * w
                    for (x in r until w - r) {
                        val d = c[cRow + x] - p[pRow + x - dx]
                        s += if (d < 0) -d else d
                    }
                }
                sad[j * n + i] = s
                if (s < best) {
                    best = s
                    bestIdx = j * n + i
                }
            }
        }
        val bj = bestIdx / n
        val bi = bestIdx % n
        val sx = if (bi in 1 until n - 1) refine(sad[bj * n + bi - 1], sad[bestIdx], sad[bj * n + bi + 1]) else 0f
        val sy = if (bj in 1 until n - 1) refine(sad[(bj - 1) * n + bi], sad[bestIdx], sad[(bj + 1) * n + bi]) else 0f
        return floatArrayOf((bi - r) + sx, (bj - r) + sy)
    }

    /** Parabola vertex offset from three SAD values around the minimum. */
    private fun refine(minus: Long, mid: Long, plus: Long): Float {
        val den = (minus - 2 * mid + plus).toFloat()
        return if (den <= 0f) 0f else 0.5f * (minus - plus).toFloat() / den
    }

    companion object {
        const val STEP = 8
        const val RADIUS = 5
        const val SAMPLING_US = 5000 // 200 Hz, the most allowed without the high-rate permission
    }
}
