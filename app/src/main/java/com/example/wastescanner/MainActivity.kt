package com.example.wastescanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    var historyReportToShow by remember { mutableStateOf<IngredientSafetyReport?>(null) }
    val historyItems = remember { mutableStateListOf<HistoryItem>() }

    var isCloudModeSelected by remember { mutableStateOf(false) }

    // Hoisted here (above NavHost) zamiast wewnątrz composable("result_screen") - ten blok jest
    // częścią kompozycji, która żyje przez cały czas trwania sesji (WasteAppNavigation nie jest
    // usuwana z kompozycji podczas nawigacji między ekranami, w przeciwieństwie do zawartości
    // poszczególnych composable("route") { ... }). Gdyby remember() był w środku bloku
    // "result_screen", oba interpretery TFLite (EAST + CRNN) byłyby wczytywane z assets od nowa
    // przy KAŻDYM wejściu na ten ekran (każde nowe zdjęcie, każdy podgląd z historii) - tutaj
    // wczytują się tylko raz na sesję i ponownie wyłącznie przy realnej zmianie trybu lokalny/chmura.
    val activeClassifier: ClassifierStrategy = remember(isCloudModeSelected) {
        if (isCloudModeSelected) CloudIngredientClassifier(context) else LocalIngredientClassifier(
            context,
            TfliteTextRecognizerEngine(context, EastTextRegionDetector(context))
        )
    }

    val database = remember { AppDatabase.getDatabase(context) }
    val dao = database.historyDao()
    val coroutineScope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val csvData = StorageManager.generateCsvData(historyItems)
                    context.contentResolver.openOutputStream(it)?.use { stream ->
                        stream.write(csvData.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    LaunchedEffect(Unit) {
        val savedHistory = dao.getAllHistory()
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
            ResultScreen(
                bitmap = capturedImage,
                initialReport = historyReportToShow,
                classifier = activeClassifier,
                onSaveToHistory = { date, report ->
                    val savedImagePath = capturedImage?.let {
                        StorageManager.saveBitmap(context, it, "scan_${System.currentTimeMillis()}")
                    }
                    val newItem = HistoryItem(
                        dateString = date,
                        imagePath = savedImagePath,
                        report = report
                    )
                    historyItems.add(0, newItem)
                    coroutineScope.launch {
                        dao.insertItem(newItem)
                    }
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
                    historyReportToShow = item.report
                    navController.navigate("result_screen")
                },
                onExportClick = { // --- NOWOŚĆ: Akcja wywołująca zapis ---
                    // Uruchamiamy systemowe okno z domyślną nazwą pliku
                    exportLauncher.launch("badania_odpady_${System.currentTimeMillis()}.csv")
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}