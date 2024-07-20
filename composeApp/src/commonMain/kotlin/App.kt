import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun App() {
    val viewModel: MyViewModel = viewModel()
    val isScanning by viewModel.isScanning.collectAsState()

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = {
            //assume has permissions
            if (true) {
                viewModel.toggleScanning()
            } else {
                // Request permissions
            }
        }) {
            Text(if (isScanning) "Stop Scanning" else "Start Scanning")
        }

    }
}