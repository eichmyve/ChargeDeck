package de.dx8.desktop

import de.dx8.monitor.Dx8Device
import de.dx8.monitor.Dx8Exception
import de.dx8.monitor.HidLink
import org.hid4java.HidDevice
import org.hid4java.HidManager
import org.hid4java.HidServices
import org.hid4java.HidServicesSpecification
import org.hid4java.ScanMode

/**
 * HID-Verbindung unter Windows ueber hidapi (hid4java).
 * Der DX8 nutzt keine Report-IDs: gesendet wird mit Report-ID 0 (hidapi stellt sie voran),
 * empfangen werden die 64 Datenbytes ohne Report-ID – genau die Frames, die [Dx8Device] erwartet.
 */
class WinHidLink private constructor(private val dev: HidDevice) : HidLink {
    override fun send(frame: ByteArray) {
        val n = dev.write(frame, 64, 0x00.toByte())
        if (n < 0) throw Dx8Exception("USB-Senden fehlgeschlagen (${dev.lastErrorMessage ?: "unbekannt"})")
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): Int {
        val n = dev.read(buf, maxOf(1, timeoutMs))
        if (n < 0) throw Dx8Exception("USB-Empfang fehlgeschlagen – Lader abgesteckt?")
        return n
    }

    override fun close() {
        try { dev.close() } catch (_: Exception) { }
    }

    companion object {
        private val services: HidServices by lazy {
            val spec = HidServicesSpecification().apply {
                isAutoStart = false
                isAutoShutdown = true
                scanMode = ScanMode.NO_SCAN
                isAutoDataRead = false          // Antworten liest nur Dx8Device, kein Hintergrund-Leser
            }
            HidManager.getHidServices(spec)
        }

        /** Angesteckte DX8 (VID 0483, PID 5751). */
        fun findAll(): List<HidDevice> =
            services.attachedHidDevices.filter { it.vendorId == Dx8Device.VID && it.productId == Dx8Device.PID }

        /** Ersten DX8 oeffnen, der sich oeffnen laesst. */
        fun open(): Dx8Device {
            val found = findAll()
            if (found.isEmpty()) throw Dx8Exception("Kein iCharger DX8 am USB gefunden")
            for (d in found) {
                try {
                    if (d.isClosed && !d.open()) continue
                    return Dx8Device(WinHidLink(d))
                } catch (_: Exception) { }
            }
            throw Dx8Exception("DX8 gefunden, lässt sich aber nicht öffnen – ist ein anderes Programm (z. B. Junsi-Software) verbunden?")
        }
    }
}
