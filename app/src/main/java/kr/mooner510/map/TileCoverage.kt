package kr.mooner510.map

import kr.mooner510.data.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Which tiles to save for a track: a corridor around the route rather than its bounding box, so a trip
 * across the country costs a few thousand tiles instead of hundreds of thousands of empty ones.
 */
object TileCoverage {
    /** Corridor half-width per zoom (km). Low zooms reach well past the route so the overview has context. */
    private fun radiusKm(z: Int): Double = when {
        z <= 10 -> 15.0
        z <= 12 -> 3.0
        z == 13 -> 1.5
        else -> 0.8
    }

    /** Longer gaps are flights, GPS outages or the like: their ends are covered, the line between them is not. */
    private const val MAX_DENSIFY_KM = 30.0

    fun tiles(source: String, points: List<TrackPoint>, minZoom: Int, maxZoom: Int): Set<TileKey> {
        val result = HashSet<TileKey>()
        for (z in minZoom..maxZoom) {
            val r = radiusKm(z)
            val step = r / 2
            samples(points, step, densify = z <= 10).forEach { (lat, lon) ->
                val dLat = r / 111.0
                val dLon = r / (111.0 * max(abs(cos(Math.toRadians(lat))), 0.2))
                val x0 = tileX(lon - dLon, z)
                val x1 = tileX(lon + dLon, z)
                val y0 = tileY(min(lat + dLat, MAX_LAT), z)
                val y1 = tileY(max(lat - dLat, -MAX_LAT), z)
                for (x in x0..x1) for (y in y0..y1) result += TileKey(source, z, x, y)
            }
        }
        return result
    }

    /** Points spaced roughly [step] km apart along the route: dense stretches thinned, short gaps filled in. */
    private fun samples(points: List<TrackPoint>, step: Double, densify: Boolean): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var last: Pair<Double, Double>? = null
        for (p in points) {
            val cur = p.latitude to p.longitude
            val prev = last
            if (prev == null) {
                out += cur
                last = cur
                continue
            }
            val d = distanceKm(prev, cur)
            if (d < step) continue
            if (d <= MAX_DENSIFY_KM || densify) {
                val n = floor(d / step).toInt()
                for (i in 1 until n) {
                    val f = i.toDouble() / n
                    out += (prev.first + (cur.first - prev.first) * f) to (prev.second + (cur.second - prev.second) * f)
                }
            }
            out += cur
            last = cur
        }
        return out
    }

    private fun distanceKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val dLat = Math.toRadians(b.first - a.first)
        val dLon = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * asin(sqrt(min(1.0, h)))
    }

    private fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    private fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val rad = Math.toRadians(lat)
        return floor((1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2 * n).toInt().coerceIn(0, n - 1)
    }

    private const val MAX_LAT = 85.05112878
}
