package com.example.wastescanner

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
    initialReport: IngredientSafetyReport? = null,
    classifier: ClassifierStrategy,
    onSaveToHistory: (String, IngredientSafetyReport) -> Unit,
    onTryAgain: () -> Unit
) {
    var currentReport by remember { mutableStateOf(initialReport) }
    var isSaved by remember { mutableStateOf(initialReport != null) }

    LaunchedEffect(bitmap) {
        if (bitmap != null && currentReport == null) {
            val report = classifier.analyzeIngredients(bitmap)
            currentReport = report

            if (!isSaved) {
                val dateFormat = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
                val currentDate = dateFormat.format(Date())
                onSaveToHistory(currentDate, report)
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
                contentDescription = "Zeskanowana etykieta",
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

                val report = currentReport

                if (report == null) {
                    Text("Analizowanie składu...", modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (report.ingredients.isNotEmpty()) {
                    // --- Werdykt ogólny (kolor i etykieta najwyższego wykrytego poziomu ryzyka) ---
                    Surface(
                        color = getRiskColor(report.overallRisk),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp)
                    ) {
                        Text(
                            text = report.overallRisk.labelPL.uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    Text(
                        "Wykryte składniki (od najbardziej do najmniej ryzykownych):",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // report.ingredients jest już posortowana malejąco wg ryzyka
                    // (patrz IngredientSafetyReport.fromIngredients w Models.kt).
                    report.ingredients.forEach { ingredient ->
                        IngredientRow(ingredient)
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(Modifier, DividerDefaults.Thickness, color = Color.LightGray.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Czas przetwarzania: ${report.executionTimeMs} ms",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.End)
                    )

                    if (report.comment != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Uwaga: ${report.comment}",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    // Brak dopasowań: albo błąd OCR (rawOcrText == null), albo tekst odczytany,
                    // ale żaden fragment nie pasował do bazy wiedzy (rawOcrText != null).
                    Text(
                        text = "Nie wykryto żadnego znanego ryzykownego składnika",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = report.comment
                            ?: "Upewnij się, że etykieta jest czytelna i dobrze oświetlona, i spróbuj ponownie.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(Modifier, DividerDefaults.Thickness, color = Color.LightGray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Narzędzie pomocnicze - nie zastępuje konsultacji z lekarzem weterynarii.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onTryAgain,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Skanuj kolejny produkt", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Pojedynczy wiersz listy wyników. Kliknięcie rozwija szczegóły: mechanizm działania,
 * listę objawów i źródło informacji - zgodnie z wymaganiem funkcjonalnym Fazy 3.
 */
@Composable
private fun IngredientRow(ingredient: IngredientMatch) {
    var expanded by remember { mutableStateOf(false) }
    val riskColor = getRiskColor(ingredient.riskLevel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(riskColor.copy(alpha = 0.08f))
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = riskColor,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.size(12.dp)
            ) {}
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ingredient.matchedName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "wykryto jako: \"${ingredient.rawText}\"",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            Text(
                ingredient.riskLevel.labelPL,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = riskColor,
                modifier = Modifier.padding(end = 6.dp)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Zwiń szczegóły" else "Rozwiń szczegóły",
                tint = Color.Gray
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(Modifier, DividerDefaults.Thickness, color = Color.LightGray.copy(alpha = 0.4f))
            Spacer(modifier = Modifier.height(10.dp))

            if (!ingredient.mechanism.isNullOrBlank()) {
                Text("Mechanizm działania", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Text(ingredient.mechanism, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (ingredient.symptoms.isNotEmpty()) {
                Text("Objawy", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Text(ingredient.symptoms.joinToString(", "), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (ingredient.sources.isNotEmpty()) {
                Text("Źródło", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                ingredient.sources.forEach { source ->
                    Text(
                        source.name,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}