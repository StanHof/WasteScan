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

@Composable
fun CameraScreen(onHistoryClick: () -> Unit, onPhotoTaken: (Bitmap) -> Unit) {
    var isProcessing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ){ uri: Uri? ->
        uri?.let {
            isProcessing = true
            val bitmap = uriToBitmap(it, context)
            if(bitmap != null){
                val croppedBitmap = cropCenterSquare(bitmap)
                isProcessing = false
                onPhotoTaken(croppedBitmap)
            }
            else{
                isProcessing = false
            }
        }
    }
    WasteScannerTheme(dynamicColor = false){
    Box(modifier = Modifier.fillMaxSize()) {
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            val rectSize = size.width * 0.7f
            val left = (size.width - rectSize) / 2
            val top = (size.height - rectSize) / 2

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

        IconButton(
            onClick = onHistoryClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .statusBarsPadding() // Zabezpieczenie przed Notchem/Wcięciem w ekranie
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = "Historia skanów",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fraction = 0.2f)
                .height(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color = MaterialTheme.colorScheme.background)

        ) {

            Button(
                onClick = { galleryLauncher.launch("image/*") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary),
                shape = CircleShape,
                modifier = Modifier
                    .align(BiasAlignment(-0.7f, 0f))
                    .size(55.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = "Wybierz z galerii",
                )
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
                                isProcessing = false
                                onPhotoTaken(croppedBitmap)
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
                border = BorderStroke(5.dp , MaterialTheme.colorScheme.onPrimary),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {}
        }

    }
        if(isProcessing){
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ){
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(60.dp),
                    strokeWidth = 6.dp
                )
            }
        }
        }
}

