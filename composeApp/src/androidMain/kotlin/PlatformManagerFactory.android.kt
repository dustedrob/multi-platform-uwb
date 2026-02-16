import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.dustedrob.uwb.ManagerFactory

@Composable
actual fun createManagerFactory(): ManagerFactory {
    val context = LocalContext.current
    return ManagerFactory(context)
}