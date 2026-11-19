package com.stump.recon.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stump.recon.rf.BandFilter
import com.stump.recon.rf.BoardPresetId
import com.stump.recon.rf.DeviceRadarEngine
import com.stump.recon.rf.DiscoveredDevice
import com.stump.recon.rf.ExportFormats
import com.stump.recon.rf.FrequencyBinStats
import com.stump.recon.rf.HopBandPreset
import com.stump.recon.rf.PacketDissector
import com.stump.recon.rf.RFPacket
import com.stump.recon.rf.RadarSortMode
import com.stump.recon.rf.RadioStatus
import com.stump.recon.rf.ScanPoint
import com.stump.recon.rf.WifiFrame
import com.stump.recon.usb.ConnectedUsbDevice
import com.stump.recon.usb.Esp32S3SlipFlasher
import com.stump.recon.usb.FlashProgress
import com.stump.recon.usb.FlashStage
import com.stump.recon.usb.StumpUsbManager
import com.stump.recon.usb.ValidatedBin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

enum class FlashImageMode { MERGED_SINGLE, THREE_PART }

data class ReconUiState(
    val usbConnected: Boolean = false,
    val usbLabel: String = "Disconnected — Plug USB-OTG Radio",
    val status: RadioStatus = RadioStatus(),
    val packets: List<RFPacket> = emptyList(),
    val wifiFrames: List<WifiFrame> = emptyList(),
    val scanPoints: List<ScanPoint> = emptyList(),
    val devices: List<DiscoveredDevice> = emptyList(),
    val totalLoraCount: Int = 0,
    val totalWifiCount: Int = 0,
    val serialLogs: List<String> = emptyList(),
    // Radar list stability & sorting controls
    val sortMode: RadarSortMode = RadarSortMode.STICKY_FIRST_SEEN,
    val bandFilter: BandFilter = BandFilter.ALL,
    val refreshIntervalSec: Int = 3, // 1, 3, 5, or 0 (Hold/Freeze)
    val listFrozen: Boolean = false,
    val lastListRefreshMs: Long = 0L,
    // Waterfall & real hardware frequency hopping
    val hopBand: HopBandPreset = HopBandPreset.US_915_SWEEP,
    val hopActive: Boolean = false,
    val hopDwellMs: Long = 450L,
    val activeHopFreqMHz: Double? = null,
    val frequencyBins: Map<Double, FrequencyBinStats> = emptyMap(),
    val waterfallRows: List<Map<Double, Double>> = emptyList(),
    // Flasher state
    val selectedBoard: BoardPresetId = BoardPresetId.HELTEC_V3,
    val flashMode: FlashImageMode = FlashImageMode.MERGED_SINGLE,
    val flashBaud: Int = 115200,
    val mergedBin: ValidatedBin? = null,
    val bootloaderBin: ValidatedBin? = null,
    val partitionsBin: ValidatedBin? = null,
    val firmwareBin: ValidatedBin? = null,
    val flashProgress: FlashProgress = FlashProgress(),
    val flashLogs: List<String> = emptyList(),
    val toastMessage: String? = null
)

/**
 * Strictly Real-Hardware ViewModel — ZERO fake data, ZERO synthetic simulators.
 * Every device, packet, Wi-Fi frame, and waterfall RSSI reading originates directly
 * from the USB-OTG connected Heltec V3 / LilyGO ESP32-S3 + SX1262 receiver.
 */
class ReconViewModel(app: Application) : AndroidViewModel(app) {

    private val usbManager = StumpUsbManager(app.applicationContext)
    private val radarEngine = DeviceRadarEngine()

    private val _state = MutableStateFlow(ReconUiState())
    val state: StateFlow<ReconUiState> = _state.asStateFlow()

    private var activeUsb: ConnectedUsbDevice? = null
    private var serialReadJob: Job? = null
    private var flashJob: Job? = null
    private var hopJob: Job? = null
    private var radarTickerJob: Job? = null
    private var activeFlasher: Esp32S3SlipFlasher? = null

    private val binMap = LinkedHashMap<Double, FrequencyBinStats>()
    private val currentSweepRow = LinkedHashMap<Double, Double>()
    private val waterfallHistory = ArrayDeque<Map<Double, Double>>()

    init {
        startRadarStabilityTicker()
    }

    private fun startRadarStabilityTicker() {
        radarTickerJob?.cancel()
        radarTickerJob = viewModelScope.launch {
            while (isActive) {
                val st = _state.value
                val waitMs = (st.refreshIntervalSec.coerceAtLeast(1) * 1000L)
                delay(waitMs)
                if (!_state.value.listFrozen) {
                    publishDeviceSnapshot()
                }
            }
        }
    }

    private fun publishDeviceSnapshot() {
        val st = _state.value
        val snap = radarEngine.snapshot(st.sortMode, st.bandFilter)
        _state.update {
            it.copy(
                devices = snap,
                lastListRefreshMs = System.currentTimeMillis()
            )
        }
    }

    fun setSortMode(mode: RadarSortMode) {
        _state.update { it.copy(sortMode = mode) }
        publishDeviceSnapshot()
    }

    fun setBandFilter(filter: BandFilter) {
        _state.update { it.copy(bandFilter = filter) }
        publishDeviceSnapshot()
    }

    fun setRefreshCadence(sec: Int) {
        _state.update { it.copy(refreshIntervalSec = sec, listFrozen = sec == 0) }
        if (sec > 0) publishDeviceSnapshot()
    }

    fun toggleFreezeList() {
        val next = !_state.value.listFrozen
        _state.update { it.copy(listFrozen = next) }
        if (!next) publishDeviceSnapshot()
    }

    private fun appendSerialLog(line: String) {
        _state.update { it.copy(serialLogs = (it.serialLogs + line).takeLast(150)) }
    }

    private fun appendFlashLog(line: String) {
        _state.update { it.copy(flashLogs = (it.flashLogs + line).takeLast(120)) }
    }

    fun clearToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    fun showToast(msg: String) {
        _state.update { it.copy(toastMessage = msg) }
    }

    fun setBoard(board: BoardPresetId) {
        _state.update { it.copy(selectedBoard = board) }
    }

    fun setFlashMode(mode: FlashImageMode) {
        _state.update { it.copy(flashMode = mode) }
    }

    fun setFlashBaud(baud: Int) {
        _state.update { it.copy(flashBaud = baud) }
    }

    fun connectUsbSniffer() {
        viewModelScope.launch {
            disconnectUsb()
            appendSerialLog("[SYS] Scanning Android USB-OTG bus for Heltec V3 / LilyGO / CH34x...")
            val dev = usbManager.openFirstDevice(115200)
            if (dev == null) {
                appendSerialLog("[SYS] No authorized USB-OTG radio found.")
                showToast("No USB-OTG serial radio detected or permission denied.")
                return@launch
            }
            activeUsb = dev
            _state.update { it.copy(usbConnected = true, usbLabel = dev.label) }
            appendSerialLog("[SYS] Connected USB-OTG: ${dev.label} @ 115200 baud")
            sendCliCommand("status")
            startUsbReadLoop(dev)
        }
    }

    fun disconnectUsb() {
        stopHopping(sendSniff = false)
        serialReadJob?.cancel()
        serialReadJob = null
        usbManager.closeDevice(activeUsb)
        activeUsb = null
        _state.update {
            it.copy(
                usbConnected = false,
                usbLabel = "Disconnected — Plug USB-OTG Radio",
                hopActive = false,
                activeHopFreqMHz = null
            )
        }
    }

    private fun startUsbReadLoop(dev: ConnectedUsbDevice) {
        serialReadJob = viewModelScope.launch(Dispatchers.IO) {
            val buf = ByteArray(1024)
            val lineAcc = StringBuilder()
            while (isActive) {
                val n = runCatching { dev.port.read(buf, 200) }.getOrDefault(-1)
                if (n < 0) {
                    withContext(Dispatchers.Main) {
                        appendSerialLog("[SYS] USB-OTG device disconnected.")
                        disconnectUsb()
                    }
                    break
                }
                if (n > 0) {
                    lineAcc.append(String(buf, 0, n, Charsets.UTF_8))
                    while (true) {
                        val nl = lineAcc.indexOf("\n")
                        if (nl < 0) break
                        val line = lineAcc.substring(0, nl).trim()
                        lineAcc.delete(0, nl + 1)
                        if (line.isNotEmpty()) {
                            handleJsonLine(line)
                        }
                    }
                }
            }
        }
    }

    fun sendCliCommand(cmd: String) {
        val clean = cmd.trim()
        if (clean.isEmpty()) return
        val usb = activeUsb
        if (usb == null) {
            showToast("Connect your Heltec V3 over USB-OTG first.")
            return
        }
        appendSerialLog("> $clean")
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { usb.port.write("$clean\n".toByteArray(Charsets.UTF_8), 1000) }
        }
    }

    // ---- Real Hardware Frequency Hopping (Waterfall & Band Survey) ----
    fun selectHopBand(preset: HopBandPreset) {
        val wasHopping = _state.value.hopActive
        _state.update { it.copy(hopBand = preset) }
        resetWaterfallBins()
        if (wasHopping) {
            startFrequencyHopping(preset)
        }
    }

    fun setHopDwellMs(dwellMs: Long) {
        _state.update { it.copy(hopDwellMs = dwellMs) }
    }

    fun resetWaterfallBins() {
        synchronized(binMap) {
            binMap.clear()
            currentSweepRow.clear()
            waterfallHistory.clear()
        }
        _state.update { it.copy(frequencyBins = emptyMap(), waterfallRows = emptyList(), scanPoints = emptyList()) }
    }

    fun startFrequencyHopping(preset: HopBandPreset = _state.value.hopBand) {
        if (activeUsb == null) {
            showToast("Connect your Heltec V3 over USB-OTG to start frequency hopping.")
            return
        }
        hopJob?.cancel()
        _state.update { it.copy(hopBand = preset, hopActive = true) }

        if (preset.usesFirmwareSweep) {
            // Heltec Core 1 autonomously hops across 903..925 MHz, measuring RSSI and decoding packets on each channel
            sendCliCommand("mode sweep")
            appendSerialLog("[HOP] Firmware Sweep active across ${preset.label}")
        } else {
            // Host-stepped frequency hopper using firmware's instant `freq <MHz>` tuning
            sendCliCommand("mode sniff")
            appendSerialLog("[HOP] Host Frequency Hopper active across ${preset.label} (${_state.value.hopDwellMs}ms dwell)")
            hopJob = viewModelScope.launch {
                var idx = 0
                while (isActive && activeUsb != null && _state.value.hopActive) {
                    val freqs = _state.value.hopBand.freqsMHz
                    val f = freqs[idx % freqs.size]
                    _state.update { it.copy(activeHopFreqMHz = f) }
                    sendCliCommand("freq ${"%.3f".format(f)}")
                    delay(_state.value.hopDwellMs)
                    idx++
                }
            }
        }
    }

    fun startFastScan() {
        if (activeUsb == null) {
            showToast("Connect your Heltec V3 over USB-OTG to start fast scan.")
            return
        }
        hopJob?.cancel()
        _state.update { it.copy(hopBand = HopBandPreset.US_915_SWEEP, hopActive = true) }
        sendCliCommand("mode scan")
        appendSerialLog("[HOP] Firmware Fast Scan active (903–925 MHz + home dwell)")
    }

    fun stopHopping(sendSniff: Boolean = true) {
        hopJob?.cancel()
        hopJob = null
        _state.update { it.copy(hopActive = false, activeHopFreqMHz = null) }
        if (sendSniff && activeUsb != null) {
            sendCliCommand("mode sniff")
        }
    }

    fun lockFrequency(freqMHz: Double) {
        stopHopping(sendSniff = false)
        if (activeUsb != null) {
            sendCliCommand("mode sniff")
            sendCliCommand("freq ${"%.3f".format(freqMHz)}")
            showToast("Locked Heltec onto ${"%.3f".format(freqMHz)} MHz")
        }
    }

    private fun snapToBinFreq(rawFreq: Double): Double {
        val presetFreqs = _state.value.hopBand.freqsMHz
        val nearest = presetFreqs.minByOrNull { abs(it - rawFreq) }
        return if (nearest != null && abs(nearest - rawFreq) <= 0.35) {
            nearest
        } else {
            kotlin.math.round(rawFreq * 1000.0) / 1000.0
        }
    }

    private fun recordFrequencySample(rawFreq: Double, rssi: Double, emitterLabel: String?, now: Long) {
        val binFreq = snapToBinFreq(rawFreq)
        val snapshotBins: Map<Double, FrequencyBinStats>
        val snapshotRows: List<Map<Double, Double>>

        synchronized(binMap) {
            val prev = binMap[binFreq]
            val newEmitters = if (!emitterLabel.isNullOrBlank()) {
                ((prev?.emitters ?: emptyList()) + emitterLabel).distinct().take(6)
            } else {
                prev?.emitters ?: emptyList()
            }
            binMap[binFreq] = FrequencyBinStats(
                freqMHz = binFreq,
                currentRssi = rssi,
                peakRssi = if (prev == null) rssi else maxOf(prev.peakRssi, rssi),
                sampleCount = (prev?.sampleCount ?: 0) + 1,
                packetCount = (prev?.packetCount ?: 0) + if (emitterLabel != null) 1 else 0,
                emitters = newEmitters,
                lastUpdatedMs = now
            )

            currentSweepRow[binFreq] = maxOf(currentSweepRow[binFreq] ?: -130.0, rssi)
            val expectedBins = _state.value.hopBand.freqsMHz.size
            // Push a new waterfall time-slice once a sweep pass covers at least half the band (or wraps)
            if (currentSweepRow.size >= (expectedBins * 2 / 3).coerceAtLeast(3)) {
                waterfallHistory.addFirst(LinkedHashMap(currentSweepRow))
                while (waterfallHistory.size > 44) waterfallHistory.removeLast()
                currentSweepRow.clear()
            }

            snapshotBins = LinkedHashMap(binMap)
            snapshotRows = waterfallHistory.toList()
        }

        _state.update {
            it.copy(
                activeHopFreqMHz = if (it.hopActive) binFreq else it.activeHopFreqMHz,
                frequencyBins = snapshotBins,
                waterfallRows = snapshotRows
            )
        }
    }

    private fun handleJsonLine(line: String) {
        appendSerialLog(line)
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        when (obj.optString("ev")) {
            "rx" -> {
                val freq = obj.optDouble("freq", _state.value.status.freq)
                val bw = obj.optDouble("bw", 125.0)
                val sf = obj.optInt("sf", 8)
                val cr = obj.optInt("cr", 5)
                val sync = obj.optInt("sync", 0x12)
                val rssi = obj.optDouble("rssi", -95.0)
                val snr = obj.optDouble("snr", 5.0)
                val ferr = obj.optDouble("ferr", 0.0)
                val crcOk = obj.optBoolean("crc_ok", true)
                val payload = obj.optString("payload", "")

                val cls = PacketDissector.classifyAndDissect(sync, payload, freq, sf, crcOk, now)
                val key = cls.rns?.identityHash ?: cls.rns?.destinationHash ?: cls.meshcore?.pubkeyPrefix ?: "lora:$freq"
                val smoothed = radarEngine.smoothRssi(key, rssi)
                val pkt = RFPacket(
                    id = UUID.randomUUID().toString(),
                    timestampMs = now,
                    freq = freq, bw = bw, sf = sf, cr = cr, sync = sync,
                    len = obj.optInt("len", payload.length / 2),
                    rssi = rssi, smoothedRssi = smoothed, snr = snr, ferr = ferr,
                    crcOk = crcOk, payloadHex = payload, protocolGuess = cls.protocol,
                    rns = cls.rns, meshcore = cls.meshcore, meshtastic = cls.meshtastic
                )
                radarEngine.ingestPacket(pkt)

                val emitterTag = when {
                    !pkt.rns?.peerName.isNullOrBlank() -> "${pkt.rns!!.peerName} (${pkt.rns.clientApp ?: "RNS"})"
                    pkt.rns != null -> "RNS ${pkt.rns.packetType} ${pkt.rns.destinationHash.take(8)}"
                    !pkt.meshcore?.nodeName.isNullOrBlank() -> "MeshCore ${pkt.meshcore!!.nodeName}"
                    pkt.meshtastic != null -> "Meshtastic CH 0x${pkt.meshtastic.channelHash}"
                    else -> "${pkt.protocolGuess} SF${pkt.sf}"
                }
                recordFrequencySample(freq, rssi, emitterTag, now)

                _state.update { st ->
                    val updatedPkts = (listOf(pkt) + st.packets).take(200)
                    val shouldPublishNow = st.devices.isEmpty() && !st.listFrozen
                    st.copy(
                        packets = updatedPkts,
                        totalLoraCount = st.totalLoraCount + 1,
                        devices = if (shouldPublishNow) radarEngine.snapshot(st.sortMode, st.bandFilter) else st.devices
                    )
                }
            }
            "wifi" -> {
                val mac = obj.optString("mac", "00:00:00:00:00:00")
                val laaFlag = if (obj.has("laa")) obj.optBoolean("laa") else null
                val (vendor, isLaa) = PacketDissector.resolveMacVendor(mac, laaFlag)
                val rssi = obj.optDouble("rssi", -70.0)
                val wf = WifiFrame(
                    id = UUID.randomUUID().toString(),
                    timestampMs = now,
                    ch = obj.optInt("ch", 1),
                    rssi = rssi,
                    smoothedRssi = radarEngine.smoothRssi("wifi:$mac", rssi),
                    ftype = obj.optString("ftype", "PROBE_REQ"),
                    mac = mac,
                    bssid = obj.optString("bssid", mac),
                    laa = isLaa,
                    ouiVendor = vendor,
                    ssid = obj.optString("ssid", ""),
                    len = obj.optInt("len", 64)
                )
                radarEngine.ingestWifi(wf)
                _state.update { st ->
                    val updatedWf = (listOf(wf) + st.wifiFrames).take(200)
                    val shouldPublishNow = st.devices.isEmpty() && !st.listFrozen
                    st.copy(
                        wifiFrames = updatedWf,
                        totalWifiCount = st.totalWifiCount + 1,
                        devices = if (shouldPublishNow) radarEngine.snapshot(st.sortMode, st.bandFilter) else st.devices
                    )
                }
            }
            "scan" -> {
                val f = obj.optDouble("freq", 915.0)
                val r = obj.optDouble("rssi", -115.0)
                val sp = ScanPoint(now, f, r)
                recordFrequencySample(f, r, null, now)
                _state.update { it.copy(scanPoints = (it.scanPoints + sp).takeLast(60)) }
            }
            "status" -> {
                val modeStr = obj.optString("mode", _state.value.status.mode)
                _state.update { st ->
                    st.copy(
                        status = st.status.copy(
                            fw = obj.optString("fw", st.status.fw),
                            mode = modeStr,
                            freq = obj.optDouble("freq", st.status.freq),
                            bw = obj.optDouble("bw", st.status.bw),
                            sf = obj.optInt("sf", st.status.sf),
                            sync = obj.optInt("sync", st.status.sync),
                            wifi = obj.optBoolean("wifi", st.status.wifi),
                            wifiCh = obj.optInt("wifich", st.status.wifiCh)
                        )
                    )
                }
            }
        }
    }

    fun clearCaptures() {
        radarEngine.clear()
        resetWaterfallBins()
        _state.update {
            it.copy(
                packets = emptyList(),
                wifiFrames = emptyList(),
                devices = emptyList(),
                totalLoraCount = 0,
                totalWifiCount = 0
            )
        }
    }

    // ---- Local .bin Loader (Android SAF) ----
    fun loadLocalBin(slot: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val cr = getApplication<Application>().contentResolver
            val name = cr.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            } ?: "firmware.bin"

            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: run {
                withContext(Dispatchers.Main) { showToast("Unable to read selected .bin file.") }
                return@launch
            }
            val boardMb = _state.value.selectedBoard.flashSizeMb
            val (offset, label) = when (slot) {
                "merged" -> 0x0 to "Merged Factory (.bin)"
                "bootloader" -> 0x0 to "Bootloader @ 0x0"
                "partitions" -> 0x8000 to "Partitions @ 0x8000"
                else -> 0x10000 to "App Firmware @ 0x10000"
            }
            val validated = Esp32S3SlipFlasher.validateEsp32Bin(name, bytes, offset, label, boardMb)
            _state.update { st ->
                when (slot) {
                    "merged" -> st.copy(mergedBin = validated)
                    "bootloader" -> st.copy(bootloaderBin = validated)
                    "partitions" -> st.copy(partitionsBin = validated)
                    else -> st.copy(firmwareBin = validated)
                }
            }
            appendFlashLog("Loaded $name (${bytes.size} B, MD5 ${validated.md5Hex.take(12)}...)")
        }
    }

    fun startUsbOtgFlash() {
        val st = _state.value
        val segments = if (st.flashMode == FlashImageMode.MERGED_SINGLE) {
            listOfNotNull(st.mergedBin)
        } else {
            listOfNotNull(st.bootloaderBin, st.partitionsBin, st.firmwareBin)
        }
        if (segments.isEmpty()) {
            showToast("Select local .bin firmware file(s) from Android storage first.")
            return
        }

        flashJob?.cancel()
        flashJob = viewModelScope.launch {
            disconnectUsb()
            _state.update { it.copy(flashLogs = emptyList(), flashProgress = FlashProgress(stage = FlashStage.CONNECTING)) }
            appendFlashLog("Requesting USB-OTG device for ${st.selectedBoard.displayName}...")

            val dev = usbManager.openFirstDevice(115200)
            if (dev == null) {
                _state.update {
                    it.copy(
                        flashProgress = FlashProgress(
                            stage = FlashStage.ERROR,
                            errorMessage = "No USB-OTG ESP32-S3 board detected. Check OTG cable & grant USB permission."
                        )
                    )
                }
                return@launch
            }

            appendFlashLog("Opened ${dev.label} (${dev.chip})")
            val flasher = Esp32S3SlipFlasher(
                onLog = { appendFlashLog(it) },
                onProgress = { prog -> _state.update { s -> s.copy(flashProgress = prog) } }
            )
            activeFlasher = flasher

            try {
                flasher.flash(dev, segments, st.selectedBoard.flashSizeMb, st.flashBaud)
                showToast("ESP32-S3 flashed and verified! Ready to sniff.")
            } catch (e: Throwable) {
                appendFlashLog("ERROR: ${e.message}")
                _state.update {
                    it.copy(
                        flashProgress = it.flashProgress.copy(
                            stage = FlashStage.ERROR,
                            errorMessage = e.message ?: "Flashing failed"
                        )
                    )
                }
            } finally {
                usbManager.closeDevice(dev)
                activeFlasher = null
            }
        }
    }

    fun cancelFlash() {
        activeFlasher?.abort()
        flashJob?.cancel()
        _state.update { it.copy(flashProgress = FlashProgress(stage = FlashStage.IDLE)) }
    }

    // ---- SAF Exports ----
    fun exportToUri(format: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val st = _state.value
            val allDevices = radarEngine.snapshot(RadarSortMode.STICKY_FIRST_SEEN, BandFilter.ALL)
            val data: ByteArray = when (format) {
                "pcap" -> ExportFormats.buildPcapBytes(st.packets)
                "charles" -> ExportFormats.buildCharlesJson(st.packets).toByteArray(Charsets.UTF_8)
                else -> ExportFormats.buildStructuredJson(allDevices, st.packets, st.wifiFrames).toByteArray(Charsets.UTF_8)
            }
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(data) }
            }.onSuccess {
                withContext(Dispatchers.Main) { showToast("Exported ${format.uppercase()} (${data.size} bytes)") }
            }.onFailure {
                withContext(Dispatchers.Main) { showToast("Export failed: ${it.message}") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectUsb()
        radarTickerJob?.cancel()
        flashJob?.cancel()
    }
}
