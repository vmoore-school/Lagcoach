package app.lagcoach.ui

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lagcoach.data.BBox
import app.lagcoach.data.LiveVehicle
import app.lagcoach.model.StopRef
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STOPS = "lag-stops"
private const val BUSES = "lag-buses"
private const val ME = "lag-me"
private const val ROUTE = "lag-route"

private fun hex(c: Long) = String.format("#%06X", c and 0xFFFFFF)

@Composable
actual fun BusMap(
    modifier: Modifier,
    dark: Boolean,
    colors: MapColors,
    stops: List<StopRef>,
    vehicles: List<LiveVehicle>,
    me: Pair<Double, Double>?,
    highlightVehicle: Long?,
    route: List<Pair<Double, Double>>,
    focus: MapFocus?,
    initial: MapFocus,
    onCamera: (BBox, Double) -> Unit,
    onStopTap: (StopRef) -> Unit,
    onVehicleTap: (LiveVehicle) -> Unit,
    onMapTap: () -> Unit,
) {
    val ctx = LocalContext.current
    remember { MapLibre.getInstance(ctx.applicationContext) }
    val mapView = remember { MapView(ctx).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val latestStops by rememberUpdatedState(stops)
    val latestVehicles by rememberUpdatedState(vehicles)
    val cameraCb by rememberUpdatedState(onCamera)
    val stopCb by rememberUpdatedState(onStopTap)
    val busCb by rememberUpdatedState(onVehicleTap)
    val mapTapCb by rememberUpdatedState(onMapTap)

    // MapView needs the host lifecycle forwarded to it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.cameraPosition = CameraPosition.Builder().target(LatLng(initial.lat, initial.lon)).zoom(initial.zoom).build()
            m.addOnCameraIdleListener {
                val b = m.projection.visibleRegion.latLngBounds
                cameraCb(BBox(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast), m.cameraPosition.zoom)
            }
            m.addOnMapClickListener { ll ->
                val p = m.projection.toScreenLocation(ll)
                val r = RectF(p.x - 28, p.y - 28, p.x + 28, p.y + 28)
                m.queryRenderedFeatures(r, "$BUSES-dot").firstOrNull()?.let { f ->
                    val id = f.getNumberProperty("id")?.toLong()
                    latestVehicles.firstOrNull { it.id == id }?.let { busCb(it); return@addOnMapClickListener true }
                }
                m.queryRenderedFeatures(r, "$STOPS-dot").firstOrNull()?.let { f ->
                    val atco = f.getStringProperty("atco")
                    latestStops.firstOrNull { it.atco == atco }?.let { stopCb(it); return@addOnMapClickListener true }
                }
                mapTapCb()
                false
            }
        }
    }

    // Style follows light/dark mode.
    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        val url = if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
        m.setStyle(Style.Builder().fromUri(url)) { s ->
            s.addSource(GeoJsonSource(ROUTE))
            s.addSource(GeoJsonSource(STOPS))
            s.addSource(GeoJsonSource(BUSES))
            s.addSource(GeoJsonSource(ME))
            // Under the stops and buses, so they stay tappable on top of it.
            s.addLayer(
                LineLayer(ROUTE, ROUTE).withProperties(
                    PropertyFactory.lineColor(hex(colors.route)),
                    PropertyFactory.lineWidth(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(11, 3f), Expression.stop(17, 7f))),
                    PropertyFactory.lineOpacity(0.85f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            s.addLayer(
                CircleLayer("$STOPS-dot", STOPS).withProperties(
                    PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.zoom(),
                        Expression.stop(14, 3.5f), Expression.stop(17, 7f))),
                    PropertyFactory.circleColor(hex(colors.stopFill)),
                    PropertyFactory.circleStrokeColor(hex(colors.stopRing)),
                    PropertyFactory.circleStrokeWidth(2f),
                ).apply { minZoom = 13.5f },
            )
            s.addLayer(
                CircleLayer(ME, ME).withProperties(
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleColor(hex(colors.me)),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(3f),
                ),
            )
            s.addLayer(
                CircleLayer("$BUSES-dot", BUSES).withProperties(
                    PropertyFactory.circleRadius(Expression.switchCase(Expression.get("hl"), Expression.literal(17f), Expression.literal(13f))),
                    PropertyFactory.circleColor(Expression.switchCase(Expression.get("hl"), Expression.literal(hex(colors.highlight)), Expression.literal(hex(colors.bus)))),
                    PropertyFactory.circleStrokeColor(hex(colors.busRing)),
                    PropertyFactory.circleStrokeWidth(2f),
                ),
            )
            s.addLayer(
                SymbolLayer("$BUSES-label", BUSES).withProperties(
                    PropertyFactory.textField(Expression.get("line")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textColor(hex(colors.busText)),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
            )
            style = s
        }
    }

    LaunchedEffect(style, stops) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(STOPS)?.setGeoJson(
            FeatureCollection.fromFeatures(stops.mapNotNull { st ->
                val lat = st.lat ?: return@mapNotNull null
                val lon = st.lon ?: return@mapNotNull null
                Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addStringProperty("atco", st.atco) }
            }),
        )
    }
    LaunchedEffect(style, vehicles, highlightVehicle) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(BUSES)?.setGeoJson(
            FeatureCollection.fromFeatures(vehicles.map { v ->
                Feature.fromGeometry(Point.fromLngLat(v.lon, v.lat)).apply {
                    addNumberProperty("id", v.id)
                    addStringProperty("line", v.line)
                    addBooleanProperty("hl", v.id == highlightVehicle)
                }
            }),
        )
    }
    LaunchedEffect(style, route) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(
                    route.takeIf { it.size >= 2 }?.let { r -> Feature.fromGeometry(LineString.fromLngLats(r.map { Point.fromLngLat(it.second, it.first) })) },
                ),
            ),
        )
    }
    LaunchedEffect(style, me) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(me?.let { Feature.fromGeometry(Point.fromLngLat(it.second, it.first)) })),
        )
    }
    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.lat, f.lon), f.zoom), 600)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
