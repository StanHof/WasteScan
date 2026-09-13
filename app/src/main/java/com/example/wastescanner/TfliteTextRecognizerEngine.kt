package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.wastescanner.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.TransformToGrayscaleOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

/**
 * Implementacja TextRecognizerEngine na bazie modelu TFLite rozpoznającego pojedyncze słowa
 * (architektura CRNN + CTC, zgodna z oficjalnym przykładem Google:
 * https://blog.tensorflow.org/2021/09/blog.tensorflow.org202109optical-character-recognition.html).
 *
 * PIPELINE (Faza 3 planu, wersja bez OpenCV/EAST - patrz TextRegionDetector.kt):
 *  1. textRegionDetector.detectRegions(bitmap) - domyślnie DeskewingTextRegionDetector (koryguje
 *     przekrzywienie całego kadru, bez OpenCV - patrz SkewCorrector.kt), cały obraz jako jeden region.
 *  2. ProjectionProfileSegmenter dzieli każdy region na linie, a linie na pojedyncze słowa.
 *  3. Każdy wycinek słowa -> model CRNN -> dekodowanie CTC (greedy) -> string.
 *
 * WYMAGANA KONFIGURACJA PROJEKTU (placeholder do podmiany na własny wytrenowany model):
 *  - app/src/main/assets/text_recognition_model.tflite
 *      Na start: skonwertowany model keras-ocr CRNN z TF Hub
 *      https://tfhub.dev/tulasiram58827/lite-model/keras-ocr/float16/2
 *  - app/src/main/assets/text_recognition_alphabet.txt
 *      Jeden wiersz, kolejność znaków zgodna z klasami wyjściowymi modelu. Dla modelu
 *      wytrenowanego na mjsynth (jak wyżej) zwykle: "0123456789abcdefghijklmnopqrstuvwxyz"
 *      - PRZED UŻYCIEM zweryfikuj to z dokumentacją/notebookiem konkretnego pobranego modelu,
 *      nie zakładaj tego na ślepo.
 *
 * Docelowo podmieniasz oba pliki na własne - kształty wejścia/wyjścia są odczytywane w runtime
 * z interpretera (ten sam wzorzec co w LocalWasteClassifier z MVP klasyfikatora odpadów),
 * więc reszta tej klasy nie wymaga zmian, o ile Twój model też jest architekturą CRNN+CTC
 * (jeden wycinek słowa na wejściu, sekwencja klas znaków na wyjściu).
 */
class TfliteTextRecognizerEngine(
    private val context: Context,
    private val textRegionDetector: TextRegionDetector = DeskewingTextRegionDetector(),
    modelFileName: String = "text_recognition_model.tflite",
    alphabetFileName: String = "text_recognition_alphabet.txt"
) : TextRecognizerEngine {

    companion object {
        private const val TAG = "TfliteTextRecognizer"

        // Wyłączone po zakończeniu diagnostyki EAST/segmentacji/dekodowania CTC (patrz sesja
        // debugowania w historii projektu) - zapis KAŻDEGO wycinka słowa jako JPEG (jakość 95) +
        // logowanie pełnej tablicy zdekodowanych indeksów per słowo było niezbędne do zdiagnozowania
        // pipeline'u, ale przy zdjęciach z dużą ilością tekstu (setki wycinków) ten narzut sam w
        // sobie zauważalnie wydłuża czas rozpoznawania. Włącz z powrotem tylko gdy aktywnie
        // debugujesz nowy model/segmentację.
        private const val LOG_PER_WORD_DIAGNOSTICS = false

        // Więcej niż ok. 4 wątki zwykle nie przyspiesza już pojedynczej, małej inferencji CRNN
        // (narzut na synchronizację zaczyna przeważać), a niższe wartości nie wykorzystują
        // wielordzeniowych telefonów - 4 to bezpieczny, sprawdzony w praktyce kompromis.
        private const val INTERPRETER_THREAD_COUNT = 4
    }

    // Zgodnie z konwencją CTC zakładamy, że ostatnia klasa wyjściowa modelu to "blank".
    // TODO przy podmianie modelu: zweryfikować tę konwencję dla własnej architektury.
    //
    // UWAGA: pobrane z TF Hub modele CRNN (np. tulasiram58827/lite-model/keras-ocr) mają
    // często WBUDOWANY W GRAF operator CTC decode (FlexCTCGreedyDecoder) - operator spoza
    // standardowego zestawu TFLite ("Select TensorFlow op"), wymagający dodatkowej biblioteki
    // org.tensorflow:tensorflow-lite-select-tf-ops i jawnej rejestracji FlexDelegate poniżej.
    // Jeśli Twój model NIE ma tego problemu (błąd "Select TensorFlow op(s)... not supported"
    // się nie pojawia), addDelegate(FlexDelegate()) jest nieszkodliwe - po prostu nieużywane.
    private val interpreter: Interpreter? = try {
        val options = Interpreter.Options().apply {
            addDelegate(org.tensorflow.lite.flex.FlexDelegate())
            setNumThreads(INTERPRETER_THREAD_COUNT)
        }
        Interpreter(FileUtil.loadMappedFile(context, modelFileName), options).also { loaded ->
            // Diagnostyka zamiast zgadywania: model z wbudowanym CTC decode może już zwracać
            // ZDEKODOWANE indeksy zamiast surowych wyników [1, T, numClasses], które zakłada
            // poniższa funkcja decodeCtc(). Loguj rzeczywisty kształt, żeby to zweryfikować
            // empirycznie zamiast na ślepo ufać założeniu z komentarza klasy.
            Log.d(TAG, "Model wczytany. Liczba tensorów wyjściowych: ${loaded.outputTensorCount}")
            for (i in 0 until loaded.outputTensorCount) {
                val t = loaded.getOutputTensor(i)
                Log.d(TAG, "  output[$i]: shape=${t.shape().toList()}, dataType=${t.dataType()}")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Nie udało się wczytać modelu '$modelFileName' z assets - silnik TFLite zwróci pustą listę słów.", e)
        null
    }

    // Kształty/typy tensorów i ImageProcessor zależą wyłącznie od WCZYTANEGO MODELU, nie od
    // konkretnego wycinka słowa - liczone raz (lazy, przy pierwszym użyciu), zamiast od nowa przy
    // każdym z setek wywołań recognizeSingleWord(). Poprzednio: 200 słów = 200x
    // interpreter.getInputTensor(0)/getOutputTensor(0) + 200x nowy ImageProcessor.Builder().
    private val inputWidth: Int by lazy { interpreter?.getInputTensor(0)?.shape()?.get(2) ?: 0 }
    private val inputHeight: Int by lazy { interpreter?.getInputTensor(0)?.shape()?.get(1) ?: 0 }
    private val inputDataType: DataType by lazy { interpreter?.getInputTensor(0)?.dataType() ?: DataType.FLOAT32 }
    private val outputShape: IntArray by lazy { interpreter?.getOutputTensor(0)?.shape() ?: intArrayOf() }
    private val outputDataType: DataType by lazy { interpreter?.getOutputTensor(0)?.dataType() ?: DataType.FLOAT32 }
    private val imageProcessor: ImageProcessor by lazy {
        ImageProcessor.Builder()
            .add(TransformToGrayscaleOp())
            .apply {
                // Zweryfikowane w kodzie źródłowym keras_ocr/recognition.py (Recognizer.recognize):
                // `image = image.astype("float32") / 255` - zakres [0, 1], NIE [-1, 1].
                if (inputDataType == DataType.FLOAT32) add(NormalizeOp(0f, 255f))
            }
            .build()
    }

    private val alphabet: String = try {
        context.assets.open(alphabetFileName).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
    } catch (e: Exception) {
        Log.e(TAG, "Nie udało się wczytać alfabetu '$alphabetFileName' z assets.", e)
        ""
    }

    /**
     * DIAGNOSTYKA: rozpoznaje zadany tekst wygenerowany programistycznie (Canvas/Paint),
     * z pominięciem segmentacji (ProjectionProfileSegmenter) i całego szumu prawdziwego
     * zdjęcia z aparatu (oświetlenie, ostrość, kompresja JPEG, tło).
     *
     * Cel: jednoznacznie rozdzielić "wina modelu/preprocessingu" od "wina segmentacji/zdjęcia".
     * - Jeśli TO poprawnie zwróci przekazany tekst -> model i recognizeSingleWord() działają
     *   poprawnie, a problem leży w segmentacji/jakości realnych zdjęć.
     * - Jeśli TO NIE zwróci poprawnego tekstu -> problem jest w recognizeSingleWord()
     *   (preprocessing, alfabet, dekodowanie CTC) albo w samym modelu, niezależnie od zdjęcia.
     *
     * Nieużywane w bieżącym kodzie (EAST + segmentacja słów zostały już zdiagnozowane i działają
     * poprawnie) - zostawione CELOWO jako gotowe narzędzie do następnej rundy diagnostyki, np. przy
     * podmianie modelu CRNN na własny wytrenowany. Wywołanie testowe dodaje się ręcznie w miejscu
     * potrzeby (np. LaunchedEffect w MainActivity.kt), tak jak poprzednio.
     */
    suspend fun runSyntheticSanityCheck(text: String = "hello"): String = withContext(Dispatchers.Default) {
        val engine = interpreter
        if (engine == null) {
            Log.w(TAG, "Sanity check: model niewczytany.")
            return@withContext ""
        }
        val syntheticBitmap = generateSyntheticTextBitmap(text)
        val result = recognizeSingleWord(engine, syntheticBitmap)
        Log.d(TAG, "SANITY CHECK: wygenerowano czysty obraz tekstu \"$text\" -> model rozpoznał: \"$result\"")
        result
    }

    /** Rysuje czarny tekst na białym tle - żadnego szumu kamery, żadnej segmentacji. */
    private fun generateSyntheticTextBitmap(text: String): Bitmap {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 60f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT
        }
        val textWidth = paint.measureText(text)
        val width = (textWidth + 40).toInt().coerceAtLeast(100)
        val height = 100

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawText(text, 20f, 65f, paint)
        return bitmap
    }

    override suspend fun recognizeWords(bitmap: Bitmap): List<String> = withContext(Dispatchers.Default) {
        val engine = interpreter
        if (engine == null || alphabet.isEmpty()) {
            Log.w(TAG, "Silnik TFLite nieskonfigurowany (brak modelu lub alfabetu w assets) - zwracam pustą listę.")
            return@withContext emptyList()
        }

        val words = mutableListOf<String>()
        val regions = textRegionDetector.detectRegions(bitmap)
        var cropIndex = 0

        for (region in regions) {
            val lines = ProjectionProfileSegmenter.segmentLines(region.bitmap)
            Log.d(TAG, "Segmentacja: znaleziono ${lines.size} linii w regionie ${region.bitmap.width}x${region.bitmap.height}")
            for (lineRect in lines) {
                val wordRects = ProjectionProfileSegmenter.segmentWords(region.bitmap, lineRect)
                Log.d(TAG, "  linia $lineRect -> ${wordRects.size} słów: ${wordRects.map { "${it.width()}x${it.height()}" }}")
                for (wordRect in wordRects) {
                    val paddedRect = padRect(wordRect, region.bitmap.width, region.bitmap.height)
                    val crop = safeCrop(region.bitmap, paddedRect) ?: continue

                    if (BuildConfig.DEBUG && LOG_PER_WORD_DIAGNOSTICS) {
                        val path = StorageManager.saveDebugBitmap(context, crop, "debug_word_crop_$cropIndex")
                        Log.d(TAG, "  wycinek #$cropIndex (${crop.width}x${crop.height}) zapisany: $path")
                        cropIndex++
                    }

                    val decoded = recognizeSingleWord(engine, crop)
                    if (decoded.isNotBlank()) words.add(decoded)
                }
            }
        }
        Log.d(TAG, "Segmentacja znalazła ${words.size} słów: $words")
        words
    }

    /** Rozszerza ramkę słowa o margines (procent jej wymiarów), żeby nie ucinać krawędzi liter. */
    private fun padRect(rect: Rect, maxWidth: Int, maxHeight: Int, paddingRatio: Float = 0.15f): Rect {
        val padX = (rect.width() * paddingRatio).toInt().coerceAtLeast(2)
        val padY = (rect.height() * paddingRatio).toInt().coerceAtLeast(2)
        return Rect(
            (rect.left - padX).coerceAtLeast(0),
            (rect.top - padY).coerceAtLeast(0),
            (rect.right + padX).coerceAtMost(maxWidth),
            (rect.bottom + padY).coerceAtMost(maxHeight)
        )
    }

    /**
     * Skaluje bitmapę proporcjonalnie tak, żeby zmieściła się w (targetWidth x targetHeight),
     * a resztę powierzchni dopełnia czarnym tłem (cval=0) - dokładnie tak jak w referencyjnej
     * implementacji keras_ocr.recognition.Recognizer.recognize() (tools.read_and_fit).
     * Zapobiega zniekształceniu kształtu liter, jakie powodowałoby zwykłe rozciągające resize.
     */
    private fun letterboxResize(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val scale = minOf(
            targetWidth.toFloat() / source.width,
            targetHeight.toFloat() / source.height
        )
        val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)

        val scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        // Zweryfikowane w kodzie źródłowym: keras_ocr.recognition.Recognizer.recognize()
        // wywołuje tools.read_and_fit(..., cval=0) - dopełnienie CZARNE, nie białe.
        canvas.drawColor(android.graphics.Color.BLACK)

        val left = (targetWidth - scaledWidth) / 2f
        val top = (targetHeight - scaledHeight) / 2f
        canvas.drawBitmap(scaledBitmap, left, top, null)

        return result
    }

    private fun safeCrop(bitmap: Bitmap, rect: Rect): Bitmap? {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null
        return try {
            Bitmap.createBitmap(bitmap, left, top, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "Błąd przy wycinaniu regionu słowa", e)
            null
        }
    }

    private fun recognizeSingleWord(interpreter: Interpreter, wordBitmap: Bitmap): String {
        return try {
            // inputWidth/inputHeight/inputDataType/outputShape/outputDataType/imageProcessor są
            // teraz cache'owane na poziomie klasy (patrz `by lazy` przy deklaracji interpretera) -
            // zależą tylko od wczytanego modelu, nie od tego konkretnego wycinka słowa.

            // Letterboxing zamiast zwykłego ResizeOp: ResizeOp rozciąga obraz do docelowych
            // wymiarów BEZ zachowania proporcji, co zniekształca kształt liter i jest bardzo
            // prawdopodobną przyczyną słabej celności CRNN (model uczy się kształtu znaku,
            // a rozciągnięcie w poziomie/pionie ten kształt psuje). Zamiast tego: skalujemy
            // proporcjonalnie i dopełniamy białym tłem do wymaganych wymiarów.
            val letterboxed = letterboxResize(wordBitmap, inputWidth, inputHeight)

            var tensorImage = TensorImage(inputDataType)
            tensorImage.load(letterboxed)
            tensorImage = imageProcessor.process(tensorImage)

            when (outputDataType) {
                DataType.INT64 -> {
                    // Model ma wbudowany CTC decode (FlexCTCGreedyDecoder) - wyjście to już
                    // gotowe, zdekodowane indeksy tokenów, a nie surowe wyniki per-klasa.
                    // TensorBuffer (support library) NIE obsługuje INT64, więc czytamy ręcznie
                    // przez surowy ByteBuffer.
                    decodeAlreadyDecodedIndices(interpreter, tensorImage, outputShape)
                }
                else -> {
                    // Klasyczny przypadek: surowe wyniki [1, T, numClasses] do ręcznego
                    // dekodowania CTC (argmax + zwinięcie powtórzeń).
                    val outputBuffer = TensorBuffer.createFixedSize(outputShape, outputDataType)
                    interpreter.run(tensorImage.buffer, outputBuffer.buffer)
                    decodeCtc(outputBuffer.floatArray, outputShape)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Błąd rozpoznawania pojedynczego słowa", e)
            ""
        }
    }

    /**
     * UWAGA - HIPOTEZA DO POTWIERDZENIA: zakłada pojedynczy, gęsty tensor wyjściowy typu INT64
     * zawierający już zdekodowaną sekwencję indeksów znaków (konwencja tf.sparse.to_dense przy
     * eksporcie CTC decodera, z wypełnieniem krótszych sekwencji wartością -1).
     *
     * Jeśli log "Liczba tensorów wyjściowych" (patrz inicjalizacja interpretera wyżej) pokazuje
     * WIĘCEJ niż 1 tensor wyjściowy, to prawdopodobnie mamy do czynienia z pełnym SparseTensor
     * (osobne tensory: indices [N,2], values [N], dense_shape [2]) i ta funkcja wymaga przepisania
     * na interpreter.runForMultipleInputsOutputs() z odczytem wszystkich trzech tensorów naraz.
     * Loguję surowe wartości poniżej właśnie po to, żeby to zweryfikować empirycznie.
     */
    private fun decodeAlreadyDecodedIndices(
        interpreter: Interpreter,
        tensorImage: TensorImage,
        outputShape: IntArray
    ): String {
        val elementCount = outputShape.fold(1) { acc, dim -> acc * dim }
        val outputBuffer = java.nio.ByteBuffer
            .allocateDirect(elementCount * 8) // INT64 = 8 bajtów na element
            .order(java.nio.ByteOrder.nativeOrder())

        interpreter.run(tensorImage.buffer, outputBuffer)
        outputBuffer.rewind()

        val indices = LongArray(elementCount)
        outputBuffer.asLongBuffer().get(indices)
        if (LOG_PER_WORD_DIAGNOSTICS) {
            Log.d(TAG, "Zdekodowane indeksy (surowe, int64, shape=${outputShape.toList()}): ${indices.toList()}")
        }

        val builder = StringBuilder()
        for (idx in indices) {
            // Konwencja tf.sparse.to_dense: krótsze sekwencje w batchu są dopełniane -1.
            if (idx < 0) continue
            val i = idx.toInt()
            if (i in alphabet.indices) builder.append(alphabet[i])
        }
        return builder.toString()
    }

    /**
     * Standardowe dekodowanie CTC (greedy): argmax na każdym kroku czasowym, zwinięcie
     * kolejnych powtórzeń tej samej klasy, usunięcie klasy "blank" (zakładanej jako ostatni
     * indeks - patrz TODO w komentarzu klasy).
     */
    private fun decodeCtc(output: FloatArray, outputShape: IntArray): String {
        if (alphabet.isEmpty() || outputShape.size < 3) return ""

        val timeSteps = outputShape[1]
        val numClasses = outputShape[2]
        val blankIndex = numClasses - 1

        val builder = StringBuilder()
        var previousIndex = -1

        for (t in 0 until timeSteps) {
            var bestIndex = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val score = output[t * numClasses + c]
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = c
                }
            }
            if (bestIndex != blankIndex && bestIndex != previousIndex && bestIndex < alphabet.length) {
                builder.append(alphabet[bestIndex])
            }
            previousIndex = bestIndex
        }
        return builder.toString()
    }
}