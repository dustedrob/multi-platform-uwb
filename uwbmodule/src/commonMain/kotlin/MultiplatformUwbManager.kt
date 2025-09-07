expect class MultiplatformUwbManager {
    fun initialize()
    fun startRanging(peerId: String)
    fun stopRanging(peerId: String)
    fun setRangingCallback(callback: (peerId: String, distance: Double) -> Unit)
    fun setErrorCallback(callback: (error: String) -> Unit)
}