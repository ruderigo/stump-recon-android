package com.stump.recon.rf

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant

/**
 * Generates Wireshark .pcap (DLT_USER0 = 147), Structured .json, and Charles .chlsj exports
 * directly into Android Storage Access Framework OutputStreams.
 */
object ExportFormats {

    fun buildPcapBytes(packets: List<RFPacket>): ByteArray {
        val out = ByteArrayOutputStream()
        // Global PCAP Header (24 bytes, little-endian, DLT_USER0 = 147)
        val gh = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        gh.putInt(0xA1B2C3D4.toInt())
        gh.putShort(2)
        gh.putShort(4)
        gh.putInt(0)
        gh.putInt(0)
        gh.putInt(65535)
        gh.putInt(147)
        out.write(gh.array())

        for (pkt in packets.asReversed()) {
            val payload = PacketDissector.hexToBytes(pkt.payloadHex)
            // 16-byte STUMP RF metadata header prepended before LoRa payload
            val meta = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            meta.putInt((pkt.freq * 1000.0).toInt())
            meta.putShort(pkt.sf.toShort())
            meta.putShort(pkt.bw.toInt().toShort())
            meta.putShort((pkt.rssi * 10).toInt().toShort())
            meta.putShort((pkt.snr * 10).toInt().toShort())
            meta.putInt(pkt.ferr.toInt())

            val frameLen = 16 + payload.size
            val sec = (pkt.timestampMs / 1000L).toInt()
            val usec = ((pkt.timestampMs % 1000L) * 1000L).toInt()
            val ph = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            ph.putInt(sec)
            ph.putInt(usec)
            ph.putInt(frameLen)
            ph.putInt(frameLen)

            out.write(ph.array())
            out.write(meta.array())
            out.write(payload)
        }
        return out.toByteArray()
    }

    fun buildStructuredJson(devices: List<DiscoveredDevice>, packets: List<RFPacket>, wifiFrames: List<WifiFrame>): String {
        val root = JSONObject()
        root.put("schemaVersion", "1.1.0-android")
        root.put("exportedAtIso", Instant.now().toString())
        root.put("platform", "Android USB-OTG (STUMP Zero-TX)")

        val devArr = JSONArray()
        for (d in devices) {
            val obj = JSONObject()
            obj.put("uid", d.uid)
            obj.put("name", d.name)
            obj.put("type", d.dtype)
            obj.put("band", d.band)
            obj.put("rssiKalmanDbm", d.rssiKalman)
            obj.put("packetCount", d.pktCount)
            obj.put("ferrAvgHz", d.ferrAvg)
            obj.put("rnsDestHash", d.destHash)
            obj.put("rnsCryptoVerified", d.cryptoVerified)
            obj.put("rnsIfacMasked", d.ifac)
            obj.put("rnsHasRatchet", d.hasRatchet)
            obj.put("rnsRatchetIds", JSONArray(d.ratchetIdsSeen))
            obj.put("rnsRelayedVia", JSONArray(d.relayedVia))
            obj.put("lxmfStampCost", d.stampCost)
            obj.put("rnodeFramed", d.rnodeFramed)
            obj.put("rnodeSplitStatus", d.rnodeSplitStatus)
            obj.put("meshcoreRole", d.nodeRole)
            obj.put("meshcoreGps", d.gpsCoords)
            obj.put("mac", d.mac)
            obj.put("ouiVendor", d.ouiVendor)
            devArr.put(obj)
        }
        root.put("discoveredDevices", devArr)

        val pktArr = JSONArray()
        for (p in packets) {
            val obj = JSONObject()
            obj.put("timestampMs", p.timestampMs)
            obj.put("protocol", p.protocolGuess)
            obj.put("freqMHz", p.freq)
            obj.put("sf", p.sf)
            obj.put("bwKHz", p.bw)
            obj.put("rssiDbm", p.rssi)
            obj.put("snrDb", p.snr)
            obj.put("ferrHz", p.ferr)
            obj.put("crcOk", p.crcOk)
            obj.put("payloadHex", p.payloadHex)
            pktArr.put(obj)
        }
        root.put("subGhzLoraPackets", pktArr)

        val wfArr = JSONArray()
        for (w in wifiFrames) {
            val obj = JSONObject()
            obj.put("timestampMs", w.timestampMs)
            obj.put("ch", w.ch)
            obj.put("ftype", w.ftype)
            obj.put("mac", w.mac)
            obj.put("ssid", w.ssid)
            obj.put("ouiVendor", w.ouiVendor)
            obj.put("rssiDbm", w.rssi)
            wfArr.put(obj)
        }
        root.put("wifi80211Frames", wfArr)

        return root.toString(2)
    }

    fun buildCharlesJson(packets: List<RFPacket>): String {
        val entries = JSONArray()
        for (pkt in packets.asReversed()) {
            val iso = Instant.ofEpochMilli(pkt.timestampMs).toString()
            val entry = JSONObject()
            entry.put("status", "COMPLETE")
            entry.put("method", if (pkt.rns != null) "RNS_RX" else if (pkt.meshcore != null) "MESHCORE_RX" else "LORA_RX")
            entry.put("protocolVersion", "RF/1.0")
            entry.put("scheme", "http")
            entry.put("host", "${pkt.protocolGuess.lowercase().replace(Regex("[^a-z0-9]"), "-")}.subghz.stump")
            entry.put("path", "/${"%.1f".format(pkt.freq)}MHz/SF${pkt.sf}/${pkt.rns?.destinationHash ?: pkt.meshcore?.pubkeyPrefix ?: "raw"}")
            entry.put("query", "rssi=${pkt.rssi}&snr=${pkt.snr}&ferr=${pkt.ferr}")
            entry.put("times", JSONObject().put("start", iso).put("end", iso))
            entry.put("response", JSONObject()
                .put("status", if (pkt.crcOk) 200 else 400)
                .put("mimeType", "application/json")
                .put("body", JSONObject().put("text", JSONObject()
                    .put("protocol", pkt.protocolGuess)
                    .put("peerName", pkt.rns?.peerName ?: pkt.meshcore?.nodeName)
                    .put("cryptoVerified", pkt.rns?.cryptoVerified)
                    .put("payloadHex", pkt.payloadHex)
                    .toString())))
            entries.put(entry)
        }
        return entries.toString(2)
    }
}
