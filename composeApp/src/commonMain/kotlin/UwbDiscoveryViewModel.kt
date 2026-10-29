import com.dustedrob.uwb.BleDiscoveryConfig
import com.dustedrob.uwb.DeviceDiscoveryManager
import com.dustedrob.uwb.DiscoveryEvent
import com.dustedrob.uwb.LOCAL_PROFILE
import com.dustedrob.uwb.ManagerFactory
import com.dustedrob.uwb.NearbyDevice
import com.dustedrob.uwb.RecoveryPolicy
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
        ),
        // Defaults, spelled out so the knobs are visible in the sample: a session that ends on its
        // own is restarted up to three times with doubling backoff, and a peer that claims to be
        // ranging but reports nothing for 10 s is treated as ended.
        RecoveryPolicy(),
        // Range with everything we discover. An app that wants to choose instead passes false and
        // calls connectPeer() from its own UI, which is what the Connect button below drives once a
        // device has been disconnected. To narrow what is even listed, use
        // BleDiscoveryConfig(deviceFilter = { _, name -> "(42)" in name }) above: that runs in the
        // BLE scan callbacks, before any UWB session scope is minted for the device.
        autoConnect = true,
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
                _connectionEvents.value = (_connectionEvents.value + event).takeLast(MAX_LOG_EVENTS)
            }
        }
    }

    private companion object {
        /**
         * The log is a debugging aid, and some events arrive continuously (iOS convergence updates
         * fire until the angles settle). Unbounded it grew without limit and copied the whole list on
         * every event, which showed up as render-thread pressure in Compose's text layer on a long
         * session with several peers.
         */
        const val MAX_LOG_EVENTS = 200
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
                    //_localConfig.value = deviceDiscoveryManager.getConnectionConfig()
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

    /** Range with a discovered peer the app chose. No-op if it is already exchanging or ranging. */
    fun connectDevice(peerId: String) {
        viewModelScope.launch { deviceDiscoveryManager.connectPeer(peerId) }
    }

    /**
     * Stop ranging with one peer but keep it listed, so it can be reconnected without rescanning.
     * With autoConnect on, the device sits Discovered until the stale purge drops it, so reconnect
     * within the 10 s window or let it be re-discovered and started again automatically.
     */
    fun disconnectDevice(peerId: String) {
        viewModelScope.launch { deviceDiscoveryManager.disconnectPeer(peerId) }
    }

    /** Restart one peer's session now, e.g. from a retry button on a failed device. */
    fun retryDevice(peerId: String) {
        viewModelScope.launch { deviceDiscoveryManager.restartPeer(peerId) }
    }

    /**
     * Give every failed peer another go. Called when the app comes back to the foreground: Android
     * ends the UWB sessions of a backgrounded app, so those peers have usually spent their automatic
     * attempts on retries that could never succeed while we were in the background.
     */
    fun onForeground() {
        if (!_isScanning.value) return
        viewModelScope.launch { deviceDiscoveryManager.restartFailedPeers() }
    }

    override fun onCleared() {
        super.onCleared()
        //deviceDiscoveryManager.cleanup()
    }
}
