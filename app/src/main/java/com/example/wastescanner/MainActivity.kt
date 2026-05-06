package com.example.wastescanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wastescanner.ui.theme.WasteScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted -> if (isGranted) recreate() }

        setContent {
            WasteScannerTheme(dynamicColor = false){
            val context = LocalContext.current
            val hasPermission = ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
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
}

@Composable
fun WasteAppNavigation() {
    val navController = rememberNavController()
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }

    NavHost(navController = navController, startDestination = "camera_screen") {
        composable("camera_screen") {
            // Funkcja CameraScreen jest teraz automatycznie pobierana z drugiego pliku!
            CameraScreen(onPhotoTaken = { bitmap ->
                capturedImage = bitmap
                navController.navigate("result_screen")
            })
        }

        composable("result_screen") {
            ResultScreen(
                bitmap = capturedImage,
                onTryAgain = {
                    capturedImage = null
                    navController.popBackStack()
                }
            )
        }
    }
}

@Composable
fun ResultScreen(bitmap: Bitmap?, onTryAgain: () -> Unit) {
    val context = LocalContext.current
    var classificationResult by remember { mutableStateOf("Analizowanie...") }

    // Inicjalizujemy i używamy naszej nowej, dedykowanej klasy!
    val classifier = remember { WasteClassifier(context) }

    LaunchedEffect(bitmap) {
        if (bitmap != null) {
            classificationResult = classifier.classify(bitmap)
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
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Zrobione zdjęcie",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f/4f)
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