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
import com.example.wastescanner.ui.theme.PawCheckTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted -> if (isGranted) recreate() }

        setContent {
            PawCheckTheme {
                val context = LocalContext.current
                val hasPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    PawCheckNavigation()
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
fun PawCheckNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    var capturedImage by remember { mutableStateOf<Bitmap?>(null) }
    var historyReportToShow by remember { mutableStateOf<IngredientSafetyReport?>(null) }
    val historyItems = remember { mutableStateListOf<HistoryItem>() }

    var selectedEngine by remember { mutableStateOf(AnalysisEngine.LOCAL_SMALL) }
    // Zdjęcie wybrane z galerii, czekające na przycięcie (pełna rozdzielczość, przed kadrowaniem).
    var pendingCropBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Hoisted here (above NavHost) zamiast wewnątrz composable("result_screen") - ten blok jest
    // częścią kompozycji, która żyje przez cały czas trwania sesji (PawCheckNavigation nie jest
    // usuwana z kompozycji podczas nawigacji między ekranami, w przeciwieństwie do zawartości
    // poszczególnych composable("route") { ... }). Gdyby remember() był w środku bloku
    // "result_screen", oba interpretery TFLite (EAST + CRNN) byłyby wczytywane z assets od nowa
    // przy KAŻDYM wejściu na ten ekran (każde nowe zdjęcie, każdy podgląd z historii).
    //
    // Obie implementacje (lokalna i chmurowa) są trzymane jednocześnie, a nie tylko ta aktualnie
    // wybrana - potrzebne dla BenchmarkScreen, które uruchamia OBIE na tym samym zdjęciu do
    // porównania (Faza 5 planu). Dzięki temu też przełączenie trybu lokalny/chmura w ogóle nie
    // wymaga już przebudowy klasyfikatora - obie instancje po prostu już istnieją.
    // Silnik lokalny: PaddleOCR (PP-OCRv6_small, ONNX Runtime) zamiast własnego EAST+CRNN -
    // decyzja podjęta po ocenie wykonalności (docs/paddleocr_evaluation.md): ~4% recall własnego
    // pipeline'u vs ~81% dla PaddleOCR na tym samym zestawie testowym. TfliteTextRecognizerEngine
    // (EAST+CRNN) zostaje w kodzie nietknięty - dalej dostępny do porównań/dalszej pracy, po prostu
    // nie jest już domyślnie aktywny.
    val localClassifier = remember {
        LocalIngredientClassifier(context, PaddleOcrTextRecognizerEngine(context))
    }
    // Wariant "medium" (PP-OCRv6_medium, ~138 MB) wczytywany LENIWIE - dopiero gdy użytkownik
    // wybierze "Lokalny M" albo wejdzie na BenchmarkScreen, żeby nie kosztował pamięci/czasu
    // startu przy normalnym skanowaniu.
    val localMediumClassifier by remember {
        lazy {
            LocalIngredientClassifier(
                context,
                PaddleOcrTextRecognizerEngine(context, modelDir = PaddleOcrTextRecognizerEngine.MEDIUM_MODEL_DIR)
            )
        }
    }
    val cloudClassifier = remember { CloudIngredientClassifier(context) }
    val activeClassifier: ClassifierStrategy = when (selectedEngine) {
        AnalysisEngine.LOCAL_SMALL -> localClassifier
        AnalysisEngine.LOCAL_MEDIUM -> localMediumClassifier
        AnalysisEngine.GEMINI -> cloudClassifier
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
                engine = selectedEngine,
                onEngineChange = { selectedEngine = it },
                onHistoryClick = { navController.navigate("history_screen") },
                onBenchmarkClick = { navController.navigate("benchmark_screen") },
                onPhotoTaken = { bitmap ->
                    capturedImage = bitmap
                    historyReportToShow = null
                    navController.navigate("result_screen")
                },
                onGalleryPhotoPicked = { bitmap ->
                    pendingCropBitmap = bitmap
                    navController.navigate("crop_screen")
                }
            )
        }

        composable("crop_screen") {
            val source = pendingCropBitmap
            if (source == null) {
                // Brak zdjęcia (np. odtworzenie ekranu po utracie stanu) - wracamy do aparatu.
                // Warunek na bieżący cel jest konieczny: po zatwierdzeniu/anulowaniu kadru ten
                // blok jest jeszcze przez chwilę komponowany (animacja wyjścia), gdy nawigacja
                // jest już na innym ekranie - bez sprawdzenia zdjęlibyśmy wtedy ekran wyniku.
                LaunchedEffect(Unit) {
                    if (navController.currentBackStackEntry?.destination?.route == "crop_screen") {
                        navController.popBackStack()
                    }
                }
            } else {
                CropScreen(
                    bitmap = source,
                    engine = selectedEngine,
                    onEngineChange = { selectedEngine = it },
                    onCancel = {
                        pendingCropBitmap = null
                        navController.popBackStack()
                    },
                    onConfirm = { cropped ->
                        pendingCropBitmap = null
                        capturedImage = cropped
                        historyReportToShow = null
                        // Wyjście z wyniku wraca do aparatu, nie do ekranu kadrowania.
                        navController.navigate("result_screen") { popUpTo("camera_screen") }
                    }
                )
            }
        }

        composable("benchmark_screen") {
            BenchmarkScreen(
                localClassifier = localClassifier,
                localMediumClassifier = localMediumClassifier,
                cloudClassifier = cloudClassifier,
                onBack = { navController.popBackStack() }
            )
        }

        composable("result_screen") {
            ResultScreen(
                bitmap = capturedImage,
                initialReport = historyReportToShow,
                classifier = activeClassifier,
                engine = selectedEngine,
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
                    exportLauncher.launch("pawcheck_historia_${System.currentTimeMillis()}.csv")
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}