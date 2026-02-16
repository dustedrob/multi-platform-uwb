import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dustedrob.uwb.NearbyDevice
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory

@Composable
fun App() {
    val factory = rememberPermissionsControllerFactory()
    val controller = remember(factory){
        factory.createPermissionsController()
    }
    BindEffect(controller)
    val managerFactory = createManagerFactory()
    val viewModel: UwbDiscoveryViewModel = viewModel{
        UwbDiscoveryViewModel(controller, managerFactory)
    }
    val isScanning by viewModel.isScanning.collectAsState()
    val nearbyDevices by viewModel.nearbyDevices.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(onClick = {
            viewModel.requestPermissions()
            if (viewModel.permissionState == PermissionState.Granted) {
                viewModel.toggleScanning()
            }
        }) {
            Text(if (isScanning) "Stop Scanning" else "Start Scanning")
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (viewModel.permissionState){
            PermissionState.NotDetermined -> {
                Text("Permissions not yet requested")
            }
            PermissionState.NotGranted -> {
                Text("Permissions not granted")
            }
            PermissionState.Granted -> {
                Text("Ready to scan for UWB devices")
            }
            PermissionState.Denied -> {
                Text("Permissions denied. Please grant permissions in settings.")
            }
            PermissionState.DeniedAlways -> {
                Text("Permissions permanently denied. Please enable in device settings.")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (nearbyDevices.isNotEmpty()) {
            Text(
                text = "Discovered Devices (${nearbyDevices.size})",
                style = MaterialTheme.typography.h6
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(nearbyDevices, key = { it.id }) { device ->
                DeviceItem(device)
            }
        }
    }
}

@Composable
private fun DeviceItem(device: NearbyDevice) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name.ifEmpty { "Unknown Device" },
                    style = MaterialTheme.typography.subtitle1
                )
                Text(
                    text = device.id,
                    style = MaterialTheme.typography.caption
                )
            }
            if (device.distance != null) {
                val distStr = device.distance.toString()
                val dotIdx = distStr.indexOf('.')
                val formatted = if (dotIdx >= 0 && dotIdx + 3 < distStr.length) distStr.substring(0, dotIdx + 3) else distStr
                Text(
                    text = "$formatted m",
                    style = MaterialTheme.typography.body1
                )
            }
        }
    }
}