import com.dustedrob.uwb.BleDiscoveryConfig
import com.dustedrob.uwb.DeviceDiscoveryManager
import com.dustedrob.uwb.DiscoveryEvent
import com.dustedrob.uwb.LOCAL_PROFILE
import com.dustedrob.uwb.ManagerFactory
import com.dustedrob.uwb.NearbyDevice
import com.dustedrob.uwb.UwbSessionConfig
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
import dev.icerock.moko.permissions.bluetooth.BLUETOOTH_ADVERTISE
import dev.icerock.moko.permissions.bluetooth.BLUETOOTH_CONNECT
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
        // Phone-to-phone over LOCAL_PROFILE, plus the Qorvo accessory (app-owned profile).
        managerFactory.createBleManager(
            BleDiscoveryConfig(
                profiles = listOf(LOCAL_PROFILE, QorvoNearbyProfile),
                // Opt in to the Android accessory protocol (the bespoke write-init / notify-back
                // exchange). It is OFF by default in the library because on Android it only works
                // against compatible custom accessory firmware (see the "Accessory ranging" section in
                // the README). With it off, QorvoNearbyProfile above is ignored on Android and only
                // phone-to-phone ranging runs; this sample turns it on so it can range with a flashed
                // Qorvo/NXP board. It is Android-only: iOS ranges with the accessory via Apple's
                // standard protocol whether or not this is set.
                enableAndroidAccessoryProtocol = true,
            )
        )
    )
    var permissionState by mutableStateOf(PermissionState.NotDetermined)
        private set

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _nearbyDevices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices = _nearbyDevices.asStateFlow()

    private val _connectionEvents = MutableStateFlow<List<DiscoveryEvent>>(emptyList())
    val connectionEvents = _connectionEvents.asStateFlow()

    private val _localConfig = MutableStateFlow<UwbSessionConfig?>(null)
    val localConfig = _localConfig.asStateFlow()

    init {
        // Observe nearby devices
        viewModelScope.launch {
            deviceDiscoveryManager.nearbyDevices.collect { devices ->
                _nearbyDevices.value = devices
            }
        }
        // Observe lifecycle events
        viewModelScope.launch {
            deviceDiscoveryManager.events.collect { event ->
                _connectionEvents.value = _connectionEvents.value + event
            }
        }
    }

    fun clearLog() {
        _connectionEvents.value = emptyList()
    }

    fun toggleScanning() {
        viewModelScope.launch {
            if (!_isScanning.value) {
                // Check permissions before starting scan
                if (checkAndRequestPermissions()) {
                    startUwbScanning()
                    _isScanning.value = true
                    _localConfig.value = deviceDiscoveryManager.getLocalConfig()
                }
            } else {
                stopUwbScanning()
                _isScanning.value = false
            }
        }
    }

    private suspend fun checkAndRequestPermissions(): Boolean {
        return try {
            controller.providePermission(Permission.BLUETOOTH_SCAN)
            controller.providePermission(Permission.BLUETOOTH_ADVERTISE)
            controller.providePermission(Permission.BLUETOOTH_CONNECT)
            // UWB_RANGING is a separate runtime permission not covered by moko / "Nearby devices".
            if (!ensureUwbRangingPermission()) {
                permissionState = PermissionState.Denied
                return false
            }
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

    private suspend fun startUwbScanning() {
        deviceDiscoveryManager.startScanning()
    }

    private suspend fun stopUwbScanning() {
        deviceDiscoveryManager.stopScanning()
    }

    override fun onCleared() {
        super.onCleared()
        deviceDiscoveryManager.cleanup()
    }
}
