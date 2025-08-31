import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
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

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = {
            viewModel.requestPermissions()
            if (viewModel.permissionState == PermissionState.Granted) {
                viewModel.toggleScanning()
            }
        }) {
            Text(if (isScanning) "Stop Scanning" else "Start Scanning")
        }

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

    }
}