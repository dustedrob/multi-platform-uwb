/**
 * Ensures the Android `UWB_RANGING` runtime permission is granted, prompting the user if needed.
 *
 * `UWB_RANGING` (API 31+) is a separate "dangerous" permission and is NOT part of the "Nearby
 * devices" group, so it must be requested on its own. moko-permissions doesn't model it, which is
 * why ranging fails with `SecurityException: Caller does not hold UWB_RANGING permission` even when
 * the Bluetooth permissions are granted. Returns true on iOS (no equivalent) and on Android below
 * API 31 (the permission doesn't exist there).
 */
expect suspend fun ensureUwbRangingPermission(): Boolean
