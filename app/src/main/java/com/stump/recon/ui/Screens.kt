package com.stump.recon.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stump.recon.rf.BandFilter
import com.stump.recon.rf.BoardPresetId
import com.stump.recon.rf.DiscoveredDevice
import com.stump.recon.rf.HopBandPreset
import com.stump.recon.rf.RadarSortMode
import com.stump.recon.usb.FlashStage
import com.stump.recon.usb.ValidatedBin

private fun formatAge(lastSeenMs: Long, nowMs: Long): String {
    val sec = ((nowMs - lastSeenMs).coerceAtLeast(0L) / 1000L).toInt()
    return when {
        sec < 2 -> "now"
        sec < 60 -> "${sec}s ago"
        else -> "${sec / 60}m ${sec % 60}s ago"
    }
}

private fun rssiToHeatColor(rssi: Double): Color = when {
    rssi >= -68.0 -> StumpColors.Rose
    rssi >= -82.0 -> StumpColors.Amber
    rssi >= -96.0 -> StumpColors.Emerald
    rssi >= -108.0 -> StumpColors.Cyan
    else -> Color(0xFF17253D)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarScreen(
    state: ReconUiState,
    onSelectSort: (RadarSortMode) -> Unit,
    onSelectBand: (BandFilter) -> Unit,
    onSelectCadence: (Int) -> Unit,
    onToggleFreeze: () -> Unit,
    onConnectUsb: () -> Unit
) {
    var selectedDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }
    val nowMs = System.currentTimeMillis()

    Column(modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Live Device Radar", color = StumpColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${state.devices.size} shown · ${state.totalLoraCount} LoRa · ${state.totalWifiCount} Wi-Fi",
                    color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            Surface(
                color = if (state.listFrozen) StumpColors.Amber else StumpColors.Surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (state.listFrozen) StumpColors.Amber else StumpColors.Border),
                modifier = Modifier.clickable { onToggleFreeze() }
            ) {
                Text(
                    if (state.listFrozen) "HELD" else "Hold List",
                    color = if (state.listFrozen) Color.Black else StumpColors.TextPrimary,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }

        // Band filter + Refresh cadence row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BandFilter.entries.forEach { bf ->
                val active = state.bandFilter == bf
                FilterChipButton(bf.label, active, StumpColors.Cyan) { onSelectBand(bf) }
            }
            Text("·", color = StumpColors.TextSecondary)
            listOf(1 to "1s", 3 to "3s", 5 to "5s").forEach { (sec, lbl) ->
                val active = !state.listFrozen && state.refreshIntervalSec == sec
                FilterChipButton("Refresh $lbl", active, StumpColors.Emerald) { onSelectCadence(sec) }
            }
        }

        // Sort mode selector row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Sort:", color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            RadarSortMode.entries.forEach { sm ->
                FilterChipButton(sm.label, state.sortMode == sm, StumpColors.Cyan) { onSelectSort(sm) }
            }
        }

        if (state.devices.isEmpty()) {
            Surface(
                color = StumpColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, StumpColors.Border),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (state.usbConnected) "Listening on ${"%.3f".format(state.status.freq)} MHz — No Real Signals Yet"
                        else "No USB-OTG Radio Connected",
                        color = StumpColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp
                    )
                    Text(
                        if (state.usbConnected) "Waiting for real Sub-GHz LoRa packets or 2.4 GHz 802.11 frames from your Heltec V3. Zero synthetic data is generated."
                        else "Plug in your Heltec V3 / LilyGO over USB-OTG and connect to start passive RF reception.",
                        color = StumpColors.TextSecondary, fontSize = 12.sp
                    )
                    if (!state.usbConnected) {
                        Button(
                            onClick = onConnectUsb,
                            colors = ButtonDefaults.buttonColors(containerColor = StumpColors.Cyan),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text("Connect USB-OTG Radio", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.devices, key = { it.uid }) { dev ->
                    val stale = (nowMs - dev.lastSeenMs) > 45_000L
                    Surface(
                        color = StumpColors.Surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (dev.band == "SUB-GHZ LORA") StumpColors.Cyan.copy(alpha = 0.45f) else StumpColors.Border),
                        modifier = Modifier.fillMaxWidth().clickable { selectedDevice = dev }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    dev.name,
                                    color = if (stale) StumpColors.TextSecondary else StumpColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                                )
                                Text(
                                    "${"%.1f".format(dev.rssiKalman)} dBm",
                                    color = if (stale) StumpColors.TextSecondary else if (dev.rssiKalman > -72) StumpColors.Emerald else StumpColors.Cyan,
                                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            val meta = buildList {
                                add(dev.dtype)
                                dev.freq?.let { add("${"%.3f".format(it)} MHz") }
                                dev.ch?.let { add("CH $it") }
                                if (dev.cryptoVerified) add("SHA256 OK")
                                if (dev.hasRatchet) add("Ratchet")
                                if (dev.ifac) add("IFAC")
                                dev.stampCost?.let { add("Stamp $it") }
                                add("${dev.pktCount} pkts")
                                add(formatAge(dev.lastSeenMs, nowMs))
                            }.joinToString(" · ")
                            Text(meta, color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }

    selectedDevice?.let { dev ->
        ModalBottomSheet(onDismissRequest = { selectedDevice = null }, containerColor = StumpColors.Surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(dev.name, color = StumpColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${dev.dtype} · ${dev.band}", color = StumpColors.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                HorizontalDivider(color = StumpColors.Border)
                DossierRow("Kalman / Raw RSSI", "${"%.1f".format(dev.rssiKalman)} / ${"%.1f".format(dev.rssiRaw)} dBm")
                DossierRow("Min / Max RSSI", "${"%.1f".format(dev.rssiMin)} / ${"%.1f".format(dev.rssiMax)} dBm")
                dev.freq?.let { DossierRow("Frequency", "${"%.3f".format(it)} MHz (SF${dev.sf ?: "?"})") }
                dev.ferrAvg?.let { DossierRow("Crystal Ferr Avg", "${it.toInt()} Hz") }
                dev.destHash?.let { DossierRow("RNS Dest Hash", it) }
                if (dev.cryptoVerified) DossierRow("ANNOUNCE Crypto", "SHA-256 Verified")
                if (dev.hasRatchet) DossierRow("Ratchet IDs (${dev.ratchetIdsSeen.size})", dev.ratchetIdsSeen.joinToString(", "))
                if (dev.relayedVia.isNotEmpty()) DossierRow("Relayed Via (HEADER_2)", dev.relayedVia.joinToString(", "))
                dev.stampCost?.let { DossierRow("LXMF Stamp Cost", "$it") }
                dev.propagation?.let { p ->
                    DossierRow("LXMF Propagation", "Active=${p.active} · Xfer=${p.transferLimitKb}KB · Sync=${p.syncLimitKb}KB")
                }
                if (dev.rnodeFramed) DossierRow("RNode Air Framing", dev.rnodeSplitStatus ?: "SINGLE")
                dev.nodeRole?.let { DossierRow("MeshCore Role", it) }
                dev.gpsCoords?.let { DossierRow("MeshCore GPS", it) }
                dev.mac?.let { DossierRow("MAC / OUI", "$it · ${dev.ouiVendor ?: ""}") }
                dev.lastPayloadHex?.let {
                    Text("Last Payload (Hex)", color = StumpColors.TextSecondary, fontSize = 11.sp)
                    Text(it.take(180), color = StumpColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FilterChipButton(label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Surface(
        color = if (active) accent.copy(alpha = 0.22f) else StumpColors.Surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (active) accent else StumpColors.Border),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            label,
            color = if (active) accent else StumpColors.TextSecondary,
            fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun DossierRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = StumpColors.TextSecondary, fontSize = 12.sp)
        Text(value, color = StumpColors.TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun WaterfallScreen(
    state: ReconUiState,
    onSelectHopBand: (HopBandPreset) -> Unit,
    onSelectDwell: (Long) -> Unit,
    onStartHop: () -> Unit,
    onStartFastScan: () -> Unit,
    onStopHop: () -> Unit,
    onLockFreq: (Double) -> Unit,
    onResetPeaks: () -> Unit
) {
    val freqs = state.hopBand.freqsMHz
    val bins = state.frequencyBins

    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Frequency Hopper & Waterfall", color = StumpColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (state.hopActive) "HOPPING · ${state.activeHopFreqMHz?.let { "%.3f MHz".format(it) } ?: state.hopBand.label} (${state.status.mode.uppercase()})"
                    else "LOCKED · ${"%.3f".format(state.status.freq)} MHz · ${state.status.mode.uppercase()}",
                    color = if (state.hopActive) StumpColors.Emerald else StumpColors.TextSecondary,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            FilterChipButton("Reset", false, StumpColors.Rose) { onResetPeaks() }
        }

        // Band selector row
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HopBandPreset.entries.forEach { preset ->
                FilterChipButton(preset.label, state.hopBand == preset, StumpColors.Cyan) { onSelectHopBand(preset) }
            }
        }

        // Hopper action controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.hopActive) {
                Button(
                    onClick = onStopHop,
                    colors = ButtonDefaults.buttonColors(containerColor = StumpColors.Amber),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("Stop Hop (Lock)", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartHop,
                    colors = ButtonDefaults.buttonColors(containerColor = StumpColors.Emerald),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("Start Freq Hop", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            OutlinedButton(
                onClick = onStartFastScan,
                border = BorderStroke(1.dp, StumpColors.Cyan),
                modifier = Modifier.weight(1f).height(44.dp)
            ) {
                Text("Fast 915 Scan", color = StumpColors.Cyan, fontSize = 12.sp)
            }
        }

        if (!state.hopBand.usesFirmwareSweep) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Dwell:", color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                listOf(300L to "300ms", 450L to "450ms", 800L to "800ms").forEach { (ms, lbl) ->
                    FilterChipButton(lbl, state.hopDwellMs == ms, StumpColors.Emerald) { onSelectDwell(ms) }
                }
            }
        }

        // Live Spectrum + Peak Hold + Scrolling Waterfall Canvas
        Surface(
            color = StumpColors.Surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, StumpColors.Border),
            modifier = Modifier.fillMaxWidth().height(220.dp)
        ) {
            if (bins.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.usbConnected) "Tap Start Freq Hop to sweep ${state.hopBand.label} and map real emitters."
                        else "Connect USB-OTG Heltec V3 to view live frequency spectrum & waterfall.",
                        color = StumpColors.TextSecondary, fontSize = 12.sp
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                    // Top half: Per-frequency RSSI bar + Peak Hold marker
                    Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        val count = freqs.size.coerceAtLeast(1)
                        val colW = size.width / count
                        freqs.forEachIndexed { idx, f ->
                            val stat = bins[f]
                            val curRssi = stat?.currentRssi ?: -125.0
                            val pkRssi = stat?.peakRssi ?: -125.0
                            val curNorm = ((curRssi + 125.0) / 75.0).coerceIn(0.03, 1.0).toFloat()
                            val pkNorm = ((pkRssi + 125.0) / 75.0).coerceIn(0.03, 1.0).toFloat()
                            val barH = size.height * curNorm
                            val isCursor = state.activeHopFreqMHz != null && kotlin.math.abs(state.activeHopFreqMHz - f) < 0.1

                            if (isCursor) {
                                drawRect(
                                    color = StumpColors.Cyan.copy(alpha = 0.14f),
                                    topLeft = Offset(idx * colW, 0f),
                                    size = Size(colW, size.height)
                                )
                            }
                            drawRect(
                                color = rssiToHeatColor(curRssi),
                                topLeft = Offset(idx * colW + 2f, size.height - barH),
                                size = Size((colW - 4f).coerceAtLeast(2f), barH)
                            )
                            // Peak-hold cap line
                            if (stat != null) {
                                val pkY = size.height * (1f - pkNorm)
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset(idx * colW + 2f, pkY),
                                    size = Size((colW - 4f).coerceAtLeast(2f), 2.5f)
                                )
                            }
                        }
                    }

                    // Frequency axis labels (start / mid / end)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${"%.1f".format(freqs.first())} MHz", color = StumpColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("${"%.1f".format(freqs[freqs.size / 2])} MHz", color = StumpColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("${"%.1f".format(freqs.last())} MHz", color = StumpColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    // Bottom half: Real scrolling Time x Frequency Waterfall
                    Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        val rows = state.waterfallRows
                        val count = freqs.size.coerceAtLeast(1)
                        val colW = size.width / count
                        val maxRows = 32
                        val rowH = size.height / maxRows
                        rows.take(maxRows).forEachIndexed { rIdx, rowMap ->
                            freqs.forEachIndexed { cIdx, f ->
                                val rssi = rowMap[f] ?: return@forEachIndexed
                                drawRect(
                                    color = rssiToHeatColor(rssi),
                                    topLeft = Offset(cIdx * colW, rIdx * rowH),
                                    size = Size(colW, rowH + 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Per-frequency Emitter Breakdown ("What is emitting on which frequency")
        Text("Frequency Activity & Emitters (Tap to Lock)", color = StumpColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        val sortedBins = bins.values.sortedWith(
            compareByDescending<com.stump.recon.rf.FrequencyBinStats> { it.packetCount > 0 }
                .thenByDescending { it.peakRssi }
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sortedBins, key = { it.freqMHz }) { stat ->
                Surface(
                    color = StumpColors.Surface,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (stat.packetCount > 0) StumpColors.Emerald.copy(alpha = 0.5f) else StumpColors.Border),
                    modifier = Modifier.fillMaxWidth().clickable { onLockFreq(stat.freqMHz) }
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${"%.3f".format(stat.freqMHz)} MHz", color = StumpColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(
                                "Cur ${stat.currentRssi.toInt()} · Peak ${stat.peakRssi.toInt()} dBm · ${stat.packetCount} pkts",
                                color = if (stat.peakRssi > -88) StumpColors.Amber else StumpColors.Cyan,
                                fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                        if (stat.emitters.isNotEmpty()) {
                            Text(
                                "Emitting: ${stat.emitters.joinToString(" · ")}",
                                color = StumpColors.Emerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        } else {
                            Text(
                                if (stat.peakRssi > -95) "RF energy detected (no LoRa sync match yet — tap to lock & inspect)"
                                else "Noise floor (${stat.sampleCount} samples)",
                                color = StumpColors.TextSecondary, fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamScreen(state: ReconUiState) {
    var filter by remember { mutableStateOf("ALL") }
    val filters = listOf("ALL", "RNS", "MESHCORE", "MESHTASTIC", "WIFI")

    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Text("Packet Stream & Dissector", color = StumpColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            filters.forEach { f ->
                FilterChipButton(f, filter == f, StumpColors.Cyan) { filter = f }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (filter == "WIFI") {
            if (state.wifiFrames.isEmpty()) {
                Text("No 802.11 frames captured yet.", color = StumpColors.TextSecondary, fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.wifiFrames, key = { it.id }) { wf ->
                        Surface(color = StumpColors.Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, StumpColors.Border), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("${wf.ftype} · CH ${wf.ch} · ${wf.rssi.toInt()} dBm", color = StumpColors.Amber, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text("${wf.mac} (${wf.ouiVendor}) · SSID: ${wf.ssid.ifBlank { "<broadcast>" }}", color = StumpColors.TextPrimary, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            val filtered = state.packets.filter {
                when (filter) {
                    "RNS" -> it.rns != null
                    "MESHCORE" -> it.meshcore != null
                    "MESHTASTIC" -> it.meshtastic != null
                    else -> true
                }
            }
            if (filtered.isEmpty()) {
                Text("No real Sub-GHz LoRa packets captured yet.", color = StumpColors.TextSecondary, fontSize = 12.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { pkt ->
                        Surface(color = StumpColors.Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, StumpColors.Border), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${pkt.protocolGuess} · ${"%.3f".format(pkt.freq)} MHz", color = StumpColors.Cyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text("${"%.1f".format(pkt.rssi)} dBm · ${pkt.len}B", color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                pkt.rns?.let { r ->
                                    Text(
                                        "${r.headerType} ${r.packetType} · Hops ${r.hops} · ${r.context}" +
                                            (if (r.cryptoVerified) " · SHA256 OK" else "") +
                                            (if (r.hasRatchet) " · Ratchet ${r.ratchetId?.take(8)}" else ""),
                                        color = StumpColors.Emerald, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                                    )
                                    if (!r.peerName.isNullOrBlank()) {
                                        Text("Peer: ${r.peerName} (${r.clientApp}) ${r.stampCost?.let { "· Stamp $it" } ?: ""}", color = StumpColors.TextPrimary, fontSize = 12.sp)
                                    }
                                }
                                pkt.meshcore?.let { mc ->
                                    Text("${mc.routeType} ${mc.payloadType} · ${mc.nodeRole} · ${mc.nodeName ?: mc.pubkeyPrefix}", color = StumpColors.Violet, fontSize = 12.sp)
                                }
                                Text(pkt.payloadHex.take(64), color = StumpColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FlasherScreen(
    state: ReconUiState,
    onSelectBoard: (BoardPresetId) -> Unit,
    onSelectMode: (FlashImageMode) -> Unit,
    onSelectBaud: (Int) -> Unit,
    onPickBin: (String) -> Unit,
    onStartFlash: () -> Unit,
    onCancelFlash: () -> Unit,
    onReconnectSniffer: () -> Unit
) {
    val prog = state.flashProgress
    val isFlashing = prog.stage !in setOf(FlashStage.IDLE, FlashStage.COMPLETE, FlashStage.ERROR)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("USB-OTG ESP32-S3 Firmware Flasher", color = StumpColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "Flash local .bin files directly over USB-OTG (CP2102, CH34x, Native ESP32-S3 USB-CDC).",
            color = StumpColors.TextSecondary, fontSize = 12.sp
        )

        Text("1. Target ESP32-S3 Board", color = StumpColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        BoardPresetId.entries.forEach { board ->
            val selected = state.selectedBoard == board
            Surface(
                color = if (selected) StumpColors.SurfaceElevated else StumpColors.Surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (selected) StumpColors.Cyan else StumpColors.Border),
                modifier = Modifier.fillMaxWidth().clickable { onSelectBoard(board) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(board.displayName, color = StumpColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("${board.chip} · ${board.usbBridge} · ${board.flashSizeMb} MB Flash", color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Text("2. Local .bin Image Mode", color = StumpColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(FlashImageMode.MERGED_SINGLE to "Merged .bin (0x0)", FlashImageMode.THREE_PART to "3-Part Images").forEach { (mode, label) ->
                val active = state.flashMode == mode
                OutlinedButton(
                    onClick = { onSelectMode(mode) },
                    border = BorderStroke(1.dp, if (active) StumpColors.Cyan else StumpColors.Border),
                    modifier = Modifier.weight(1f).height(46.dp)
                ) {
                    Text(label, color = if (active) StumpColors.Cyan else StumpColors.TextSecondary, fontSize = 12.sp)
                }
            }
        }

        if (state.flashMode == FlashImageMode.MERGED_SINGLE) {
            BinPickerCard("Merged Factory Binary @ 0x0", state.mergedBin) { onPickBin("merged") }
        } else {
            BinPickerCard("Bootloader (.bin) @ 0x0", state.bootloaderBin) { onPickBin("bootloader") }
            BinPickerCard("Partitions (.bin) @ 0x8000", state.partitionsBin) { onPickBin("partitions") }
            BinPickerCard("App Firmware (.bin) @ 0x10000", state.firmwareBin) { onPickBin("firmware") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(115200, 460800, 921600).forEach { b ->
                val active = state.flashBaud == b
                OutlinedButton(
                    onClick = { onSelectBaud(b) },
                    border = BorderStroke(1.dp, if (active) StumpColors.Cyan else StumpColors.Border),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("${b / 1000}k", color = if (active) StumpColors.Cyan else StumpColors.TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (isFlashing) {
            Button(
                onClick = onCancelFlash,
                colors = ButtonDefaults.buttonColors(containerColor = StumpColors.Rose),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Abort Flash (${prog.stage.name})", fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onStartFlash,
                colors = ButtonDefaults.buttonColors(containerColor = StumpColors.Cyan),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Flash Local .bin via USB-OTG", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        if (prog.stage != FlashStage.IDLE) {
            Surface(color = StumpColors.Surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, StumpColors.Border), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stage: ${prog.stage.name} · ${prog.percent}%", color = StumpColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    LinearProgressIndicator(
                        progress = { (prog.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = if (prog.stage == FlashStage.ERROR) StumpColors.Rose else StumpColors.Emerald
                    )
                    prog.chipInfo?.let { Text("Target: $it", color = StumpColors.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    if (prog.totalBytes > 0) {
                        Text("${prog.writtenBytes} / ${prog.totalBytes} B · ${prog.rateKBps} KB/s", color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    prog.errorMessage?.let {
                        Text(it, color = StumpColors.Rose, fontSize = 12.sp)
                        Text("Manual recovery: Hold BOOT button -> tap RST -> release BOOT -> tap Flash again.", color = StumpColors.Amber, fontSize = 11.sp)
                    }
                    if (prog.stage == FlashStage.COMPLETE) {
                        Button(
                            onClick = onReconnectSniffer,
                            colors = ButtonDefaults.buttonColors(containerColor = StumpColors.Emerald),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text("Connect Live RF Sniffer", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (state.flashLogs.isNotEmpty()) {
            Surface(color = Color.Black, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, StumpColors.Border), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    state.flashLogs.takeLast(12).forEach { line ->
                        Text(line, color = StumpColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun BinPickerCard(title: String, bin: ValidatedBin?, onPick: () -> Unit) {
    Surface(
        color = StumpColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (bin?.magicValid == true) StumpColors.Emerald else StumpColors.Border),
        modifier = Modifier.fillMaxWidth().clickable { onPick() }
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, color = StumpColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (bin == null) "Pick .bin" else "Change", color = StumpColors.Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            if (bin != null) {
                Text("${bin.fileName} · ${bin.bytes.size} B · ${bin.spiMode} · MD5 ${bin.md5Hex.take(10)}", color = StumpColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                bin.warning?.let { Text(it, color = StumpColors.Amber, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
fun TunerExportScreen(
    state: ReconUiState,
    onSendCommand: (String) -> Unit,
    onClearCaptures: () -> Unit,
    onExport: (String) -> Unit
) {
    var cmdInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("RF Tuner & Forensic Exports", color = StumpColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        Text("Quick Presets (Zero-TX Receive Only)", color = StumpColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onSendCommand("freq 915.0"); onSendCommand("sf 8"); onSendCommand("sync 0x12") }, modifier = Modifier.weight(1f).height(46.dp)) {
                Text("RNS 915", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { onSendCommand("freq 906.875"); onSendCommand("sf 11"); onSendCommand("sync 0x2b") }, modifier = Modifier.weight(1f).height(46.dp)) {
                Text("Mesh US", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { onSendCommand("freq 868.1"); onSendCommand("sf 8"); onSendCommand("sync 0x12") }, modifier = Modifier.weight(1f).height(46.dp)) {
                Text("EU 868", fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onSendCommand("wifi off") }, modifier = Modifier.weight(1f).height(44.dp)) {
                Text("Wi-Fi OFF", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { onSendCommand("wifi on") }, modifier = Modifier.weight(1f).height(44.dp)) {
                Text("Wi-Fi ON", fontSize = 12.sp)
            }
        }

        Text("Save Real Session to Android Storage", color = StumpColors.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onExport("pcap") }, modifier = Modifier.weight(1f).height(48.dp)) {
                Text("PCAP", fontSize = 12.sp)
            }
            Button(onClick = { onExport("json") }, modifier = Modifier.weight(1f).height(48.dp)) {
                Text("JSON", fontSize = 12.sp)
            }
            Button(onClick = { onExport("charles") }, modifier = Modifier.weight(1f).height(48.dp)) {
                Text("Charles", fontSize = 12.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmdInput,
                onValueChange = { cmdInput = it },
                placeholder = { Text("CLI cmd (e.g. mode sweep, status)", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (cmdInput.isNotBlank()) {
                        onSendCommand(cmdInput)
                        cmdInput = ""
                    }
                },
                modifier = Modifier.height(52.dp)
            ) {
                Text("Send")
            }
        }

        OutlinedButton(onClick = onClearCaptures, modifier = Modifier.fillMaxWidth().height(46.dp)) {
            Text("Clear Captured Session Buffers", color = StumpColors.Rose)
        }

        Box(modifier = Modifier.fillMaxWidth().background(Color.Black, RoundedCornerShape(10.dp)).border(1.dp, StumpColors.Border, RoundedCornerShape(10.dp)).padding(10.dp)) {
            Column {
                state.serialLogs.takeLast(14).forEach { l ->
                    Text(l, color = StumpColors.TextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
