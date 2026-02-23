import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dustedrob.uwb.DeviceState
import com.dustedrob.uwb.DiscoveryEvent
import com.dustedrob.uwb.EventType
import com.dustedrob.uwb.NearbyDevice
import com.dustedrob.uwb.UwbSessionConfig
import dev.icerock.moko.permissions.PermissionState
import dev.icerock.moko.permissions.compose.BindEffect
import dev.icerock.moko.permissions.compose.rememberPermissionsControllerFactory

@Composable
fun App() {
    val factory = rememberPermissionsControllerFactory()
    val controller = remember(factory) {
        factory.createPermissionsController()
    }
    BindEffect(controller)
    val managerFactory = createManagerFactory()
    val viewModel: UwbDiscoveryViewModel = viewModel {
        UwbDiscoveryViewModel(controller, managerFactory)
    }
    val isScanning by viewModel.isScanning.collectAsState()
    val nearbyDevices by viewModel.nearbyDevices.collectAsState()
    val connectionEvents by viewModel.connectionEvents.collectAsState()
    val localConfig by viewModel.localConfig.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp)
    ) {
        // Local device info
        LocalDeviceInfo(localConfig, isScanning)

        Spacer(modifier = Modifier.height(8.dp))

        // Scan button
        Button(
            onClick = { viewModel.toggleScanning() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Stop Scanning" else "Start Scanning")
        }

        when (viewModel.permissionState) {
            PermissionState.NotDetermined -> Text("Permissions not yet requested", style = MaterialTheme.typography.caption)
            PermissionState.NotGranted -> Text("Permissions not granted", style = MaterialTheme.typography.caption)
            PermissionState.Granted -> {}
            PermissionState.Denied -> Text("Permissions denied. Please grant in settings.", color = Color.Red, style = MaterialTheme.typography.caption)
            PermissionState.DeniedAlways -> Text("Permissions permanently denied. Enable in device settings.", color = Color.Red, style = MaterialTheme.typography.caption)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Device list
        if (nearbyDevices.isNotEmpty()) {
            Text("Devices (${nearbyDevices.size})", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(nearbyDevices, key = { it.id }) { device ->
                DeviceItem(device)
            }
            if (nearbyDevices.isEmpty() && isScanning) {
                item {
                    Text(
                        "Scanning for nearby UWB devices…",
                        style = MaterialTheme.typography.body2,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }

        // Event log
        Divider(modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Event Log", style = MaterialTheme.typography.subtitle2)
            TextButton(onClick = { viewModel.clearLog() }) {
                Text("Clear", fontSize = 12.sp)
            }
        }

        val logListState = rememberLazyListState()
        LaunchedEffect(connectionEvents.size) {
            if (connectionEvents.isNotEmpty()) {
                logListState.animateScrollToItem(connectionEvents.size - 1)
            }
        }

        LazyColumn(
            state = logListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFF5F5F5))
                .padding(4.dp)
        ) {
            items(connectionEvents) { event ->
                EventLogItem(event)
            }
        }
    }
}

@Composable
private fun LocalDeviceInfo(config: UwbSessionConfig?, isScanning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp,
        backgroundColor = Color(0xFFE3F2FD)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Local Device", style = MaterialTheme.typography.subtitle2)
            if (config != null) {
                val addrHex = config.uwbAddress.joinToString(":") { byte ->
                    (byte.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()
                }
                Text("UWB Address: $addrHex", style = MaterialTheme.typography.caption)
                Text("Session ID: ${config.sessionId}  Channel: ${config.channel}", style = MaterialTheme.typography.caption)
            } else {
                Text(
                    if (isScanning) "Initializing UWB…" else "Not started",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun DeviceItem(device: NearbyDevice) {
    val stateColor = when (device.state) {
        DeviceState.Discovered -> Color.Gray
        DeviceState.ExchangingConfig -> Color(0xFFFFC107) // yellow
        DeviceState.Ranging -> Color(0xFF4CAF50) // green
        DeviceState.Disconnected -> Color.Gray
        DeviceState.Error -> Color.Red
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // State indicator dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(stateColor)
            )
            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name.ifEmpty { "Unknown Device" },
                    style = MaterialTheme.typography.subtitle1
                )
                Text(
                    text = "${device.state.name} • ${device.id.takeLast(8)}",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray
                )
                if (device.sessionId != null) {
                    Text(
                        text = "Session: ${device.sessionId}  Ch: ${device.channel}",
                        style = MaterialTheme.typography.caption
                    )
                }
                val errMsg = device.errorMessage
                if (errMsg != null) {
                    Text(
                        text = errMsg,
                        style = MaterialTheme.typography.caption,
                        color = Color.Red
                    )
                }
            }

            if (device.distance != null) {
                val distStr = device.distance.toString()
                val dotIdx = distStr.indexOf('.')
                val formatted = if (dotIdx >= 0 && dotIdx + 3 < distStr.length) distStr.substring(0, dotIdx + 3) else distStr
                Text(
                    text = "${formatted}m",
                    style = MaterialTheme.typography.h6,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
private fun EventLogItem(event: DiscoveryEvent) {
    val color = when (event.type) {
        EventType.Error -> Color.Red
        EventType.RangingStarted -> Color(0xFF4CAF50)
        EventType.ConfigExchangeComplete -> Color(0xFF2196F3)
        else -> Color.DarkGray
    }
    // Simple time display: seconds since midnight-ish
    val timeStr = ((event.timestamp / 1000) % 86400).let { secs ->
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    }
    Text(
        text = "$timeStr  ${event.message}",
        style = MaterialTheme.typography.caption,
        color = color,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}
