package de.dx8.monitor

/**
 * Steuerablaeufe fuer einen Kanal (Start, Halten, Weiter, Stopp, Rueckfrage) – dieselben Pruefungen wie in der
 * Android-App, hier ohne Oberflaeche, damit die Windows-App sie unveraendert nutzt.
 * Abgelehnte Vorgaenge werfen [Dx8Refusal] (Titel + Erklaerung fuer ein Hinweisfenster).
 * Alle Methoden blockieren (USB-Verkehr) und gehoeren in einen Hintergrund-Thread.
 */
class ChargerControl(
    private val dev: Dx8Device,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) }
) {
    /** Run-Codes, an denen ein angenommener Start erkennbar ist (oder 41 = abgelehnt). */
    private val STARTED = setOf(2, 5, 6, 7, 10, 11, 12, 13, 17, ChannelLogic.RUN_WAITING, 41)

    fun channel(k: Int) = ChannelData.parse(dev.readMany(4, (k + 1) * 0x100, 60))

    /** Wartet bis der Kanal einen der Run-Codes [target] meldet, hoechstens [ms] (Abfrage alle 300 ms). */
    fun waitRun(k: Int, target: Set<Int>, ms: Long): ChannelData {
        var c = channel(k)
        var left = ms
        while (c.run !in target && left > 0) {
            sleep(300); left -= 300
            c = channel(k)
        }
        return c
    }

    /**
     * Programm [p] mit Betriebsart [op] auf Kanal [k] starten.
     * [otherAkkuZuAkku]: laeuft auf dem anderen Kanal ein (aus der App gestartetes) Akku-zu-Akku-Programm? null = unbekannt.
     * [forced]: Rueckfrage "Trotzdem starten" wurde bestaetigt.
     * Liefert true, wenn der Kanal jetzt als Akku-zu-Akku auf seinen Partner wartet.
     */
    fun start(p: Program, op: Int, k: Int, otherAkkuZuAkku: Boolean?, forced: Boolean = false): Boolean {
        var c = channel(k)
        if (c.isActive || c.currentA != 0.0) throw Dx8Refusal("Kanal ${k + 1} läuft", "Kanal ${k + 1} läuft gerade – erst Halten/Stopp")
        if (c.noBattery || c.uoutV > 36.0) throw Dx8Refusal("Kein Akku", "Kanal ${k + 1}: kein Akku angeschlossen")
        // Partner-Kanal wartet (Akku zu Akku)? Dann nicht ueber USB starten – stoert die Kopplung
        val other = channel(1 - k)
        val both = if (k == 0) listOf(c, other) else listOf(other, c)
        val check = ChannelLogic.partnerCheck(k, both, otherAkkuZuAkku)
        if (check == ChannelLogic.PartnerCheck.BLOCK || (check == ChannelLogic.PartnerCheck.ASK && !forced))
            throw Dx8Refusal("Kanal ${k + 1} nicht starten", ChannelLogic.partnerWaitingReason(k, both) ?: "")
        // Aus "Halten" (oder Fehler) wuerde RUN das alte Programm fortsetzen -> erst auf "Bereit" setzen
        if (c.run != 0) {
            dev.stop(k)
            c = waitRun(k, setOf(0), 4000)
            if (c.run != 0) throw Dx8Exception("Kanal ${k + 1} geht nicht auf Bereit (${c.runText}) – bitte am Lader zur Hauptanzeige")
        }
        // Programm-/Kanalwahl separat setzen, zuruecklesen, dann RUN
        dev.write(0x8000, intArrayOf(op, p.slot, k))
        val ctl = dev.read(3, 0x8000, 3)
        if (ctl[0] != op || ctl[1] != p.slot || ctl[2] != k)
            throw Dx8Exception("Programmwahl nicht übernommen (gelesen ${ctl.joinToString()})")
        dev.run(op, p.slot, k)
        // Kontrolle: lehnt der Lader den Start ab (Fehler 1305 = Display nicht in der Hauptanzeige)?
        val r = waitRun(k, STARTED, 3000)
        if (r.run == 41) {
            val code = errCode(r.err)
            throw Dx8Refusal("Kanal ${k + 1}: Start abgelehnt (Fehler $code)", ChannelLogic.startRejectedHint(code))
        }
        return ChannelLogic.isAkkuZuAkku(op, p.raw)
    }

    /** Hinweistext nach einem Akku-zu-Akku-Start auf Kanal [k]. */
    fun akkuZuAkkuHint(k: Int) =
        "Kanal ${k + 1} wartet jetzt auf seinen Partner.\n\n" +
                "Jetzt Kanal ${2 - k} direkt am Ladegerät mit „Laden“ starten.\n\n" +
                "Über die App lässt sich Kanal ${2 - k} solange nicht starten – der Lader würde den Start ablehnen (Fehler 1305)."

    /** Halten: ein STOP -> Kanal haelt (Run 1). */
    fun hold(k: Int) {
        val c = channel(k)
        if (c.run == 12) throw Dx8Refusal("Rückfrage offen", "Rückfrage offen – bitte mit Ja/Nein beantworten")
        if (!c.isActive) throw Dx8Refusal("Kanal ${k + 1}", "Kanal ${k + 1} läuft nicht")
        dev.stop(k)
    }

    /**
     * Weiter: aus "Halten" mit RUN fortsetzen. [remembered] = beim Start gemerkte (Betriebsart, Platz).
     * Liefert die verwendete (Betriebsart, Platz).
     */
    fun resume(k: Int, remembered: Pair<Int, Int>?): Pair<Int, Int> {
        val now = channel(k)
        if (now.noBattery || now.uoutV > 36.0) throw Dx8Refusal("Kein Akku", "Kanal ${k + 1}: kein Akku angeschlossen")
        if (now.run != 1) throw Dx8Exception("Kanal steht nicht mehr auf Halten (${now.runText})")
        val target = ChannelLogic.continueTarget(k, remembered, dev.read(3, 0x8000, 3))
            ?: throw Dx8Refusal("Weiter nicht möglich", "Betriebsart unbekannt (am Lader gestartet) – bitte am Ladegerät fortsetzen")
        dev.run(target.first, target.second, k)
        val r = waitRun(k, STARTED - ChannelLogic.RUN_WAITING, 4000)
        if (r.run == 1) throw Dx8Exception("Lader hat nicht fortgesetzt – bitte am Lader zur Hauptanzeige")
        if (r.run == 41) throw Dx8Exception("Fehler ${errCode(r.err)} ${ERR_TEXT[errCode(r.err)] ?: ""}".trim())
        return target
    }

    /** Stopp bis "Bereit" (laufend -> Halten -> Bereit), danach Kontrolle, dass kein Strom mehr fliesst. */
    fun stop(k: Int, prevTs: Long? = null) {
        val c = channel(k)
        if (c.run == 12) throw Dx8Refusal("Rückfrage offen", "Rückfrage offen – bitte mit Ja/Nein beantworten")
        if (c.run == 0 && !ChannelLogic.isRunning(c, prevTs)) throw Dx8Refusal("Kanal ${k + 1}", "Kanal ${k + 1} ist bereits bereit")
        // nicht nur dem Statusregister trauen: auch Strom zaehlt als "laeuft"
        if (ChannelLogic.isRunning(c)) {
            dev.stop(k)
            waitRun(k, setOf(1, 0, 41), 4000)
        }
        if (channel(k).run != 0) {
            dev.stop(k)
            val r = waitRun(k, setOf(0), 4000)
            if (r.run != 0) throw Dx8Exception("Kanal steht auf ${r.runText}")
        }
        // Kontrolle: Strom muss weg sein, Laufzeit darf nicht weiterzaehlen
        val a = channel(k); sleep(2200); val b = channel(k)
        if (ChannelLogic.isRunning(b, a.ts))
            throw Dx8Refusal("Kanal ${k + 1} reagiert nicht auf STOP",
                "Status „${b.runText}“, Strom %+.2f A, Laufzeit zählt weiter.\n\n".format(b.currentA) +
                        "Bitte am Ladegerät mit STOP beenden. Reagiert er nicht, Lader vom Strom trennen.")
    }

    /** Rueckfrage am Lader beantworten (nur wenn wirklich eine offen ist). */
    fun answer(k: Int, yes: Boolean) {
        if (channel(k).run != 12) throw Dx8Refusal("Keine Rückfrage", "Kanal ${k + 1} hat keine offene Rückfrage")
        dev.answerDialog(k, yes)
    }
}
