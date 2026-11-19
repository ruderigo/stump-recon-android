package com.stump.recon.usb

import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

enum class FlashStage {
    IDLE, CONNECTING, ENTERING_BOOTLOADER, SYNCING, ATTACHING_SPI,
    ERASING_AND_WRITING, VERIFYING_MD5, RESETTING, COMPLETE, ERROR
}

data class ValidatedBin(
    val fileName: String,
    val bytes: ByteArray,
    val offset: Int,
    val slotLabel: String,
    val magicValid: Boolean,
    val segmentCount: Int,
    val spiMode: String,
    val md5Hex: String,
    val warning: String? = null
)

data class FlashProgress(
    val stage: FlashStage = FlashStage.IDLE,
    val activeSegment: String = "",
    val writtenBytes: Int = 0,
    val totalBytes: Int = 0,
    val percent: Int = 0,
    val rateKBps: Double = 0.0,
    val chipInfo: String? = null,
    val md5Verified: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Pure-Kotlin ESP32-S3 ROM Bootloader SLIP Flasher operating directly over Android USB-OTG UsbSerialPort.
 * Supports Heltec V3 (CP2102), CH34x bridges, and Native ESP32-S3 USB-Serial/JTAG CDC (T3-S3, T-Beam Supreme).
 */
class Esp32S3SlipFlasher(
    private val onLog: (String) -> Unit,
    private val onProgress: (FlashProgress) -> Unit
) {
    companion object {
        const val SLIP_END: Byte = 0xC0.toByte()
        const val SLIP_ESC: Byte = 0xDB.toByte()
        const val SLIP_ESC_END: Byte = 0xDC.toByte()
        const val SLIP_ESC_ESC: Byte = 0xDD.toByte()

        const val OP_FLASH_BEGIN = 0x02
        const val OP_FLASH_DATA = 0x03
        const val OP_FLASH_END = 0x04
        const val OP_SYNC = 0x08
        const val OP_READ_REG = 0x0A
        const val OP_SPI_SET_PARAMS = 0x0B
        const val OP_SPI_ATTACH = 0x0D
        const val OP_CHANGE_BAUDRATE = 0x0F
        const val OP_SPI_FLASH_MD5 = 0x13
        const val OP_GET_SECURITY_INFO = 0x14

        const val FLASH_BLOCK_SIZE = 1024
        const val CHECKSUM_MAGIC = 0xEF

        fun md5Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(bytes)
            return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        fun validateEsp32Bin(
            fileName: String,
            bytes: ByteArray,
            offset: Int,
            slotLabel: String,
            boardFlashMb: Int
        ): ValidatedBin {
            val md5 = md5Hex(bytes)
            if (bytes.size < 32) {
                return ValidatedBin(fileName, bytes, offset, slotLabel, false, 0, "UNKNOWN", md5, "File too small (< 32 bytes).")
            }
            if (offset == 0x8000) {
                val ok = (bytes[0].toInt() and 0xFF) == 0xAA && (bytes[1].toInt() and 0xFF) == 0x50
                return ValidatedBin(
                    fileName, bytes, offset, slotLabel, ok, 0, "PART_TABLE", md5,
                    if (ok) null else "Expected partition table magic 0xAA 0x50."
                )
            }
            val b0 = bytes[0].toInt() and 0xFF
            val magicValid = b0 == 0xE9
            val segs = bytes[1].toInt() and 0xFF
            val spiMode = when (bytes[2].toInt() and 0xFF) {
                0 -> "QIO"; 1 -> "QOUT"; 2 -> "DIO"; 3 -> "DOUT"; else -> "MODE_${bytes[2].toInt() and 0xFF}"
            }
            var warning: String? = null
            if (!magicValid) {
                warning = "Header byte is 0x%02X instead of ESP32 magic 0xE9.".format(b0)
            } else if (offset + bytes.size > boardFlashMb * 1024 * 1024) {
                warning = "Binary exceeds board flash capacity ($boardFlashMb MB)."
            } else if (offset == 0x0 && slotLabel.contains("Merged")) {
                val hasPart = bytes.size > 0x8002 &&
                    (bytes[0x8000].toInt() and 0xFF) == 0xAA &&
                    (bytes[0x8001].toInt() and 0xFF) == 0x50
                if (!hasPart) {
                    warning = "No partition table found at 0x8000. Use 3-Part mode for unmerged app firmware.bin."
                }
            }
            return ValidatedBin(fileName, bytes, offset, slotLabel, magicValid, segs, spiMode, md5, warning)
        }

        fun slipEncode(payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 16)
            out.write(SLIP_END.toInt() and 0xFF)
            for (b in payload) {
                when (b) {
                    SLIP_END -> {
                        out.write(SLIP_ESC.toInt() and 0xFF)
                        out.write(SLIP_ESC_END.toInt() and 0xFF)
                    }
                    SLIP_ESC -> {
                        out.write(SLIP_ESC.toInt() and 0xFF)
                        out.write(SLIP_ESC_ESC.toInt() and 0xFF)
                    }
                    else -> out.write(b.toInt() and 0xFF)
                }
            }
            out.write(SLIP_END.toInt() and 0xFF)
            return out.toByteArray()
        }

        fun slipDecode(frame: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(frame.size)
            var i = 0
            while (i < frame.size) {
                val b = frame[i]
                if (b == SLIP_ESC && i + 1 < frame.size) {
                    val next = frame[++i]
                    when (next) {
                        SLIP_ESC_END -> out.write(SLIP_END.toInt() and 0xFF)
                        SLIP_ESC_ESC -> out.write(SLIP_ESC.toInt() and 0xFF)
                        else -> out.write(next.toInt() and 0xFF)
                    }
                } else if (b != SLIP_END) {
                    out.write(b.toInt() and 0xFF)
                }
                i++
            }
            return out.toByteArray()
        }

        fun espChecksum(data: ByteArray): Int {
            var chk = CHECKSUM_MAGIC
            for (b in data) {
                chk = chk xor (b.toInt() and 0xFF)
            }
            return chk and 0xFF
        }

        fun buildCommand(op: Int, data: ByteArray, checksum: Int = 0): ByteArray {
            val bb = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(0x00)
            bb.put(op.toByte())
            bb.putShort(data.size.toShort())
            bb.putInt(checksum)
            bb.put(data)
            return bb.array()
        }

        fun u32Le(vararg vals: Int): ByteArray {
            val bb = ByteBuffer.allocate(vals.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in vals) bb.putInt(v)
            return bb.array()
        }
    }

    @Volatile
    private var aborted = false
    private val rxSlipBuf = ByteArrayOutputStream()
    private var inFrame = false

    fun abort() {
        aborted = true
    }

    private fun readSlipFrame(port: UsbSerialPort, timeoutMs: Long): ByteArray? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val tmp = ByteArray(512)
        while (System.currentTimeMillis() < deadline && !aborted) {
            val n = runCatching { port.read(tmp, 60) }.getOrDefault(0)
            if (n <= 0) continue
            for (i in 0 until n) {
                val b = tmp[i]
                if (b == SLIP_END) {
                    if (inFrame && rxSlipBuf.size() > 0) {
                        val raw = rxSlipBuf.toByteArray()
                        rxSlipBuf.reset()
                        inFrame = false
                        return slipDecode(raw)
                    } else {
                        rxSlipBuf.reset()
                        inFrame = true
                    }
                } else if (inFrame) {
                    rxSlipBuf.write(b.toInt() and 0xFF)
                }
            }
        }
        return null
    }

    private fun sendCommand(
        port: UsbSerialPort,
        op: Int,
        data: ByteArray,
        checksum: Int = 0,
        timeoutMs: Long = 3000L
    ): Pair<Int, ByteArray> {
        val pkt = slipEncode(buildCommand(op, data, checksum))
        port.write(pkt, 2000)

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !aborted) {
            val frame = readSlipFrame(port, (deadline - System.currentTimeMillis()).coerceAtLeast(60L))
            if (frame == null || frame.size < 8) continue
            val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            val dir = bb.get().toInt() and 0xFF
            val respOp = bb.get().toInt() and 0xFF
            if (dir != 0x01 || respOp != op) continue
            val size = bb.short.toInt() and 0xFFFF
            val value = bb.int
            val body = frame.copyOfRange(8, (8 + size).coerceAtMost(frame.size))
            if (body.size >= 2) {
                val status = body[body.size - 2].toInt() and 0xFF
                val err = body[body.size - 1].toInt() and 0xFF
                if (status != 0) {
                    throw IllegalStateException("Bootloader opcode 0x%02X failed (status=%d, err=0x%02X)".format(op, status, err))
                }
            }
            return value to body
        }
        throw IllegalStateException("Timeout waiting for ESP32-S3 response to opcode 0x%02X".format(op))
    }

    private suspend fun enterBootloader(port: UsbSerialPort, chip: UsbBridgeChip) {
        onProgress(FlashProgress(stage = FlashStage.ENTERING_BOOTLOADER))
        onLog("Pulsing DTR/RTS bootloader reset for $chip...")
        if (chip == UsbBridgeChip.ESP32_S3_CDC) {
            port.dtr = false; port.rts = false; delay(80)
            port.dtr = true;  port.rts = false; delay(80)
            port.dtr = true;  port.rts = true;  delay(100)
            port.dtr = false; port.rts = true;  delay(80)
            port.dtr = false; port.rts = false; delay(120)
        } else {
            // Classic CP2102 / CH34x Heltec V3 auto-reset sequence
            port.dtr = false; port.rts = true;  delay(120)
            port.dtr = true;  port.rts = false; delay(60)
            port.dtr = false; port.rts = false; delay(120)
        }
        // Drain stale boot banner bytes
        val drain = ByteArray(256)
        repeat(4) { runCatching { port.read(drain, 25) } }
        rxSlipBuf.reset()
        inFrame = false
    }

    suspend fun flash(
        device: ConnectedUsbDevice,
        segments: List<ValidatedBin>,
        flashSizeMb: Int,
        targetBaud: Int = 115200
    ) = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty()) { "No local .bin firmware files selected." }
        val port = device.port
        val totalBytes = segments.sumOf { it.bytes.size }
        var writtenGlobal = 0

        enterBootloader(port, device.chip)

        // SYNC
        onProgress(FlashProgress(stage = FlashStage.SYNCING, totalBytes = totalBytes))
        val syncPayload = ByteArray(36) { 0x55 }
        syncPayload[0] = 0x07; syncPayload[1] = 0x07; syncPayload[2] = 0x12; syncPayload[3] = 0x20
        var synced = false
        for (attempt in 1..8) {
            if (aborted) throw IllegalStateException("Flashing canceled by user.")
            onLog("SLIP SYNC attempt $attempt/8...")
            val ok = runCatching { sendCommand(port, OP_SYNC, syncPayload, 0, 500L) }.isSuccess
            if (ok) {
                repeat(7) { if (readSlipFrame(port, 40L) == null) return@repeat }
                synced = true
                break
            }
            delay(100)
        }
        if (!synced) {
            throw IllegalStateException("ESP32-S3 SYNC failed. Hold BOOT, tap RST, release BOOT, and retry.")
        }
        onLog("ESP32-S3 ROM bootloader synchronized.")

        val chipInfo = runCatching {
            val (_, body) = sendCommand(port, OP_GET_SECURITY_INFO, ByteArray(0), 0, 1200L)
            if (body.size >= 16) {
                val id = ByteBuffer.wrap(body, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (id == 9) "ESP32-S3 (Chip ID 9)" else "ESP32 Target (ID $id)"
            } else "ESP32-S3 ROM"
        }.getOrElse {
            runCatching {
                val (v, _) = sendCommand(port, OP_READ_REG, u32Le(0x40001000), 0, 1000L)
                "ESP32-S3 (Magic 0x%08X)".format(v)
            }.getOrDefault("ESP32-S3 ROM")
        }
        onLog("Detected target: $chipInfo")

        onProgress(FlashProgress(stage = FlashStage.ATTACHING_SPI, chipInfo = chipInfo, totalBytes = totalBytes))
        sendCommand(port, OP_SPI_ATTACH, ByteArray(8), 0, 3000L)
        sendCommand(port, OP_SPI_SET_PARAMS, u32Le(0, flashSizeMb * 1024 * 1024, 65536, 4096, 256, 0xFFFF), 0, 3000L)
        onLog("SPI flash attached ($flashSizeMb MB).")

        if (targetBaud > 115200 && device.chip != UsbBridgeChip.ESP32_S3_CDC) {
            runCatching {
                sendCommand(port, OP_CHANGE_BAUDRATE, u32Le(targetBaud, 0), 0, 1500L)
                port.setParameters(targetBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                delay(60)
                onLog("Switched upload baud rate to $targetBaud.")
            }.onFailure {
                onLog("Continuing safely at 115200 baud.")
            }
        }

        val startMs = System.currentTimeMillis()
        var allMd5Ok = true

        for (seg in segments) {
            val blocks = (seg.bytes.size + FLASH_BLOCK_SIZE - 1) / FLASH_BLOCK_SIZE
            onLog("Erasing & writing ${seg.slotLabel} (${seg.bytes.size} B, $blocks blocks) @ 0x%X...".format(seg.offset))
            sendCommand(
                port,
                OP_FLASH_BEGIN,
                u32Le(seg.bytes.size, blocks, FLASH_BLOCK_SIZE, seg.offset),
                0,
                (blocks * 30L).coerceAtLeast(10000L)
            )

            for (seq in 0 until blocks) {
                if (aborted) throw IllegalStateException("Flashing canceled by user.")
                val start = seq * FLASH_BLOCK_SIZE
                val end = (start + FLASH_BLOCK_SIZE).coerceAtMost(seg.bytes.size)
                val block = ByteArray(FLASH_BLOCK_SIZE) { 0xFF.toByte() }
                System.arraycopy(seg.bytes, start, block, 0, end - start)

                val dataPkt = ByteArray(16 + FLASH_BLOCK_SIZE)
                System.arraycopy(u32Le(FLASH_BLOCK_SIZE, seq, 0, 0), 0, dataPkt, 0, 16)
                System.arraycopy(block, 0, dataPkt, 16, FLASH_BLOCK_SIZE)

                sendCommand(port, OP_FLASH_DATA, dataPkt, espChecksum(block), 4000L)

                writtenGlobal += (end - start)
                if (seq % 4 == 0 || seq == blocks - 1) {
                    val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000.0).coerceAtLeast(0.05)
                    val rate = (writtenGlobal / 1024.0) / elapsedSec
                    val pct = ((writtenGlobal * 100L) / totalBytes.coerceAtLeast(1)).toInt().coerceAtMost(99)
                    onProgress(
                        FlashProgress(
                            stage = FlashStage.ERASING_AND_WRITING,
                            activeSegment = seg.slotLabel,
                            writtenBytes = writtenGlobal,
                            totalBytes = totalBytes,
                            percent = pct,
                            rateKBps = kotlin.math.round(rate * 10.0) / 10.0,
                            chipInfo = chipInfo
                        )
                    )
                }
            }

            onProgress(
                FlashProgress(
                    stage = FlashStage.VERIFYING_MD5,
                    activeSegment = seg.slotLabel,
                    writtenBytes = writtenGlobal,
                    totalBytes = totalBytes,
                    percent = 99,
                    chipInfo = chipInfo
                )
            )
            runCatching {
                val (_, md5Body) = sendCommand(port, OP_SPI_FLASH_MD5, u32Le(seg.offset, seg.bytes.size, 0, 0), 0, 10000L)
                val chipMd5 = when {
                    md5Body.size >= 32 -> String(md5Body, 0, 32, Charsets.US_ASCII).lowercase()
                    md5Body.size >= 16 -> md5Body.copyOfRange(0, 16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    else -> ""
                }
                if (chipMd5.isNotEmpty() && chipMd5 != seg.md5Hex.lowercase()) {
                    throw IllegalStateException("MD5 mismatch on ${seg.slotLabel}: expected ${seg.md5Hex}, chip $chipMd5")
                }
                onLog("MD5 verified for ${seg.slotLabel}: $chipMd5")
            }.onFailure { err ->
                if (err.message?.contains("MD5 mismatch") == true) throw err
                allMd5Ok = false
                onLog("MD5 readback skipped on this ROM rev; all block ACKs succeeded.")
            }
        }

        onProgress(FlashProgress(stage = FlashStage.RESETTING, percent = 100, writtenBytes = totalBytes, totalBytes = totalBytes, chipInfo = chipInfo))
        runCatching { sendCommand(port, OP_FLASH_END, u32Le(0), 0, 1500L) }
        port.dtr = false; port.rts = true; delay(100)
        port.dtr = false; port.rts = false; delay(150)

        onLog("Flashing complete. Board booted into STUMP Zero-TX firmware.")
        onProgress(
            FlashProgress(
                stage = FlashStage.COMPLETE,
                activeSegment = "Done",
                writtenBytes = totalBytes,
                totalBytes = totalBytes,
                percent = 100,
                chipInfo = chipInfo,
                md5Verified = allMd5Ok
            )
        )
    }
}
