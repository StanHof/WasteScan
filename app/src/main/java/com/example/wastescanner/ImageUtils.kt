package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.graphics.Color

// Obracanie zdjęcia ze sprzętowej matrycy aparatu
fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

// Konwersja obrazka z galerii (Uri) na format zrozumiały dla TensorFlow (Bitmap)
fun uriToBitmap(uri: Uri, context: Context): Bitmap? {
    return try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        // Kopiowanie w formacie ARGB_8888 zabezpiecza przed błędami z biblioteką TensorFlow
        bitmap.copy(Bitmap.Config.ARGB_8888, true)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// Funkcja wycinająca kwadrat ze środka zdjęcia
fun cropCenterSquare(bitmap: Bitmap, cropPercentage: Float = 0.40f): Bitmap {
    val width = bitmap.width
    val height = bitmap.height

    // 1. Znajdujemy krótszy bok (zazwyczaj to szerokość w trybie pionowym)
    val minSide = Math.min(width, height)

    // 2. Obliczamy fizyczny rozmiar wycinanego kwadratu (np. 75% szerokości)
    val cropSize = (minSide * cropPercentage).toInt()

    // 3. Obliczamy punkt startowy (X i Y), aby cięcie zaczęło się idealnie na środku
    val startX = (width - cropSize) / 2
    val startY = (height - cropSize) / 2

    // 4. Tworzymy i zwracamy nową, wyciętą Bitmapę
    return Bitmap.createBitmap(bitmap, startX, startY, cropSize, cropSize)
}
fun getBinColor(wasteType: String): Color {
    return when (wasteType.lowercase()) {
        "plastic", "metal" -> Color(0xFFFFD54F) // Żółty
        "paper", "cardboard" -> Color(0xFF4FC3F7) // Niebieski
        "glass", "glass" -> Color(0xFF81C784) // Zielony
        "bio", "organiczne" -> Color(0xFFA1887F) // Brązowy
        "mixed" -> Color(0xFF616161) // Ciemnoszary
        else -> Color(0xFF9E9E9E) // Domyślny szary, gdy nie rozpozna
    }
}