package de.dx8.monitor

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** HID-Verbindung ueber Android-USB (Interrupt-Endpunkte, sonst SET_REPORT). */
class UsbHidLink(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint?
) : HidLink {
    override fun send(frame: ByteArray) {
        val n = if (epOut != null) conn.bulkTransfer(epOut, frame, 64, 1000)
        else conn.controlTransfer(0x21, 0x09, 0x0200, intf.id, frame, 64, 1000) // HID SET_REPORT
        if (n < 0) throw Dx8Exception("USB-Senden fehlgeschlagen")
    }

    override fun receive(buf: ByteArray, timeoutMs: Int) = conn.bulkTransfer(epIn, buf, 64, timeoutMs)

    override fun close() {
        try { conn.releaseInterface(intf) } catch (_: Exception) { }
        conn.close()
    }
}

/** DX8 unter den angesteckten USB-Geraeten suchen (Android). */
fun Dx8Device.Companion.find(mgr: UsbManager): UsbDevice? =
    mgr.deviceList.values.firstOrNull { it.vendorId == Dx8Device.VID && it.productId == Dx8Device.PID }

/** DX8 ueber Android-USB oeffnen (HID-Schnittstelle, Interrupt-Endpunkte). */
fun Dx8Device.Companion.open(mgr: UsbManager, dev: UsbDevice): Dx8Device {
    var hid: UsbInterface? = null
    for (i in 0 until dev.interfaceCount) {
        val f = dev.getInterface(i)
        if (f.interfaceClass == UsbConstants.USB_CLASS_HID) { hid = f; break }
    }
    hid ?: throw Dx8Exception("Keine HID-Schnittstelle gefunden")
    var inEp: UsbEndpoint? = null
    var outEp: UsbEndpoint? = null
    for (e in 0 until hid.endpointCount) {
        val ep = hid.getEndpoint(e)
        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
    }
    inEp ?: throw Dx8Exception("Kein Interrupt-IN-Endpunkt")
    val c = mgr.openDevice(dev) ?: throw Dx8Exception("Geraet konnte nicht geoeffnet werden")
    if (!c.claimInterface(hid, true)) { c.close(); throw Dx8Exception("Schnittstelle belegt") }
    return Dx8Device(UsbHidLink(c, hid, inEp, outEp))
}
