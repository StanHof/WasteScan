package com.example.wastescanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted -> if (isGranted) recreate() }

        setContent {
            val context = LocalContext.current
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                // Uruchamiamy nasz nowy system nawigacji
                WasteAppNavigation()
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("Oczekuję na uprawnienia do aparatu...")
                }
                LaunchedEffect(Unit) {
                    requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }
}

// --- GŁÓWNA NAWIGACJA ---
@Composable
fun WasteAppNavigation() {
    val navController = rememberNavController()
    // Zmienna przechowująca zrobione zdjęcie w pamięci podręcznej
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }

    NavHost(navController = navController, startDestination = "camera_screen") {

        // EKRAN 1: APARAT
        composable("camera_screen") {
            CameraScreen(onPhotoTaken = { bitmap ->
                capturedImage = bitmap
                navController.navigate("result_screen") // Przejście do wyników
            })
        }

        // EKRAN 2: WYNIKI
        composable("result_screen") {
            ResultScreen(
                bitmap = capturedImage,
                onTryAgain = {
                    capturedImage = null
                    navController.popBackStack() // Powrót do aparatu
                }
            )
        }
    }
}

// --- EKRAN 1: KAMERA I CELOWNIK ---
@Composable
fun CameraScreen(onPhotoTaken: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Zamiast ImageAnalysis używamy ImageCapture (do robienia pojedynczych zdjęć)
    val imageCapture = remember { ImageCapture.Builder().build() }

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

        // RYSOWANIE CELOWNIKA (Canvas pozwala rysować kształty na ekranie)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rectSize = 700f
            val left = (size.width - rectSize) / 2
            val top = (size.height - rectSize) / 2

            // 1. Ścieżka pokrywająca cały ekran
            val backgroundPath = androidx.compose.ui.graphics.Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            }

            // 2. Ścieżka "wycięcia" (kształt naszego celownika)
            val cutoutPath = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect = androidx.compose.ui.geometry.Rect(left, top, left + rectSize, top + rectSize),
                        cornerRadius = CornerRadius(32f, 32f)
                    )
                )
            }

            // 3. Odjęcie wycięcia od tła (robimy dziurę w środku)
            val combinedPath = androidx.compose.ui.graphics.Path().apply {
                op(backgroundPath, cutoutPath, androidx.compose.ui.graphics.PathOperation.Difference)
            }

            // 4. Rysowanie przyciemnionego tła wokół dziury
            drawPath(
                path = combinedPath,
                color = Color.Black.copy(alpha = 0.6f) // Stopień przyciemnienia: 0.6 to 60%
            )

            // 5. Rysowanie samej ramki celownika
            drawRoundRect(
                color = Color.Green.copy(alpha = 0.8f),
                topLeft = Offset(left, top),
                size = Size(rectSize, rectSize),
                cornerRadius = CornerRadius(32f, 32f),
                style = Stroke(width = 6f) // Troszkę cieńsza ramka dla lepszego wyglądu
            )
        }

        // PRZYCISK ZDJĘCIA
        Button(
            onClick = {
                val executor = ContextCompat.getMainExecutor(context)
                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        // Pobieramy zdjęcie i odpowiednio je obracamy (aparaty w telefonach nagrywają bokiem)
                        val rotatedBitmap = rotateBitmap(image.toBitmap(), image.imageInfo.rotationDegrees)
                        onPhotoTaken(rotatedBitmap)
                        image.close() // Ważne, by zamknąć proces
                    }
                    override fun onError(exception: ImageCaptureException) {
                        exception.printStackTrace()
                    }
                })
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(80.dp), // Okrągły przycisk
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
        ) {
            // Wnętrze przycisku (możesz tu wstawić ikonę w przyszłości)
        }
    }
}

// --- EKRAN 2: WIDOK WYNIKÓW ---
@Composable
fun ResultScreen(bitmap: Bitmap?, onTryAgain: () -> Unit) {
    val context = LocalContext.current
    var classificationResult by remember { mutableStateOf("Analizowanie...") }

    // Uruchamiamy klasyfikację od razu po otwarciu tego ekranu
    LaunchedEffect(bitmap) {
        if (bitmap != null) {
            classificationResult = classifyImage(bitmap, context)
        } else {
            classificationResult = "Błąd: Brak zdjęcia"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (bitmap != null) {
            // Wyświetlamy zrobione zdjęcie
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Zrobione zdjęcie",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f/4f) // Zachowujemy proporcje
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = classificationResult,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onTryAgain) {
            Text("Skanuj kolejny odpad")
        }
    }
}

// --- FUNKCJE POMOCNICZE (MÓZG APLIKACJI) ---

fun classifyImage(bitmap: Bitmap, context: Context): String {
    return try {
        // 1. Wczytanie etykiet z pliku tekstowego
        val labels = context.assets.open("labels.txt").bufferedReader().readLines()

        // 2. Załadowanie Twojego modelu (PODMIEŃ NAZWĘ JEŚLI JEST INNA!)
        val model = FileUtil.loadMappedFile(context, "model.tflite")
        val interpreter = Interpreter(model)

        // 3. Zmiana rozmiaru zdjęcia (Teachable Machine zazwyczaj wymaga 224x224)
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
            .build()

        var tensorImage = TensorImage(interpreter.getInputTensor(0).dataType())
        tensorImage.load(bitmap)
        tensorImage = imageProcessor.process(tensorImage)

        // 4. Przygotowanie bufora na wyniki
        val outputTensor = interpreter.getOutputTensor(0)
        val probabilityBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), outputTensor.dataType())

        // 5. Klasyfikacja (Magia dzieje się tutaj!)
        interpreter.run(tensorImage.buffer, probabilityBuffer.buffer)

        // 6. Przeszukiwanie wyników, aby znaleźć ten z najwyższym procentem
        val probabilities = probabilityBuffer.floatArray
        var maxIdx = 0
        var maxProb = 0f

        for (i in probabilities.indices) {
            if (probabilities[i] > maxProb) {
                maxProb = probabilities[i]
                maxIdx = i
            }
        }

        // Teachable Machine często dodaje cyfry do nazw (np. "0 Plastik"), ta funkcja usuwa te cyfry
        val label = labels.getOrElse(maxIdx) { "Nieznany" }.replace(Regex("^\\d+\\s+"), "")
        val confidence = (maxProb * 100).toInt()

        // Zamykamy interpreter, aby nie zużywał pamięci
        interpreter.close()

        "$label - $confidence%"

    } catch (e: Exception) {
        "Błąd: ${e.message}"
    }
}

// Fizyczne aparaty często odczytują matrycę "bokiem", ta funkcja obraca obraz do pionu
fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}