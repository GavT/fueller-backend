package uk.co.fueller.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import uk.co.fueller.app.viewmodel.FuelViewModel

@Composable
fun FuellerApp(viewModel: FuelViewModel = viewModel()) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsState()

    NavHost(navController = navController, startDestination = "search") {
        composable("search") {
            FuelSearchScreen(
                state = state,
                onPostcodeChanged = viewModel::onPostcodeChanged,
                onSearch = viewModel::search,
                onStationClick = { station ->
                    viewModel.selectStation(station)
                    navController.navigate("map")
                }
            )
        }
        composable("map") {
            MapScreen(
                station = state.selectedStation,
                allStations = state.stations,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
