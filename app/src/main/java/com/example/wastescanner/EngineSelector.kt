package com.example.wastescanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Wybór silnika analizy: lokalny PP-OCRv6 small, lokalny PP-OCRv6 medium albo Gemini (chmura).
 * Współdzielony przez ekran aparatu i ekran kadrowania, żeby oba pokazywały ten sam, jeden stan.
 */
@Composable
fun EngineSelector(
    selected: AnalysisEngine,
    onSelect: (AnalysisEngine) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnalysisEngine.values().forEach { engine ->
            FilterChip(
                selected = engine == selected,
                onClick = { onSelect(engine) },
                label = { Text(engine.label) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = Color.White,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    }
}
