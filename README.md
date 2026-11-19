# STUMP Dual-Band RF Recon — Native Android Edition

Strictly passive (Zero-TX) Sub-GHz LoRa + 2.4 GHz 802.11 reconnaissance terminal and USB-OTG ESP32-S3 firmware flasher built with **Kotlin + Jetpack Compose + Material 3 + `usb-serial-for-android`**.

## Supported Hardware & USB-UART Bridges
- **Heltec WiFi LoRa 32 V3 / V3.2** — Silicon Labs CP2102 (`0x10C4:0xEA60`)
- **LilyGO T3-S3 (SX1262)** — Native ESP32-S3 USB-Serial/JTAG CDC (`0x303A:0x1001`)
- **LilyGO T-Beam Supreme (SX1262)** — Native ESP32-S3 USB-Serial/JTAG CDC (`0x303A:0x1001`)
- **WCH CH340 / CH341 / CH343 / CH9102** (`0x1A86:0x7523`, `0x55D3`, `0x55D4`) & **FTDI FT232R** (`0x0403:0x6001`)

## Build & Install APK
1. Open this folder in **Android Studio Ladybug (2024.2+)** with JDK 17.
2. Wait for Gradle Sync (pulls Jetpack Compose BOM and `com.github.mik3y:usb-serial-for-android:3.8.1` from JitPack).
3. Run unit parity tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```
4. Build & install on your Android phone (Android 8.0 / API 26+):
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Flashing Your Heltec V3 / LilyGO Board via Android USB-OTG
1. Build the single merged `.bin` from the companion `stump-esp32s3-firmware.zip` using PlatformIO:
   ```bash
   pio run -e heltec_wifi_lora_32_V3
   # Output: .pio/build/heltec_wifi_lora_32_V3/stump_heltec_wifi_lora_32_V3_merged.bin
   ```
2. Copy the `.bin` file to your Android phone's **Downloads** folder.
3. Plug your Heltec V3 into your Android phone using a **USB-C data / OTG cable** (avoid charge-only cables).
4. Open **STUMP Recon** -> **Flasher** tab -> select your board -> pick the local `.bin` -> tap **Flash Local .bin via USB-OTG**.
5. If auto-reset is blocked by an adapter, hold **BOOT** on the board, tap **RST**, release **BOOT**, and tap **Flash** again.
