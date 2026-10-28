package com.hbkbeats.cinecam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import kotlin.math.roundToInt

/** One selectable camera as the phone actually exposes it. Built from real characteristics. */
data class Lens(
    val id: String,
    val label: String,
    val isFront: Boolean,
    val manualSensor: Boolean,
    val isoMin: Int,
    val isoMax: Int,
    val expMinNs: Long,
    val expMaxNs: Long,
    val minFocusDiopters: Float,
    val awbModes: List<Int>
)

object LensCatalog {

    fun list(context: Context): List<Lens> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val out = mutableListOf<Lens>()
        val ids = try { cm.cameraIdList } catch (e: Exception) { return emptyList() }

        for (id in ids) {
            try {
                val c = cm.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING)
                val front = facing == CameraCharacteristics.LENS_FACING_FRONT
                if (!front && facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull() ?: 0f
                val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val mp = if (px != null) (px.width.toLong() * px.height / 1_000_000.0).roundToInt() else 0
                val label = when {
                    front -> "Front ${mp}MP"
                    focal > 8f -> "Tele"
                    focal in 0.1f..3.0f -> "Ultra"
                    else -> "Wide"
                }

                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                val manual = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
                val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val awb = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()

                out.add(
                    Lens(
                        id = id,
                        label = label,
                        isFront = front,
                        manualSensor = manual && iso != null && exp != null,
                        isoMin = iso?.lower ?: 100,
                        isoMax = iso?.upper ?: 800,
                        expMinNs = exp?.lower ?: 1_000_000L,
                        expMaxNs = exp?.upper ?: 33_000_000L,
                        minFocusDiopters = minFocus,
                        awbModes = awb
                    )
                )
            } catch (e: Exception) {
                // Skip a camera we cannot read; the Info report shows the details.
            }
        }
        return out.sortedWith(compareBy({ it.isFront }, { it.id }))
    }
}
