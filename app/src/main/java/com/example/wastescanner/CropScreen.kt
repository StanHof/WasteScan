package com.example.wastescanner

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Prostokąt kadrowania we współrzędnych znormalizowanych (0..1) względem całego obrazu. */
private data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

private enum class CropHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, RIGHT, TOP, BOTTOM }

private const val MIN_CROP_FRACTION = 0.08f
private const val DISPLAY_MAX_SIDE = 2048

// Maksymalny dłuższy bok obrazu przekazywanego do analizy. Pełnowymiarowe zdjęcie z aparatu
// (np. 4000x3000) dawałoby tensor detekcji OCR rzędu 150 MB - ten sam rząd wielkości, który
// wcześniej dawał kwadrat po cropCenterSquare, więc nie pogarsza to dotychczasowej jakości.
private const val ANALYSIS_MAX_SIDE = 2560

/**
 * Ekran kadrowania zdjęcia wybranego z galerii: przeciąganie rogów/krawędzi zmienia prostokąt,
 * przeciąganie wnętrza go przesuwa. Bez zewnętrznych bibliotek (czysty Compose Canvas).
 * Po zatwierdzeniu zwraca wycięty fragment ORYGINALNEGO (pełnej rozdzielczości) bitmapy -
 * podgląd na ekranie to tylko pomniejszona kopia, żeby nie przekroczyć limitu tekstury GPU.
 */
@Composable
fun CropScreen(
    bitmap: Bitmap,
    engine: AnalysisEngine,
    onEngineChange: (AnalysisEngine) -> Unit,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val displayBitmap = remember(bitmap) { scaleDownToMaxSide(bitmap, DISPLAY_MAX_SIDE) }
    val imageBitmap = remember(displayBitmap) { displayBitmap.asImageBitmap() }

    var crop by remember(bitmap) { mutableStateOf(CropRect(0f, 0f, 1f, 1f)) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeHandle by remember { mutableStateOf(CropHandle.NONE) }

    val density = LocalDensity.current
    val hitRadiusPx = with(density) { 36.dp.toPx() }
    val handleRadiusPx = with(density) { 9.dp.toPx() }
    val borderPx = with(density) { 2.dp.toPx() }

    // Prostokąt, w którym obraz jest faktycznie narysowany (dopasowany do kontenera, z zachowaniem proporcji).
    fun fitRect(): Rect {
        if (boxSize.width == 0 || boxSize.height == 0) return Rect.Zero
        val scale = min(boxSize.width.toFloat() / displayBitmap.width, boxSize.height.toFloat() / displayBitmap.height)
        val w = displayBitmap.width * scale
        val h = displayBitmap.height * scale
        val left = (boxSize.width - w) / 2f
        val top = (boxSize.height - h) / 2f
        return Rect(left, top, left + w, top + h)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Text(
            "Przytnij zdjęcie do etykiety",
            color = Color.White,
            fontSize = 20.sp,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
        )
        Text(
            "Przeciągnij rogi lub krawędzie ramki. Im mniej tła, tym lepszy odczyt.",
            color = Color.Gray,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        )
        EngineSelector(
            selected = engine,
            onSelect = onEngineChange,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .onSizeChanged { boxSize = it }
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(displayBitmap) {
                        detectDragGestures(
                            onDragStart = { position ->
                                activeHandle = hitTest(position, crop, fitRect(), hitRadiusPx)
                            },
                            onDragEnd = { activeHandle = CropHandle.NONE },
                            onDragCancel = { activeHandle = CropHandle.NONE }
                        ) { change, dragAmount ->
                            change.consume()
                            val fit = fitRect()
                            if (fit.width > 0f && fit.height > 0f) {
                                crop = applyDrag(crop, activeHandle, dragAmount.x / fit.width, dragAmount.y / fit.height)
                            }
                        }
                    }
            ) {
                val fit = fitRect()
                if (fit.width <= 0f) return@Canvas

                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                    dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt())
                )

                val cl = fit.left + crop.left * fit.width
                val ct = fit.top + crop.top * fit.height
                val cr = fit.left + crop.right * fit.width
                val cb = fit.top + crop.bottom * fit.height

                // Przyciemnienie wszystkiego poza ramką kadru.
                val dim = Color.Black.copy(alpha = 0.6f)
                drawRect(dim, Offset(fit.left, fit.top), Size(fit.width, ct - fit.top))
                drawRect(dim, Offset(fit.left, cb), Size(fit.width, fit.bottom - cb))
                drawRect(dim, Offset(fit.left, ct), Size(cl - fit.left, cb - ct))
                drawRect(dim, Offset(cr, ct), Size(fit.right - cr, cb - ct))

                drawRect(Color.White, Offset(cl, ct), Size(cr - cl, cb - ct), style = Stroke(borderPx))

                // Siatka trójpodziału ułatwia ustawienie ramki równolegle do linii tekstu.
                val grid = Color.White.copy(alpha = 0.35f)
                for (i in 1..2) {
                    val x = cl + (cr - cl) * i / 3f
                    val y = ct + (cb - ct) * i / 3f
                    drawLine(grid, Offset(x, ct), Offset(x, cb), strokeWidth = 1f)
                    drawLine(grid, Offset(cl, y), Offset(cr, y), strokeWidth = 1f)
                }

                listOf(Offset(cl, ct), Offset(cr, ct), Offset(cl, cb), Offset(cr, cb)).forEach {
                    drawCircle(Color.White, handleRadiusPx, it)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) { Text("Anuluj") }
            OutlinedButton(onClick = { crop = CropRect(0f, 0f, 1f, 1f) }) { Text("Cały") }
            OutlinedButton(onClick = { crop = centeredSquare(bitmap) }) { Text("Kwadrat") }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Button(onClick = { onConfirm(cropForAnalysis(bitmap, crop)) }) { Text("Analizuj") }
            }
        }
    }
}

private fun hitTest(position: Offset, crop: CropRect, fit: Rect, radius: Float): CropHandle {
    if (fit.width <= 0f) return CropHandle.NONE
    val l = fit.left + crop.left * fit.width
    val t = fit.top + crop.top * fit.height
    val r = fit.left + crop.right * fit.width
    val b = fit.top + crop.bottom * fit.height

    fun near(px: Float, py: Float) = hypot(position.x - px, position.y - py) <= radius
    if (near(l, t)) return CropHandle.TOP_LEFT
    if (near(r, t)) return CropHandle.TOP_RIGHT
    if (near(l, b)) return CropHandle.BOTTOM_LEFT
    if (near(r, b)) return CropHandle.BOTTOM_RIGHT

    val withinX = position.x in (l - radius)..(r + radius)
    val withinY = position.y in (t - radius)..(b + radius)
    if (withinY && kotlin.math.abs(position.x - l) <= radius) return CropHandle.LEFT
    if (withinY && kotlin.math.abs(position.x - r) <= radius) return CropHandle.RIGHT
    if (withinX && kotlin.math.abs(position.y - t) <= radius) return CropHandle.TOP
    if (withinX && kotlin.math.abs(position.y - b) <= radius) return CropHandle.BOTTOM
    if (position.x in l..r && position.y in t..b) return CropHandle.MOVE
    return CropHandle.NONE
}

private fun applyDrag(crop: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect {
    if (handle == CropHandle.NONE) return crop
    if (handle == CropHandle.MOVE) {
        val w = crop.right - crop.left
        val h = crop.bottom - crop.top
        val l = (crop.left + dx).coerceIn(0f, 1f - w)
        val t = (crop.top + dy).coerceIn(0f, 1f - h)
        return CropRect(l, t, l + w, t + h)
    }

    var l = crop.left
    var t = crop.top
    var r = crop.right
    var b = crop.bottom
    if (handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.LEFT) {
        l = (l + dx).coerceIn(0f, r - MIN_CROP_FRACTION)
    }
    if (handle == CropHandle.TOP_RIGHT || handle == CropHandle.BOTTOM_RIGHT || handle == CropHandle.RIGHT) {
        r = (r + dx).coerceIn(l + MIN_CROP_FRACTION, 1f)
    }
    if (handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT || handle == CropHandle.TOP) {
        t = (t + dy).coerceIn(0f, b - MIN_CROP_FRACTION)
    }
    if (handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.BOTTOM_RIGHT || handle == CropHandle.BOTTOM) {
        b = (b + dy).coerceIn(t + MIN_CROP_FRACTION, 1f)
    }
    return CropRect(l, t, r, b)
}

/** Wyśrodkowany kwadrat o boku 90% krótszego boku obrazu (dawne zachowanie cropCenterSquare). */
private fun centeredSquare(bitmap: Bitmap): CropRect {
    val side = min(bitmap.width, bitmap.height) * 0.9f
    val halfW = side / bitmap.width / 2f
    val halfH = side / bitmap.height / 2f
    return CropRect(0.5f - halfW, 0.5f - halfH, 0.5f + halfW, 0.5f + halfH)
}

private fun cropForAnalysis(source: Bitmap, crop: CropRect): Bitmap {
    val x = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 1)
    val y = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 1)
    val w = ((crop.right - crop.left) * source.width).roundToInt().coerceIn(1, source.width - x)
    val h = ((crop.bottom - crop.top) * source.height).roundToInt().coerceIn(1, source.height - y)
    val cropped = if (x == 0 && y == 0 && w == source.width && h == source.height) source
    else Bitmap.createBitmap(source, x, y, w, h)
    return scaleDownToMaxSide(cropped, ANALYSIS_MAX_SIDE)
}

private fun scaleDownToMaxSide(bitmap: Bitmap, maxSide: Int): Bitmap {
    val longest = max(bitmap.width, bitmap.height)
    if (longest <= maxSide) return bitmap
    val ratio = maxSide.toFloat() / longest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
        (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
        true
    )
}
