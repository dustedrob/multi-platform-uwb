import androidx.compose.runtime.Composable
import com.dustedrob.uwb.ManagerFactory

@Composable
actual fun createManagerFactory(): ManagerFactory {
    return ManagerFactory()
}