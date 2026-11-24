package de.dx8.monitor

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Schreibt jeden Ladevorgang je Kanal als CSV nach Download/DX8/.
 * Datei wird beim Programmstart angelegt, jede Sekunde ergaenzt und beim Ende (Halten/Bereit/Fehler) geschlossen.
 * Format fuer deutsches Excel: Semikolon, Dezimalkomma.
 */
class RunLogger(private val ctx: Context, private val onSaved: (String) -> Unit) {

    private class Session(val name: String, val out: OutputStream, var lastTs: Long = -1, var rows: Int = 0)

    private val io = Executors.newSingleThreadExecutor()
    private val open = arrayOfNulls<Session>(2)
    /** Vom Start-Knopf gesetzt: Programm + Betriebsart fuer den naechsten Lauf. */
    @Volatile var programInfo = arrayOfNulls<String>(2)

    private val de = Locale.GERMANY
    private val tFmt = SimpleDateFormat("HH:mm:ss", de)

    fun update(k: Int, c: ChannelData) = io.execute {
        try {
            var s = open[k]
            // neuer Lauf ohne Pause dazwischen (Laufzeit springt zurueck)
            if (s != null && c.isActive && s.lastTs >= 0 && c.ts < s.lastTs - 1500) { finish(k); s = null }
            if (s == null && c.isActive) s = begin(k, c)
            if (s == null) return@execute
            if (c.ts != s.lastTs || !c.isActive) writeRow(s, c)
            if (!c.isActive) finish(k)
        } catch (_: Exception) { }
    }

    fun closeAll() = io.execute { for (k in 0..1) try { finish(k) } catch (_: Exception) { } }

    private fun sanitize(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40)

    private fun begin(k: Int, c: ChannelData): Session {
        val now = Date()
        val prog = programInfo[k]
        programInfo[k] = null          // gilt nur fuer genau diesen Lauf
        val name = "DX8_K${k + 1}_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", de).format(now) +
                (prog?.let { "_" + sanitize(it) } ?: "") + ".csv"
        val out: OutputStream = if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DX8")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("Download-Ordner nicht beschreibbar")
            ctx.contentResolver.openOutputStream(uri, "w") ?: throw IllegalStateException("Datei nicht beschreibbar")
        } else {
            // vor Android 10: app-eigener Download-Ordner (kein Speicherrecht noetig)
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "DX8").apply { mkdirs() }
            File(dir, name).outputStream()
        }
        val hdr = StringBuilder()
        hdr.append("# iCharger DX8 - Kanal ${k + 1}\n")
        hdr.append("# Programm: ${prog ?: "am Lader gestartet (unbekannt)"}\n")
        hdr.append("# Aufzeichnung ab: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", de).format(now)}" +
                (if (c.ts > 2000) " (Programm lief bereits ${c.ts / 1000} s)" else "") + "\n")
        hdr.append("Uhrzeit;Laufzeit_s;Status;Spannung_V;Strom_A;Leistung_W;Kapazitaet_mAh;Temp_intern_C;Eingang_V;")
        hdr.append((1..8).joinToString(";") { "Z${it}_V" })
        hdr.append(";Fehler\n")
        out.write(hdr.toString().toByteArray(Charsets.UTF_8))
        val s = Session(name, out)
        open[k] = s
        return s
    }

    private fun writeRow(s: Session, c: ChannelData) {
        val cells = (0 until 8).joinToString(";") { i -> c.cells.getOrNull(i)?.let { String.format(de, "%.3f", it / 1000.0) } ?: "" }
        val err = if (c.run == 41 && c.err != 0) "${errCode(c.err)} ${ERR_TEXT[errCode(c.err)] ?: ""}".trim() else ""
        val row = String.format(de, "%s;%d;%s;%.3f;%.2f;%.2f;%d;%.1f;%.3f;%s;%s\n",
            tFmt.format(Date()), c.ts / 1000, c.runText, c.uoutV, c.currentA, c.powerW, c.capMah, c.tInt, c.uinV, cells, err)
        s.out.write(row.toByteArray(Charsets.UTF_8))
        s.out.flush()
        s.lastTs = c.ts
        s.rows++
    }

    private fun finish(k: Int) {
        val s = open[k] ?: return
        open[k] = null
        try { s.out.close() } catch (_: Exception) { }
        onSaved("CSV gespeichert: Download/DX8/${s.name} (${s.rows} Zeilen)")
    }
}
