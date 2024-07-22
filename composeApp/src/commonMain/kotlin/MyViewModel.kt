import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.icerock.moko.permissions.DeniedAlwaysException
import dev.icerock.moko.permissions.DeniedException
import dev.icerock.moko.permissions.Permission
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.PermissionsController
import dev.icerock.moko.permissions.RequestCanceledException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MyViewModel(val controller: PermissionsController) : ViewModel() {
    var permissionState by mutableStateOf(PermissionState.NotDetermined)
        private set

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

    fun requestPermissions(){
        viewModelScope.launch {
            try {
                controller.getPermissionState(Permission.BLUETOOTH_LE)
                permissionState = PermissionState.Granted
            } catch (e: DeniedAlwaysException){
                permissionState = PermissionState.DeniedAlways
            }
            catch (e: DeniedException){
                permissionState = PermissionState.Denied
            }
            catch (e: RequestCanceledException) {
                e.printStackTrace()
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