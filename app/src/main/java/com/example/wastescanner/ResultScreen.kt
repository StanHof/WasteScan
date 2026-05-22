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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultScreen(
    bitmap: Bitmap?,
    initialResults: List<ClassificationResult> = emptyList(),
    onSaveToHistory: (String, Int, String, List<ClassificationResult>) -> Unit,
    onTryAgain: () -> Unit
) {
    val context = LocalContext.current
    val classifier = remember { WasteClassifier(context) }

    // Inicjalizujemy stan wartościami początkowymi (jeśli przyszły z historii)
    var results by remember { mutableStateOf(initialResults) }
    var isSaved by remember { mutableStateOf(initialResults.isNotEmpty()) }

    LaunchedEffect(bitmap) {
        // 3. ZMIANA: Analizujemy TYLKO wtedy, gdy lista wyników jest pusta (nowy skan z aparatu)
        if (bitmap != null && results.isEmpty()) {
            val classification = classifier.classify(bitmap)
            results = classification

            if (classification.isNotEmpty() && !isSaved) {
                val topResult = classification.first()
                val dateFormat = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
                val currentDate = dateFormat.format(Date())

                // Zapisujemy komplet danych (łącznie z listą wszystkich słupków)
                onSaveToHistory(
                    topResult.label,
                    (topResult.confidence * 100).toInt(),
                    currentDate,
                    classification
                )
                isSaved = true
            }
        }
    }

    val topResult = results.firstOrNull()

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
                contentScale = ContentScale.Crop, // Wypełnia ładnie kwadrat
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // Kwadratowe proporcje
                    .clip(RoundedCornerShape(24.dp)) // Mocno zaokrąglone rogi
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- 2. KARTA Z WYNIKIEM ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp) // Delikatny cień
        ) {
            Column(
                modifier = Modifier.padding(24.dp),

                ) {
                if (topResult != null) {
                    Surface(
                        color = getBinColor(topResult.label),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .padding(bottom = 20.dp)
                    ) {
                        Text(
                            text = topResult.label.uppercase(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }



                Text(
                    "Wykres prawdopodobieństwa:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(12.dp)) // Rozpychacz - spycha przycisk na dół ekranu

                results.forEach { res ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = res.label,
                            modifier = Modifier.width(90.dp),
                            fontSize = 14.sp,
                            fontWeight = if (res == topResult) FontWeight.Bold else FontWeight.Normal
                        )

                        LinearProgressIndicator(
                            progress = { res.confidence },
                            modifier = Modifier
                                .weight(1f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            color = getBinColor(res.label),
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = "${(res.confidence * 100).toInt()}%",
                            fontSize = 14.sp,
                            modifier = Modifier.width(40.dp),
                            fontWeight = if (res == topResult) FontWeight.Bold else FontWeight.Normal
                        )

                    }
                }
            } else {
                Text("Analizowanie obrazu..." , modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
        Spacer(modifier = Modifier.weight(1f))
        Button(
            onClick = onTryAgain,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),

            ) {
            Text("Skanuj kolejny odpad", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        }

    }



