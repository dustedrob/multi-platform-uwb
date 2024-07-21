import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MyViewModel: ViewModel() {
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    fun toggleScanning() {
        coroutineScope.launch {
            _isScanning.value = !_isScanning.value
            if (_isScanning.value) {
                startUwbScanning()
            } else {
                stopUwbScanning()
            }
        }
    }

    private suspend fun startUwbScanning() {
        startUwb()
    }

    private suspend fun stopUwbScanning() {
        stopUwb()
    }
}