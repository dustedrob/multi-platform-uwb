import kotlinx.datetime.Clock

data class NearbyDevice(
    val id: String,
    val name: String,
    val distance: Double? = null,
    val lastSeen: Long = Clock.System.now().toEpochMilliseconds()
)