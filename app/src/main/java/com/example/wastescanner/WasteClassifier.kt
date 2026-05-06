package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class WasteClassifier(private val context: Context) {

    fun classify(bitmap: Bitmap): String {
        return try {
            val labels = context.assets.open("labels.txt").bufferedReader().readLines()

            // Pamiętaj, aby nazwa pliku zgadzała się z tą w folderze assets!
            val model = FileUtil.loadMappedFile(context, "model.tflite")
            val interpreter = Interpreter(model)

            val imageProcessor = ImageProcessor.Builder()
                .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                .build()

            var tensorImage = TensorImage(interpreter.getInputTensor(0).dataType())
            tensorImage.load(bitmap)
            tensorImage = imageProcessor.process(tensorImage)

            val outputTensor = interpreter.getOutputTensor(0)
            val probabilityBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), outputTensor.dataType())

            interpreter.run(tensorImage.buffer, probabilityBuffer.buffer)

            val probabilities = probabilityBuffer.floatArray
            var maxIdx = 0
            var maxProb = 0f

            for (i in probabilities.indices) {
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    maxIdx = i
                }
            }

            val label = labels.getOrElse(maxIdx) { "Nieznany" }.replace(Regex("^\\d+\\s+"), "")
            val confidence = (maxProb * 100).toInt()

            interpreter.close()

            "$label - $confidence%"

        } catch (e: Exception) {
            "Błąd: ${e.message}"
        }
    }
}