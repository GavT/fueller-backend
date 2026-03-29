package uk.co.fueller.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.co.fueller.app.data.StationWithPrices
import uk.co.fueller.app.viewmodel.FuelUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")
    .withZone(ZoneId.of("Europe/London"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelSearchScreen(
    state: FuelUiState,
    onPostcodeChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onStationClick: (StationWithPrices) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fueller") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            SearchBar(
                postcode = state.postcode,
                isLoading = state.isLoading,
                onPostcodeChanged = onPostcodeChanged,
                onSearch = onSearch
            )

            // Error
            if (state.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = state.error,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Data refresh info
            if (state.dataLastRefreshed != null) {
                DataRefreshInfo(
                    lastRefreshed = state.dataLastRefreshed,
                    nextRefresh = state.nextDataRefresh
                )
            }

            // Loading
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Results
            if (state.stations.isNotEmpty()) {
                Text(
                    text = "${state.stations.size} stations within 5 miles",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(state.stations, key = { it.nodeId }) { station ->
                    StationCard(station = station, onClick = { onStationClick(station) })
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    postcode: String,
    isLoading: Boolean,
    onPostcodeChanged: (String) -> Unit,
    onSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = postcode,
            onValueChange = onPostcodeChanged,
            modifier = Modifier.weight(1f),
            label = { Text("Enter postcode") },
            placeholder = { Text("e.g. SW1A 1AA") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
        )

        Spacer(modifier = Modifier.width(12.dp))

        Button(
            onClick = onSearch,
            enabled = !isLoading,
            modifier = Modifier.height(56.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search")
            Spacer(modifier = Modifier.width(4.dp))
            Text("Find Fuel")
        }
    }
}

@Composable
private fun DataRefreshInfo(lastRefreshed: String, nextRefresh: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Data updated: ${formatTimestamp(lastRefreshed)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (nextRefresh != null) {
            Text(
                text = "Next refresh: ${formatTimestamp(nextRefresh)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StationCard(station: StationWithPrices, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.LocalGasStation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = station.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (station.brand != null && station.brand != station.name) {
                            Text(
                                text = station.brand,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${station.distanceMiles} mi",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        Icons.Default.Map,
                        contentDescription = "View on map",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Address
            Text(
                text = station.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (station.temporaryClosure) {
                Text(
                    text = "Temporarily closed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Badges
            if (station.isSupermarket || station.isMotorway) {
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    if (station.isSupermarket) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Supermarket", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    if (station.isMotorway) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("Motorway", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            }

            // Prices
            if (station.prices.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                station.prices.forEach { price ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = price.fuelLabel,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${price.pencePerLitre}p",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (price.lastUpdated != null) {
                                Text(
                                    text = "Updated: ${formatTimestamp(price.lastUpdated)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "No price data available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun formatTimestamp(isoTimestamp: String): String {
    return try {
        val instant = Instant.parse(isoTimestamp)
        timeFormatter.format(instant)
    } catch (_: Exception) {
        isoTimestamp
    }
}
