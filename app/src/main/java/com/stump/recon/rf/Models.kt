package com.stump.recon.rf

enum class BoardPresetId(
    val displayName: String,
    val chip: String,
    val usbBridge: String,
    val flashSizeMb: Int,
    val pioEnv: String,
    val pinout: String
) {
    HELTEC_V3(
        displayName = "Heltec WiFi LoRa 32 V3 / V3.2",
        chip = "ESP32-S3FN8 · SX1262",
        usbBridge = "CP2102 (0x10C4:0xEA60)",
        flashSizeMb = 8,
        pioEnv = "heltec_wifi_lora_32_V3",
        pinout = "NSS 8 · SCK 9 · MOSI 10 · MISO 11 · RST 12 · BUSY 13 · DIO1 14"
    ),
    LILYGO_T3_S3(
        displayName = "LilyGO T3-S3 (SX1262)",
        chip = "ESP32-S3FH4R2 · SX1262",
        usbBridge = "ESP32-S3 USB-CDC (0x303A:0x1001)",
        flashSizeMb = 4,
        pioEnv = "lilygo_t3_s3_sx1262",
        pinout = "NSS 7 · SCK 5 · MOSI 6 · MISO 3 · RST 8 · BUSY 34 · DIO1 33"
    ),
    LILYGO_TBEAM_SUPREME(
        displayName = "LilyGO T-Beam Supreme (SX1262)",
        chip = "ESP32-S3FN8 · SX1262",
        usbBridge = "ESP32-S3 USB-CDC (0x303A:0x1001)",
        flashSizeMb = 8,
        pioEnv = "lilygo_tbeam_supreme_sx1262",
        pinout = "NSS 10 · SCK 12 · MOSI 11 · MISO 13 · RST 5 · BUSY 4 · DIO1 1"
    )
}

enum class RadarSortMode(val label: String) {
    STICKY_FIRST_SEEN("Sticky"),
    SIGNAL_DBM("Signal"),
    LAST_SEEN("Recent"),
    PACKETS("Packets"),
    NAME("Name")
}

enum class BandFilter(val label: String) {
    ALL("All"),
    LORA_ONLY("LoRa"),
    WIFI_ONLY("Wi-Fi")
}

enum class HopBandPreset(
    val label: String,
    val freqsMHz: List<Double>,
    val usesFirmwareSweep: Boolean
) {
    US_915_SWEEP(
        label = "US 915 (903–925)",
        freqsMHz = listOf(903.0, 905.0, 907.0, 909.0, 911.0, 913.0, 915.0, 917.0, 919.0, 921.0, 923.0, 925.0),
        usesFirmwareSweep = true
    ),
    US_MESH_RNS(
        label = "US RNS / Mesh",
        freqsMHz = listOf(906.875, 910.525, 915.0, 919.875, 923.3),
        usesFirmwareSweep = false
    ),
    EU_868(
        label = "EU 868 (865–869.5)",
        freqsMHz = listOf(865.2, 866.0, 867.1, 867.5, 868.1, 868.3, 868.5, 869.525),
        usesFirmwareSweep = false
    ),
    ISM_433(
        label = "433 ISM (433.1–434.7)",
        freqsMHz = listOf(433.175, 433.375, 433.775, 433.92, 434.175, 434.475, 434.775),
        usesFirmwareSweep = false
    )
}

data class FrequencyBinStats(
    val freqMHz: Double,
    val currentRssi: Double,
    val peakRssi: Double,
    val sampleCount: Int,
    val packetCount: Int,
    val emitters: List<String> = emptyList(),
    val lastUpdatedMs: Long = 0L
)

data class RadioStatus(
    val fw: String = "0.3.8-MULTIBOARD-TDM",
    val mode: String = "sniff",
    val running: Boolean = true,
    val freq: Double = 915.0,
    val bw: Double = 125.0,
    val sf: Int = 8,
    val cr: Int = 5,
    val sync: Int = 0x12,
    val wifi: Boolean = true,
    val wifiCh: Int = 0,
    val payload: Boolean = true
)

data class LXMFPropagationInfo(
    val active: Boolean,
    val timebase: Long? = null,
    val transferLimitKb: Int? = null,
    val syncLimitKb: Int? = null,
    val stampCost: Double? = null,
    val stampFlex: Double? = null,
    val peeringCost: Double? = null,
    val version: String? = null,
    val syncStratum: Int? = null,
    val syncThrottle: Int? = null,
    val authBand: Int? = null,
    val utilPressure: Double? = null
)

data class DissectedRNS(
    val packetType: String,
    val headerType: String = "HEADER_1",
    val destType: String = "SINGLE",
    val propagationType: String = "BROADCAST",
    val hops: Int = 0,
    val destinationHash: String = "",
    val transportId: String? = null,
    val identityHash: String? = null,
    val context: String = "NONE",
    val isEncrypted: Boolean = true,
    val ifac: Boolean = false,
    val hasRatchet: Boolean = false,
    val ratchetId: String? = null,
    val cryptoVerified: Boolean = false,
    val announceTimestamp: Long? = null,
    val rnodeFramed: Boolean = false,
    val rnodeSeq: Int? = null,
    val rnodeSplitStatus: String? = null,
    val clientApp: String? = null,
    val peerName: String? = null,
    val nameHash: String? = null,
    val stampCost: Double? = null,
    val propagation: LXMFPropagationInfo? = null
)

data class DissectedMeshCore(
    val routeType: String,
    val payloadType: String,
    val hops: Int,
    val pathHex: String,
    val pubkeyPrefix: String,
    val nodeRole: String,
    val nodeName: String? = null,
    val gpsCoords: String? = null
)

data class DissectedMeshtastic(
    val hopLimit: Int,
    val wantAck: Boolean,
    val channelHash: String,
    val portNum: String,
    val isEncrypted: Boolean = true
)

data class RFPacket(
    val id: String,
    val timestampMs: Long,
    val freq: Double,
    val bw: Double,
    val sf: Int,
    val cr: Int,
    val sync: Int,
    val len: Int,
    val rssi: Double,
    val smoothedRssi: Double,
    val snr: Double,
    val ferr: Double,
    val crcOk: Boolean,
    val payloadHex: String,
    val protocolGuess: String,
    val rns: DissectedRNS? = null,
    val meshcore: DissectedMeshCore? = null,
    val meshtastic: DissectedMeshtastic? = null
)

data class WifiFrame(
    val id: String,
    val timestampMs: Long,
    val ch: Int,
    val rssi: Double,
    val smoothedRssi: Double,
    val ftype: String,
    val mac: String,
    val bssid: String,
    val laa: Boolean,
    val ouiVendor: String,
    val ssid: String,
    val len: Int
)

data class ScanPoint(
    val timestampMs: Long,
    val freq: Double,
    val rssi: Double
)

data class DiscoveredDevice(
    val uid: String,
    val name: String,
    val dtype: String,
    val band: String,
    val rssiRaw: Double,
    val rssiKalman: Double,
    val rssiMin: Double,
    val rssiMax: Double,
    val pktCount: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val freq: Double? = null,
    val sf: Int? = null,
    val bw: Double? = null,
    val sync: Int? = null,
    val snr: Double? = null,
    val ferrAvg: Double? = null,
    val destHash: String? = null,
    val rnsDestsSeen: List<String> = emptyList(),
    val hops: Int? = null,
    val rnsType: String? = null,
    val rnsContext: String? = null,
    val clientApp: String? = null,
    val peerName: String? = null,
    val ifac: Boolean = false,
    val cryptoVerified: Boolean = false,
    val hasRatchet: Boolean = false,
    val ratchetIdsSeen: List<String> = emptyList(),
    val relayedVia: List<String> = emptyList(),
    val stampCost: Double? = null,
    val propagation: LXMFPropagationInfo? = null,
    val rnodeFramed: Boolean = false,
    val rnodeSplitStatus: String? = null,
    val nodeRole: String? = null,
    val pubkeyPrefix: String? = null,
    val gpsCoords: String? = null,
    val meshChannel: String? = null,
    val mac: String? = null,
    val ouiVendor: String? = null,
    val laa: Boolean = false,
    val ch: Int? = null,
    val ssidsSeen: List<String> = emptyList(),
    val lastPayloadHex: String? = null
)
