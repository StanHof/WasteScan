package com.example.wastescanner

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs

/**
 * Wykrywa i koryguje przekrzywienie (skew) sfotografowanego tekstu - klasyczna technika
 * "projection profile method": szukamy kąta obrotu, przy którym pozioma projekcja jasności
 * (suma "atramentu" per wiersz) ma najbardziej "ostre" naprzemienne piki/doliny (czyli linie
 * tekstu są faktycznie poziome). Czysty Kotlin, bez OpenCV.
 *
 * WYDAJNOŚĆ: szukanie kąta odbywa się na małej miniaturze (SEARCH_THUMBNAIL_MAX_DIM px),
 * nie na pełnej rozdzielczości zdjęcia - koszt wyszukiwania jest pomijalny (~20-25 prób na
 * obrazku rzędu 300x300px). Obrót w pełnej rozdzielczości wykonywany jest dokładnie RAZ,
 * już po znalezieniu najlepszego kąta.
 *
 * To jest odpowiedź na ograniczenie ProjectionProfileSegmenter, które zakłada tekst mniej
 * więcej poziomy - przy realnych zdjęciach (etykieta/pudełko sfotografowane pod kątem)
 * segmentacja linii/słów bez tej korekty zawodzi, bo sąsiednie linie "rozmazują się" w
 * projekcji poziomej zamiast tworzyć czyste naprzemienne pasma.
 */
object SkewCorrector {

    private const val SEARCH_THUMBNAIL_MAX_DIM = 300

    private const val COARSE_RANGE_DEG = 20f
    private const val COARSE_STEP_DEG = 4f
    private const val FINE_RANGE_DEG = 3f
    private const val FINE_STEP_DEG = 0.5f

    private const val MIN_CORRECTION_DEG = 0.3f // poniżej tego nie warto obracać (szum pomiaru)

    /** Zwraca zdjęcie obrócone tak, żeby tekst był poziomy. Jeśli wykryty kąt jest znikomy,
     *  zwraca oryginalną bitmapę bez zmian (unika niepotrzebnej utraty jakości przy re-encode). */
    fun correctSkew(bitmap: Bitmap): Bitmap {
        val angle = detectSkewAngle(bitmap)
        if (abs(angle) < MIN_CORRECTION_DEG) return bitmap
        return rotate(bitmap, -angle)
    }

    /** Wykrywa kąt przekrzywienia (w stopniach) - dodatni = tekst nachylony w prawo. */
    fun detectSkewAngle(bitmap: Bitmap): Float {
        val thumbnail = createThumbnail(bitmap, SEARCH_THUMBNAIL_MAX_DIM)

        val coarseBest = searchBestAngle(thumbnail, -COARSE_RANGE_DEG, COARSE_RANGE_DEG, COARSE_STEP_DEG)
        val fineBest = searchBestAngle(thumbnail, coarseBest - FINE_RANGE_DEG, coarseBest + FINE_RANGE_DEG, FINE_STEP_DEG)
        return fineBest
    }

    private fun searchBestAngle(thumbnail: Bitmap, from: Float, to: Float, step: Float): Float {
        var bestAngle = 0f
        var bestScore = Float.NEGATIVE_INFINITY
        var angle = from
        while (angle <= to) {
            val score = projectionSharpnessScore(thumbnail, angle)
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
            angle += step
        }
        return bestAngle
    }

    /**
     * "Ostrość" poziomej projekcji przy danym kącie obrotu - im wyższa wariancja liczby
     * pikseli "atramentu" między wierszami, tym bardziej linie tekstu są faktycznie poziome
     * (czyste naprzemienne pasma tekst/przerwa zamiast rozmytej, ciągłej smugi).
     */
    private fun projectionSharpnessScore(thumbnail: Bitmap, angleDegrees: Float): Float {
        val rotated = rotate(thumbnail, angleDegrees)
        val ink = ProjectionProfileSegmenter.binarize(rotated)
        val width = rotated.width
        val height = rotated.height

        val rowCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width) {
                if (ink[y * width + x]) count++
            }
            rowCounts[y] = count
        }

        val mean = rowCounts.average()
        var variance = 0.0
        for (v in rowCounts) variance += (v - mean) * (v - mean)
        variance /= rowCounts.size.coerceAtLeast(1)
        return variance.toFloat()
    }

    private fun createThumbnail(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxDim) return bitmap
        val scale = maxDim.toFloat() / longestSide
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        // Ten wariant createBitmap zwraca bitmapę powiększoną tak, by pomieścić obrócony obraz
        // w całości (z marginesem) - tak samo jak istniejący rotateBitmap() w ImageUtils.kt.
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}