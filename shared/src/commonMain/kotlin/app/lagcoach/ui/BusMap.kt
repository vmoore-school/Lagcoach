package app.lagcoach.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.lagcoach.data.BBox
import app.lagcoach.data.LiveVehicle
import app.lagcoach.model.StopRef

/** Move the camera; a new [nonce] re-applies the same target. */
data class MapFocus(val lat: Double, val lon: Double, val zoom: Double, val nonce: Long)

data class MapColors(
    val stopFill: Long,
    val stopRing: Long,
    val bus: Long,
    val busText: Long,
    val busRing: Long,
    val highlight: Long,
    val me: Long,
    val route: Long,
)

/**
 * Map of stops and live buses. Android uses MapLibre with OpenFreeMap tiles (no Google
 * services or API key needed).
 */
@Composable
expect fun BusMap(
    modifier: Modifier,
    dark: Boolean,
    colors: MapColors,
    stops: List<StopRef>,
    vehicles: List<LiveVehicle>,
    me: Pair<Double, Double>?,
    highlightVehicle: Long?,
    /** A bus's path to trace, as (lat, lon); empty for none. */
    route: List<Pair<Double, Double>>,
    focus: MapFocus?,
    initial: MapFocus,
    onCamera: (BBox, Double) -> Unit,
    onStopTap: (StopRef) -> Unit,
    onVehicleTap: (LiveVehicle) -> Unit,
    /** A tap that didn't land on a bus or stop. */
    onMapTap: () -> Unit,
)
