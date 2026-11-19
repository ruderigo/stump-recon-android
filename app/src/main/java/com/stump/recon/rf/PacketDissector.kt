package com.stump.recon.rf

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Full-parity passive RF packet dissector for Android:
 *  - Reticulum (RNS 1.6.x) with synchronous SHA-256 ANNOUNCE verification, 32-byte X25519 ratchets,
 *    HEADER_1/HEADER_2 relay IDs, full 8-bit hops, IFAC-MASKED isolation, complete context/aspect maps
 *  - RNode 1-byte LoRa air header stripping & 2-fragment FLAG_SPLIT reassembly (MTU up to 508B)
 *  - LXMF 1.3.x MessagePack delivery (including fractional float stamp costs & nil names)
 *    and 7-element propagation node announces + PN_META_* integer keys
 *  - MeshCore, Meshtastic, LoRaWAN, and 802.11 OUI/LAA resolution
 */
object PacketDissector {

    private val RNS_CONTEXT_MAP = mapOf(
        0x00 to "NONE",
        0x01 to "RESOURCE",
        0x02 to "RESOURCE_ADV",
        0x03 to "RESOURCE_REQ",
        0x04 to "RESOURCE_HMU",
        0x05 to "RESOURCE_PRF",
        0x06 to "RESOURCE_ICL",
        0x07 to "RESOURCE_RCL",
        0x08 to "CACHE_REQUEST",
        0x09 to "REQUEST",
        0x0A to "RESPONSE",
        0x0B to "PATH_RESPONSE",
        0x0C to "COMMAND",
        0x0D to "COMMAND_STATUS",
        0x0E to "CHANNEL",
        0xFA to "KEEPALIVE",
        0xFB to "LINKIDENTIFY",
        0xFC to "LINKCLOSE",
        0xFD to "LINKPROOF",
        0xFE to "LRRTT",
        0xFF to "LRPROOF"
    )

    private val RNS_ASPECT_MAP: Map<String, String> = mapOf(
        aspectHash("lxmf.delivery") to "Sideband / LXMF",
        aspectHash("lxmf.propagation") to "LXMF Propagation",
        aspectHash("nomadnetwork.node") to "NomadNet BBS",
        aspectHash("rnstransport.probe") to "RNS Transport Probe",
        aspectHash("rnstransport.path.request") to "RNS Path Request",
        aspectHash("rnstransport.tunnel.synthesize") to "RNS Tunnel Synth",
        aspectHash("rnstransport.remote.management") to "RNS Remote Mgmt",
        aspectHash("call.audio") to "LXST / Call Audio",
        aspectHash("lxst.telephony") to "LXST Telephony",
        aspectHash("rnode.telemetry") to "RNode Telemetry",
        aspectHash("sideband.telemetry") to "Sideband Telemetry",
        aspectHash("meshchat.delivery") to "MeshChat Delivery",
        aspectHash("rns_unit_tests.link.establish") to "RNS Link Test"
    )

    private val OUI_MAP = mapOf(
        "00:17:f2" to "Apple Inc.",
        "a4:83:e7" to "Apple Inc.",
        "3c:22:fb" to "Apple Inc.",
        "24:0a:c4" to "Espressif Systems",
        "30:ae:a4" to "Espressif Systems",
        "dc:a6:32" to "Raspberry Pi",
        "14:8f:30" to "Ralink Tech",
        "00:e0:4c" to "Realtek",
        "f8:1a:67" to "TP-Link"
    )

    private data class SplitEntry(val payload: ByteArray, val timestampMs: Long)
    private val splitCache = HashMap<String, SplitEntry>()

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun aspectHash(aspect: String): String = bytesToHex(sha256(aspect.toByteArray(Charsets.UTF_8)).copyOfRange(0, 10))

    fun computeRnsDestinationHash(nameHash10: ByteArray, publicKey64: ByteArray): String {
        val idHash = sha256(publicKey64).copyOfRange(0, 16)
        val combined = ByteArray(26)
        System.arraycopy(nameHash10, 0, combined, 0, 10)
        System.arraycopy(idHash, 0, combined, 10, 16)
        return bytesToHex(sha256(combined).copyOfRange(0, 16))
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(Regex("[^0-9a-fA-F]"), "")
        val len = clean.length / 2
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun resolveMacVendor(mac: String, laaFlag: Boolean? = null): Pair<String, Boolean> {
        val clean = mac.lowercase()
        val prefix = clean.take(8)
        OUI_MAP[prefix]?.let { return it to false }
        val first = clean.take(2).toIntOrNull(16)
        val isLaa = laaFlag ?: (first != null && (first and 0x02) != 0)
        return (if (isLaa) "Randomized (LAA)" else "Global IEEE OUI") to isLaa
    }

    private fun toUtf8OrNull(v: Any?): String? = when (v) {
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is ByteArray -> String(v, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun toDoubleOrNull(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        else -> null
    }

    private data class UnpackedAppData(
        val peerName: String? = null,
        val stampCost: Double? = null,
        val propagation: LXMFPropagationInfo? = null
    )

    @Suppress("UNCHECKED_CAST")
    private fun unpackRnsAppData(appBytes: ByteArray, aspectApp: String?): UnpackedAppData {
        if (appBytes.isEmpty()) return UnpackedAppData()
        val decoded = MsgPackDecoder.decode(appBytes)

        if (decoded is List<*>) {
            // LXMF 1.3 Propagation Node 7-element announce
            if (decoded.size >= 6 && decoded[1] is Number && decoded[2] is Boolean) {
                val timebase = (decoded[1] as Number).toLong()
                val active = decoded[2] as Boolean
                val transferKb = (decoded[3] as? Number)?.toInt()
                val syncKb = (decoded[4] as? Number)?.toInt()
                val costs = decoded[5] as? List<*>
                val stampCost = costs?.getOrNull(0)?.let(::toDoubleOrNull)
                val stampFlex = costs?.getOrNull(1)?.let(::toDoubleOrNull)
                val peeringCost = costs?.getOrNull(2)?.let(::toDoubleOrNull)

                var nodeName: String? = null
                var version: String? = null
                var stratum: Int? = null
                var throttle: Int? = null
                var authBand: Int? = null
                var utilPressure: Double? = null

                val meta = decoded.getOrNull(6) as? Map<Any, Any?>
                if (meta != null) {
                    for ((k, v) in meta) {
                        val ki = (k as? Number)?.toInt() ?: continue
                        when (ki) {
                            0x00 -> version = toUtf8OrNull(v)
                            0x01 -> nodeName = toUtf8OrNull(v)
                            0x02 -> stratum = (v as? Number)?.toInt()
                            0x03 -> throttle = (v as? Number)?.toInt()
                            0x04 -> authBand = (v as? Number)?.toInt()
                            0x05 -> utilPressure = toDoubleOrNull(v)
                        }
                    }
                }
                val prop = LXMFPropagationInfo(
                    active = active,
                    timebase = timebase,
                    transferLimitKb = transferKb,
                    syncLimitKb = syncKb,
                    stampCost = stampCost,
                    stampFlex = stampFlex,
                    peeringCost = peeringCost,
                    version = version,
                    syncStratum = stratum,
                    syncThrottle = throttle,
                    authBand = authBand,
                    utilPressure = utilPressure
                )
                return UnpackedAppData(peerName = nodeName, stampCost = stampCost, propagation = prop)
            }

            // LXMF Delivery [display_name_or_nil, stamp_cost]
            if (decoded.isNotEmpty()) {
                val peerName = toUtf8OrNull(decoded[0])
                val stampCost = decoded.getOrNull(1)?.let(::toDoubleOrNull)
                return UnpackedAppData(peerName = peerName, stampCost = stampCost)
            }
        }

        // Plain UTF-8 fallback (NomadNet BBS or custom RNS node)
        if (aspectApp != "Sideband / LXMF") {
            val txt = String(appBytes, Charsets.UTF_8).trim()
            if (txt.isNotEmpty() && txt.all { it.code in 32..126 }) {
                return UnpackedAppData(peerName = txt.take(40))
            }
        }
        return UnpackedAppData()
    }

    private fun parseRnsAtOffset(raw: ByteArray, offset: Int, rnodeFramed: Boolean, rnodeSeq: Int?, splitStatus: String?): DissectedRNS? {
        if (raw.size - offset < 19) return null
        val buf = if (offset == 0) raw else raw.copyOfRange(offset, raw.size)
        val flags = buf[0].toInt() and 0xFF
        val hops = buf[1].toInt() and 0xFF

        val ifacFlag = (flags and 0x80) != 0
        val headerTypeBit = (flags shr 6) and 0x01
        val contextFlag = (flags shr 5) and 0x01
        val propTypeBit = (flags shr 4) and 0x01
        val destTypeBits = (flags shr 2) and 0x03
        val pktTypeBits = flags and 0x03

        val headerType = if (headerTypeBit == 1) "HEADER_2" else "HEADER_1"
        val propagationType = if (propTypeBit == 1) "TRANSPORT" else "BROADCAST"
        val destType = when (destTypeBits) {
            0 -> "SINGLE"; 1 -> "GROUP"; 2 -> "PLAIN"; else -> "LINK"
        }
        val packetType = when (pktTypeBits) {
            0 -> "DATA"; 1 -> "ANNOUNCE"; 2 -> "LINK"; else -> "PROOF"
        }

        if (ifacFlag) {
            return DissectedRNS(
                packetType = packetType,
                headerType = headerType,
                destType = destType,
                propagationType = propagationType,
                hops = hops,
                destinationHash = "ifac_masked",
                context = "IFAC-MASKED",
                isEncrypted = true,
                ifac = true,
                rnodeFramed = rnodeFramed,
                rnodeSeq = rnodeSeq,
                rnodeSplitStatus = splitStatus
            )
        }

        var transportId: String? = null
        val destStart: Int
        if (headerTypeBit == 1) {
            if (buf.size < 35) return null
            transportId = bytesToHex(buf.copyOfRange(2, 18))
            destStart = 18
        } else {
            destStart = 2
        }

        val destHash = bytesToHex(buf.copyOfRange(destStart, destStart + 16))
        val contextByte = buf[destStart + 16].toInt() and 0xFF
        val context = RNS_CONTEXT_MAP[contextByte] ?: "CTX_0x%02X".format(contextByte)
        val dataStart = destStart + 17

        if (packetType != "ANNOUNCE") {
            return DissectedRNS(
                packetType = packetType,
                headerType = headerType,
                destType = destType,
                propagationType = propagationType,
                hops = hops,
                destinationHash = destHash,
                transportId = transportId,
                context = context,
                isEncrypted = true,
                rnodeFramed = rnodeFramed,
                rnodeSeq = rnodeSeq,
                rnodeSplitStatus = splitStatus
            )
        }

        // ANNOUNCE layout: pubKey(64) + nameHash(10) + randomHash(10) + [ratchetPub(32) if contextFlag==1] + sig(64) + [appData]
        val hasRatchet = contextFlag == 1
        val minAnnounceLen = dataStart + 64 + 10 + 10 + (if (hasRatchet) 32 else 0) + 64
        if (buf.size < dataStart + 84) {
            return DissectedRNS(
                packetType = "ANNOUNCE",
                headerType = headerType,
                destType = destType,
                propagationType = propagationType,
                hops = hops,
                destinationHash = destHash,
                transportId = transportId,
                context = context,
                isEncrypted = false,
                hasRatchet = hasRatchet,
                rnodeFramed = rnodeFramed,
                rnodeSeq = rnodeSeq,
                rnodeSplitStatus = splitStatus
            )
        }

        val pubKey = buf.copyOfRange(dataStart, dataStart + 64)
        val nameHash = buf.copyOfRange(dataStart + 64, dataStart + 74)
        val randomHash = buf.copyOfRange(dataStart + 74, dataStart + 84)
        val nameHashHex = bytesToHex(nameHash)
        val idHashHex = bytesToHex(sha256(pubKey).copyOfRange(0, 16))
        val expectedDest = computeRnsDestinationHash(nameHash, pubKey)
        val cryptoVerified = expectedDest.equals(destHash, ignoreCase = true)

        // 40-bit big-endian Unix timestamp in randomHash[5..9]
        var ts = 0L
        for (i in 5..9) {
            ts = (ts shl 8) or (randomHash[i].toLong() and 0xFF)
        }
        val announceTs = if (ts in 1_600_000_000L..2_100_000_000L) ts else null

        var ratchetId: String? = null
        if (hasRatchet && buf.size >= dataStart + 116) {
            val ratchetPub = buf.copyOfRange(dataStart + 84, dataStart + 116)
            ratchetId = bytesToHex(sha256(ratchetPub).copyOfRange(0, 10))
        }

        val aspectApp = RNS_ASPECT_MAP[nameHashHex]
        val appDataBytes = if (buf.size > minAnnounceLen) buf.copyOfRange(minAnnounceLen, buf.size) else ByteArray(0)
        val unpacked = unpackRnsAppData(appDataBytes, aspectApp)
        val resolvedApp = when {
            unpacked.propagation != null -> "LXMF Propagation"
            aspectApp != null -> aspectApp
            unpacked.stampCost != null -> "Sideband / LXMF"
            else -> "RNS Node"
        }

        return DissectedRNS(
            packetType = "ANNOUNCE",
            headerType = headerType,
            destType = destType,
            propagationType = propagationType,
            hops = hops,
            destinationHash = destHash,
            transportId = transportId,
            identityHash = idHashHex,
            context = context,
            isEncrypted = false,
            hasRatchet = hasRatchet,
            ratchetId = ratchetId,
            cryptoVerified = cryptoVerified,
            announceTimestamp = announceTs,
            rnodeFramed = rnodeFramed,
            rnodeSeq = rnodeSeq,
            rnodeSplitStatus = splitStatus,
            clientApp = resolvedApp,
            peerName = unpacked.peerName,
            nameHash = nameHashHex,
            stampCost = unpacked.stampCost,
            propagation = unpacked.propagation
        )
    }

    private fun scoreRns(r: DissectedRNS?): Int {
        if (r == null) return -100
        var s = 0
        if (r.cryptoVerified) s += 100
        if (r.rnodeSplitStatus == "REASSEMBLED") s += 40
        if (r.clientApp != null && r.clientApp != "RNS Node") s += 20
        if (r.propagation != null || r.peerName != null) s += 15
        if (r.context in RNS_CONTEXT_MAP.values) s += 8
        if (r.ifac) s += 5
        return s
    }

    fun dissectRnsWithRNode(raw: ByteArray, freq: Double, sf: Int, nowMs: Long = System.currentTimeMillis()): DissectedRNS? {
        if (raw.size < 19) return null
        val hdr0 = raw[0].toInt() and 0xFF
        val rnodeReservedOk = (hdr0 and 0x0E) == 0
        val rnodeSplit = (hdr0 and 0x01) == 1
        val rnodeSeq = (hdr0 shr 4) and 0x0F

        val rawDirect = parseRnsAtOffset(raw, 0, false, null, null)

        // Handle 2-fragment RNode FLAG_SPLIT reassembly (up to 508B MTU)
        if (rnodeReservedOk && rnodeSplit && (rawDirect == null || !rawDirect.cryptoVerified)) {
            val key = "${(freq * 10).roundToInt()}_${sf}_$rnodeSeq"
            val prev = splitCache[key]
            val body = raw.copyOfRange(1, raw.size)

            if (prev != null && (nowMs - prev.timestampMs) <= 8_000L && prev.payload.size + body.size <= 508) {
                splitCache.remove(key)
                val merged = ByteArray(prev.payload.size + body.size)
                System.arraycopy(prev.payload, 0, merged, 0, prev.payload.size)
                System.arraycopy(body, 0, merged, prev.payload.size, body.size)
                val reassembled = parseRnsAtOffset(merged, 0, true, rnodeSeq, "REASSEMBLED")
                if (reassembled != null && (reassembled.cryptoVerified || scoreRns(reassembled) > scoreRns(rawDirect))) {
                    return reassembled
                }
            } else if (raw.size >= 250 || (parseRnsAtOffset(raw, 1, true, rnodeSeq, "FRAG_1")?.packetType == "ANNOUNCE")) {
                splitCache[key] = SplitEntry(body, nowMs)
                return parseRnsAtOffset(raw, 1, true, rnodeSeq, "FRAG_1")
            }
        }

        val rnodeSingle = if (rnodeReservedOk && !rnodeSplit) {
            parseRnsAtOffset(raw, 1, true, rnodeSeq, "SINGLE")
        } else null

        val s0 = scoreRns(rawDirect)
        val s1 = scoreRns(rnodeSingle)
        return if (s1 > s0) rnodeSingle else rawDirect
    }

    fun dissectMeshCore(raw: ByteArray): DissectedMeshCore? {
        if (raw.size < 4) return null
        val header = raw[0].toInt() and 0xFF
        val routeBits = header and 0x03
        val typeBits = (header shr 2) and 0x0F
        val verBits = (header shr 6) and 0x03
        if (verBits != 0) return null

        val routeType = when (routeBits) {
            0 -> "TRANS_FLOOD"; 1 -> "FLOOD"; 2 -> "DIRECT"; else -> "TRANS_DIRECT"
        }
        val payloadType = when (typeBits) {
            0 -> "REQ"; 1 -> "RESPONSE"; 2 -> "TXT_MSG"; 3 -> "ACK"; 4 -> "ADVERT"
            5 -> "GRP_TXT"; 6 -> "GRP_DATA"; 7 -> "ANON_REQ"; 8 -> "PATH"; 9 -> "TRACE"
            else -> "RAW"
        }

        var idx = 1
        if (routeBits == 0 || routeBits == 3) {
            if (raw.size < idx + 4) return null
            idx += 4
        }
        if (raw.size <= idx) return null
        val pathLen = raw[idx++].toInt() and 0xFF
        if (pathLen > 64 || raw.size < idx + pathLen) return null
        val pathHex = bytesToHex(raw.copyOfRange(idx, idx + pathLen))
        idx += pathLen

        if (payloadType == "ADVERT" && raw.size >= idx + 101) {
            val pubPrefix = bytesToHex(raw.copyOfRange(idx, idx + 8))
            val appOffset = idx + 100
            val flags = raw[appOffset].toInt() and 0xFF
            val role = when (flags and 0x0F) {
                1 -> "Chat Node"; 2 -> "Repeater"; 3 -> "Room Server"; 4 -> "Sensor"; else -> "MeshCore Node"
            }
            var pos = appOffset + 1
            var gps: String? = null
            if ((flags and 0x10) != 0 && raw.size >= pos + 8) {
                val bb = ByteBuffer.wrap(raw, pos, 8).order(ByteOrder.LITTLE_ENDIAN)
                val lat = bb.int / 1e6
                val lon = bb.int / 1e6
                if (lat in -90.0..90.0 && lon in -180.0..180.0) {
                    gps = "%.4f, %.4f".format(lat, lon)
                }
                pos += 8
            }
            var nodeName: String? = null
            if ((flags and 0x80) != 0 && pos < raw.size) {
                val txt = String(raw.copyOfRange(pos, raw.size), Charsets.UTF_8).trim()
                if (txt.isNotEmpty()) nodeName = txt.take(32)
            }
            return DissectedMeshCore(routeType, payloadType, pathLen, pathHex, pubPrefix, role, nodeName, gps)
        }
        return null
    }

    fun dissectMeshtastic(raw: ByteArray): DissectedMeshtastic? {
        if (raw.size < 16) return null
        val flags = raw[12].toInt() and 0xFF
        val hopLimit = flags and 0x07
        val wantAck = (flags and 0x08) != 0
        val chHash = "%02x".format(raw[13].toInt() and 0xFF)
        return DissectedMeshtastic(hopLimit, wantAck, chHash, "ENCRYPTED_PROTOBUF", true)
    }

    data class Classification(
        val protocol: String,
        val rns: DissectedRNS? = null,
        val meshcore: DissectedMeshCore? = null,
        val meshtastic: DissectedMeshtastic? = null
    )

    fun classifyAndDissect(sync: Int, payloadHex: String, freq: Double, sf: Int, crcOk: Boolean, nowMs: Long = System.currentTimeMillis()): Classification {
        if (!crcOk || payloadHex.length < 6) return Classification("Noise / Corrupt")
        val raw = hexToBytes(payloadHex)
        if (sync == 0x2B) {
            return Classification("Meshtastic", meshtastic = dissectMeshtastic(raw))
        }
        if (sync == 0x34) {
            return Classification("LoRaWAN")
        }
        val rns = dissectRnsWithRNode(raw, freq, sf, nowMs)
        if (rns != null && (rns.cryptoVerified || rns.rnodeSplitStatus == "REASSEMBLED")) {
            return Classification("Reticulum (RNS)", rns = rns)
        }
        val mc = dissectMeshCore(raw)
        if (mc != null && (rns == null || !rns.cryptoVerified)) {
            return Classification("MeshCore", meshcore = mc)
        }
        if (rns != null) {
            return Classification("Reticulum (RNS)", rns = rns)
        }
        return Classification("Custom / Proprietary")
    }
}
