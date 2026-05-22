package com.example.wastescanner

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

data class  ClassificationResult(val label: String, val confidence: Float)
class WasteClassifier(private val context: Context) {

    fun classify(bitmap: Bitmap): List<ClassificationResult> {
        return try {
            val labels = context.assets.open("labels.txt").bufferedReader().readLines()
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
            interpreter.close()


            probabilities.indices.map { idx ->
                val cleanLabel = labels.getOrElse(idx) {"Nieznany"}.replace(Regex("^\\d+\\s+"), "")
                ClassificationResult(cleanLabel, probabilities[idx])
            }.sortedByDescending { it.confidence }


        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}