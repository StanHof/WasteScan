package com.example.wastescanner
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.wastescanner.ui.theme.WasteScannerTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@Composable
fun CameraScreen(
    isCloudMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onHistoryClick: () -> Unit,
    onPhotoTaken: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val coroutineScope = rememberCoroutineScope()
    var isProcessing by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            isProcessing = true
            val bitmap = uriToBitmap(it, context)
            if (bitmap != null) {
                val croppedBitmap = cropCenterSquare(bitmap)
                onPhotoTaken(croppedBitmap)
                coroutineScope.launch {
                    kotlinx.coroutines.delay(500)
                    isProcessing = false
                }
            } else {
                isProcessing = false
            }
        }
    }

    WasteScannerTheme(dynamicColor = false) {
        Box(modifier = Modifier.fillMaxSize()) {
            // --- 1. PODGLĄD Z KAMERY ---
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val executor = ContextCompat.getMainExecutor(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                            )
                        } catch (exc: Exception) { exc.printStackTrace() }
                    }, executor)
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // --- 2. CELOWNIK NA EKRANIE ---
            Canvas(modifier = Modifier.fillMaxSize()) {
                val rectSize = 900f
                val left = (size.width - rectSize) / 2
                val top = (size.height - rectSize) / 2 - 100f

                val backgroundPath = androidx.compose.ui.graphics.Path().apply {
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                }
                val cutoutPath = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            rect = androidx.compose.ui.geometry.Rect(left, top, left + rectSize, top + rectSize),
                            cornerRadius = CornerRadius(32f, 32f)
                        )
                    )
                }
                val combinedPath = androidx.compose.ui.graphics.Path().apply {
                    op(backgroundPath, cutoutPath, androidx.compose.ui.graphics.PathOperation.Difference)
                }

                drawPath(path = combinedPath, color = Color.Black.copy(alpha = 0.6f))
                drawRoundRect(
                    color = Color.Gray.copy(alpha = 0.8f),
                    topLeft = Offset(left, top),
                    size = Size(rectSize, rectSize),
                    cornerRadius = CornerRadius(32f, 32f),
                    style = Stroke(width = 6f)
                )
            }

            // --- NOWOŚĆ: PANEL WYBORU TRYBU BADANIA (NA GÓRZE PO LEWEJ) ---
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !isCloudMode,
                    onClick = { onModeChange(false) },
                    label = { Text("Lokalny (TFLite)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = isCloudMode,
                    onClick = { onModeChange(true) },
                    label = { Text("Chmura (API)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            // --- PRZYCISK HISTORII (NA GÓRZE PO PRAWEJ) ---
            IconButton(
                onClick = onHistoryClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .statusBarsPadding()
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Historia skanów",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            // --- 3. DOLNY PANEL INTERFEJSU ---
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(fraction = 0.2f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(bottom = 16.dp)
            ) {
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    modifier = Modifier
                        .align(BiasAlignment(-0.7f, 0f))
                        .size(56.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = "Galeria")
                }

                Button(
                    onClick = {
                        if (isProcessing) return@Button
                        isProcessing = true
                        val executor = ContextCompat.getMainExecutor(context)
                        imageCapture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val rotatedBitmap = rotateBitmap(image.toBitmap(), image.imageInfo.rotationDegrees)
                                    val croppedBitmap = cropCenterSquare(rotatedBitmap)
                                    onPhotoTaken(croppedBitmap)
                                    coroutineScope.launch {
                                        kotlinx.coroutines.delay(500)
                                        isProcessing = false
                                    }
                                    image.close()
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    isProcessing = false
                                    exception.printStackTrace()
                                }
                            })
                    },
                    modifier = Modifier
                        .size(80.dp)
                        .align(Alignment.Center),
                    shape = CircleShape,
                    border = BorderStroke(4.dp, Color.DarkGray),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) {}
            }

            // --- NAKŁADKA ŁADOWANIA ---
            if (isProcessing) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(60.dp),
                        strokeWidth = 6.dp
                    )
                }
            }
        }
    }
}