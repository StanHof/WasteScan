package com.example.wastescanner

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Czysto matematyczna geometria potrzebna do postprocessingu DB (Differentiable Binarization) z
 * PaddleOCR - ZAMIENNIK funkcji OpenCV (findContours, minAreaRect, fillPoly), których oficjalne
 * demo PaddleOCR (deploy/ppocr-android) używa. Świadoma decyzja architektoniczna, ta sama co przy
 * EAST (EastTextDetector.kt) - patrz docs/paddleocr_evaluation.md: trzy kolejne awarie natywne
 * OpenCV podczas testów demo (usunięty symbol Bionic, niezgodność ABI libc++, dryf sygnatur JNI)
 * potwierdziły empirycznie ryzyko tej zależności. Wszystkie poniższe algorytmy są standardowe i
 * dobrze udokumentowane (nie zgadywane) - referencje podane przy każdej funkcji.
 *
 * UWAGA o poprawności: wypełniony (flood-fill) spójny obszar ma taką samą otoczkę wypukłą jak sam
 * jego brzeg (piksele wewnętrzne z definicji leżą wewnątrz otoczki wypukłej brzegu) - dlatego
 * [connectedComponentBoundaries] zwraca tylko piksele brzegowe, nie całe obszary, bez wpływu na
 * poprawność [convexHull]/[minAreaRect].
 */
object PaddleOcrGeometry {

    data class PointD(val x: Double, val y: Double)

    /** Rotowany prostokąt opisany 4 wierzchołkami w tej samej konwencji co OpenCV RotatedRect. */
    data class RotatedQuad(val points: List<PointD>) {
        val shortSide: Double
            get() {
                val w = hypot(points[1].x - points[0].x, points[1].y - points[0].y)
                val h = hypot(points[3].x - points[0].x, points[3].y - points[0].y)
                return minOf(w, h)
            }
    }

    /**
     * Etykietowanie spójnych składowych (8-spójność, BFS) na masce binarnej, zwraca dla każdej
     * składowej TYLKO piksele brzegowe (piksel foreground mający sąsiada background/poza
     * granicami w 4-spójności) - patrz uwaga o poprawności w komentarzu klasy.
     */
    fun connectedComponentBoundaries(mask: BooleanArray, width: Int, height: Int): List<List<PointD>> {
        val visited = BooleanArray(mask.size)
        val components = mutableListOf<List<PointD>>()
        val queue = ArrayDeque<Int>()

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            val boundary = mutableListOf<PointD>()
            queue.clear()
            queue.add(start)
            visited[start] = true

            while (queue.isNotEmpty()) {
                val idx = queue.removeFirst()
                val x = idx % width
                val y = idx / width

                var isBoundary = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                        val nIdx = ny * width + nx
                        if (mask[nIdx]) {
                            if (!visited[nIdx]) {
                                visited[nIdx] = true
                                queue.add(nIdx)
                            }
                        }
                        // 4-spójne sąsiedztwo decyduje o "brzegowości" (jak w typowej definicji
                        // konturu binarnego), 8-spójność decyduje tylko o przynależności do składowej.
                        if ((dx == 0 || dy == 0) && !mask[nIdx]) {
                            isBoundary = true
                        }
                    }
                }
                if (x == 0 || x == width - 1 || y == 0 || y == height - 1) isBoundary = true
                if (isBoundary) boundary.add(PointD(x.toDouble(), y.toDouble()))
            }
            if (boundary.isNotEmpty()) components.add(boundary)
        }
        return components
    }

    /**
     * Otoczka wypukła - algorytm Andrew's monotone chain (standardowy, O(n log n)).
     * https://en.wikibooks.org/wiki/Algorithm_Implementation/Geometry/Convex_hull/Monotone_chain
     */
    fun convexHull(points: List<PointD>): List<PointD> {
        if (points.size < 3) return points
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted

        fun cross(o: PointD, a: PointD, b: PointD): Double =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val lower = mutableListOf<PointD>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = mutableListOf<PointD>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * Minimalny obszarowo prostokąt otaczający (odpowiednik cv2.minAreaRect) - "rotating
     * calipers" na otoczce wypukłej: dla każdej krawędzi otoczki liczymy prostokąt zrównany z jej
     * kierunkiem, wybieramy ten o najmniejszym polu. Standardowy algorytm:
     * https://en.wikipedia.org/wiki/Rotating_calipers#Minimum-area_enclosing_rectangle
     */
    fun minAreaRect(hull: List<PointD>): RotatedQuad {
        require(hull.size >= 3) { "Otoczka wypukła wymaga min. 3 punktów" }

        var bestArea = Double.MAX_VALUE
        var bestPoints: List<PointD> = emptyList()

        for (i in hull.indices) {
            val p1 = hull[i]
            val p2 = hull[(i + 1) % hull.size]
            val edgeAngle = atan2(p2.y - p1.y, p2.x - p1.x)
            val cosA = cos(-edgeAngle)
            val sinA = sin(-edgeAngle)

            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in hull) {
                val rx = p.x * cosA - p.y * sinA
                val ry = p.x * sinA + p.y * cosA
                if (rx < minX) minX = rx
                if (rx > maxX) maxX = rx
                if (ry < minY) minY = ry
                if (ry > maxY) maxY = ry
            }

            val area = (maxX - minX) * (maxY - minY)
            if (area < bestArea) {
                bestArea = area
                val cosBack = cos(edgeAngle)
                val sinBack = sin(edgeAngle)
                fun back(rx: Double, ry: Double) = PointD(rx * cosBack - ry * sinBack, rx * sinBack + ry * cosBack)
                bestPoints = listOf(
                    back(minX, minY), back(maxX, minY), back(maxX, maxY), back(minX, maxY),
                )
            }
        }
        return RotatedQuad(bestPoints)
    }

    /**
     * Porządkuje 4 wierzchołki prostokąta jako [góra-lewo, góra-prawo, dół-prawo, dół-lewo] - port
     * 1:1 logiki QuadGeometry.orderMinAreaRectPoints z oficjalnego demo (Apache 2.0), tylko z
     * org.opencv.core.Point zamienionym na PointD.
     */
    fun orderQuadPoints(points: List<PointD>): List<PointD> {
        val sorted = points.sortedBy { it.x }
        val (topLeft, bottomLeft) = if (sorted[1].y > sorted[0].y) sorted[0] to sorted[1] else sorted[1] to sorted[0]
        val (topRight, bottomRight) = if (sorted[3].y > sorted[2].y) sorted[2] to sorted[3] else sorted[3] to sorted[2]
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * Rozszerza wielokąt na zewnątrz o odległość proporcjonalną do stosunku pole/obwód * unclipRatio
     * (odpowiednik Clipper "polygon offsetting" użytego w PaddleOCR). Port 1:1 logiki
     * PolygonUnclip.unclip z oficjalnego demo (Apache 2.0) - algorytm był już tam w czystym
     * Kotlinie, jedyna zmiana to typ punktu (org.opencv.core.Point -> PointD).
     */
    fun unclipPolygon(points: List<PointD>, unclipRatio: Float): List<PointD> {
        if (points.size < 3) return points
        val eps = 1e-6

        val signedArea = run {
            var sum = 0.0
            for (i in points.indices) {
                val p1 = points[i]
                val p2 = points[(i + 1) % points.size]
                sum += p1.x * p2.y - p2.x * p1.y
            }
            sum / 2.0
        }
        val area = abs(signedArea)
        val perimeter = run {
            var length = 0.0
            for (i in points.indices) {
                val p1 = points[i]
                val p2 = points[(i + 1) % points.size]
                length += hypot(p2.x - p1.x, p2.y - p1.y)
            }
            length
        }
        if (!area.isFinite() || !perimeter.isFinite() || area <= eps || perimeter <= eps) return points

        val distance = area * unclipRatio / perimeter
        if (!distance.isFinite() || distance <= eps) return points

        val clockwise = signedArea > 0.0
        val normals = mutableListOf<PointD>()
        for (i in points.indices) {
            val start = points[i]
            val end = points[(i + 1) % points.size]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val len = hypot(dx, dy)
            if (!len.isFinite() || len <= eps) return points
            normals.add(if (clockwise) PointD(dy / len, -dx / len) else PointD(-dy / len, dx / len))
        }

        val expanded = mutableListOf<PointD>()
        fun appendPoint(p: PointD) {
            val last = expanded.lastOrNull()
            if (last == null || hypot(p.x - last.x, p.y - last.y) > eps) expanded.add(p)
        }
        fun arcStepAngle(): Double {
            val arcTolerance = 0.25
            val ratio = (1.0 - arcTolerance / distance).coerceIn(-1.0, 1.0)
            val step = 2.0 * acos(ratio)
            return if (step.isFinite() && step > eps) step else PI / 8.0
        }

        for (i in points.indices) {
            val center = points[i]
            val fromNormal = normals[(i - 1 + normals.size) % normals.size]
            val toNormal = normals[i]
            var startAngle = atan2(fromNormal.y, fromNormal.x)
            var endAngle = atan2(toNormal.y, toNormal.x)
            if (clockwise) {
                while (endAngle < startAngle) endAngle += 2.0 * PI
            } else {
                while (endAngle > startAngle) endAngle -= 2.0 * PI
            }
            val sweep = endAngle - startAngle
            val steps = ceil(abs(sweep) / arcStepAngle()).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                val angle = startAngle + sweep * step.toDouble() / steps.toDouble()
                appendPoint(PointD(center.x + cos(angle) * distance, center.y + sin(angle) * distance))
            }
        }
        return if (expanded.size >= 3) expanded else points
    }

    /**
     * Średnia wartość mapy prawdopodobieństwa wewnątrz wielokąta - wypełnienie skanline
     * (standardowa technika rasteryzacji wielokątów), odpowiednik cv2.fillPoly + cv2.mean.
     */
    fun polygonMeanScore(probMap: FloatArray, mapWidth: Int, mapHeight: Int, points: List<PointD>): Float {
        if (points.isEmpty()) return 0f
        val ys = points.map { it.y }
        val yMin = ys.min().let { kotlin.math.floor(it).toInt() }.coerceIn(0, mapHeight - 1)
        val yMax = ys.max().let { kotlin.math.ceil(it).toInt() }.coerceIn(0, mapHeight - 1)

        var sum = 0.0
        var count = 0
        for (y in yMin..yMax) {
            val yc = y + 0.5
            val intersections = mutableListOf<Double>()
            for (i in points.indices) {
                val p1 = points[i]
                val p2 = points[(i + 1) % points.size]
                if ((p1.y <= yc && p2.y > yc) || (p2.y <= yc && p1.y > yc)) {
                    val t = (yc - p1.y) / (p2.y - p1.y)
                    intersections.add(p1.x + t * (p2.x - p1.x))
                }
            }
            intersections.sort()
            var i = 0
            while (i + 1 < intersections.size) {
                val xStart = kotlin.math.floor(intersections[i]).toInt().coerceIn(0, mapWidth - 1)
                val xEnd = kotlin.math.ceil(intersections[i + 1]).toInt().coerceIn(0, mapWidth - 1)
                for (x in xStart..xEnd) {
                    sum += probMap[y * mapWidth + x]
                    count++
                }
                i += 2
            }
        }
        return if (count > 0) (sum / count).toFloat() else 0f
    }

    /** Port 1:1 MathUtils.roundHalfToEven z oficjalnego demo - zaokrąglanie "do parzystej", tak
     *  jak robi to numpy/OpenCV przy skalowaniu wymiarów, żeby wyniki zgadzały się z referencją. */
    fun roundHalfToEven(value: Double): Int {
        val floored = kotlin.math.floor(value)
        val diff = value - floored
        return when {
            diff < 0.5 -> floored.toInt()
            diff > 0.5 -> floored.toInt() + 1
            floored.toInt() % 2 == 0 -> floored.toInt()
            else -> floored.toInt() + 1
        }
    }
}
