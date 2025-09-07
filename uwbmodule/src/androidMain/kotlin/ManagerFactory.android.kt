import android.content.Context
import androidx.core.uwb.UwbManager

actual class ManagerFactory(private val context: Context) {
    actual fun createUwbManager(): MultiplatformUwbManager {
        val uwbManager = UwbManager.createInstance(context)
        return MultiplatformUwbManager(uwbManager)
    }

    actual fun createBleManager(): BleManager {
        return BleManager(context)
    }
}