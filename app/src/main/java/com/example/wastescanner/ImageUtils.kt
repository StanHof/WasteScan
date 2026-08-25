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

// Konwersja obrazka z galerii (Uri) na format zrozumiały dla klasyfikatora (Bitmap)
// Faza 3 - poprawka: dodano odczyt i zastosowanie orientacji EXIF. Ani ImageDecoder (API 28+),
// ani MediaStore.Images.Media.getBitmap NIE stosują automatycznie tagu EXIF Orientation - zdjęcia
// zrobione "w pionie" bywają fizycznie zapisane w danych pikselowych "na leżąco" z samym tagiem
// EXIF mówiącym o obrocie. Bez tej poprawki tekst trafiał do ML Kit obrócony i OCR nie znajdował
// w nim nic sensownego, mimo że na oko zdjęcie wyglądało poprawnie (bo galeria/podgląd honoruje EXIF).
fun uriToBitmap(uri: Uri, context: Context): Bitmap? {
    return try {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }.copy(Bitmap.Config.ARGB_8888, true)

        val rotationDegrees = readExifRotationDegrees(context, uri)
        if (rotationDegrees != 0) rotateBitmap(bitmap, rotationDegrees) else bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Wymaga zależności: implementation("androidx.exifinterface:exifinterface:1.3.7")
 * (wersja AndroidX, nie android.media.ExifInterface - ta pierwsza działa spójnie na wszystkich
 * wspieranych wersjach API i przyjmuje InputStream bezpośrednio z ContentResolvera).
 */
private fun readExifRotationDegrees(context: Context, uri: Uri): Int {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = androidx.exifinterface.media.ExifInterface(stream)
            when (exif.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        e.printStackTrace()
        0
    }
}

// Funkcja wycinająca kwadrat ze środka zdjęcia
// Faza 3: cropPercentage podniesiony z 0.40f do 0.90f. Stara wartość była dobrana pod MVP
// klasyfikatora odpadów (pojedynczy przedmiot, blisko obiektywu) - przy skanowaniu etykiety
// tekstowej 40% środka kadru w praktyce ucinało cały tekst, co skutkowało pustym wynikiem OCR.
fun cropCenterSquare(bitmap: Bitmap, cropPercentage: Float = 0.60f): Bitmap {
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
// Faza 1: getBinColor (kolory kategorii odpadów) zastąpiony przez getRiskColor,
// bo wynikiem analizy jest teraz poziom ryzyka dla psa, a nie kategoria odpadu.
fun getRiskColor(riskLevel: RiskLevel): Color {
    return when (riskLevel) {
        RiskLevel.SAFE -> Color(0xFF66BB6A)       // Zielony
        RiskLevel.CAUTION -> Color(0xFFFFB300)    // Bursztynowy
        RiskLevel.DANGEROUS -> Color(0xFFEF6C00)  // Pomarańczowy
        RiskLevel.CRITICAL -> Color(0xFFE53935)   // Czerwony
    }
}