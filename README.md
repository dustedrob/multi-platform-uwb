# Mesh Networking App with UWB (Ultra-Wideband) – Kotlin Multiplatform

This repository contains the source code for a mobile application built using **Kotlin Multiplatform**. The app enables **mesh networking** functionality via **Ultra-Wideband (UWB)** technology on both **Android** and **iOS** platforms. It allows devices to communicate directly with one another in a mesh network, offering a low-power, high-accuracy, and real-time communication experience for a variety of use cases such as indoor navigation, asset tracking, and peer-to-peer interactions.

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

This mobile app leverages the **Kotlin Multiplatform** framework to share common code between Android and iOS, with platform-specific implementations for accessing UWB hardware capabilities on both platforms. The mesh networking functionality is based on UWB, enabling devices to communicate without relying on a traditional centralized network.

### Key Concepts
- **Mesh Network**: A decentralized network structure where each device (node) can communicate with other devices, forwarding messages between them to create a scalable and fault-tolerant network.
- **Ultra-Wideband (UWB)**: A wireless communication technology that enables precise location tracking and high-speed data transfer over short distances.
- **Kotlin Multiplatform**: Allows the sharing of common business logic and core functionality between Android and iOS, while enabling platform-specific code for UWB integration.

---

## Features

- **Real-time Mesh Networking**: Devices can join the network and communicate directly with each other.
- **UWB Integration**: Utilizes UWB hardware for accurate location tracking and direct device-to-device communication.
- **Cross-Platform Support**: Built using Kotlin Multiplatform to support both **Android** and **iOS** with shared code.
- **Battery Efficient**: Designed to operate efficiently, utilizing UWB’s low-power characteristics.
- **Automatic Network Discovery**: Devices automatically detect and connect to others in range.
- **Multi-device Communication**: Supports simultaneous communication between multiple devices within the mesh network.

---

## Architecture

This application is structured as follows:

1. **Kotlin Multiplatform Module**:
  - Shared business logic and networking code.
  - Includes common abstractions for mesh networking and UWB communication.

2. **Platform-Specific Code**:
  - **Android**: UWB integration using the Android UWB APIs and Bluetooth Low Energy (BLE) for initial device discovery.
  - **iOS**: UWB integration via Core Location and Core Bluetooth APIs.

3. **UI Layer**:
  - Implemented separately for Android and iOS using their respective UI frameworks (Jetpack Compose for Android, SwiftUI for iOS).
  - Platform-specific UI components interact with the shared logic layer to facilitate network management.

4. **Mesh Networking Protocol**:
  - Custom protocol built for efficient data exchange, leveraging UWB's high bandwidth and low latency.

---

## Setup & Installation

### Prerequisites

Before getting started, make sure you have the following tools installed on your machine:

- **Android Studio** for Android development
- **Xcode** for iOS development
- **Kotlin** (>= 1.5.x) with **Kotlin Multiplatform** plugin
- **UWB Hardware Support** on your Android and iOS devices

### Clone the Repository

```bash
git clone https://github.com/your-username/mesh-networking-uwb-app.git
cd mesh-networking-uwb-app
```

### Android Setup

1. Open the `android` directory in **Android Studio**.
2. Ensure you have the correct **UWB** and **Bluetooth permissions** in your `AndroidManifest.xml`.
3. Build and run on a physical Android device that supports UWB.

### iOS Setup

1. Open the `ios` directory in **Xcode**.
2. Configure your **UWB** and **Bluetooth** permissions in the `Info.plist`.
3. Build and run on a physical iOS device with UWB support.

---

## Usage

Once the app is installed on both Android and iOS devices, follow these steps to use the mesh networking features:

1. **Launch the App** on both devices.
2. **Join the Network**: The app will automatically discover nearby devices via UWB and prompt you to join the mesh network.
3. **Start Communicating**: Once connected, you can send and receive messages, share data, or interact with other devices in real-time.

The app will manage connections, ensure reliability, and automatically handle the addition of new devices to the network.

---

## Prerequisites

- **Android**: Requires Android 11 (API level 30) or higher and UWB-capable hardware.
- **iOS**: Requires iOS 14.0 or higher and UWB-capable hardware (iPhone 11 or newer).
- **UWB Chipset**: Devices must have UWB hardware to participate in the network.

---

## Dependencies

- **Kotlin** (1.5.x+)
- **Kotlin Multiplatform** (1.5.x+)
- **Jetpack Compose** (for Android UI)
- **SwiftUI** (for iOS UI)
- **UWB SDKs** (Android and iOS native SDKs for UWB communication)
- **Bluetooth Low Energy** libraries (for device discovery)

---

## Contributing

We welcome contributions to improve the functionality and performance of the mesh networking app. Here’s how you can help:

1. **Fork** the repository and clone your fork.
2. Create a **feature branch** (`git checkout -b feature-branch`).
3. Commit your changes (`git commit -am 'Add new feature'`).
4. Push to the branch (`git push origin feature-branch`).
5. Open a **Pull Request** and describe your changes.

Please ensure that you follow the coding style and write tests where applicable.

---

## License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for more details.

---

If you have any questions or need assistance, feel free to open an issue in the repository or reach out to the maintainers.

---

Enjoy building with Kotlin Multiplatform and UWB! 🚀