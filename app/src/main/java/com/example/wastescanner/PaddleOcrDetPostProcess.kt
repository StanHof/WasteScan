package com.example.wastescanner

import kotlin.math.hypot

/**
 * Postprocessing DB (Differentiable Binarization) - zamienia mapę prawdopodobieństwa zwróconą
 * przez model detekcji PP-OCRv6 na listę rotowanych czworokątów tekstu. Port logiki
 * DBPostProcessor.process z oficjalnego demo PaddleOCR (Apache 2.0), z operacjami OpenCV
 * (threshold/dilate/findContours/minAreaRect/fillPoly) zastąpionymi przez [PaddleOcrGeometry]
 * (patrz uzasadnienie w komentarzu tamtej klasy).
 */
object PaddleOcrDetPostProcess {
    private const val MIN_SIZE_BEFORE_UNCLIP = 3.0
    private const val MIN_SIZE_AFTER_UNCLIP = 5.0

    data class DetectedBox(val points: List<PaddleOcrGeometry.PointD>)

    fun process(
        pred: FloatArray,
        mapHeight: Int,
        mapWidth: Int,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        maxCandidates: Int,
        originalW: Int,
        originalH: Int,
    ): List<DetectedBox> {
        val scaleX = originalW.toDouble() / mapWidth
        val scaleY = originalH.toDouble() / mapHeight

        val mask = BooleanArray(pred.size) { pred[it] > thresh }
        val components = PaddleOcrGeometry.connectedComponentBoundaries(mask, mapWidth, mapHeight)
        val limited = if (components.size > maxCandidates) components.take(maxCandidates) else components

        val boxes = mutableListOf<DetectedBox>()
        for (boundary in limited) {
            if (boundary.size < 3) continue
            val hull = PaddleOcrGeometry.convexHull(boundary)
            if (hull.size < 3) continue

            val rect = PaddleOcrGeometry.minAreaRect(hull)
            if (rect.shortSide < MIN_SIZE_BEFORE_UNCLIP) continue

            val ordered = PaddleOcrGeometry.orderQuadPoints(rect.points)
            val score = PaddleOcrGeometry.polygonMeanScore(pred, mapWidth, mapHeight, ordered)
            if (score < boxThresh) continue

            val expandedPts = PaddleOcrGeometry.unclipPolygon(ordered, unclipRatio)
            if (expandedPts.size < 3) continue
            val expandedHull = PaddleOcrGeometry.convexHull(expandedPts)
            if (expandedHull.size < 3) continue
            val expandedRect = PaddleOcrGeometry.minAreaRect(expandedHull)
            if (expandedRect.shortSide < MIN_SIZE_AFTER_UNCLIP) continue

            val finalOrdered = PaddleOcrGeometry.orderQuadPoints(expandedRect.points)
            val scaled = finalOrdered.map { scalePoint(it, scaleX, scaleY, originalW, originalH) }

            val boxW = hypot(scaled[1].x - scaled[0].x, scaled[1].y - scaled[0].y)
            val boxH = hypot(scaled[3].x - scaled[0].x, scaled[3].y - scaled[0].y)
            if (boxW <= 3 || boxH <= 3) continue

            boxes.add(DetectedBox(scaled))
        }
        return boxes
    }

    private fun scalePoint(
        point: PaddleOcrGeometry.PointD,
        scaleX: Double,
        scaleY: Double,
        originalW: Int,
        originalH: Int,
    ): PaddleOcrGeometry.PointD {
        val x = PaddleOcrGeometry.roundHalfToEven(point.x * scaleX).coerceIn(0, originalW)
        val y = PaddleOcrGeometry.roundHalfToEven(point.y * scaleY).coerceIn(0, originalH)
        return PaddleOcrGeometry.PointD(x.toDouble(), y.toDouble())
    }

    /** Sortowanie w kolejności czytania (góra->dół, w obrębie wiersza lewo->prawo) - port logiki
     *  BoxSorter.sortInReadingOrder z oficjalnego demo (już czysty Kotlin, bez OpenCV). */
    fun sortInReadingOrder(boxes: List<DetectedBox>): List<DetectedBox> {
        if (boxes.size <= 1) return boxes
        val rowThresholdY = 10.0
        val list = boxes.sortedWith(compareBy({ it.points[0].y }, { it.points[0].x })).toMutableList()
        for (i in 0 until list.size - 1) {
            var j = i
            while (j >= 0) {
                val next = list[j + 1]
                val curr = list[j]
                if (kotlin.math.abs(next.points[0].y - curr.points[0].y) < rowThresholdY &&
                    next.points[0].x < curr.points[0].x
                ) {
                    list[j] = next
                    list[j + 1] = curr
                    j--
                } else {
                    break
                }
            }
        }
        return list
    }
}
