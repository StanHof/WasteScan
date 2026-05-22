package com.example.wastescanner
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 1. AKTUALIZACJA MODELU: dodajemy bitmapę oraz listę wszystkich wyników z wykresu
data class HistoryItem(
    val id: Long,
    val label: String,
    val confidence: Int,
    val dateString: String,
    val imagePath: String?, // <--- TO JEST ZMIANA
    val allResults: List<ClassificationResult>
)

@Composable
fun ScanHistoryScreen(
    historyList: List<HistoryItem>,
    onItemClick: (HistoryItem) -> Unit, // 2. NOWY PARAMETR: akcja po kliknięciu w pozycję
    onBack: () -> Unit
) {
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
                    Text("Historia Skanów", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Brak zapisanych skanów", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(historyList.reversed()) { item ->
                    // --- NOWOŚĆ: Logika asynchronicznego wczytywania zdjęcia wewnątrz elementu listy ---
                    var loadedBitmap: Bitmap? by remember(item.imagePath) { mutableStateOf<Bitmap?>(null) }

                    LaunchedEffect(item.imagePath) {
                        if (loadedBitmap == null && item.imagePath != null) {
                            // Wczytujemy z dysku tylko raz dla tego wpisu
                            loadedBitmap = StorageManager.loadBitmap(item.imagePath)
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemClick(item) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        // Zmieniamy układ na CenterVertically, by wszystko ładnie pasowało do miniaturki
                        Row(
                            modifier = Modifier.padding(12.dp), // Trochę mniejszy padding
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            // --- NOWOŚĆ: Sekcja Miniaturek ---
                            Box(
                                modifier = Modifier
                                    .size(60.dp) // Sztywny rozmiar miniaturki
                                    .clip(RoundedCornerShape(12.dp)) // Ładne zaokrąglone rogi
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)), // Delikatne tło
                                contentAlignment = Alignment.Center
                            ) {
                                if (loadedBitmap != null) {
                                    // Wyświetlamy zdjęcie, jeśli wczytane
                                    Image(
                                        bitmap = loadedBitmap!!.asImageBitmap(),
                                        contentDescription = "Miniatura: ${item.label}",
                                        contentScale = ContentScale.Crop, // Wypełnia ładnie kwadrat
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else if (item.imagePath == null) {
                                    // Jeśli wpis nie ma zdjęcia (np. starszy wpis bez path)
                                    Icon(
                                        imageVector = Icons.Default.BrokenImage,
                                        contentDescription = "Brak zdjęcia",
                                        tint = Color.Gray.copy(alpha = 0.5f)
                                    )
                                } else {
                                    // W trakcie ładowania pokazujemy kółko
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(16.dp)) // Odstęp od miniaturki

                            // --- Sekcja tekstowa (bez zmian, tylko w Column) ---
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.label,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = item.dateString,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // --- Kółko procentowe (bez zmian) ---
                            Surface(
                                color = getBinColor(item.label),
                                shape = CircleShape,
                                modifier = Modifier.size(50.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${item.confidence}%",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}