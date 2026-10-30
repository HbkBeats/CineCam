package com.hbkbeats.cinecam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hbkbeats.cinecam.camera.CameraCapabilities
import com.hbkbeats.cinecam.camera.LensCatalog
import com.hbkbeats.cinecam.diagnostics.GyroSyncLogger
import com.hbkbeats.cinecam.stabilize.GyroData
import com.hbkbeats.cinecam.stabilize.StabMode
import com.hbkbeats.cinecam.stabilize.Stabilizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // required look on Android 15 when targetSdk = 35
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = Color.Black,
                    contentColor = Color.White
                ) { AppRoot() }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var cameraOk by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> cameraOk = result[Manifest.permission.CAMERA] == true }

    if (cameraOk) {
        CameraScreen()
    } else {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("CineCam needs camera access to show the preview and take photos.")
            Spacer(Modifier.height(8.dp))
            Text("Microphone access is optional and only used for video sound.", fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }) { Text("Grant permissions") }
        }
    }
}

private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

/** Picks one exact camera by its Camera2 ID (this is how we reach the ultrawide or a second front camera). */
private fun selectorFor(id: String): CameraSelector =
    CameraSelector.Builder()
        .addCameraFilter(CameraFilter { infos ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == id }
        })
        .build()

private fun fmtShutter(ns: Long): String =
    if (ns >= 250_000_000L) "%.1fs".format(ns / 1e9) else "1/${(1e9 / ns).roundToInt()} s"

private val tightPadding = PaddingValues(horizontal = 10.dp)

@SuppressLint("MissingPermission") // RECORD_AUDIO is checked before use below
@Composable
private fun CameraScreen() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(ctx) }

    val lenses = remember { LensCatalog.list(ctx) }
    if (lenses.isEmpty()) {
        Text("No usable camera found.", Modifier.padding(24.dp))
        return
    }
    val initialLens = remember { lenses.firstOrNull { !it.isFront && it.label == "Wide" } ?: lenses.first() }
    var lens by remember { mutableStateOf(initialLens) }

    val controller = remember {
        LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
            cameraSelector = selectorFor(initialLens.id)
        }
    }
    LaunchedEffect(controller, owner) { controller.bindToLifecycle(owner) }

    var uhd by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var status by remember { mutableStateOf("Ready") }
    var report by remember { mutableStateOf<String?>(null) }

    // Stage 4a: OIS choice (0 = phone default, 1 = on, 2 = off) and the gyro sync test
    var ois by remember { mutableIntStateOf(0) }
    var gyroTest by remember { mutableStateOf(false) }
    var logging by remember { mutableStateOf(false) }
    val logger = remember { GyroSyncLogger(ctx) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    // Stage 4b: stabilize the last clip after recording
    var recordGyro by remember { mutableStateOf(true) }
    var lastVideoUri by remember { mutableStateOf<Uri?>(null) }
    var lastGyro by remember { mutableStateOf<GyroData?>(null) }
    var stabilizing by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()

    fun startStabilize(mode: StabMode) {
        val uri = lastVideoUri ?: return
        val gyro = lastGyro ?: return
        if (stabilizing) return
        stabilizing = true
        uiScope.launch {
            try {
                status = "Stabilizing: analysing clip..."
                val plan = withContext(Dispatchers.Default) {
                    Stabilizer.plan(ctx, uri, gyro, mode) { msg -> status = msg }
                }
                status = "Stabilizing: rendering 0%"
                val file = Stabilizer.export(ctx, uri, plan) { pct -> status = "Stabilizing: rendering $pct%" }
                status = "Saving..."
                val ok = withContext(Dispatchers.IO) { Stabilizer.saveToGallery(ctx, file, mode) }
                status = if (ok) "Stabilized video saved (${mode.label}): ${plan.summary}"
                else "Could not save the stabilized video"
            } catch (e: Exception) {
                status = "Stabilize failed: ${e.message}"
            } finally {
                stabilizing = false
            }
        }
    }

    // Manual controls state
    var showPro by remember { mutableStateOf(false) }
    var manualAe by remember { mutableStateOf(false) }
    var iso by remember { mutableFloatStateOf(100f) }
    var shutterT by remember { mutableFloatStateOf(0.5f) }
    var manualAf by remember { mutableStateOf(false) }
    var focusV by remember { mutableFloatStateOf(1f) }
    var wb by remember { mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO) }
    var evIndex by remember { mutableIntStateOf(0) }
    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }
    var evStep by remember { mutableFloatStateOf(0f) }

    // Derived manual values for the current lens
    val isoClamped = iso.coerceIn(lens.isoMin.toFloat(), lens.isoMax.toFloat())
    val expLo = maxOf(lens.expMinNs, 1L).toDouble()
    val expHi = maxOf(minOf(lens.expMaxNs, 100_000_000L), lens.expMinNs).toDouble()
    val shutterNs = (expLo * (expHi / expLo).pow(shutterT.toDouble())).toLong()
    val focusDiopters = lens.minFocusDiopters * (1f - focusV)

    fun applyManual() {
        val cc = controller.cameraControl ?: return
        val b = CaptureRequestOptions.Builder()
        if (manualAe && lens.manualSensor) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, isoClamped.roundToInt())
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
        }
        if (manualAf && lens.minFocusDiopters > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        }
        if (wb != CameraMetadata.CONTROL_AWB_MODE_AUTO) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb)
        }
        if (lens.hasOis && ois != 0) {
            b.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (ois == 1) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        try {
            // Replaces all earlier options, so switching back to Auto clears them.
            Camera2CameraControl.from(cc).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            status = "Manual control failed: ${e.message}"
        }
    }

    // After a lens change, wait for the new camera to open, then read its EV range.
    LaunchedEffect(lens.id) {
        delay(800)
        applyManual()
        val state = controller.cameraInfo?.exposureState
        if (state != null) {
            evMin = state.exposureCompensationRange.lower
            evMax = state.exposureCompensationRange.upper
            evStep = state.exposureCompensationStep.toFloat()
        }
    }
    LaunchedEffect(manualAe, iso, shutterT, manualAf, focusV, wb, ois) { applyManual() }
    // Tapping the preview would trigger autofocus and fight manual focus.
    LaunchedEffect(manualAf) { controller.isTapToFocusEnabled = !manualAf }
    // Gyro sync test: preview + video + analysis (no photos) at 1080p.
    LaunchedEffect(gyroTest) {
        if (gyroTest) {
            uhd = false
            controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            controller.setImageAnalysisAnalyzer(analysisExecutor, logger)
            controller.setEnabledUseCases(CameraController.VIDEO_CAPTURE or CameraController.IMAGE_ANALYSIS)
        } else {
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
            controller.clearImageAnalysisAnalyzer()
        }
    }
    LaunchedEffect(evIndex) { controller.cameraControl?.setExposureCompensationIndex(evIndex) }

    // Stop an active recording if this screen leaves composition.
    DisposableEffect(Unit) { onDispose { recording?.stop() } }

    LaunchedEffect(uhd) {
        val q = if (uhd) Quality.UHD else Quality.FHD
        controller.videoCaptureQualitySelector =
            QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q))
    }

    fun takePhoto() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "CineCam_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CineCam")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        controller.takePicture(opts, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) { status = "Photo saved" }
            override fun onError(e: ImageCaptureException) { status = "Photo failed: ${e.message}" }
        })
    }

    fun toggleRecording() {
        val active = recording
        if (active != null) { active.stop(); return }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "CineCam_${stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CineCam")
        }
        val opts = MediaStoreOutputOptions.Builder(
            ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()
        val audioOk = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (gyroTest || recordGyro) logging = logger.start(csv = gyroTest)
        recording = controller.startRecording(opts, AudioConfig.create(audioOk), mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start ->
                    status = if (audioOk) "Recording (audio ON)" else "Recording (audio OFF: no mic permission)"
                is VideoRecordEvent.Finalize -> {
                    val base = if (event.hasError()) "Video error code ${event.error}" else "Video saved"
                    lastVideoUri = if (event.hasError()) null else event.outputResults.outputUri
                    lastGyro = null
                    status = if (logging) {
                        logging = false
                        if (gyroTest) {
                            val oisName = listOf("default", "on", "off")[ois]
                            val msg = logger.stop("lens=${lens.id} ${lens.label},ois=$oisName,res=1080p", oisName)
                            lastGyro = logger.snapshot()
                            "$base. $msg"
                        } else {
                            lastGyro = logger.stopQuiet()
                            "$base. Gyro recorded. Tap Stabilize to smooth this clip."
                        }
                    } else base
                    recording = null
                }
                else -> {}
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { PreviewView(it).apply { this.controller = controller } }
        )

        Text(
            status,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            color = if (recording != null) Color.Red else Color.White
        )

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth()
        ) {
            if (showPro) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .background(Color(0xCC000000))
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Manual exposure", Modifier.weight(1f))
                        Switch(
                            checked = manualAe,
                            enabled = lens.manualSensor,
                            onCheckedChange = { manualAe = it }
                        )
                    }
                    if (manualAe) {
                        Text("ISO ${isoClamped.roundToInt()}", fontSize = 13.sp)
                        Slider(
                            value = isoClamped,
                            onValueChange = { iso = it },
                            valueRange = lens.isoMin.toFloat()..lens.isoMax.toFloat()
                        )
                        Text("Shutter ${fmtShutter(shutterNs)}", fontSize = 13.sp)
                        Slider(value = shutterT, onValueChange = { shutterT = it }, valueRange = 0f..1f)
                        Text(
                            "In video, a shutter slower than 1/30 s will lower the frame rate.",
                            fontSize = 11.sp
                        )
                    } else if (evMax > evMin) {
                        Text("Exposure compensation ${"%.1f".format(evIndex * evStep)} EV", fontSize = 13.sp)
                        Slider(
                            value = evIndex.toFloat(),
                            onValueChange = { evIndex = it.roundToInt() },
                            valueRange = evMin.toFloat()..evMax.toFloat(),
                            steps = (evMax - evMin - 1).coerceAtLeast(0)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Manual focus", Modifier.weight(1f))
                        Switch(
                            checked = manualAf,
                            enabled = lens.minFocusDiopters > 0f,
                            onCheckedChange = { manualAf = it }
                        )
                    }
                    if (lens.minFocusDiopters <= 0f) {
                        Text("This lens is fixed focus.", fontSize = 11.sp)
                    }
                    if (manualAf) {
                        val dist = if (focusDiopters < 0.05f) "infinity"
                        else "${(100f / focusDiopters).roundToInt()} cm"
                        Text("Focus distance: $dist", fontSize = 13.sp)
                        Slider(value = focusV, onValueChange = { focusV = it }, valueRange = 0f..1f)
                    }

                    Text("Optical stabilization (OIS)", fontSize = 13.sp)
                    if (lens.hasOis) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf("Default", "On", "Off").forEachIndexed { i, name ->
                                FilterChip(
                                    selected = ois == i,
                                    onClick = { ois = i },
                                    label = { Text(name) },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                    } else {
                        Text("This lens has no OIS.", fontSize = 11.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Record gyro (needed to stabilize)", Modifier.weight(1f))
                        Switch(
                            checked = recordGyro,
                            enabled = recording == null,
                            onCheckedChange = { recordGyro = it }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Gyro sync test (1080p)", Modifier.weight(1f))
                        Switch(
                            checked = gyroTest,
                            enabled = recording == null,
                            onCheckedChange = { gyroTest = it }
                        )
                    }
                    if (gyroTest) {
                        Text(
                            "Record about 10 s of handheld panning. A log file is saved to Download/CineCam. Photos are off in this mode.",
                            fontSize = 11.sp
                        )
                    }

                    Text("White balance", fontSize = 13.sp)
                    val wbOptions = listOf(
                        "Auto" to CameraMetadata.CONTROL_AWB_MODE_AUTO,
                        "Daylight" to CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT,
                        "Cloudy" to CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                        "Tungsten" to CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT,
                        "Fluorescent" to CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                    ).filter { it.second == CameraMetadata.CONTROL_AWB_MODE_AUTO || lens.awbModes.contains(it.second) }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        wbOptions.forEach { (name, mode) ->
                            FilterChip(
                                selected = wb == mode,
                                onClick = { wb = mode },
                                label = { Text(name) },
                                modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                }
            }

            // Stabilize the last clip
            if (lastVideoUri != null && lastGyro != null && recording == null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stabilize last clip:", fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                    StabMode.values().forEach { mode ->
                        OutlinedButton(
                            onClick = { startStabilize(mode) },
                            enabled = !stabilizing,
                            contentPadding = tightPadding,
                            modifier = Modifier.padding(end = 6.dp)
                        ) { Text(mode.label) }
                    }
                }
            }

            // Lens picker
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                lenses.forEach { l ->
                    FilterChip(
                        selected = l.id == lens.id,
                        enabled = recording == null,
                        onClick = {
                            if (l.id != lens.id) {
                                lens = l
                                manualAe = false
                                manualAf = false
                                wb = CameraMetadata.CONTROL_AWB_MODE_AUTO
                                evIndex = 0
                                ois = 0
                                controller.cameraSelector = selectorFor(l.id)
                            }
                        },
                        label = { Text(l.label) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { report = CameraCapabilities.report(ctx) },
                    contentPadding = tightPadding
                ) { Text("Info") }
                OutlinedButton(
                    enabled = recording == null && !gyroTest,
                    onClick = { uhd = !uhd },
                    contentPadding = tightPadding
                ) { Text(if (uhd) "4K" else "1080p") }
                OutlinedButton(
                    onClick = { showPro = !showPro },
                    contentPadding = tightPadding
                ) { Text("Pro") }
                Button(
                    onClick = { takePhoto() },
                    enabled = recording == null && !gyroTest,
                    contentPadding = tightPadding
                ) { Text("Photo") }
                Button(
                    onClick = { toggleRecording() },
                    enabled = !stabilizing,
                    contentPadding = tightPadding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (recording != null) Color.Red else MaterialTheme.colorScheme.primary
                    )
                ) { Text(if (recording != null) "Stop" else "Record") }
            }
        }
    }

    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Camera capabilities") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Close") } }
        )
    }
}
