package de.dx8.monitor

/** Ereignis eines Kanals, das auch im Hintergrund gemeldet wird (Benachrichtigung mit Ton). */
data class ChannelEvent(val channel: Int, val kind: Kind, val title: String, val text: String) {
    enum class Kind { FINISHED, ERROR, DIALOG, GUARD_STOP, MISMATCH }
}

/**
 * Erkennt aus zwei aufeinanderfolgenden Abfragen die Ereignisse, die gemeldet werden sollen. Ohne Android, unit-testbar.
 * [userActionMs] = Zeitpunkt der letzten Bedienung dieses Kanals in der App (Stopp/Halten/Start):
 * ein dadurch ausgeloestes Ende wird nicht als "fertig" gemeldet.
 */
object ChannelEvents {
    const val USER_QUIET_MS = 8000L

    fun detect(k: Int, prev: ChannelData?, cur: ChannelData, nowMs: Long, userActionMs: Long = 0L): List<ChannelEvent> {
        prev ?: return emptyList()
        val out = ArrayList<ChannelEvent>()
        val name = "Kanal ${k + 1}"
        val byUser = userActionMs != 0L && nowMs - userActionMs < USER_QUIET_MS
        // Fehler neu aufgetreten
        if (cur.run == 41 && prev.run != 41) {
            val code = errCode(cur.err)
            out += ChannelEvent(k, ChannelEvent.Kind.ERROR, "$name: Fehler $code",
                ERR_TEXT[code] ?: "Bitte am Ladegerät prüfen")
        }
        // Lauf beendet: aktiv (ausser Warten/Rueckfrage/RI) -> Fertig (40) oder Bereit, ohne Bedienung in der App
        val wasRunning = prev.isActive && prev.run != ChannelLogic.RUN_WAITING && prev.run != 12 && prev.run != ChannelLogic.RUN_IR
        if (wasRunning && (cur.run == 40 || (cur.run == 0 && !byUser))) {
            out += ChannelEvent(k, ChannelEvent.Kind.FINISHED, "$name fertig",
                "%s beendet · %d mAh · %.3f V".format(prev.runText, prev.capMah, cur.uoutV))
        }
        // Rueckfrage am Lader neu
        if (cur.run == 12 && prev.run != 12) {
            out += ChannelEvent(k, ChannelEvent.Kind.DIALOG, "$name: Rückfrage am Lader",
                "Der Lader wartet auf eine Bestätigung (Ja/Nein)")
        }
        return out
    }

    /** Kurztext eines Kanals fuer die laufende Benachrichtigung, z. B. "K1 Laden 1.80 A 62 mAh". */
    fun short(k: Int, c: ChannelData?): String = when {
        c == null -> "K${k + 1} –"
        c.isActive -> "K${k + 1} ${c.runText} %+.2f A %d mAh".format(c.currentA, c.capMah)
        else -> "K${k + 1} ${c.runText}"
    }
}
