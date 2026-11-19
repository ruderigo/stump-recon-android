package com.stump.recon.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class UsbBridgeChip {
    CP210X, CH34X, ESP32_S3_CDC, FTDI, GENERIC
}

data class ConnectedUsbDevice(
    val driver: UsbSerialDriver,
    val port: UsbSerialPort,
    val connection: UsbDeviceConnection,
    val chip: UsbBridgeChip,
    val label: String
)

class StumpUsbManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "com.stump.recon.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun buildProber(): UsbSerialProber {
        val table = ProbeTable()
        // Silicon Labs CP2102 / CP2104 / CP2105 / CP2108 (Heltec WiFi LoRa 32 V3 / V3.2)
        table.addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
        table.addProduct(0x10C4, 0xEA70, Cp21xxSerialDriver::class.java)
        table.addProduct(0x10C4, 0xEA71, Cp21xxSerialDriver::class.java)
        // Espressif Native ESP32-S3 USB-Serial/JTAG CDC (LilyGO T3-S3, T-Beam Supreme)
        table.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
        table.addProduct(0x303A, 0x0002, CdcAcmSerialDriver::class.java)
        // WCH CH340 / CH341 / CH343 / CH9102
        table.addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
        table.addProduct(0x1A86, 0x55D3, CdcAcmSerialDriver::class.java)
        table.addProduct(0x1A86, 0x55D4, CdcAcmSerialDriver::class.java)
        // FTDI FT232R
        table.addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
        return UsbSerialProber(table)
    }

    fun listDrivers(): List<UsbSerialDriver> {
        val custom = buildProber().findAllDrivers(usbManager)
        if (custom.isNotEmpty()) return custom
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
    }

    fun detectChip(device: UsbDevice): UsbBridgeChip = when (device.vendorId) {
        0x10C4 -> UsbBridgeChip.CP210X
        0x1A86 -> UsbBridgeChip.CH34X
        0x303A -> UsbBridgeChip.ESP32_S3_CDC
        0x0403 -> UsbBridgeChip.FTDI
        else -> UsbBridgeChip.GENERIC
    }

    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        val deferred = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    deferred.complete(granted)
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pi)

        return try {
            deferred.await()
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    suspend fun openFirstDevice(baudRate: Int = 115200): ConnectedUsbDevice? = withContext(Dispatchers.IO) {
        val drivers = listDrivers()
        val driver = drivers.firstOrNull() ?: return@withContext null
        val device = driver.device
        if (!requestPermission(device)) return@withContext null

        val connection = usbManager.openDevice(device) ?: return@withContext null
        val port = driver.ports.firstOrNull() ?: run {
            connection.close()
            return@withContext null
        }

        port.open(connection)
        port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching {
            port.dtr = false
            port.rts = false
        }

        val chip = detectChip(device)
        val label = "${device.productName ?: chip.name} (0x%04X:0x%04X)".format(device.vendorId, device.productId)
        ConnectedUsbDevice(driver, port, connection, chip, label)
    }

    fun closeDevice(dev: ConnectedUsbDevice?) {
        if (dev == null) return
        runCatching { dev.port.close() }
        runCatching { dev.connection.close() }
    }
}
