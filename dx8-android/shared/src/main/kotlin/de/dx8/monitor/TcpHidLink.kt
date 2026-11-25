package de.dx8.monitor

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Verbindung zum DX8 ueber die WLAN-Bruecke (Raspberry Pi, dx8bridge.py), Protokoll siehe dort:
 * Anmeldung "DX8BRIDGE 1 <passwort>\n" -> "OK 1\n", danach 64-Byte-Frames wie ueber USB.
 * Steuer-Frames [0xFF, ...] gehen nur an die Bruecke (z. B. Schutzgrenze).
 */
class TcpHidLink(
    val host: String,
    val port: Int,
    token: String,
    connectTimeoutMs: Int = 4000
) : HidLink {
    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream
    /** Teilweise empfangener Frame (TCP liefert in beliebigen Stuecken). */
    private val pending = ByteArray(64)
    private var have = 0

    init {
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = connectTimeoutMs
            input = socket.getInputStream()
            output = socket.getOutputStream()
            output.write("DX8BRIDGE 1 $token\n".toByteArray(Charsets.US_ASCII)); output.flush()
            val reply = readLine()
            if (!reply.startsWith("OK")) throw Dx8Exception(when {
                reply.startsWith("ERR belegt") -> "WLAN-Brücke: zu viele Geräte gleichzeitig verbunden – eines trennen"
                reply.startsWith("ERR") -> "WLAN-Brücke: Anmeldung abgelehnt (Passwort?)"
                else -> "WLAN-Brücke antwortet nicht passend"
            })
        } catch (e: Dx8Exception) {
            close(); throw e
        } catch (e: Exception) {
            close(); throw Dx8Exception("WLAN-Brücke $host:$port nicht erreichbar (${e.message})")
        }
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (sb.length < 200) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString().trim()
    }

    override fun send(frame: ByteArray) {
        try {
            output.write(frame, 0, 64); output.flush()
        } catch (e: Exception) {
            throw Dx8Exception("WLAN-Brücke: Senden fehlgeschlagen (${e.message})")
        }
    }

    override fun receive(buf: ByteArray, timeoutMs: Int): Int {
        try {
            socket.soTimeout = maxOf(1, timeoutMs)
            while (have < 64) {
                val n = input.read(pending, have, 64 - have)
                if (n < 0) throw Dx8Exception("WLAN-Brücke hat die Verbindung getrennt")
                have += n
            }
            pending.copyInto(buf, 0, 0, 64)
            have = 0
            return 64
        } catch (_: SocketTimeoutException) {
            return 0                      // Teilstueck bleibt fuer den naechsten Aufruf erhalten
        } catch (e: Dx8Exception) {
            throw e
        } catch (e: Exception) {
            throw Dx8Exception("WLAN-Brücke: Empfang fehlgeschlagen (${e.message})")
        }
    }

    override fun close() {
        try { socket.close() } catch (_: Exception) { }
    }

    companion object {
        const val DEFAULT_PORT = 5751
        /** Steuer-Frame "Schutz setzen": [0xFF, 'G', an, mV hi, mV lo] */
        fun guardFrame(enabled: Boolean, limitV: Double): ByteArray {
            val mv = Math.round(limitV * 1000).toInt().coerceIn(0, 65535)
            return ByteArray(64).also {
                it[0] = 0xFF.toByte(); it[1] = 'G'.code.toByte(); it[2] = if (enabled) 1 else 0
                it[3] = (mv shr 8).toByte(); it[4] = mv.toByte()
            }
        }
    }
}
