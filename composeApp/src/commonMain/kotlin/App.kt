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
    val viewModel: MyViewModel = viewModel{
        MyViewModel(controller)
    }
    val isScanning by viewModel.isScanning.collectAsState()

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = {
            viewModel.requestPermissions()
            if (true) {
                viewModel.toggleScanning()
            } else {
                // Request permissions
            }
        }) {
            Text(if (isScanning) "Stop Scanning" else "Start Scanning")
        }

        when (viewModel.permissionState){
            PermissionState.NotDetermined -> {

            }
            PermissionState.NotGranted -> {

            }
            PermissionState.Granted -> {

            }
            PermissionState.Denied -> {

            }
            PermissionState.DeniedAlways -> {

            }
        }

    }
}