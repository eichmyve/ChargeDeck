package de.dx8.monitor

import java.net.HttpURLConnection
import java.net.URL

/**
 * Firmware-Pruefung wie im Junsi-Upgrader (iChargerUpgraderServer):
 * Die Update-Liste iChargerUpdateInfo.inf (INI-Format, Abschnitt [File List] mit TypeN/VerN/UpdatedN/URLN/ReleaseURLN)
 * wird von jun-si.com geladen (Ersatz: hillrc.com). Zusaetzlich werden die Release Notes des DX8 gelesen,
 * weil dort neuere Versionen stehen koennen als in der Update-Liste.
 * Nur lesen – Aufspielen bleibt beim Junsi-Upgrader.
 */
object FirmwareCheck {
    const val MODEL = "iCharger DX8"
    val INFO_URLS = listOf(
        "http://www.jun-si.com/UploadFiles/iChargerUpdateInfo.inf",
        "http://www.hillrc.com/UploadFiles/iChargerUpdateInfo.inf"
    )
    const val NOTES_URL = "http://www.jun-si.com/UploadFiles/ReleaseNotes-iChargerDX8.txt"

    /** Version Haupt.Neben.Patch; Anzeige wie am Lader-Display ("1.17", "1.21"). */
    data class Ver(val major: Int, val minor: Int, val patch: Int) : Comparable<Ver> {
        override fun compareTo(other: Ver) = compareValuesBy(this, other, Ver::major, Ver::minor, Ver::patch)
        override fun toString() = if (minor < 10 && patch < 10) "$major.$minor$patch" else "$major.$minor.$patch"

        companion object {
            /** Firmware-Register des Laders: 1107 -> 1.1.7 (Display "1.17"). */
            fun fromCode(code: Int) = Ver(code / 1000, code / 100 % 10, code % 100)

            /** "V1.17" (Update-Liste) -> 1.1.7; "V1.2.1" / "V1.0.8 beta12" (Release Notes) -> 1.2.1 / 1.0.8. */
            fun parse(s: String): Ver? {
                val m = Regex("""V?\s*(\d+)\.(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE).find(s.trim()) ?: return null
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2]
                val c = m.groupValues[3]
                return when {
                    c.isNotEmpty() -> Ver(a, b.toInt(), c.toInt())
                    b.length == 2 -> Ver(a, b[0].digitToInt(), b[1].digitToInt())
                    else -> Ver(a, b.toInt(), 0)
                }
            }
        }
    }

    data class Entry(val type: String, val ver: Ver, val updated: String, val url: String, val releaseUrl: String)
    data class Release(val ver: Ver, val date: String)

    /** Eintrag des Modells aus der Update-Liste, oder null. */
    fun parseInfo(text: String, model: String = MODEL): Entry? {
        val kv = text.lineSequence()
            .map { it.trim() }
            .filter { '=' in it && !it.startsWith("[") && !it.startsWith(";") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        val n = kv.entries.firstOrNull { (k, v) -> Regex("""Type\d+""").matches(k) && v.equals(model, ignoreCase = true) }
            ?.key?.removePrefix("Type") ?: return null
        val ver = Ver.parse(kv["Ver$n"] ?: return null) ?: return null
        return Entry(kv.getValue("Type$n"), ver, kv["Updated$n"].orEmpty(), kv["URL$n"].orEmpty(), kv["ReleaseURL$n"].orEmpty())
    }

    /** Neueste Version aus den Release Notes ("Version: V1.2.1" / "Date of Release: 2026/11/04"). */
    fun latestRelease(notes: String): Release? {
        val out = mutableListOf<Release>()
        var cur: Ver? = null
        for (line in notes.lineSequence()) {
            Regex("""Version:\s*(V[\d.]+(?:\s*beta\d+)?)""", RegexOption.IGNORE_CASE).find(line)?.let { cur = Ver.parse(it.groupValues[1]) }
            Regex("""Date of Release:\s*([\d/.\-]+)""", RegexOption.IGNORE_CASE).find(line)?.let { m ->
                cur?.let { out += Release(it, m.groupValues[1]) }
                cur = null
            }
        }
        return out.maxWithOrNull(compareBy<Release> { it.ver }.thenBy { it.date })
    }

    /** Ergebnistext fuer das Info-Fenster. installed = null, wenn der Lader nicht gelesen werden konnte. */
    fun report(installed: Ver?, entry: Entry?, release: Release?, source: String): String = buildString {
        fun line(k: String, v: String) = append("  %-24s %s\n".format(k, v))
        fun verdict(v: Ver) = when {
            installed == null -> ""
            v > installed -> "  → NEUER als am Lader"
            v == installed -> "  → aktuell"
            else -> "  → älter als am Lader"
        }
        append("FIRMWARE-PRÜFUNG ($source)\n")
        line("Am Lader", installed?.toString() ?: "unbekannt")
        if (entry != null) line("Junsi-Upgrader bietet", "${entry.ver} vom ${entry.updated}" + verdict(entry.ver))
        else line("Junsi-Upgrader bietet", "kein Eintrag für $MODEL")
        if (release != null) line("Release Notes neueste", "${release.ver} vom ${release.date}" + verdict(release.ver))
        val newer = installed != null && listOfNotNull(entry?.ver, release?.ver).any { it > installed }
        append(
            if (newer) "\n  Neuere Firmware vorhanden. Aufspielen mit dem Junsi-Upgrader am PC\n  (jun-si.com) – nicht über ChargeDeck.\n"
            else if (installed != null) "\n  Der Lader ist auf dem neuesten Stand.\n"
            else ""
        )
        if (entry != null && release != null && release.ver > entry.ver)
            append("  Hinweis: Version ${release.ver} steht erst in den Release Notes,\n  der Upgrader bietet sie noch nicht an.\n")
    }

    /** Laedt Update-Liste und Release Notes und liefert den Ergebnistext. Nicht im UI-Thread aufrufen. */
    fun check(installed: Ver?, fetch: (String) -> String = { httpGet(it) }): String {
        var entry: Entry? = null
        var source = ""
        var lastError: Exception? = null
        for (u in INFO_URLS) {
            try {
                entry = parseInfo(fetch(u))
                source = URL(u).host.removePrefix("www.")
                break
            } catch (e: Exception) { lastError = e }
        }
        if (source.isEmpty()) throw lastError ?: IllegalStateException("Server nicht erreichbar")
        val notesUrl = entry?.releaseUrl?.takeIf { it.startsWith("http") } ?: NOTES_URL
        val release = try { latestRelease(fetch(notesUrl)) } catch (e: Exception) { null }
        return report(installed, entry, release, source)
    }

    fun httpGet(url: String, timeoutMs: Int = 8000): String {
        val c = URL(url.replace(" ", "%20")).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "ChargeDeck")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }
}
