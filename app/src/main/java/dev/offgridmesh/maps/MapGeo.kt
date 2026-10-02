package dev.offgridmesh.maps

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** GeoJSON builders for the app overlays and the no-pack grid fallback (§6). Pure, unit-testable. */
object MapGeo {
    data class Point(val lat: Double, val lng: Double, val props: Map<String, Any?>)

    fun points(points: List<Point>): String {
        val features = JSONArray()
        points.forEach { p ->
            val props = JSONObject()
            p.props.forEach { (k, v) -> props.put(k, v ?: JSONObject.NULL) }
            features.put(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(p.lng).put(p.lat)))
                    .put("properties", props),
            )
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private val SPACINGS = doubleArrayOf(30.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1, 0.05, 0.02, 0.01, 0.005, 0.002, 0.001, 0.0005)

    /** Grid spacing (degrees) giving roughly 4–10 lines across [spanDegrees]. */
    fun gridSpacing(spanDegrees: Double): Double = SPACINGS.firstOrNull { spanDegrees / it >= 4 } ?: SPACINGS.last()

    /** Graticule over the visible box: a blank screen is useless, a grid with true relative positions is not. */
    fun grid(b: Bbox): String {
        val span = maxOf(b.east - b.west, b.north - b.south)
        val step = gridSpacing(span)
        val features = JSONArray()
        fun line(coords: List<Pair<Double, Double>>, label: String) {
            val c = JSONArray()
            coords.forEach { (lng, lat) -> c.put(JSONArray().put(lng).put(lat)) }
            features.put(
                JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "LineString").put("coordinates", c))
                    .put("properties", JSONObject().put("label", label)),
            )
        }
        val digits = maxOf(0, -floor(ln(step) / ln(10.0)).toInt())
        fun fmt(v: Double) = "%.${digits}f°".format(v)
        var lng = floor(b.west / step) * step
        while (lng <= b.east) {
            line(listOf(lng to b.south.coerceAtLeast(-85.0), lng to b.north.coerceAtMost(85.0)), fmt(lng))
            lng += step
        }
        var lat = floor(b.south / step) * step
        while (lat <= b.north) {
            if (lat in -85.0..85.0) line(listOf(b.west to lat, b.east to lat), fmt(lat))
            lat += step
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    /** Largest 1/2/5×10ⁿ metres not longer than [maxMeters], for the scale bar. */
    fun niceDistance(maxMeters: Double): Double {
        if (maxMeters <= 0) return 0.0
        val pow = 10.0.pow(floor(kotlin.math.log10(maxMeters)))
        return listOf(5.0, 2.0, 1.0).map { it * pow }.first { it <= maxMeters }
    }

    fun formatDistance(m: Double): String = if (m >= 1000) "${(m / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km" else "${m.toInt()} m"
}
