package uk.co.fueller.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import uk.co.fueller.app.data.FuelApi
import uk.co.fueller.app.data.StationWithPrices

data class FuelUiState(
    val postcode: String = "",
    val isLoading: Boolean = false,
    val stations: List<StationWithPrices> = emptyList(),
    val error: String? = null,
    val dataLastRefreshed: String? = null,
    val nextDataRefresh: String? = null,
    val selectedStation: StationWithPrices? = null
)

class FuelViewModel : ViewModel() {

    private val api = FuelApi.create()

    private val _state = MutableStateFlow(FuelUiState())
    val state: StateFlow<FuelUiState> = _state

    fun onPostcodeChanged(postcode: String) {
        _state.value = _state.value.copy(postcode = postcode)
    }

    fun search() {
        val postcode = _state.value.postcode.trim()
        if (postcode.isBlank()) {
            _state.value = _state.value.copy(error = "Please enter a postcode")
            return
        }

        _state.value = _state.value.copy(isLoading = true, error = null, stations = emptyList())

        viewModelScope.launch {
            try {
                val response = api.search(postcode, radius = 5.0)
                _state.value = _state.value.copy(
                    isLoading = false,
                    stations = response.stations,
                    dataLastRefreshed = response.dataLastRefreshed,
                    nextDataRefresh = response.nextDataRefresh
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to fetch fuel prices"
                )
            }
        }
    }

    fun selectStation(station: StationWithPrices?) {
        _state.value = _state.value.copy(selectedStation = station)
    }
}
