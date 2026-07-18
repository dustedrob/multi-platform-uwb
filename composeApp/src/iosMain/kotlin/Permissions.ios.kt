// iOS has no UWB_RANGING permission (Nearby Interaction is gated by NSNearbyInteractionUsageDescription),
// so there is nothing to request here.
actual suspend fun ensureUwbRangingPermission(): Boolean = true
