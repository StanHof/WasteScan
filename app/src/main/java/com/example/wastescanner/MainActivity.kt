package com.example.wastescanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
            WasteScannerTheme {
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
    val context = LocalContext.current
    val navController = rememberNavController()
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var historyReportToShow by remember { mutableStateOf<AnalysisReport?>(null) }
    val historyItems = remember { mutableStateListOf<HistoryItem>() }

    // NOWOŚĆ: Globalny stan wybranego modelu (domyślnie lokalny)
    var isCloudModeSelected by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val savedHistory = StorageManager.loadHistory(context)
        historyItems.addAll(savedHistory)
    }

    NavHost(
        navController = navController,
        startDestination = "camera_screen",
        enterTransition = { fadeIn(animationSpec = tween(400)) },
        exitTransition = { fadeOut(animationSpec = tween(400)) },
        popEnterTransition = { fadeIn(animationSpec = tween(400)) },
        popExitTransition = { fadeOut(animationSpec = tween(400)) }
    ) {
        composable("camera_screen") {
            CameraScreen(
                isCloudMode = isCloudModeSelected,
                onModeChange = { isCloudModeSelected = it }, // Zapisujemy wybór użytkownika
                onHistoryClick = { navController.navigate("history_screen") },
                onPhotoTaken = { bitmap ->
                    capturedImage = bitmap
                    historyReportToShow = null
                    navController.navigate("result_screen")
                }
            )
        }

        composable("result_screen") {
            val activeClassifier: ClassifierStrategy = remember(isCloudModeSelected) {
                if (isCloudModeSelected) CloudWasteClassifier() else LocalWasteClassifier(context)
            }

            ResultScreen(
                bitmap = capturedImage,
                initialReport = historyReportToShow,
                classifier = activeClassifier,
                onSaveToHistory = { label, confidence, date, report ->
                    val timestamp = System.currentTimeMillis()
                    val savedImagePath = capturedImage?.let {
                        StorageManager.saveBitmap(context, it, "scan_$timestamp")
                    }
                    val newItem = HistoryItem(
                        id = timestamp,
                        label = label,
                        confidence = confidence,
                        dateString = date,
                        imagePath = savedImagePath,
                        analysisReport = report
                    )
                    historyItems.add(newItem)
                    StorageManager.saveHistory(context, historyItems)
                },
                onTryAgain = {
                    capturedImage = null
                    historyReportToShow = null
                    navController.popBackStack()
                }
            )
        }

        composable("history_screen") {
            ScanHistoryScreen(
                historyList = historyItems,
                onItemClick = { item ->
                    capturedImage = StorageManager.loadBitmap(item.imagePath)
                    historyReportToShow = item.analysisReport
                    navController.navigate("result_screen")
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}