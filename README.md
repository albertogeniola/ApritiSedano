# ApritiSedano

ApritiSedano is a secure, fully offline, Bluetooth Low Energy (BLE) based smart garage door opener. The project consists of a microcontroller unit (ESP32) that interfaces with the physical garage hardware, and a companion Android application to securely trigger the door and monitor its state.


## Features

- **100% Offline & Secure**: No internet connection is required. The system relies entirely on BLE.
- **TOTP-based Authentication**: The Android app generates a Time-Based One-Time Password (TOTP) signed with HMAC-SHA1 to trigger the door. This prevents replay attacks and ensures only authorized devices can open the garage.
- **Real-time State Monitoring**: The MCU continuously broadcasts the garage door state (Open/Closed) via a non-connectable BLE Advertisement.
- **Cryptographic State Integrity**: The state beacon is signed with a time-based HMAC to prevent spoofing and replay attacks. The Android app mathematically verifies the state beacon before displaying it.
- **Energy Efficient**: Carefully optimized BLE scanning and advertising duty cycles on the ESP32 to prevent overheating and reduce power consumption.
- **NFC & Voice Assistant Support**: The Android app can be triggered automatically via NFC tags or Google Assistant shortcuts for a seamless "tap to open" or "voice to open" experience.
- **Offline Timekeeping**: Uses a DS3231 I2C RTC module on the ESP32 to maintain accurate time across reboots, which is critical for TOTP validation.

## Repository Structure

- `/MCU`: The C++ source code for the ESP32 microcontroller. Built using the Arduino framework.
- `/ApritiSedano`: The Android App source code (Java/Kotlin).
- `/docs`: Detailed documentation including the [BLE Communication Protocol and Sequence Diagrams](docs/ble_protocol.md).
- `apritisedano_case.scad`: An OpenSCAD file for 3D printing a custom enclosure for the hardware.
- `specs.md`: The original detailed technical specifications and design choices for the project.

## Hardware Requirements

To build the MCU component, you will need:
- An ESP32 development board (e.g., Seeed Studio XIAO ESP32C6 or standard ESP32-WROOM-32).
- A 3.3V/5V Relay Module (to trigger the garage motor).
- A Magnetic Reed Switch / Sensor (to detect if the door is physically open or closed).
- A DS3231 RTC Module (for accurate offline timekeeping).
- Optional: LED and Piezo Buzzer for visual and auditory feedback.

*Note: Pin configurations can be adjusted in `MCU/HardwareConfig.h`.*

## Setup and Installation

### 1. MCU Configuration
1. Open the `MCU` folder in the Arduino IDE (or PlatformIO).
2. Install the necessary ESP32 board packages.
3. In `HardwareConfig.h`, uncomment the board you are using and verify the pinout.
4. Flash the code to your ESP32.
5. On first boot, you can connect via Serial monitor and type `conf` to enter configuration mode. Here you can set the Base32 Secret Key (must match the one in the Android app) and sync the RTC time.

### 2. Android App
1. Open the `/ApritiSedano` folder in Android Studio.
2. In `ApritiSedanoService.java` and `MainActivity.java`, ensure the `SECRET_KEY` matches the one provisioned on your ESP32.
3. Build and install the APK on your Android device.
4. Grant the required Location/Bluetooth permissions upon the first launch.

## Security Architecture

The communication between the phone and the garage door is strictly one-way during operation:
1. **To Open**: The phone acts as a BLE Broadcaster (sending a 6-digit TOTP in the Manufacturer Specific Data). The ESP32 acts as a passive scanner, intercepts the packet, validates the TOTP against the shared secret and the current time, and triggers the relay.
2. **To Read State**: The ESP32 acts as a BLE Broadcaster, transmitting a 10-byte payload containing the door state, a timestamp, and an HMAC-SHA1 signature. The phone scans for this beacon, verifies the signature using the shared secret, ensures the timestamp is strictly increasing (anti-replay), and updates the UI.

This architecture removes the need for slow BLE pairing/connections, making the response time near-instantaneous.

## License

This project is open-source and available under standard open-source licenses. Please refer to individual components for specific licensing details.
