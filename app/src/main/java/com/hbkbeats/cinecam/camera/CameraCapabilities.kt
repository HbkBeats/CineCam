package com.hbkbeats.cinecam.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.os.Build

/**
 * Reads what the phone's camera HAL actually exposes to third-party apps.
 * Every query is wrapped so one failing camera ID never breaks the report.
 */
object CameraCapabilities {

    private val capabilityNames = mapOf(
        0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING",
        3 to "RAW", 4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS",
        6 to "BURST_CAPTURE", 7 to "YUV_REPROCESSING", 8 to "DEPTH_OUTPUT",
        9 to "CONSTRAINED_HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING", 11 to "LOGICAL_MULTI_CAMERA",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR", 18 to "DYNAMIC_RANGE_TEN_BIT",
        19 to "STREAM_USE_CASE", 20 to "COLOR_SPACE_PROFILES"
    )

    fun report(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

        runCatching {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            sb.appendLine("Gyroscope: ${gyro?.name ?: "none"}, min delay ${gyro?.minDelay} us")
        }

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { cm.cameraIdList.toList() }.getOrElse {
            return sb.appendLine("Could not list cameras: ${it.message}").toString()
        }
        sb.appendLine("Camera IDs exposed to apps: $ids")

        for (id in ids) {
            sb.appendLine()
            sb.appendLine("=== Camera $id ===")
            val c = try {
                cm.getCameraCharacteristics(id)
            } catch (e: Exception) {
                sb.appendLine("  unreadable: ${e.message}")
                continue
            }
            line(sb, "Facing") {
                when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external/unknown"
                }
            }
            line(sb, "Hardware level") {
                when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                    else -> "unknown"
                }
            }
            line(sb, "Capabilities") {
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.joinToString { capabilityNames[it] ?: "#$it" }
            }
            line(sb, "Physical camera IDs") { c.physicalCameraIds.toString() }
            line(sb, "Focal lengths (mm)") {
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString()
            }
            line(sb, "Pixel array") {
                c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toString()
            }
            line(sb, "Sensor orientation (deg)") {
                c.get(CameraCharacteristics.SENSOR_ORIENTATION)?.toString()
            }
            line(sb, "Frame timestamp source") {
                when (c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME (same clock as gyro)"
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> "UNKNOWN (gyro sync is harder)"
                    else -> "n/a"
                }
            }
            line(sb, "ISO range") {
                c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.toString()
            }
            line(sb, "Exposure time range (ns)") {
                c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.toString()
            }
            line(sb, "Exposure comp range / step") {
                "${c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)} / " +
                    "${c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)}"
            }
            line(sb, "Min focus distance (diopters)") {
                c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.toString()
            }
            line(sb, "AF modes") {
                c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString()
            }
            line(sb, "AWB modes") {
                c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.joinToString()
            }
            line(sb, "Max digital zoom") {
                c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.toString()
            }
            line(sb, "OIS modes (0=off,1=on)") {
                c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.joinToString()
            }
            line(sb, "OIS data modes (0=off,1=on)") {
                c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES)?.joinToString()
            }
            line(sb, "Video stabilization (0=off,1=on,2=preview)") {
                c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString()
            }
            line(sb, "AE target FPS ranges") {
                c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString()
            }
            if (Build.VERSION.SDK_INT >= 33) {
                line(sb, "Dynamic range profiles (bitmask ids)") {
                    c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                        ?.supportedProfiles?.joinToString()
                }
                line(sb, "Stream use cases") {
                    c.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)?.joinToString()
                }
            }
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            line(sb, "Largest video sizes") {
                map?.getOutputSizes(MediaRecorder::class.java)
                    ?.sortedByDescending { it.width.toLong() * it.height }
                    ?.take(6)?.joinToString { "${it.width}x${it.height}" }
            }
            line(sb, "High-speed video sizes and FPS") {
                val m = map
                if (m == null) null else
                    m.highSpeedVideoSizes.take(3).joinToString("; ") { s ->
                        "${s.width}x${s.height} = " + m.getHighSpeedVideoFpsRangesFor(s).joinToString()
                    }
            }
            line(sb, "Largest JPEG sizes") {
                map?.getOutputSizes(android.graphics.ImageFormat.JPEG)
                    ?.sortedByDescending { it.width.toLong() * it.height }
                    ?.take(3)?.joinToString { "${it.width}x${it.height}" }
            }
        }
        return sb.toString()
    }

    private inline fun line(sb: StringBuilder, label: String, block: () -> String?) {
        val value = runCatching { block() }.getOrElse { "error: ${it.message}" }
        sb.appendLine("  $label: ${value ?: "n/a"}")
    }
}
