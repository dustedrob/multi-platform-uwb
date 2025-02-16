import android.content.Context
import androidx.core.uwb.UwbManager

actual class ManagerFactory(private val context: Context) {
    actual fun createUwbManager(): MultiplatformUwbManager {
        val androidUwbManager = context.getSystemService(Context.UWB_SERVICE) as UwbManager
        return MultiplatformUwbManager(androidUwbManager)
    }

    actual fun createBleManager(): BleManager {
        return BleManager(context)
    }
}