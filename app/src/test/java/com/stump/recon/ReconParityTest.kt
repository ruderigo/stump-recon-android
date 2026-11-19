package com.stump.recon

import com.stump.recon.rf.PacketDissector
import com.stump.recon.usb.Esp32S3SlipFlasher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReconParityTest {

    private fun buildRnsAnnounce(
        flags: Int,
        hops: Int,
        aspect: String,
        pubKey: ByteArray,
        ratchetPub: ByteArray?,
        appData: ByteArray,
        transportId: ByteArray? = null,
        rnodeHeader: Int? = null
    ): ByteArray {
        val nameHash = PacketDissector.sha256(aspect.toByteArray(Charsets.UTF_8)).copyOfRange(0, 10)
        val destHash = PacketDissector.hexToBytes(PacketDissector.computeRnsDestinationHash(nameHash, pubKey))
        val randomHash = byteArrayOf(1, 2, 3, 4, 5, 0x00, 0x67, 0x10, 0x20, 0x30)
        val sig = ByteArray(64) { 0x5A }

        val out = ByteArrayOutputStream()
        if (rnodeHeader != null) out.write(rnodeHeader and 0xFF)
        out.write(flags and 0xFF)
        out.write(hops and 0xFF)
        if (transportId != null) out.write(transportId)
        out.write(destHash)
        out.write(0x00) // context = NONE
        out.write(pubKey)
        out.write(nameHash)
        out.write(randomHash)
        if (ratchetPub != null) out.write(ratchetPub)
        out.write(sig)
        out.write(appData)
        return out.toByteArray()
    }

    @Test
    fun testSlipFramingAndChecksum() {
        val raw = byteArrayOf(0x00, 0x08, 0xC0.toByte(), 0xDB.toByte(), 0x55)
        val encoded = Esp32S3SlipFlasher.slipEncode(raw)
        assertEquals(Esp32S3SlipFlasher.SLIP_END, encoded.first())
        assertEquals(Esp32S3SlipFlasher.SLIP_END, encoded.last())
        val decoded = Esp32S3SlipFlasher.slipDecode(encoded.copyOfRange(1, encoded.size - 1))
        assertArrayEquals(raw, decoded)

        val chk = Esp32S3SlipFlasher.espChecksum(byteArrayOf(0x01, 0x02, 0x04))
        assertEquals(0xEF xor 0x01 xor 0x02 xor 0x04, chk)
    }

    @Test
    fun testEsp32BinMagicValidation() {
        val bad = ByteArray(64) { 0x00 }
        assertFalse(Esp32S3SlipFlasher.validateEsp32Bin("bad.bin", bad, 0x0, "Merged Factory (.bin)", 8).magicValid)

        val merged = ByteArray(0x9000) { 0xFF.toByte() }
        merged[0] = 0xE9.toByte()
        merged[1] = 0x04
        merged[2] = 0x02 // DIO
        merged[0x8000] = 0xAA.toByte()
        merged[0x8001] = 0x50.toByte()
        val v = Esp32S3SlipFlasher.validateEsp32Bin("stump_heltec_v3_merged.bin", merged, 0x0, "Merged Factory (.bin)", 8)
        assertTrue(v.magicValid)
        assertNull(v.warning)
        assertEquals("DIO", v.spiMode)
    }

    @Test
    fun testRns16RatchetFloatStampAndRNodeHeader() {
        val pub = ByteArray(64) { (it + 11).toByte() }
        val ratchet = ByteArray(32) { (it + 77).toByte() }
        // MsgPack [ "FieldNode-1", float32(8.5) ]
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(8.5f).array()
        val app = byteArrayOf(0x92.toByte(), 0xAB.toByte()) + "FieldNode-1".toByteArray() + byteArrayOf(0xCA.toByte()) + bb

        val pkt = buildRnsAnnounce(
            flags = 0x21, // HEADER_1, context_flag=1 (ratchet), ANNOUNCE
            hops = 37,
            aspect = "lxmf.delivery",
            pubKey = pub,
            ratchetPub = ratchet,
            appData = app,
            rnodeHeader = 0x70 // seq=7, single
        )
        val res = PacketDissector.classifyAndDissect(0x12, PacketDissector.bytesToHex(pkt), 915.0, 8, true)
        assertEquals("Reticulum (RNS)", res.protocol)
        val rns = res.rns!!
        assertTrue(rns.cryptoVerified)
        assertTrue(rns.rnodeFramed)
        assertEquals(7, rns.rnodeSeq)
        assertEquals(37, rns.hops)
        assertTrue(rns.hasRatchet)
        assertNotNull(rns.ratchetId)
        assertEquals("FieldNode-1", rns.peerName)
        assertEquals(8.5, rns.stampCost!!, 0.01)
    }

    @Test
    fun testRNodeSplitReassemblyAndIfacIsolation() {
        val pub = ByteArray(64) { (it + 33).toByte() }
        val longName = "SplitPacket-Ridge-Relay-Node-270B"
        val pad = ByteArray(90) { 0x41 }
        val app = byteArrayOf(0x92.toByte(), 0xD9.toByte(), longName.length.toByte()) + longName.toByteArray() + byteArrayOf(0x0E) + pad
        val full = buildRnsAnnounce(0x01, 2, "lxmf.delivery", pub, null, app)
        assertTrue(full.size > 255)

        val frag1 = byteArrayOf(0x91.toByte()) + full.copyOfRange(0, 254)
        val frag2 = byteArrayOf(0x91.toByte()) + full.copyOfRange(254, full.size)

        val r1 = PacketDissector.classifyAndDissect(0x12, PacketDissector.bytesToHex(frag1), 915.0, 8, true, 1000L)
        assertEquals("FRAG_1", r1.rns?.rnodeSplitStatus)
        val r2 = PacketDissector.classifyAndDissect(0x12, PacketDissector.bytesToHex(frag2), 915.0, 8, true, 1250L)
        assertEquals("REASSEMBLED", r2.rns?.rnodeSplitStatus)
        assertTrue(r2.rns!!.cryptoVerified)
        assertEquals(longName, r2.rns!!.peerName)

        // IFAC-masked packet isolation
        val ifacRaw = ByteArray(36) { 0xAB.toByte() }
        ifacRaw[0] = 0x80.toByte()
        ifacRaw[1] = 0x04
        val ifacRes = PacketDissector.classifyAndDissect(0x12, PacketDissector.bytesToHex(ifacRaw), 915.0, 8, true)
        assertTrue(ifacRes.rns!!.ifac)
        assertEquals("IFAC-MASKED", ifacRes.rns!!.context)
        assertEquals("ifac_masked", ifacRes.rns!!.destinationHash)
    }
}
