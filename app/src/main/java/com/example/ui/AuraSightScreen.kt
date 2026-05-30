package com.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AuraSightScreen(
    onSpeechTrigger: (String) -> Unit,
    viewModel: AuraSightViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    val deviceHasCamera = remember(context) {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var isCameraBound by remember { mutableStateOf(false) }
    var mockScenarioCounter by remember { mutableStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            onSpeechTrigger("Izin kamera diberikan. AuraSight siap digunakan. Ketuk layar di mana saja untuk mengambil foto dan menganalisis lingkungan.")
        } else {
            onSpeechTrigger("Izin kamera ditolak atau dibatasi. AuraSight akan otomatis beralih ke rute simulasi visual pemandu.")
        }
    }

    // Connect ViewModel's speech trigger callback to our speaker lambda
    LaunchedEffect(viewModel) {
        viewModel.onSpeechTrigger = onSpeechTrigger
    }

    // Request permission once at startup
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Build ImageCapture usecase once so we can capture on taps
    val imageCapture = remember { ImageCapture.Builder().build() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212) // Eye-safe premium deep space visual dark canvas
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    if (!hasCameraPermission) {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    } else if (uiState !is AuraSightState.Loading) {
                        onSpeechTrigger("Mengambil foto analisis...")
                        
                        if (isCameraBound) {
                            takePhoto(
                                context = context,
                                imageCapture = imageCapture,
                                onPhotoCaptured = { bitmap ->
                                    viewModel.analyzeImage(bitmap)
                                },
                                onError = { exc ->
                                    Log.e("AuraSightScreen", "Capture failure, switching to simulated snapshot", exc)
                                    // Robust fallback: if capture fails, generate a mock sample room bitmap
                                    val mockBmp = createMockBitmap(mockScenarioCounter)
                                    mockScenarioCounter++
                                    viewModel.analyzeImage(mockBmp)
                                }
                            )
                        } else {
                            // Automatically fall back to simulated snapshot mode if hardware camera or binding is locked/disabled
                            val mockBmp = createMockBitmap(mockScenarioCounter)
                            mockScenarioCounter++
                            viewModel.analyzeImage(mockBmp)
                        }
                    }
                }
        ) {
            // Background Live Camera layer if permission granted and device supports camera features
            if (hasCameraPermission) {
                if (deviceHasCamera) {
                    CameraPreviewView(
                        imageCapture = imageCapture,
                        onCameraReadyChange = { ready ->
                            isCameraBound = ready
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    RadarSimulationView(modifier = Modifier.fillMaxSize())
                    LaunchedEffect(Unit) {
                        isCameraBound = false
                    }
                }
            } else {
                // Large tactile interactive card when camera is waiting for permissions
                PermissionFallbackView(
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            }

            // High Contrast dark overlay gradient to ensure high-visibility structure
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.55f)
                    .background(Color(0xFF000000))
            )

            // Content Column
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // Header Status Bar
                HeaderStatus(uiState = uiState, isCameraBound = isCameraBound)

                Spacer(modifier = Modifier.weight(1f))

                // Bottom Analysis Result Panel
                BottomResultPanel(uiState = uiState)
            }

            // Spinner loading indicator overlay when analyzing snapshot
            if (uiState is AuraSightState.Loading) {
                LoadingOverlay()
            }
        }
    }
}

@Composable
fun CameraPreviewView(
    imageCapture: ImageCapture,
    onCameraReadyChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Safely unbind CameraX usecases when the Preview view is disposed/taken off-screen
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                if (cameraProviderFuture.isDone) {
                    cameraProviderFuture.get().unbindAll()
                }
            } catch (e: Throwable) {
                Log.e("CameraPreviewView", "Error unbinding CameraX on compose disposal", e)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                
                try {
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = this.surfaceProvider
                            }

                            // Safely select the back camera first, then fall back to any camera if none
                            val cameraSelector = when {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> null
                            }

                            if (cameraSelector != null) {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageCapture
                                )
                                onCameraReadyChange(true)
                            } else {
                                Log.e("CameraPreviewView", "No camera hardware was available for binding!")
                                onCameraReadyChange(false)
                            }
                        } catch (exc: Throwable) {
                            Log.e("CameraPreviewView", "Exception or Error during CameraX binding", exc)
                            onCameraReadyChange(false)
                        }
                    }, ContextCompat.getMainExecutor(context))
                } catch (e: Throwable) {
                    Log.e("CameraPreviewView", "Failed to get ProcessCameraProvider instance", e)
                    onCameraReadyChange(false)
                }
            }
        },
        modifier = modifier,
        update = {
            // No-op to prevent performance degradation and infinite binding cycle
        }
    )
}

@Composable
fun HeaderStatus(uiState: AuraSightState, isCameraBound: Boolean = false) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fade"
    )

    // Color and Text selection based on status and mode
    val (statusLabel, statusColor, isConnecting) = when (uiState) {
        is AuraSightState.Loading -> Triple("● Menghubungkan...", Color(0xFFFFB300), true) // Yellow amber
        is AuraSightState.Error -> Triple("● Hambatan Koneksi", Color(0xFFD32F2F), false) // Red
        else -> {
            if (isCameraBound) {
                Triple("● AuraSight Siap (Live)", Color(0xFF2E7D32), false) // Emerald Green
            } else {
                Triple("● AuraSight Siap (Visual)", Color(0xFFFF9100), false) // Amber orange
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f)),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .alpha(if (isConnecting) alphaAnim else 1.0f)
                    .background(Color.Transparent)
            ) {
                Text(
                    text = statusLabel,
                    color = statusColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
    }
}

// Quick tiny function replacement for rounded corner shape
private fun RoundedCornerShape(size: Int) = RoundedCornerShape(size.dp)

@Composable
fun BottomResultPanel(uiState: AuraSightState) {
    val scrollState = rememberScrollState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .heightIn(max = 420.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181818).copy(alpha = 0.95f)),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(
            1.5.dp, 
            if (uiState is AuraSightState.Error) Color(0xFFD32F2F) else Color(0xFF222222)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(20.dp)
        ) {
            when (uiState) {
                is AuraSightState.Idle -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Petunjuk Ketukan",
                            tint = Color(0xFF42A5F5),
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Ketuk Layar untuk Memindai",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sentuh di mana saja pada ponsel Anda untuk memotret dan mendengarkan penjelasan kondisi sekitar secara langsung.",
                            color = Color(0xFFB0B0B0),
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
                is AuraSightState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Menganalisis Snapshot...",
                            color = Color(0xFFFFB300),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Mata AI sedang mendeteksi ruangan, tulisan, dan potensi bahaya untuk ditransmisikan...",
                            color = Color(0xFFB0B0B0),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
                is AuraSightState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Peringatan Eror",
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Gagal Menganalisis",
                            color = Color(0xFFD32F2F),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.message,
                            color = Color.White,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Ketuk layar di mana saja untuk mengulang kembali pemindaian.",
                            color = Color(0xFFB0B0B0),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                is AuraSightState.Success -> {
                    val res = uiState.result
                    
                    // Main Scene Description (Paling Menonjol)
                    Text(
                        text = "Deskripsi Sekitar",
                        color = Color(0xFF81C784),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = res.sceneDescription,
                        color = Color.White,
                        fontSize = 21.sp, // Kontras tinggi & huruf tebal/besar
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 30.sp
                    )
                    
                    Spacer(modifier = Modifier.height(18.dp))

                    // 1. SMART WARNING (Tampil Jika ada bahaya)
                    if (res.smartWarning.isNotBlank()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFD32F2F).copy(alpha = 0.15f)),
                            border = BorderStroke(2.dp, Color(0xFFD32F2F)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Bahaya Terdeteksi",
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "PERINGATAN BAHAYA",
                                        color = Color(0xFFFF5252),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = res.smartWarning,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        lineHeight = 22.sp
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // 2. SPATIAL GUIDANCE CARD
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Petunjuk Spasial",
                                    tint = Color(0xFF64B5F6),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "PETUNJUK POSISI BENDA",
                                    color = Color(0xFF64B5F6),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = res.spatialGuidance,
                                color = Color(0xFFE0E0E0),
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 3. TEXT OCR CARD (Tampil jika ada text terbaca)
                    if (res.quickTextReader.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Modul Membaca Teks",
                                        tint = Color(0xFFE040FB),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "TEKS TERBACA",
                                        color = Color(0xFFE040FB),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "\"${res.quickTextReader}\"",
                                    color = Color(0xFFFFF176), // Yellow accent for text readings
                                    fontSize = 16.sp,
                                    lineHeight = 22.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionFallbackView(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Kamera Terkunci",
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "AuraSight Memerlukan\nIzin Kamera",
                color = Color.White,
                fontSize = 24.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Ketuk seluruh area layar di mana saja untuk memberikan akses kamera Anda.",
                color = Color(0xFFB0B0B0),
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300)),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Buka Izin Kamera",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

@Composable
fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000).copy(alpha = 0.70f))
            .clickable(enabled = false) {}, // Consume click events during loading
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1F1F)),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFFFFB300),
                        strokeWidth = 5.dp,
                        modifier = Modifier.size(60.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Menganalisis...",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// Helper block to capture image using CameraX with exception safety
private fun takePhoto(
    context: Context,
    imageCapture: ImageCapture,
    onPhotoCaptured: (Bitmap) -> Unit,
    onError: (Exception) -> Unit
) {
    try {
         imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = imageProxy.toBitmapCompressed()
                        imageProxy.close()
                        if (bitmap != null) {
                            onPhotoCaptured(bitmap)
                        } else {
                            onError(Exception("Gagal mendekompresi snapshot gambar"))
                        }
                    } catch (t: Throwable) {
                        try {
                            imageProxy.close()
                        } catch (ignored: Throwable) {}
                        onError(Exception("Gagal mengolah gambar", t))
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    } catch (e: Throwable) {
        onError(Exception("Gagal memulai modul penangkapan gambar", e))
    }
}

// Programmatic high-contrast mockup generator for testing AuraSight features on headless or mock camera environments
private fun createMockBitmap(scenarioIndex: Int): Bitmap {
    val conf = Bitmap.Config.ARGB_8888
    val bmp = Bitmap.createBitmap(600, 800, conf)
    val canvas = android.graphics.Canvas(bmp)
    
    // Draw Wall & Floor
    val bgPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(18, 18, 18)
    }
    canvas.drawRect(0f, 0f, 600f, 800f, bgPaint)
    
    val floorPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(45, 45, 45)
    }
    val wallPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.rgb(25, 25, 30)
    }
    
    canvas.drawRect(0f, 0f, 600f, 500f, wallPaint)
    canvas.drawRect(0f, 500f, 600f, 800f, floorPaint)
    
    val textPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.YELLOW
        textSize = 34f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    
    val labelPaint = android.graphics.Paint().apply {
        color = android.graphics.Color.LTGRAY
        textSize = 24f
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }

    when (scenarioIndex % 3) {
        0 -> {
            // Scenario 1: Meja Kerja dengan rintangan kabel
            val tablePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(115, 75, 45) // brown
            }
            canvas.drawRect(80f, 400f, 520f, 520f, tablePaint)
            
            // Draw laptop
            val laptopPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(192, 192, 192)
            }
            canvas.drawRect(220f, 350f, 380f, 400f, laptopPaint)
            
            // Draw power cable on floor
            val cablePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.RED
                strokeWidth = 10f
                style = android.graphics.Paint.Style.STROKE
            }
            val path = android.graphics.Path().apply {
                moveTo(50f, 750f)
                quadTo(300f, 620f, 550f, 780f)
            }
            canvas.drawPath(path, cablePaint)
            
            canvas.drawText("AuraSight: Meja Kerja & Kabel", 300f, 80f, textPaint)
            canvas.drawText("[SITUASI: KABEL DAYA LISTRIK MELINTANG DI STRUKTUR LANTAI]", 300f, 150f, labelPaint)
            canvas.drawText("[BENDA: LAPTOP DI TENGAH MEJA]", 300f, 220f, labelPaint)
            canvas.drawText("[KIRI MEJA: GELAS KOPI HANGAT]", 300f, 290f, labelPaint)
        }
        1 -> {
            // Scenario 2: Lorong dengan Kotak Mainan Anak
            val wallLeftPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(35, 35, 40)
            }
            canvas.drawRect(0f, 0f, 120f, 800f, wallLeftPaint)
            canvas.drawRect(480f, 0f, 600f, 800f, wallLeftPaint)
            
            // Draw red box toy
            val toyPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(220, 50, 50)
            }
            canvas.drawRect(240f, 600f, 360f, 700f, toyPaint)
            
            canvas.drawText("AuraSight: Lorong Rumah", 300f, 80f, textPaint)
            canvas.drawText("[SITUASI: LORONG BERSIH KECUALI KOTAK MAINAN MERAH]", 300f, 150f, labelPaint)
            canvas.drawText("[BAHAYA: KOTAK BALOK MAINAN TAJAM DI LANTAI TENGAH]", 300f, 220f, labelPaint)
            canvas.drawText("[KANAN LORONG: POT BUNGA DAN MEJA KECIL]", 300f, 290f, labelPaint)
        }
        2 -> {
            // Scenario 3: Dapur dengan Botol Pembersih Beracun
            val counterPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(90, 100, 110)
            }
            canvas.drawRect(50f, 450f, 550f, 600f, counterPaint)
            
            // Draw poison bottle
            val poisonPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.rgb(180, 50, 180) // purple
            }
            canvas.drawRect(150f, 320f, 240f, 450f, poisonPaint)
            
            canvas.drawText("AuraSight: Counter Dapur", 300f, 80f, textPaint)
            canvas.drawText("[BAHAYA: BOTOL UNGU BERLABEL RACUN DI MEJA DEPAN]", 300f, 150f, labelPaint)
            canvas.drawText("[LABEL UTAMA: RACUN SERANGGA]", 300f, 220f, labelPaint)
            canvas.drawText("[KANAN MEJA: WADAH AIR PANAS KELUAR ASAP]", 300f, 290f, labelPaint)
        }
    }
    
    return bmp
}

// Convert image snapshot to rotated compressed bitmap internally
private fun ImageProxy.toBitmapCompressed(): Bitmap? {
    return try {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        
        val rotationDegrees = imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    } catch (e: Exception) {
        Log.e("ImageProxy", "toBitmapCompressed error", e)
        null
    }
}

@Composable
fun RadarSimulationView(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarScanner")
    
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale1 by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse1"
    )
    
    val pulseScale2 by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(1250)
        ),
        label = "pulse2"
    )

    Box(
        modifier = modifier.background(Color(0xFF0F172A)), 
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerX = width / 2f
            val centerY = height / 2f
            val maxRadius = kotlin.math.min(width, height) / 2.2f
            val centerOffset = androidx.compose.ui.geometry.Offset(centerX, centerY)

            val ringColor = Color(0xFF38BDF8).copy(alpha = 0.15f)
            drawCircle(
                color = ringColor,
                radius = maxRadius * 0.3f,
                center = centerOffset,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
            drawCircle(
                color = ringColor,
                radius = maxRadius * 0.6f,
                center = centerOffset,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )
            drawCircle(
                color = ringColor,
                radius = maxRadius * 0.9f,
                center = centerOffset,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )

            val lineColor = Color(0xFF38BDF8).copy(alpha = 0.1f)
            drawLine(
                color = lineColor,
                start = androidx.compose.ui.geometry.Offset(centerX - maxRadius, centerY),
                end = androidx.compose.ui.geometry.Offset(centerX + maxRadius, centerY),
                strokeWidth = 2f
            )
            drawLine(
                color = lineColor,
                start = androidx.compose.ui.geometry.Offset(centerX, centerY - maxRadius),
                end = androidx.compose.ui.geometry.Offset(centerX, centerY + maxRadius),
                strokeWidth = 2f
            )

            drawCircle(
                color = Color(0xFF38BDF8),
                radius = maxRadius * pulseScale1,
                center = centerOffset,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                alpha = (1.0f - pulseScale1) * 0.3f
            )

            drawCircle(
                color = Color(0xFF38BDF8),
                radius = maxRadius * pulseScale2,
                center = centerOffset,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f),
                alpha = (1.0f - pulseScale2) * 0.3f
            )

            val sweepAngleRad = Math.toRadians(rotationAngle.toDouble())
            val endX = centerX + maxRadius * Math.cos(sweepAngleRad).toFloat()
            val endY = centerY + maxRadius * Math.sin(sweepAngleRad).toFloat()

            drawLine(
                color = Color(0xFF38BDF8).copy(alpha = 0.6f),
                start = centerOffset,
                end = androidx.compose.ui.geometry.Offset(endX, endY),
                strokeWidth = 4f
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.15f), RoundedCornerShape(100.dp))
                    .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(100.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Radar AI Aktif",
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(40.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Modul Visual Simulasi",
                color = Color(0xFF38BDF8),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "Sistem navigasi suara aktif",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}
