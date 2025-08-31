import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun createManagerFactory(): ManagerFactory {
    val context = LocalContext.current
    return ManagerFactory(context)
}