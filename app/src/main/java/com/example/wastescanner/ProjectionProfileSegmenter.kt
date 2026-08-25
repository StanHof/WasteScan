package com.example.wastescanner

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Segmentacja obrazu na linie tekstu, a linii na pojedyncze słowa - metodą profilu projekcji
 * jasności (histogram "atramentu" per wiersz/kolumna). Czysty Kotlin, bez OpenCV.
 *
 * To jest zamiennik etapu detekcji (EAST), świadomie uproszczony - patrz TextRegionDetector.kt
 * i decyzja z Fazy 3 planu o pominięciu OpenCV na tym etapie.
 *
 * ZAŁOŻENIE / OGRANICZENIE (do wskazania w pracy jako uproszczenie MVP): zakłada ciemny tekst
 * na jasnym tle - typowe dla drukowanych list składników. Odwrócony kontrast (jasny tekst na
 * ciemnym opakowaniu) obecnie nie jest obsługiwany - patrz TODO w binarize().
 */
object ProjectionProfileSegmenter {

    private const val LINE_GAP_MIN_PX = 3      // liczba "pustych" wierszy pod rząd = koniec linii

    // Podniesione z 8 -> 20px: próg 8px przepuszczał linie drobnego druku (np. dopiski prawne,
    // stopki, symbole ® w tej samej ramce EAST co właściwy tekst) wysokości 11-13px - potwierdzone
    // empirycznie w logach (np. "linia Rect(0, 57 - 199, 68)" -> słowa 16x11/20x11/21x11,
    // zdekodowane jako pojedyncze bezsensowne litery). Model CRNN oczekuje wejścia o wysokości
    // ~31px (konwencja keras-ocr) - linia rzędu 11-13px po letterboxingu to głównie rozmycie,
    // model nie ma szans nic z niej odczytać. 20px to nadal bezpieczny margines poniżej
    // najmniejszych DOTYCHCZAS potwierdzonych czytelnych linii (60-90px), ale wyraźnie odcina
    // drobny druk poniżej rozdzielczości modelu.
    private const val MIN_LINE_HEIGHT_PX = 20

    // Podniesione z 4 -> 12px: przy liniach rzędu 60-90px wysokości (typowe dla regionów
    // zwracanych przez EastTextRegionDetector), nawet najwęższa realna litera to zwykle
    // 15-30px szerokości. 4px przepuszczało pojedyncze piksele/artefakty krawędzi jako
    // "słowa" (potwierdzone empirycznie w logach - wycinki rzędu 4x58, 6x68, 10x57px nie
    // zawierały żadnego czytelnego tekstu). 12px to nadal bezpieczny margines poniżej
    // najwęższych realnych liter, ale odcina wyraźne piksele szumu.
    private const val MIN_WORD_WIDTH_PX = 12

    // Próg przerwy międzywyrazowej jako UŁAMEK wysokości linii, nie stała liczba pikseli.
    // Odstępy MIĘDZY LITERAMI w obrębie słowa są zwykle rzędu kilku-kilkunastu % wysokości
    // czcionki, a odstęp MIĘDZY SŁOWAMI to zwykle >40-50% wysokości linii - stała wartość
    // w pikselach (poprzednia wersja: WORD_GAP_MIN_PX = 6) nie skaluje się z rozdzielczością
    // zdjęcia i przy większej czcionce/zoomie zaczyna mylić przerwy międzyliterowe z
    // międzywyrazowymi, tnąc pojedyncze słowa na osobne litery.
    private const val WORD_GAP_HEIGHT_RATIO = 0.45
    private const val WORD_GAP_MIN_PX_FALLBACK = 3 // dolny limit na wypadek bardzo niskich linii

    private const val MIN_INK_DENSITY = 0.03f  // poniżej tego = prawdopodobnie szum/artefakt
    private const val MAX_INK_DENSITY = 0.60f  // powyżej tego = prawdopodobnie plama/cień, nie litery

    /** Zwraca prostokąty linii tekstu (współrzędne w układzie przekazanej bitmapy). */
    fun segmentLines(bitmap: Bitmap): List<Rect> {
        val ink = binarize(bitmap)
        val width = bitmap.width
        val height = bitmap.height

        val rowInkCounts = IntArray(height)
        for (y in 0 until height) {
            var count = 0
            for (x in 0 until width) {
                if (ink[y * width + x]) count++
            }
            rowInkCounts[y] = count
        }

        return findRuns(rowInkCounts, threshold = 0, minGap = LINE_GAP_MIN_PX, minRunLength = MIN_LINE_HEIGHT_PX)
            .map { (start, end) -> Rect(0, start, width, end) }
    }

    /** Zwraca prostokąty pojedynczych słów w obrębie jednej linii (współrzędne oryginalnej bitmapy). */
    fun segmentWords(bitmap: Bitmap, lineRect: Rect): List<Rect> {
        val ink = binarize(bitmap)
        val width = bitmap.width

        val colInkCounts = IntArray(width)
        for (x in 0 until width) {
            var count = 0
            for (y in lineRect.top until lineRect.bottom) {
                if (ink[y * width + x]) count++
            }
            colInkCounts[x] = count
        }

        val wordGapPx = (lineRect.height() * WORD_GAP_HEIGHT_RATIO)
            .toInt()
            .coerceAtLeast(WORD_GAP_MIN_PX_FALLBACK)

        return findRuns(colInkCounts, threshold = 0, minGap = wordGapPx, minRunLength = MIN_WORD_WIDTH_PX)
            .map { (start, end) -> Rect(start, lineRect.top, end, lineRect.bottom) }
            .filter { rect -> hasPlausibleTextDensity(ink, bitmap.width, rect) }
    }

    /**
     * Odrzuca regiony, których gęstość "atramentu" nie wygląda jak realny tekst - prawdziwe
     * słowo ma zwykle 10-45% pikseli w swojej ramce jako "ink" (kreski liter na tle).
     * Region niemal pusty (<3%) to najpewniej szum/artefakt binaryzacji, a niemal cały wypełniony
     * (>60%) to plama/cień, nie pojedyncze znaki - w obu przypadkach model i tak dostanie
     * bezsensowny wzorzec i wygeneruje losowe "słowo", więc lepiej odrzucić to przed inferencją.
     */
    private fun hasPlausibleTextDensity(ink: BooleanArray, bitmapWidth: Int, rect: Rect): Boolean {
        var inkPixels = 0
        val totalPixels = rect.width() * rect.height()
        if (totalPixels <= 0) return false

        for (y in rect.top until rect.bottom) {
            for (x in rect.left until rect.right) {
                if (ink[y * bitmapWidth + x]) inkPixels++
            }
        }
        val density = inkPixels.toFloat() / totalPixels
        return density in MIN_INK_DENSITY..MAX_INK_DENSITY
    }

    /** Zamienia bitmapę na tablicę bool: true = piksel "atramentu" (prawdopodobny tekst).
     *  internal (nie private) - reużywane przez SkewCorrector do wykrywania kąta przekrzywienia. */
    internal fun binarize(bitmap: Bitmap): BooleanArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(pixels.size)
        var sum = 0L
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            gray[i] = luminance
            sum += luminance
        }

        // Prosty globalny próg względem średniej jasności.
        // TODO (backlog): próg adaptacyjny (np. metoda Otsu) + obsługa odwróconego kontrastu.
        val mean = (sum / gray.size).toInt()
        val threshold = mean - mean / 4

        return BooleanArray(gray.size) { gray[it] < threshold }
    }

    /**
     * Znajduje ciągłe przedziały indeksów, w których wartość przekracza próg, traktując
     * `minGap` kolejnych wartości poniżej progu jako koniec przedziału (odstęp).
     */
    private fun findRuns(counts: IntArray, threshold: Int, minGap: Int, minRunLength: Int): List<Pair<Int, Int>> {
        val runs = mutableListOf<Pair<Int, Int>>()
        var runStart = -1
        var gapLength = 0

        for (i in counts.indices) {
            val isInk = counts[i] > threshold
            if (isInk) {
                if (runStart == -1) runStart = i
                gapLength = 0
            } else if (runStart != -1) {
                gapLength++
                if (gapLength >= minGap) {
                    val runEnd = i - gapLength + 1
                    if (runEnd - runStart >= minRunLength) runs.add(runStart to runEnd)
                    runStart = -1
                    gapLength = 0
                }
            }
        }
        if (runStart != -1) {
            val runEnd = counts.size - gapLength
            if (runEnd - runStart >= minRunLength) runs.add(runStart to runEnd)
        }
        return runs
    }
}