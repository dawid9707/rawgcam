package com.example.rawgcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (!cameraGranted) {
            Toast.makeText(this, "Aparat wymaga uprawnień do działania", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        checkAndRequestPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF8AB4F8),
                    surface = Color(0xFF121212),
                    background = Color.Black
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GCamRawApp(cameraExecutor)
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

enum class CaptureMode(val label: String) {
    ZERO_PROCESSING("Zero ISP"),
    RAW_SENSOR("Direct RAW"),
    FAST_YUV("Fast Frame")
}

data class ShutterOption(val label: String, val nanos: Long)

val SHUTTER_SPEEDS = listOf(
    ShutterOption("Auto", -1L),
    ShutterOption("1/2000s", 500_000L),
    ShutterOption("1/1000s", 1_000_000L),
    ShutterOption("1/500s", 2_000_000L),
    ShutterOption("1/250s", 4_000_000L),
    ShutterOption("1/125s", 8_000_000L),
    ShutterOption("1/60s", 16_666_666L),
    ShutterOption("1/30s", 33_333_333L),
    ShutterOption("1/15s", 66_666_666L),
    ShutterOption("1/4s", 250_000_000L),
    ShutterOption("1s", 1_000_000_000L)
)

val ISO_OPTIONS = listOf(-1, 100, 200, 400, 800, 1600, 3200, 6400)

data class WbOption(val label: String, val mode: Int)

val WB_OPTIONS = listOf(
    WbOption("Auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
    WbOption("Słońce", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    WbOption("Chmury", CaptureRequest.CONTROL_AWB_MODE_CLOUDY),
    WbOption("Żarówka", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    WbOption("Fluoresc.", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT)
)

data class FocusOption(val label: String, val diopters: Float)

val FOCUS_OPTIONS = listOf(
    FocusOption("Auto", -1f),
    FocusOption("Makro", 10.0f),
    FocusOption("0.5m", 2.0f),
    FocusOption("1m", 1.0f),
    FocusOption("∞", 0.0f)
)

enum class ManualTab { ISO, SHUTTER, EV, WB, FOCUS }

@Composable
fun GCamRawApp(cameraExecutor: ExecutorService) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var selectedMode by remember { mutableStateOf(CaptureMode.ZERO_PROCESSING) }
    var isGridEnabled by remember { mutableStateOf(false) }
    var isFlashEnabled by remember { mutableStateOf(false) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    // Ustawienia Manualne Pro
    var isManualPanelOpen by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(ManualTab.ISO) }
    
    var selectedIso by remember { mutableIntStateOf(-1) } // -1 = Auto
    var selectedShutter by remember { mutableStateOf(SHUTTER_SPEEDS[0]) } // Auto
    var selectedEv by remember { mutableIntStateOf(0) } // 0 EV
    var selectedWb by remember { mutableStateOf(WB_OPTIONS[0]) } // Auto WB
    var selectedFocus by remember { mutableStateOf(FOCUS_OPTIONS[0]) } // Auto Focus

    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Przeładowanie aparatu przy zmianie parametrów manualnych
        key(lensFacing, selectedIso, selectedShutter, selectedEv, selectedWb, selectedFocus) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val previewBuilder = Preview.Builder()
                        val captureBuilder = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .setJpegQuality(100)

                        // Extender dla podglądu i przechwytywania kadrów
                        listOf(
                            Camera2Interop.Extender(previewBuilder),
                            Camera2Interop.Extender(captureBuilder)
                        ).forEach { extender ->
                            // 1. Zero ISP processing
                            extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                            extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                            extender.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                            extender.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
                            extender.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)

                            // 2. Ręczna Ekspozycja (ISO & Czas Naświetlania)
                            if (selectedIso != -1 || selectedShutter.nanos != -1L) {
                                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                                if (selectedIso != -1) {
                                    extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
                                }
                                if (selectedShutter.nanos != -1L) {
                                    extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter.nanos)
                                }
                            } else {
                                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, selectedEv)
                            }

                            // 3. Ręczny Balans Bieli (WB)
                            extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, selectedWb.mode)

                            // 4. Ręczna Ostrość (Focus)
                            if (selectedFocus.diopters >= 0f) {
                                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, selectedFocus.diopters)
                            } else {
                                extender.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                        }

                        val preview = previewBuilder.build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val imgCapture = captureBuilder.build()
                        imageCapture = imgCapture

                        val cameraSelector = CameraSelector.Builder()
                            .requireLensFacing(lensFacing)
                            .build()

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imgCapture
                            )
                        } catch (exc: Exception) {
                            Log.e("RawGCam", "Błąd inicjalizacji aparatu", exc)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isGridEnabled) {
            CameraGridOverlay()
        }

        // Górny pasek kontrolny
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 40.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { isFlashEnabled = !isFlashEnabled },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isFlashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                    contentDescription = "Flash Toggle",
                    tint = Color.White
                )
            }

            // Przycisk otwierania panelu Manual/Pro
            Surface(
                onClick = { isManualPanelOpen = !isManualPanelOpen },
                color = if (isManualPanelOpen) Color(0xFF8AB4F8) else Color(0xFF1E88E5).copy(alpha = 0.85f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = if (isManualPanelOpen) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isManualPanelOpen) "Zamknij PRO" else "Ustawienia PRO",
                        color = if (isManualPanelOpen) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            IconButton(
                onClick = { isGridEnabled = !isGridEnabled },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isGridEnabled) Icons.Default.GridOn else Icons.Default.GridOff,
                    contentDescription = "Grid Toggle",
                    tint = Color.White
                )
            }
        }

        // Dolny panel ustawień i migawki
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(bottom = 24.dp, top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Panel Manualny (Gdy otwarty)
            AnimatedVisibility(
                visible = isManualPanelOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    // Zakładki ustawień (ISO, Shutter, EV, WB, Focus)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        ManualTab.entries.forEach { tab ->
                            val isSelected = activeTab == tab
                            Text(
                                text = when(tab) {
                                    ManualTab.ISO -> "ISO (${if (selectedIso == -1) "Auto" else selectedIso})"
                                    ManualTab.SHUTTER -> "Czas (${selectedShutter.label})"
                                    ManualTab.EV -> "EV (${if (selectedEv > 0) "+$selectedEv" else selectedEv})"
                                    ManualTab.WB -> "WB (${selectedWb.label})"
                                    ManualTab.FOCUS -> "AF/MF (${selectedFocus.label})"
                                },
                                color = if (isSelected) Color(0xFF8AB4F8) else Color.LightGray,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFF8AB4F8).copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable { activeTab = tab }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = Color.DarkGray, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 6.dp))

                    // Opcje dla wybranej zakładki
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (activeTab) {
                            ManualTab.ISO -> {
                                items(ISO_OPTIONS) { iso ->
                                    val isSelected = selectedIso == iso
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedIso = iso },
                                        label = { Text(if (iso == -1) "Auto" else "$iso") }
                                    )
                                }
                            }
                            ManualTab.SHUTTER -> {
                                items(SHUTTER_SPEEDS) { shutter ->
                                    val isSelected = selectedShutter == shutter
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedShutter = shutter },
                                        label = { Text(shutter.label) }
                                    )
                                }
                            }
                            ManualTab.EV -> {
                                items((-3..3).toList()) { ev ->
                                    val isSelected = selectedEv == ev
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedEv = ev },
                                        label = { Text(if (ev > 0) "+$ev EV" else "$ev EV") }
                                    )
                                }
                            }
                            ManualTab.WB -> {
                                items(WB_OPTIONS) { wb ->
                                    val isSelected = selectedWb == wb
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedWb = wb },
                                        label = { Text(wb.label) }
                                    )
                                }
                            }
                            ManualTab.FOCUS -> {
                                items(FOCUS_OPTIONS) { focus ->
                                    val isSelected = selectedFocus == focus
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedFocus = focus },
                                        label = { Text(focus.label) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Przełącznik trybów przetwarzania
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                CaptureMode.entries.forEach { mode ->
                    val isSelected = selectedMode == mode
                    Text(
                        text = mode.label,
                        color = if (isSelected) Color(0xFF8AB4F8) else Color.Gray,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clickable { selectedMode = mode }
                    )
                }
            }

            // Przyciski migawki, podglądu i zmiany obiektywu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.DarkGray)
                        .clickable {
                            lastCapturedUri?.let {
                                Toast.makeText(context, "Zdjęcie zapisane w galerii", Toast.LENGTH_SHORT).show()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (lastCapturedUri != null) {
                        Icon(Icons.Default.Image, contentDescription = "Gallery", tint = Color.White)
                    } else {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Gallery Empty", tint = Color.Gray)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isCapturing) Color.Red else Color.White)
                        .clickable {
                            if (!isCapturing && imageCapture != null) {
                                isCapturing = true
                                takeUnprocessedPicture(
                                    context = context,
                                    imageCapture = imageCapture!!,
                                    executor = cameraExecutor,
                                    onCaptured = { uri ->
                                        isCapturing = false
                                        lastCapturedUri = uri
                                        Toast.makeText(context, "Zapisano bez ISP processing!", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { exc ->
                                        isCapturing = false
                                        Toast.makeText(context, "Błąd: ${exc.message}", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                )

                IconButton(
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.DarkGray.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun CameraGridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(width / 3, 0f),
            end = androidx.compose.ui.geometry.Offset(width / 3, height),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(2 * width / 3, 0f),
            end = androidx.compose.ui.geometry.Offset(2 * width / 3, height),
            strokeWidth = 1.dp.toPx()
        )

        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(0f, height / 3),
            end = androidx.compose.ui.geometry.Offset(width, height / 3),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = androidx.compose.ui.geometry.Offset(0f, 2 * height / 3),
            end = androidx.compose.ui.geometry.Offset(width, 2 * height / 3),
            strokeWidth = 1.dp.toPx()
        )
    }
}

private fun takeUnprocessedPicture(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    onCaptured: (Uri) -> Unit,
    onError: (Exception) -> Unit
) {
    val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "RAW_GCAM_$name.jpg")
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RawGCam")
        }
    }

    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                    uri?.let { targetUri ->
                        resolver.openOutputStream(targetUri)?.use { outputStream ->
                            outputStream.write(bytes)
                            outputStream.flush()
                        }
                        ContextCompat.getMainExecutor(context).execute {
                            onCaptured(targetUri)
                        }
                    } ?: run {
                        ContextCompat.getMainExecutor(context).execute {
                            onError(Exception("Nie udało się utworzyć pliku MediaStore"))
                        }
                    }
                } catch (e: Exception) {
                    ContextCompat.getMainExecutor(context).execute {
                        onError(e)
                    }
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                ContextCompat.getMainExecutor(context).execute {
                    onError(exception)
                }
            }
        }
    )
}
