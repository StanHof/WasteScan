package com.example.wastescanner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ResultScreen(
    bitmap: Bitmap?,
    initialReport: AnalysisReport? = null,
    classifier: ClassifierStrategy,
    onSaveToHistory: (String, Int, String, AnalysisReport) -> Unit,
    onTryAgain: () -> Unit
) {
    var currentReport by remember { mutableStateOf(initialReport) }
    var isSaved by remember { mutableStateOf(initialReport != null) }

    val resultsList = currentReport?.results ?: emptyList()
    val topResult = resultsList.firstOrNull()

    LaunchedEffect(bitmap) {
        if (bitmap != null && currentReport == null) {
            val report = classifier.classify(bitmap)
            currentReport = report

            if (report.results.isNotEmpty() && !isSaved) {
                val bestMatch = report.results.first()
                val dateFormat = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
                val currentDate = dateFormat.format(Date())

                onSaveToHistory(
                    bestMatch.label,
                    (bestMatch.confidence * 100).toInt(),
                    currentDate,
                    report
                )
                isSaved = true
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Zeskanowany odpad",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {


                if (currentReport == null) {
                    Text("Analizowanie obrazu...", modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                else if (topResult != null) {
                    Surface(
                        color = getBinColor(topResult.label),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp)
                    ) {
                        Text(
                            text = topResult.label.uppercase(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Text("Wykres prawdopodobieństwa:", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    Spacer(modifier = Modifier.height(12.dp))

                    resultsList.forEach { res ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(res.label, modifier = Modifier.width(90.dp), fontSize = 14.sp, fontWeight = if (res == topResult) FontWeight.Bold else FontWeight.Normal)
                            LinearProgressIndicator(
                                progress = { res.confidence }, modifier = Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)),
                                color = getBinColor(res.label), trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("${(res.confidence * 100).toInt()}%", fontSize = 14.sp, modifier = Modifier.width(40.dp), fontWeight = if (res == topResult) FontWeight.Bold else FontWeight.Normal)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(Modifier, DividerDefaults.Thickness, color = Color.LightGray.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Czas przetwarzania: ${currentReport?.executionTimeMs ?: 0} ms", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.align(Alignment.End))

                    if (currentReport?.comment != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Porada AI: ${currentReport?.comment}", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
                // 3. STAN: Awaria! Raport przyszedł, ale lista wyników jest pusta (wystąpił błąd łapany przez try-catch)
                else {
                    Text(
                        text = "Błąd analizy chmurowej",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = currentReport?.comment ?: "Nieznany błąd. Sprawdź połączenie z internetem i konfigurację API.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onTryAgain,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Skanuj kolejny odpad", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}