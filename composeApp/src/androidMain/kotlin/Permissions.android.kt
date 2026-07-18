import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred

/**
 * Registers and drives the Android `UWB_RANGING` runtime permission request.
 *
 * [register] must be called from the host Activity's `onCreate` (before it is STARTED) so the
 * result launcher is valid. moko-permissions has no `UWB_RANGING` type, so this is requested
 * through the platform API directly.
 */
object UwbRangingPermission {
    private var appContext: Context? = null
    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    fun register(activity: ComponentActivity) {
        appContext = activity.applicationContext
        launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pending?.complete(granted)
            pending = null
        }
    }

    private fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.UWB_RANGING) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun ensure(): Boolean {
        // UWB_RANGING did not exist before API 31; nothing to request.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val context = appContext ?: return false
        if (isGranted(context)) return true
        val l = launcher ?: return isGranted(context)
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        l.launch(Manifest.permission.UWB_RANGING)
        return deferred.await()
    }
}

actual suspend fun ensureUwbRangingPermission(): Boolean = UwbRangingPermission.ensure()
