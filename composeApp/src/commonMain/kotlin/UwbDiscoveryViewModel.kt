import com.dustedrob.uwb.DeviceDiscoveryManager
import com.dustedrob.uwb.ManagerFactory
import com.dustedrob.uwb.NearbyDevice
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
import dev.icerock.moko.permissions.bluetooth.BLUETOOTH_SCAN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class UwbDiscoveryViewModel(
    private val controller: PermissionsController,
    private val managerFactory: ManagerFactory
) : ViewModel() {
    private val deviceDiscoveryManager = DeviceDiscoveryManager(
        managerFactory.createUwbManager(),
        managerFactory.createBleManager()
    )
    var permissionState by mutableStateOf(PermissionState.NotDetermined)
        private set

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _nearbyDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices = _nearbyDevices.asStateFlow()

    init {
        // Observe nearby devices
        viewModelScope.launch {
            deviceDiscoveryManager.nearbyDevices.collect { devices ->
                _nearbyDevices.value = devices
            }
        }
    }

    fun toggleScanning() {
        viewModelScope.launch {
            if (!_isScanning.value) {
                // Check permissions before starting scan
                if (checkAndRequestPermissions()) {
                    startUwbScanning()
                    _isScanning.value = true
                }
            } else {
                stopUwbScanning()
                _isScanning.value = false
            }
        }
    }

    private suspend fun checkAndRequestPermissions(): Boolean {
        return try {
            // Request Bluetooth scan permission (needed for BLE scanning)
            controller.providePermission(Permission.BLUETOOTH_SCAN)
            // Note: You might need to add UWB permission to moko-permissions library
            // or handle it separately using platform-specific code
            permissionState = PermissionState.Granted
            true
        } catch (e: DeniedAlwaysException) {
            permissionState = PermissionState.DeniedAlways
            false
        } catch (e: DeniedException) {
            permissionState = PermissionState.Denied
            false
        } catch (e: RequestCanceledException) {
            e.printStackTrace()
            false
        }
    }

    fun requestPermissions() {
        viewModelScope.launch {
            checkAndRequestPermissions()
        }
    }

    private suspend fun startUwbScanning() {
        deviceDiscoveryManager.startScanning()
    }

    private suspend fun stopUwbScanning() {
        deviceDiscoveryManager.stopScanning()
    }

    override fun onCleared() {
        super.onCleared()
        deviceDiscoveryManager.stopScanning()
    }
}
