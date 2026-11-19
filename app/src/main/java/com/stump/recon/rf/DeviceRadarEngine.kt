package com.stump.recon.rf

import kotlin.math.abs
import kotlin.math.round

class KalmanFilter(
    private val q: Double = 0.25,
    private val r: Double = 4.0
) {
    private var x: Double? = null
    private var p: Double = 1.0

    fun filter(measurement: Double): Double {
        val prev = x ?: run {
            x = measurement
            return measurement
        }
        p += q
        val k = p / (p + r)
        val est = prev + k * (measurement - prev)
        p *= (1.0 - k)
        x = est
        return est
    }
}

/**
 * Persistent sticky device registry.
 * Devices remain stably tracked in memory even when high-rate 2.4 GHz Wi-Fi traffic arrives,
 * and sorting uses 3 dBm signal quantization buckets to eliminate constant card-swapping jitter.
 */
class DeviceRadarEngine {
    private val kalmanMap = HashMap<String, KalmanFilter>()
    private val registry = LinkedHashMap<String, DiscoveredDevice>()

    @Synchronized
    fun clear() {
        kalmanMap.clear()
        registry.clear()
    }

    @Synchronized
    fun smoothRssi(key: String, rssi: Double): Double {
        val kf = kalmanMap.getOrPut(key) { KalmanFilter() }
        return round(kf.filter(rssi) * 10.0) / 10.0
    }

    @Synchronized
    fun ingestPacket(pkt: RFPacket) {
        val proto = pkt.protocolGuess
        val ferrBucket = (round(pkt.ferr / 1200.0) * 1200).toInt()
        val uid = when {
            pkt.rns?.ifac == true -> "lora:rns:ifac:${pkt.freq.toInt()}:$ferrBucket"
            pkt.rns?.identityHash != null -> "lora:rns:id:${pkt.rns.identityHash}"
            pkt.meshcore?.pubkeyPrefix != null -> "lora:mc:${pkt.meshcore.pubkeyPrefix}"
            pkt.rns != null -> {
                val match = registry.values.firstOrNull { d ->
                    d.dtype == "Reticulum (RNS)" && !d.ifac && d.ferrAvg != null && abs(d.ferrAvg - pkt.ferr) <= 1200.0
                }
                match?.uid ?: "lora:rns:${pkt.rns.destinationHash.take(12)}:$ferrBucket"
            }
            else -> "lora:${proto.lowercase()}:${"%.1f".format(pkt.freq)}:$ferrBucket"
        }

        val existing = registry[uid]
        if (existing == null) {
            val displayName = when {
                pkt.rns?.ifac == true -> "IFAC-Masked RNS Node"
                !pkt.rns?.peerName.isNullOrBlank() -> pkt.rns!!.peerName!!
                !pkt.meshcore?.nodeName.isNullOrBlank() -> pkt.meshcore!!.nodeName!!
                pkt.rns != null -> "${pkt.rns.clientApp ?: "RNS"} · ${pkt.rns.destinationHash.take(8)}"
                pkt.meshcore != null -> "${pkt.meshcore.nodeRole} · ${pkt.meshcore.pubkeyPrefix.take(8)}"
                pkt.meshtastic != null -> "Meshtastic CH 0x${pkt.meshtastic.channelHash}"
                else -> "$proto · ${"%.3f".format(pkt.freq)} MHz"
            }
            registry[uid] = DiscoveredDevice(
                uid = uid,
                name = displayName,
                dtype = proto,
                band = "SUB-GHZ LORA",
                rssiRaw = pkt.rssi,
                rssiKalman = pkt.smoothedRssi,
                rssiMin = pkt.rssi,
                rssiMax = pkt.rssi,
                pktCount = 1,
                firstSeenMs = pkt.timestampMs,
                lastSeenMs = pkt.timestampMs,
                freq = pkt.freq,
                sf = pkt.sf,
                bw = pkt.bw,
                sync = pkt.sync,
                snr = pkt.snr,
                ferrAvg = pkt.ferr,
                destHash = pkt.rns?.destinationHash,
                rnsDestsSeen = listOfNotNull(pkt.rns?.destinationHash),
                hops = pkt.rns?.hops ?: pkt.meshcore?.hops,
                rnsType = pkt.rns?.packetType,
                rnsContext = pkt.rns?.context,
                clientApp = pkt.rns?.clientApp,
                peerName = pkt.rns?.peerName,
                ifac = pkt.rns?.ifac == true,
                cryptoVerified = pkt.rns?.cryptoVerified == true,
                hasRatchet = pkt.rns?.hasRatchet == true,
                ratchetIdsSeen = listOfNotNull(pkt.rns?.ratchetId),
                relayedVia = listOfNotNull(pkt.rns?.transportId),
                stampCost = pkt.rns?.stampCost,
                propagation = pkt.rns?.propagation,
                rnodeFramed = pkt.rns?.rnodeFramed == true,
                rnodeSplitStatus = pkt.rns?.rnodeSplitStatus,
                nodeRole = pkt.meshcore?.nodeRole,
                pubkeyPrefix = pkt.meshcore?.pubkeyPrefix,
                gpsCoords = pkt.meshcore?.gpsCoords,
                meshChannel = pkt.meshtastic?.channelHash,
                lastPayloadHex = pkt.payloadHex
            )
        } else {
            val n = existing.pktCount + 1
            val newFerr = ((existing.ferrAvg ?: pkt.ferr) * existing.pktCount + pkt.ferr) / n
            val dests = (existing.rnsDestsSeen + listOfNotNull(pkt.rns?.destinationHash)).distinct().take(6)
            val ratchets = (existing.ratchetIdsSeen + listOfNotNull(pkt.rns?.ratchetId)).distinct().take(6)
            val relays = (existing.relayedVia + listOfNotNull(pkt.rns?.transportId)).distinct().take(6)
            registry[uid] = existing.copy(
                name = pkt.rns?.peerName ?: pkt.meshcore?.nodeName ?: existing.name,
                freq = pkt.freq,
                rssiRaw = pkt.rssi,
                rssiKalman = pkt.smoothedRssi,
                rssiMin = minOf(existing.rssiMin, pkt.rssi),
                rssiMax = maxOf(existing.rssiMax, pkt.rssi),
                pktCount = n,
                lastSeenMs = maxOf(existing.lastSeenMs, pkt.timestampMs),
                snr = pkt.snr,
                ferrAvg = newFerr,
                destHash = pkt.rns?.destinationHash ?: existing.destHash,
                rnsDestsSeen = dests,
                hops = pkt.rns?.hops ?: existing.hops,
                rnsType = pkt.rns?.packetType ?: existing.rnsType,
                rnsContext = pkt.rns?.context ?: existing.rnsContext,
                clientApp = pkt.rns?.clientApp ?: existing.clientApp,
                peerName = pkt.rns?.peerName ?: existing.peerName,
                cryptoVerified = existing.cryptoVerified || (pkt.rns?.cryptoVerified == true),
                hasRatchet = existing.hasRatchet || (pkt.rns?.hasRatchet == true),
                ratchetIdsSeen = ratchets,
                relayedVia = relays,
                stampCost = pkt.rns?.stampCost ?: existing.stampCost,
                propagation = pkt.rns?.propagation ?: existing.propagation,
                rnodeFramed = existing.rnodeFramed || (pkt.rns?.rnodeFramed == true),
                rnodeSplitStatus = pkt.rns?.rnodeSplitStatus ?: existing.rnodeSplitStatus,
                lastPayloadHex = pkt.payloadHex
            )
        }
    }

    @Synchronized
    fun ingestWifi(wf: WifiFrame) {
        val uid = if (wf.laa && wf.ssid.isNotBlank()) "wifi:laa:${wf.ssid}" else "wifi:${wf.mac}"
        val dtype = when {
            wf.ouiVendor.contains("Espressif", ignoreCase = true) -> "ESP32 / Heltec"
            wf.ftype == "BEACON" -> "Wi-Fi AP"
            else -> "Wi-Fi Client"
        }
        val existing = registry[uid]
        if (existing == null) {
            // Cap Wi-Fi entries at 250 so dense urban environments don't grow unboundedly
            if (registry.size > 300) {
                val oldestWifi = registry.values
                    .filter { it.band == "2.4GHZ WIFI" }
                    .minByOrNull { it.lastSeenMs }
                if (oldestWifi != null) registry.remove(oldestWifi.uid)
            }
            registry[uid] = DiscoveredDevice(
                uid = uid,
                name = if (wf.ssid.isNotBlank()) wf.ssid else "${wf.ouiVendor} (${wf.mac.takeLast(5)})",
                dtype = dtype,
                band = "2.4GHZ WIFI",
                rssiRaw = wf.rssi,
                rssiKalman = wf.smoothedRssi,
                rssiMin = wf.rssi,
                rssiMax = wf.rssi,
                pktCount = 1,
                firstSeenMs = wf.timestampMs,
                lastSeenMs = wf.timestampMs,
                mac = wf.mac,
                ouiVendor = wf.ouiVendor,
                laa = wf.laa,
                ch = wf.ch,
                ssidsSeen = listOfNotNull(wf.ssid.takeIf { it.isNotBlank() })
            )
        } else {
            val ssids = (existing.ssidsSeen + listOfNotNull(wf.ssid.takeIf { it.isNotBlank() })).distinct().take(6)
            registry[uid] = existing.copy(
                name = if (wf.ssid.isNotBlank() && existing.name.startsWith("Randomized")) wf.ssid else existing.name,
                rssiRaw = wf.rssi,
                rssiKalman = wf.smoothedRssi,
                rssiMin = minOf(existing.rssiMin, wf.rssi),
                rssiMax = maxOf(existing.rssiMax, wf.rssi),
                pktCount = existing.pktCount + 1,
                lastSeenMs = maxOf(existing.lastSeenMs, wf.timestampMs),
                ch = wf.ch,
                ssidsSeen = ssids
            )
        }
    }

    @Synchronized
    fun snapshot(sortMode: RadarSortMode, bandFilter: BandFilter): List<DiscoveredDevice> {
        val filtered = registry.values.filter { d ->
            when (bandFilter) {
                BandFilter.ALL -> true
                BandFilter.LORA_ONLY -> d.band == "SUB-GHZ LORA"
                BandFilter.WIFI_ONLY -> d.band == "2.4GHZ WIFI"
            }
        }
        return when (sortMode) {
            RadarSortMode.STICKY_FIRST_SEEN -> filtered.sortedBy { it.firstSeenMs }
            RadarSortMode.SIGNAL_DBM -> filtered.sortedWith(
                // Quantize RSSI into 3 dBm steps so minor 1 dBm jitter never swaps cards
                compareByDescending<DiscoveredDevice> { (it.rssiKalman / 3.0).toInt() }
                    .thenBy { it.firstSeenMs }
            )
            RadarSortMode.LAST_SEEN -> filtered.sortedWith(
                compareByDescending<DiscoveredDevice> { it.lastSeenMs / 5000L }
                    .thenBy { it.firstSeenMs }
            )
            RadarSortMode.PACKETS -> filtered.sortedWith(
                compareByDescending<DiscoveredDevice> { it.pktCount }
                    .thenBy { it.firstSeenMs }
            )
            RadarSortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
        }
    }
}
