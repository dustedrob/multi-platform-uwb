import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import org.jetbrains.compose.ui.tooling.preview.Preview
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
                Text("Session ID: ${config.sessionId?.toHexString()}  Channel: ${config.channel}", style = MaterialTheme.typography.caption)
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
                        text = "Session: ${device.sessionId!!.toHexString()}  Ch: ${device.channel}",
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

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (device.state == DeviceState.Ranging) {
                    DirectionArrow(
                        azimuthDeg = device.azimuth,
                        elevationDeg = device.elevation,
                        modifier = Modifier.size(48.dp)
                    )
                }
                val dist = device.distance
                if (dist != null) {
                    Text(
                        text = "${formatMeters(dist)}m",
                        style = MaterialTheme.typography.subtitle1,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

/** Trims a distance in meters to 2 decimal places without pulling in platform formatting. */
private fun formatMeters(meters: Double): String {
    val s = meters.toString()
    val dot = s.indexOf('.')
    return if (dot >= 0 && dot + 3 < s.length) s.substring(0, dot + 3) else s
}

/**
 * A small compass that draws an arrow pointing toward a ranging peer.
 *
 * [azimuthDeg] rotates the arrow (0 = straight ahead, positive = to the right) and
 * [elevationDeg] foreshortens it (larger angle = shorter, i.e. more above/below than ahead).
 * Both are in degrees. When [azimuthDeg] is `null` (direction not resolved yet) only the ring
 * is drawn, so a card never looks broken while direction is still converging.
 */
@Composable
private fun DirectionArrow(
    azimuthDeg: Double?,
    elevationDeg: Double?,
    modifier: Modifier = Modifier,
) {
    val arrowColor = MaterialTheme.colors.primary
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f * 0.85f

        drawCircle(color = Color.LightGray, radius = radius, center = center, style = Stroke(width = 2f))

        if (azimuthDeg == null) return@Canvas

        val elevScale = elevationDeg?.let { cos(it * PI / 180.0).toFloat() } ?: 1f
        val len = radius * elevScale

        rotate(degrees = azimuthDeg.toFloat(), pivot = center) {
            val tip = Offset(center.x, center.y - len)
            drawLine(
                color = arrowColor,
                start = center,
                end = tip,
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
            val head = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - 7f, tip.y + 11f)
                lineTo(tip.x + 7f, tip.y + 11f)
                close()
            }
            drawPath(head, arrowColor)
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

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun DirectionArrowPreview() {
    Row(
        modifier = Modifier.background(Color.White).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Straight ahead
        DirectionArrow(azimuthDeg = 0.0, elevationDeg = 0.0, modifier = Modifier.size(64.dp))
        // 45° to the right
        DirectionArrow(azimuthDeg = 45.0, elevationDeg = 0.0, modifier = Modifier.size(64.dp))
        // To the left and tilted up (foreshortened)
        DirectionArrow(azimuthDeg = -60.0, elevationDeg = 40.0, modifier = Modifier.size(64.dp))
        // Direction not resolved yet — ring only
        DirectionArrow(azimuthDeg = null, elevationDeg = null, modifier = Modifier.size(64.dp))
    }
}

@Preview
@Composable
private fun DeviceItemPreview() {
    Column(
        modifier = Modifier.background(Color(0xFFFAFAFA)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Ranging with resolved direction
        DeviceItem(
            NearbyDevice(
                id = "AA:BB:CC:DD:EE:FF",
                name = "Qorvo DWM3001",
                distance = 1.234,
                azimuth = 30.0,
                elevation = 10.0,
                state = DeviceState.Ranging,
                sessionId = 42,
                channel = 9,
            )
        )
        // Ranging but direction not yet available
        DeviceItem(
            NearbyDevice(
                id = "11:22:33:44:55:66",
                name = "Pixel 8 Pro",
                distance = 3.5,
                azimuth = null,
                elevation = null,
                state = DeviceState.Ranging,
                sessionId = 7,
                channel = 9,
            )
        )
        // Just discovered
        DeviceItem(
            NearbyDevice(
                id = "77:88:99:AA:BB:CC",
                name = "Unknown Device",
                state = DeviceState.Discovered,
            )
        )
        // Error state
        DeviceItem(
            NearbyDevice(
                id = "DE:AD:BE:EF:00:11",
                name = "Flaky Accessory",
                state = DeviceState.Error,
                errorMessage = "Peer disconnected",
            )
        )
    }
}

@Preview
@Composable
private fun LocalDeviceInfoPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LocalDeviceInfo(
            config = UwbSessionConfig(
                scope = 0,
                sessionId = 42,
                channel = 9,
                preambleIndex = 10,
                uwbAddress = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D),
            ),
            isScanning = true,
        )
        LocalDeviceInfo(config = null, isScanning = false)
    }
}

@Preview
@Composable
private fun EventLogItemPreview() {
    Column(modifier = Modifier.background(Color(0xFFF5F5F5)).padding(4.dp)) {
        EventLogItem(DiscoveryEvent(0L, EventType.DeviceDiscovered, "peer", "Discovered nearby device"))
        EventLogItem(DiscoveryEvent(0L, EventType.RangingStarted, "peer", "Ranging started"))
        EventLogItem(DiscoveryEvent(0L, EventType.ConfigExchangeComplete, "peer", "Config exchange complete"))
        EventLogItem(DiscoveryEvent(0L, EventType.Error, "peer", "Something went wrong"))
    }
}
