package com.example.wastescanner

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Masowe porównanie local vs. cloud na stałym, zbundlowanym zestawie testowym (Faza 5 planu:
 * ewaluacja).
 *
 * Zestaw testowy (zdjęcia + manifest ground truth) jest częścią aplikacji -
 * app/src/main/assets/benchmark/manifest.json + app/src/main/assets/benchmark/<fileName z manifestu>
 * - a nie czymś wybieranym za każdym razem z galerii. Świadoma decyzja: dzięki temu zestaw jest
 * wersjonowany razem z kodem (odtwarzalny na każdym urządzeniu, do zacytowania w pracy jako
 * konkretny, ustalony zestaw ewaluacyjny), zamiast być ad-hoc wyborem z galerii za każdym razem.
 *
 * Przepływ: ekran wczytuje manifest przy starcie -> użytkownik klika "Uruchom benchmark" -> dla
 * KAŻDEGO wpisu manifestu wczytywane jest zdjęcie z assets, a potem uruchamiane SEKWENCYJNIE obie
 * strategie (LocalIngredientClassifier, CloudIngredientClassifier) na tej samej, jednakowo
 * przygotowanej bitmapie (ta sama ścieżka co normalny import z galerii: cropCenterSquare) -> wynik
 * trafia do listy na ekranie i może zostać wyeksportowany do CSV
 * (StorageManager.generateBenchmarkCsvData) do dalszej analizy poza aplikacją.
 *
 * Sekwencyjnie, nie równolegle - celowo: zapytania do Gemini i tak są ograniczone limitem/kosztem
 * API, a sekwencyjne wykonanie daje czyste, nienakładające się pomiary czasu per zdjęcie.
 */
@Composable
fun BenchmarkScreen(
    localClassifier: ClassifierStrategy,
    cloudClassifier: ClassifierStrategy,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var manifest by remember { mutableStateOf<List<BenchmarkGroundTruthEntry>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressDone by remember { mutableIntStateOf(0) }
    val results = remember { mutableStateListOf<BenchmarkResult>() }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Wczytanie manifestu z assets jest tanie (mały plik JSON) - robimy to raz, przy wejściu na
    // ekran, żeby użytkownik od razu widział ile zdjęć jest w zestawie, zanim cokolwiek uruchomi.
    LaunchedEffect(Unit) {
        manifest = StorageManager.loadBenchmarkManifest(context)
        if (manifest.isEmpty()) {
            errorMessage = "Nie znaleziono zestawu testowego. Dodaj app/src/main/assets/benchmark/manifest.json " +
                    "oraz odpowiadające mu zdjęcia i przebuduj aplikację."
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val csvData = StorageManager.generateBenchmarkCsvData(results)
                    context.contentResolver.openOutputStream(it)?.use { stream ->
                        stream.write(csvData.toByteArray())
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Powrót")
                    }
                    Text("Benchmark: Lokalny vs Chmura", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                "Zestaw testowy: ${manifest.size} zdjęć (app/src/main/assets/benchmark/manifest.json). " +
                        "Każde zostanie przeanalizowane OBOMA silnikami (lokalnym i chmurowym), a wynik - " +
                        "wraz z recall/precyzją względem oczekiwanych substancji - można wyeksportować do CSV.",
                fontSize = 13.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    isProcessing = true
                    progressDone = 0
                    results.clear()
                    errorMessage = null
                    coroutineScope.launch {
                        for (entry in manifest) {
                            try {
                                val bitmap = StorageManager.loadBenchmarkImage(context, entry.fileName)
                                if (bitmap != null) {
                                    val cropped = cropCenterSquare(bitmap)
                                    val localReport = localClassifier.analyzeIngredients(cropped)
                                    val cloudReport = cloudClassifier.analyzeIngredients(cropped)
                                    results.add(
                                        BenchmarkResult(
                                            imageLabel = entry.fileName,
                                            localReport = localReport,
                                            cloudReport = cloudReport,
                                            groundTruth = entry
                                        )
                                    )
                                } else {
                                    errorMessage = "Nie znaleziono zdjęcia '${entry.fileName}' w assets/benchmark/ - pomijam."
                                }
                            } catch (e: Exception) {
                                errorMessage = "Błąd przy przetwarzaniu '${entry.fileName}': ${e.localizedMessage}"
                            }
                            progressDone++
                        }
                        isProcessing = false
                    }
                },
                enabled = !isProcessing && manifest.isNotEmpty()
            ) {
                Text("Uruchom benchmark (${manifest.size} zdjęć)")
            }

            if (isProcessing) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Przetwarzanie $progressDone/${manifest.size}... (chmura może potrwać ~20s/zdjęcie)")
                }
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            if (results.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wyniki (${results.size})", fontWeight = FontWeight.Bold)
                    TextButton(
                        onClick = { exportLauncher.launch("benchmark_${System.currentTimeMillis()}.csv") }
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Eksportuj CSV")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(results) { result -> BenchmarkResultRow(result) }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkResultRow(result: BenchmarkResult) {
    val agrees = result.localReport.overallRisk == result.cloudReport.overallRisk
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(result.imageLabel, fontSize = 12.sp, color = Color.Gray)
            result.groundTruth?.let { gt ->
                Text(
                    "Oczekiwane: ${gt.expectedSubstanceIds.ifEmpty { listOf("(kontrolne - brak)") }.joinToString(", ")}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                VerdictColumn("Lokalny", result.localReport)
                VerdictColumn("Chmura", result.cloudReport)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (agrees) "Werdykty zgodne" else "Werdykty RÓŻNE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (agrees) Color(0xFF66BB6A) else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun VerdictColumn(label: String, report: IngredientSafetyReport) {
    Column {
        Text(label, fontSize = 11.sp, color = Color.Gray)
        Text(
            report.overallRisk.labelPL,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = getRiskColor(report.overallRisk)
        )
        Text("${report.executionTimeMs} ms", fontSize = 11.sp, color = Color.Gray)
    }
}
