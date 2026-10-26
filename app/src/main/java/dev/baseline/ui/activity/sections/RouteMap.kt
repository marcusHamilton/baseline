package dev.baseline.ui.activity.sections

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
const val FALLBACK_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val ROUTE_BLUE = "#60A5FA"
private const val START_GREEN = "#34D399"
private const val BG = "#0E1013"
private const val PADDING_PX = 48

/**
 * The route on an OpenFreeMap basemap: a blue line, a green start dot, camera fitted to the route. It is a still picture
 * (gestures off) so the page keeps scrolling; the attribution button stays on, as the tile data licence asks.
 * [latLon] is (latitude, longitude) pairs, at least two.
 */
@Composable
fun RouteMap(latLon: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    // A new route gets a fresh MapView (and a fresh lifecycle registration) instead of reusing a styled one.
    key(latLon) {
        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val mapView = remember { MapLibre.getInstance(context); MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true).foregroundLoadColor(android.graphics.Color.parseColor(BG))) }
        DisposableEffect(lifecycle, mapView) {
            var started = false
            var resumed = false
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> { mapView.onStart(); started = true }
                    Lifecycle.Event.ON_RESUME -> { mapView.onResume(); resumed = true }
                    Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                    Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                    else -> {}
                }
            }
            val memory = object : ComponentCallbacks2 {
                @Suppress("OVERRIDE_DEPRECATION") // the only signal MapView.onLowMemory is documented for
                override fun onLowMemory() = mapView.onLowMemory()
                override fun onTrimMemory(level: Int) {}
                override fun onConfigurationChanged(newConfig: Configuration) {}
            }
            context.registerComponentCallbacks(memory)
            mapView.onCreate(null) // in the effect, before the observer replays START/RESUME
            lifecycle.addObserver(observer) // replays the events up to the current state
            onDispose {
                lifecycle.removeObserver(observer)
                context.unregisterComponentCallbacks(memory)
                // Leaving the screen while the host lives on: walk the map down ourselves, exactly once each.
                if (resumed) mapView.onPause()
                if (started) mapView.onStop()
                mapView.onDestroy()
            }
        }
        // getMapAsync queues until the map exists, so the style setup can run at view creation.
        AndroidView(
            { mapView.also { setUp(it, latLon) } },
            modifier.testTag("route-map").semantics { contentDescription = "Map of the route, starting at the green dot" },
        )
    }
}

// Texture mode (set where the view is made) draws into a TextureView, which scrolls and clips with the page.
private fun setUp(view: MapView, latLon: List<Pair<Double, Double>>) {
    view.getMapAsync { map ->
        map.uiSettings.setAllGesturesEnabled(false)
        map.uiSettings.isCompassEnabled = false
        var styleLoaded = false
        var fellBack = false
        fun load(url: String) = map.setStyle(Style.Builder().fromUri(url)) { style ->
            styleLoaded = true
            drawRoute(map, style, latLon)
        }
        view.addOnDidFailLoadingMapListener {
            // The dark style may be gone: switch to the light one once, and only while no style has shown.
            if (!styleLoaded && !fellBack) { fellBack = true; load(FALLBACK_STYLE) }
        }
        load(DARK_STYLE)
    }
}

private fun drawRoute(map: MapLibreMap, style: Style, latLon: List<Pair<Double, Double>>) {
    val points = latLon.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
    style.addSource(GeoJsonSource("route", Feature.fromGeometry(LineString.fromLngLats(points))))
    style.addLayer(
        LineLayer("route-line", "route").withProperties(
            lineColor(ROUTE_BLUE), lineWidth(4f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addSource(GeoJsonSource("route-start", Feature.fromGeometry(points.first())))
    style.addLayer(
        CircleLayer("route-start-dot", "route-start").withProperties(
            circleRadius(7f), circleColor(START_GREEN), circleStrokeColor(BG), circleStrokeWidth(2f),
        ),
    )
    val bounds = LatLngBounds.Builder().includes(latLon.map { (lat, lon) -> LatLng(lat, lon) }).build()
    val tiny = bounds.latitudeSpan < 1e-4 && bounds.longitudeSpan < 1e-4 // a route that never left one spot
    map.moveCamera(if (tiny) CameraUpdateFactory.newLatLngZoom(bounds.center, 15.0) else CameraUpdateFactory.newLatLngBounds(bounds, PADDING_PX))
}
