import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

actual class UwbClient(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val uwbClient = UwbClient.create(context)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    private var bleScanning = false

    actual fun startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            println("Bluetooth is not available or not enabled.")
            return
        }

        bleScanning = true
        println("Starting BLE device scan...")
        bluetoothAdapter?.startDiscovery()
    }

    actual fun connectToDevice(device: UwbDevice) {
        coroutineScope.launch {
            val connectionResult = uwbClient.connect(device)
            connectionResult.onSuccess {
                println("Connected to UWB device: ${device.address}")
            }.onFailure { error ->
                println("Failed to connect to UWB device: ${error.message}")
            }
        }
    }

    actual fun stopScanning() {
        if (bleScanning) {
            bluetoothAdapter?.cancelDiscovery()
            bleScanning = false
            println("Stopped BLE device scan.")
        }
    }
}