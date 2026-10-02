package dev.offgridmesh.maps

import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled style (§6): background, land use, water, roads and labels for the Protomaps basemap schema,
 * plus the app's own GeoJSON layers (grid, nodes, pins). Fonts load from assets, so rendering needs no
 * network once a pack is on the phone. One light palette on purpose: it reads best in sunlight.
 */
object MapStyle {
    const val GRID = "grid"
    const val NODES = "nodes"
    const val PINS = "pins"
    const val PIN_LAYER = "pins-circle"
    const val NODE_LAYER = "nodes-circle"

    private const val FONT = "Noto Sans Regular"
    private const val LAND = "#f2efe9"
    private const val WATER = "#a4cbe0"

    /** [tileUrl] is `mbtiles:///…` for a pack, `pmtiles://https://…` for the online area picker, or null for grid only. */
    fun build(tileUrl: String?): String {
        val sources = JSONObject()
        val layers = JSONArray()
        layers.put(layer("background", "background", null).put("paint", JSONObject().put("background-color", LAND)))

        if (tileUrl != null) {
            sources.put("base", JSONObject().put("type", "vector").put("url", tileUrl).put("attribution", MapPackManager.ATTRIBUTION))
            layers.put(
                fill("landuse", "landuse", "#d8e8c8")
                    .put("filter", inKind("park", "nature_reserve", "forest", "wood", "golf_course", "cemetery", "grass", "meadow", "national_park", "protected_area")),
            )
            layers.put(fill("water", "water", WATER).put("filter", geom("Polygon")))
            layers.put(
                line("water-lines", "water", WATER, interp(8 to 0.5, 15 to 3.0)).put("filter", geom("LineString")),
            )
            layers.put(fill("buildings", "buildings", "#e0dbd2").put("minzoom", 14))
            layers.put(
                line("boundaries", "boundaries", "#9e9cab", interp(3 to 0.6, 10 to 1.5))
                    .apply { getJSONObject("paint").put("line-dasharray", JSONArray(listOf(3, 2))) },
            )
            layers.put(
                line("roads-path", "roads", "#a08c74", interp(13 to 0.6, 18 to 2.0))
                    .put("minzoom", 13)
                    .put("filter", inKind("path"))
                    .apply { getJSONObject("paint").put("line-dasharray", JSONArray(listOf(2, 1))) },
            )
            layers.put(
                line("roads-casing", "roads", "#c3bba9", interp(7 to 1.0, 12 to 2.5, 16 to 10.0))
                    .put("filter", inKind("highway", "major_road", "minor_road")),
            )
            layers.put(line("roads-minor", "roads", "#ffffff", interp(11 to 0.8, 16 to 7.0)).put("minzoom", 11).put("filter", inKind("minor_road")))
            layers.put(line("roads-major", "roads", "#fbe3a1", interp(7 to 0.8, 12 to 2.0, 16 to 8.0)).put("filter", inKind("major_road")))
            layers.put(line("roads-highway", "roads", "#f3a65a", interp(5 to 0.8, 12 to 2.5, 16 to 9.0)).put("filter", inKind("highway")))
            layers.put(
                symbol("road-labels", "roads", 11.0, "#4a4a4a")
                    .put("minzoom", 13)
                    .apply { getJSONObject("layout").put("symbol-placement", "line") },
            )
            layers.put(
                symbol("place-labels", "places", 13.0, "#222222")
                    .put("filter", inKind("locality", "neighbourhood", "macrohood", "region", "country"))
                    .apply {
                        getJSONObject("layout").put(
                            "text-size",
                            JSONArray("""["match", ["get", "kind"], "country", 15, "region", 14, "locality", 13, 11]"""),
                        )
                    },
            )
        }

        // App overlays, always present; data is pushed at runtime.
        listOf(GRID, NODES, PINS).forEach { sources.put(it, emptyGeoJson()) }
        layers.put(
            JSONObject().put("id", "grid-lines").put("type", "line").put("source", GRID)
                .put("paint", JSONObject().put("line-color", "#b0a99a").put("line-width", 1)),
        )
        layers.put(
            JSONObject().put("id", "grid-labels").put("type", "symbol").put("source", GRID)
                .put(
                    "layout",
                    JSONObject().put("text-field", JSONArray("""["get", "label"]""")).put("text-font", JSONArray(listOf(FONT)))
                        .put("text-size", 11).put("symbol-placement", "line"),
                )
                .put("paint", JSONObject().put("text-color", "#7a7366").put("text-halo-color", LAND).put("text-halo-width", 1.5)),
        )
        layers.put(
            JSONObject().put("id", NODE_LAYER).put("type", "circle").put("source", NODES)
                .put(
                    "paint",
                    JSONObject().put("circle-radius", 7).put("circle-color", "#00838f")
                        .put("circle-stroke-color", "#ffffff").put("circle-stroke-width", 2)
                        .put("circle-opacity", JSONArray("""["case", ["get", "stale"], 0.4, 1.0]""")),
                ),
        )
        layers.put(overlayLabel("node-labels", NODES, "name", "#005f66"))
        layers.put(
            JSONObject().put("id", PIN_LAYER).put("type", "circle").put("source", PINS)
                .put(
                    "paint",
                    JSONObject().put("circle-radius", 10)
                        .put(
                            "circle-color",
                            JSONArray(
                                """["match", ["get", "type"], "HAZARD", "#d32f2f", "RALLY_POINT", "#2e7d32",
                                   "RESOURCE", "#1565c0", "#6a1b9a"]""",
                            ),
                        )
                        .put("circle-stroke-color", "#ffffff").put("circle-stroke-width", 2.5),
                ),
        )
        layers.put(overlayLabel("pin-labels", PINS, "label", "#1a1a1a"))

        return JSONObject()
            .put("version", 8)
            .put("glyphs", "asset://glyphs/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun emptyGeoJson() = JSONObject().put("type", "geojson")
        .put("data", JSONObject().put("type", "FeatureCollection").put("features", JSONArray()))

    private fun layer(id: String, type: String, sourceLayer: String?) = JSONObject().put("id", id).put("type", type).apply {
        if (sourceLayer != null) put("source", "base").put("source-layer", sourceLayer)
    }

    private fun fill(id: String, src: String, color: String) =
        layer(id, "fill", src).put("paint", JSONObject().put("fill-color", color))

    private fun line(id: String, src: String, color: String, width: JSONArray) =
        layer(id, "line", src)
            .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round"))
            .put("paint", JSONObject().put("line-color", color).put("line-width", width))

    private fun symbol(id: String, src: String, size: Double, color: String) =
        layer(id, "symbol", src)
            .put(
                "layout",
                JSONObject().put("text-field", JSONArray("""["get", "name"]""")).put("text-font", JSONArray(listOf(FONT)))
                    .put("text-size", size),
            )
            .put("paint", JSONObject().put("text-color", color).put("text-halo-color", "#ffffff").put("text-halo-width", 1.5))

    private fun overlayLabel(id: String, source: String, field: String, color: String) =
        JSONObject().put("id", id).put("type", "symbol").put("source", source)
            .put(
                "layout",
                JSONObject().put("text-field", JSONArray("""["get", "$field"]""")).put("text-font", JSONArray(listOf(FONT)))
                    .put("text-size", 13).put("text-offset", JSONArray(listOf(0, 1.3))).put("text-anchor", "top")
                    .put("text-allow-overlap", false),
            )
            .put("paint", JSONObject().put("text-color", color).put("text-halo-color", "#ffffff").put("text-halo-width", 2))

    private fun inKind(vararg kinds: String) =
        JSONArray().put("in").put(JSONArray().put("get").put("kind")).put(JSONArray().put("literal").put(JSONArray(kinds.toList())))

    private fun geom(type: String) = JSONArray().put("==").put(JSONArray().put("geometry-type")).put(type)

    private fun interp(vararg stops: Pair<Int, Double>) = JSONArray().put("interpolate").put(JSONArray().put("exponential").put(1.5))
        .put(JSONArray().put("zoom")).apply { stops.forEach { (z, w) -> put(z).put(w) } }
}
