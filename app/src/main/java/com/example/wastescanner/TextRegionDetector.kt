package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log

/**
 * Pojedynczy region zdjęcia przekazywany dalej do rozpoznawania tekstu.
 *
 * @param bitmap wycinek/obraz gotowy do podania na wejście modelu rozpoznającego
 * @param boundingBox opcjonalna ramka we współrzędnych oryginalnego zdjęcia - przydatna
 *        do debugowania/wizualizacji (np. narysowania wykrytych ramek na podglądzie),
 *        na razie nieużywana nigdzie w UI
 */
data class TextRegion(
    val bitmap: Bitmap,
    val boundingBox: Rect? = null
)

/**
 * Etap "obróbki i detekcji" w pipeline OCR, wydzielony jako osobny wymienny krok - dokładnie
 * to miejsce, w którym w przyszłości zostanie podłączona detekcja EAST + prostowanie
 * perspektywy przez OpenCV (patrz NIEZAIMPLEMENTOWANY szkic EastOpenCvTextRegionDetector
 * poniżej w komentarzu), bez konieczności zmiany TextRecognizerEngine ani reszty pipeline'u.
 *
 * Kontrakt: wejście - całe zdjęcie (już wycięte przez cropCenterSquare na etapie CameraScreen),
 * wyjście - lista regionów do przekazania do modelu rozpoznającego, każdy w formie gotowej
 * bitmapy (ewentualnie już wyprostowanej/wykadrowanej wokół pojedynczego bloku tekstu).
 */
interface TextRegionDetector {
    fun detectRegions(bitmap: Bitmap): List<TextRegion>
}

/**
 * Obecna, docelowo TYMCZASOWA implementacja: nie wykonuje żadnej detekcji ani obróbki obrazu -
 * po prostu zwraca całe wejściowe zdjęcie jako jeden, jedyny region. To świadomy placeholder
 * zgodny z decyzją o pominięciu detekcji na tym etapie (Faza 3 planu, wersja bez OpenCV):
 * dalsza segmentacja na pojedyncze słowa odbywa się już wewnątrz TfliteTextRecognizerEngine
 * metodą prostego profilu projekcji jasności (bez OpenCV).
 *
 * Ta klasa istnieje właśnie po to, żeby dało się ją later podmienić na coś lepszego bez
 * zmiany sygnatur w reszcie kodu - patrz TODO w komentarzu poniżej.
 */
class NoOpTextRegionDetector : TextRegionDetector {
    override fun detectRegions(bitmap: Bitmap): List<TextRegion> {
        return listOf(TextRegion(bitmap = bitmap, boundingBox = null))
    }
}

/**
 * Pośredni krok między NoOp a pełnym EAST+OpenCV: koryguje przekrzywienie (skew) całego
 * zdjęcia metodą profilu projekcji (SkewCorrector, czysty Kotlin, bez OpenCV), a dopiero
 * potem zwraca je jako pojedynczy region - dokładnie tak jak NoOp, tylko z jednym
 * dodatkowym krokiem obróbki przed segmentacją słów.
 *
 * Naprawia konkretny, zaobserwowany problem: realne zdjęcia (pudełko/etykieta sfotografowane
 * pod kątem) łamią założenie ProjectionProfileSegmenter o mniej więcej poziomym tekście -
 * sąsiednie linie "rozmazują się" w projekcji poziomej zamiast tworzyć czyste naprzemienne
 * pasma tekst/przerwa, więc segmentacja linii/słów zawodzi.
 *
 * Nie zastępuje pełnego EAST+OpenCV (patrz TODO niżej) - nie radzi sobie z perspektywą,
 * wieloma niezależnie zorientowanymi blokami tekstu w jednym kadrze, ani z silnym zakrzywieniem
 * (np. tekst na zgiętym opakowaniu). Rozwiązuje tylko jednolity obrót całego kadru.
 */
class DeskewingTextRegionDetector : TextRegionDetector {
    override fun detectRegions(bitmap: Bitmap): List<TextRegion> {
        val corrected = SkewCorrector.correctSkew(bitmap)
        return listOf(TextRegion(bitmap = corrected, boundingBox = null))
    }
}

/**
 * Detektor regionów tekstu oparty o prawdziwy model EAST (patrz EastTextDetector.kt) zamiast
 * heurystyk (profil projekcji, korekta skosu). Rozwiązuje klasę problemów, na które heurystyki
 * się wywracały: tekst pod kątem, wiele niezależnie zorientowanych bloków w jednym kadrze,
 * mylenie tła/refleksów z tekstem - EAST został wytrenowany rozpoznawać kształt tekstu wprost,
 * zamiast wnioskować go z globalnej statystyki jasności całego kadru.
 *
 * Świadomie NIE używa biblioteki OpenCV (mimo że oryginalny przykład Google/OpenCV jej używa) -
 * NMS i prostowanie perspektywy każdej wykrytej ramki są zaimplementowane przez natywne API
 * Androida (Matrix.setPolyToPoly + Canvas), co daje ten sam efekt bez dodawania sporej,
 * natywnej zależności i ryzyka kolejnej rundy problemów z konfiguracją builda.
 */
class EastTextRegionDetector(
    context: Context,
    private val scoreThreshold: Float = 0.5f,
    private val nmsIouThreshold: Float = 0.4f
) : TextRegionDetector {

    companion object {
        private const val TAG = "EastTextRegionDetector"

        // Wyrażony w przestrzeni WEJŚCIA modelu (stałe 320x320, patrz EastTextDetector.inputWidth/
        // Height), a nie oryginalnego zdjęcia - dzięki temu próg nie zależy od rozdzielczości
        // zdjęcia z aparatu. EAST dekoduje geometrię z siatki 80x80 (downsample=4), więc ramka
        // węższa/niższa niż ok. 2 komórki siatki (8px w przestrzeni 320x320) niemal na pewno nie
        // jest realnym tekstem, tylko pojedynczą, odosobnioną aktywacją mapy wyniku (szum, krawędź
        // koloru na opakowaniu, logo) - bez tego filtra taka ramka trafiała do perspectiveCrop(),
        // która i tak wymuszała minimum 8px, tworząc pozornie poprawny, ale bezsensowny region
        // wejściowy dla dalszej segmentacji/rozpoznawania.
        private const val MIN_BOX_DIMENSION_PX = 8f
    }

    private val detector = EastTextDetector(context)

    override fun detectRegions(bitmap: Bitmap): List<TextRegion> {
        // EAST wymaga stałego rozmiaru wejścia (zwykle 320x320) - detekcja odbywa się na
        // przeskalowanej kopii, a wykryte współrzędne są przeliczane z powrotem na oryginalny,
        // pełnorozdzielczy obraz, żeby wycięcia trafiające do modelu rozpoznającego (dalszy
        // etap pipeline'u) miały pełną, oryginalną jakość.
        val scaleX = bitmap.width.toFloat() / detector.inputWidth
        val scaleY = bitmap.height.toFloat() / detector.inputHeight

        val rawBoxes = detector.detect(bitmap, scoreThreshold, nmsIouThreshold)
        val tooSmallCount = rawBoxes.count { it.width < MIN_BOX_DIMENSION_PX || it.height < MIN_BOX_DIMENSION_PX }
        val boxes = rawBoxes.filter { it.width >= MIN_BOX_DIMENSION_PX && it.height >= MIN_BOX_DIMENSION_PX }
        if (tooSmallCount > 0) {
            Log.d(TAG, "Odrzucono $tooSmallCount z ${rawBoxes.size} ramek EAST jako zbyt małe (< ${MIN_BOX_DIMENSION_PX}px w przestrzeni 320x320) - prawdopodobny szum, nie tekst.")
        }
        if (boxes.isEmpty()) {
            Log.w(TAG, "EAST nie wykrył żadnego regionu tekstu - zwracam całe zdjęcie jako fallback.")
            return listOf(TextRegion(bitmap = bitmap, boundingBox = null))
        }

        return boxes.mapNotNull { box ->
            try {
                val scaledBox = RotatedTextBox(
                    centerX = box.centerX * scaleX,
                    centerY = box.centerY * scaleY,
                    width = box.width * scaleX,
                    height = box.height * scaleY,
                    angleDegrees = box.angleDegrees,
                    confidence = box.confidence
                )
                val warped = perspectiveCrop(bitmap, scaledBox)
                val boundingRect = Rect(
                    (scaledBox.centerX - scaledBox.width / 2).toInt(),
                    (scaledBox.centerY - scaledBox.height / 2).toInt(),
                    (scaledBox.centerX + scaledBox.width / 2).toInt(),
                    (scaledBox.centerY + scaledBox.height / 2).toInt()
                )
                TextRegion(bitmap = warped, boundingBox = boundingRect)
            } catch (e: Exception) {
                Log.e(TAG, "Błąd prostowania wykrytego regionu tekstu, pomijam go", e)
                null
            }
        }
    }

    /**
     * Odpowiednik OpenCV fourPointsTransform() (getPerspectiveTransform + warpPerspective),
     * zaimplementowany przez Matrix.setPolyToPoly (Android obsługuje pełną transformację
     * perspektywiczną dla 4 par punktów) zamiast biblioteki OpenCV.
     */
    private fun perspectiveCrop(source: Bitmap, box: RotatedTextBox): Bitmap {
        val corners = box.corners()
        val outWidth = box.width.toInt().coerceAtLeast(8)
        val outHeight = box.height.toInt().coerceAtLeast(8)

        val srcPoints = floatArrayOf(
            corners[0][0], corners[0][1],
            corners[1][0], corners[1][1],
            corners[2][0], corners[2][1],
            corners[3][0], corners[3][1]
        )
        val dstPoints = floatArrayOf(
            0f, (outHeight - 1).toFloat(),
            0f, 0f,
            (outWidth - 1).toFloat(), 0f,
            (outWidth - 1).toFloat(), (outHeight - 1).toFloat()
        )

        val matrix = android.graphics.Matrix()
        val ok = matrix.setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
        if (!ok) throw IllegalStateException("setPolyToPoly nie potrafiło rozwiązać transformacji dla tej ramki")

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }
}