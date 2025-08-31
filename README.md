# Multiplatform UWB Library – Kotlin Multiplatform

This repository contains a **Kotlin Multiplatform library** for **Ultra-Wideband (UWB)** device discovery and ranging on both **Android** and **iOS** platforms. The library provides a unified API for UWB functionality while leveraging platform-specific implementations for optimal performance and hardware access.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Setup & Installation](#setup--installation)
- [Usage](#usage)
- [Prerequisites](#prerequisites)
- [Dependencies](#dependencies)
- [Contributing](#contributing)
- [License](#license)

---

## Overview

This library provides a **Kotlin Multiplatform** abstraction for **Ultra-Wideband (UWB)** device discovery and ranging. It combines **Bluetooth Low Energy (BLE)** for initial device discovery with **UWB** for precise distance measurement, offering a unified API across Android and iOS platforms.

### Key Concepts
- **Device Discovery**: Uses BLE advertising and scanning to find nearby UWB-capable devices
- **UWB Ranging**: Provides precise distance measurements between devices using UWB technology
- **Cross-Platform Abstraction**: Common API with platform-specific implementations for optimal hardware access
- **Reactive Architecture**: Uses Kotlin Flow for real-time device and distance updates

---

## Features

- **BLE Device Discovery**: Automatic scanning and advertising for UWB-capable devices
- **Precise UWB Ranging**: Real-time distance measurements with centimeter-level accuracy
- **Cross-Platform API**: Unified interface for both Android and iOS platforms
- **Reactive Data Flow**: Kotlin Flow-based real-time updates for device discovery and ranging
- **Permission Management**: Integrated permission handling for BLE and UWB access
- **Error Handling**: Comprehensive error reporting and recovery mechanisms

---

## Architecture

The library follows a layered architecture with platform-specific implementations:

### Core Components

1. **Common Module** (`commonMain/kotlin/`):
   - `DeviceDiscoveryManager`: Orchestrates BLE discovery and UWB ranging
   - `NearbyDevice`: Data model for discovered devices with distance information
   - `MultiplatformUwbManager`: Expect class for UWB operations
   - `BleManager`: Expect class for Bluetooth LE operations
   - `ManagerFactory`: Factory for creating platform-specific managers
   - `PlatformManagerFactory`: Platform-specific factory creation functions
   - `UwbDiscoveryViewModel`: ViewModel for UI integration with permission handling

2. **Android Implementation** (`androidMain/kotlin/`):
   - `MultiplatformUwbManager.android.kt`: Uses androidx.core.uwb for UWB ranging
   - `BleManager.kt`: Android Bluetooth LE scanning and advertising
   - `ManagerFactory.android.kt`: Creates Android-specific manager instances

3. **iOS Implementation** (`iosMain/kotlin/`):
   - `MultiplatformUwbManager.ios.kt`: Uses NearbyInteraction framework for UWB
   - `BleManager.kt`: CoreBluetooth-based scanning and advertising
   - `ManagerFactory.ios.kt`: Creates iOS-specific manager instances

### Data Flow

1. **Discovery Phase**: BLE scanning discovers nearby devices advertising UWB service UUID
2. **Ranging Phase**: Discovered devices automatically initiate UWB ranging sessions
3. **Updates**: Real-time distance measurements flow through reactive Kotlin Flow streams
4. **Session Management**: Automatic lifecycle management for UWB sessions

### Callback Architecture

The library uses a callback-based system to bridge platform-specific implementations with common code:
- Device discovery callbacks trigger UWB session initiation
- Ranging callbacks provide real-time distance updates
- Error callbacks handle platform-specific failures

---

## Setup & Installation

### Prerequisites

- **Android Studio** for Android development
- **Xcode** for iOS development  
- **Kotlin Multiplatform** plugin
- **UWB-capable devices** for testing

### Integration

Add the library to your Kotlin Multiplatform project:

```kotlin
// In your commonMain dependencies
implementation("com.dustedrob:multiplatform-uwb:1.0.0")
```

### Android Configuration

Add required permissions to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.UWB_RANGING" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### iOS Configuration

Add required permissions to `Info.plist`:
```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to discover nearby UWB devices</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>This app uses Bluetooth to advertise UWB capabilities</string>
```

---

## Usage

### Basic Implementation

```kotlin
// Create managers using factory
val managerFactory = ManagerFactory(context) // Android context or iOS equivalent
val deviceDiscoveryManager = DeviceDiscoveryManager(
    managerFactory.createUwbManager(),
    managerFactory.createBleManager()
)

// Observe nearby devices
deviceDiscoveryManager.nearbyDevices.collect { devices ->
    devices.forEach { device ->
        println("Device: ${device.name}, Distance: ${device.distance}m")
    }
}

// Start discovery and ranging
deviceDiscoveryManager.startScanning()

// Stop when done
deviceDiscoveryManager.stopScanning()
```

### Integration with UI

```kotlin
class UwbDiscoveryViewModel(
    private val controller: PermissionsController,
    private val managerFactory: ManagerFactory
) : ViewModel() {
    private val deviceDiscoveryManager = DeviceDiscoveryManager(
        managerFactory.createUwbManager(),
        managerFactory.createBleManager()
    )
    
    val nearbyDevices = deviceDiscoveryManager.nearbyDevices
    val isScanning: StateFlow<Boolean>
    var permissionState: PermissionState
    
    fun toggleScanning() {
        // Handle scanning state with permission checks
    }
    
    fun requestPermissions() {
        // Request BLE and UWB permissions
    }
}
```

---

## Prerequisites

- **Android**: Requires Android 11 (API level 30) or higher and UWB-capable hardware.
- **iOS**: Requires iOS 14.0 or higher and UWB-capable hardware (iPhone 11 or newer).
- **UWB Chipset**: Devices must have UWB hardware to participate in the network.

---

## Dependencies

### Common Dependencies
- **kotlinx-coroutines**: For asynchronous operations
- **kotlinx-datetime**: For timestamp management
- **moko-permissions**: Cross-platform permission handling

### Android Dependencies
- **androidx.core.uwb**: Android UWB API
- **Android Bluetooth LE APIs**: Device discovery and advertising

### iOS Dependencies
- **NearbyInteraction**: iOS UWB framework
- **CoreBluetooth**: iOS Bluetooth LE framework

---

## API Reference

### DeviceDiscoveryManager
Main orchestrator class that combines BLE discovery with UWB ranging.

```kotlin
class DeviceDiscoveryManager(
    multiplatformUwbManager: MultiplatformUwbManager,
    bleManager: BleManager
)

// Properties
val nearbyDevices: Flow<List<NearbyDevice>>

// Methods
fun startScanning()
fun stopScanning()
```

### NearbyDevice
Data class representing a discovered device.

```kotlin
data class NearbyDevice(
    val id: String,
    val name: String,
    val distance: Double? = null,
    val lastSeen: Long
)
```

### MultiplatformUwbManager
Cross-platform UWB ranging interface.

```kotlin
expect class MultiplatformUwbManager {
    fun initialize()
    fun startRanging(peerId: String)
    fun stopRanging(peerId: String)
    fun setRangingCallback(callback: (peerId: String, distance: Double) -> Unit)
    fun setErrorCallback(callback: (error: String) -> Unit)
}
```

### BleManager
Cross-platform Bluetooth LE interface.

```kotlin
expect class BleManager {
    fun startScanning()
    fun stopScanning()
    fun advertise()
    fun stopAdvertising()
    fun setDeviceDiscoveredCallback(callback: (id: String, name: String) -> Unit)
}
```

---

## Contributing

Contributions are welcome! Please ensure you follow the existing code patterns and test on both platforms.

---

## License

This project is licensed under the **MIT License**.

---

Build precise location-aware applications with Kotlin Multiplatform and UWB! 🚀