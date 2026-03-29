package uk.co.fueller.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import uk.co.fueller.app.data.StationWithPrices

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    station: StationWithPrices?,
    allStations: List<StationWithPrices>,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir
            osmdroidTileCache = context.cacheDir.resolve("tiles")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(station?.name ?: "Map")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createMapView(ctx, station, allStations)
                }
            )
        }
    }
}

private fun createMapView(
    context: Context,
    focusStation: StationWithPrices?,
    allStations: List<StationWithPrices>
): MapView {
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)

        controller.setZoom(14.0)

        // Centre on the selected station or first station
        val centre = focusStation ?: allStations.firstOrNull()
        if (centre != null) {
            controller.setCenter(GeoPoint(centre.latitude, centre.longitude))
        }

        // Add markers for all stations
        allStations.forEach { station ->
            val marker = Marker(this).apply {
                position = GeoPoint(station.latitude, station.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = station.name

                val priceText = station.prices
                    .joinToString("\n") { "${it.fuelLabel}: ${it.pencePerLitre}p" }
                    .ifEmpty { "No price data" }
                snippet = "${station.address}\n$priceText"
            }
            overlays.add(marker)
        }

        // Highlight the selected station
        if (focusStation != null) {
            val selected = overlays.filterIsInstance<Marker>()
                .find { it.position.latitude == focusStation.latitude && it.position.longitude == focusStation.longitude }
            selected?.showInfoWindow()
        }
    }
}
