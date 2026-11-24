package de.dx8.monitor

/**
 * Testversion mit Ablaufdatum (fest beim Bauen). Nach Ablauf gesperrt: Start sowie Programme anlegen/aendern.
 * Immer erlaubt: Stopp, Halten, Weiter, Ueberwachung, Meldungen, Eingangsschutz, Anzeige.
 *
 * Schutz gegen Zurueckstellen der Uhr: die App merkt sich den spaetesten gesehenen Zeitpunkt (lastSeen) und
 * rechnet mit max(jetzt, lastSeen). Nach Ablauf bleibt die Sperre deshalb auch bei zurueckgestellter Uhr.
 */
object Trial {
    /** 12.12.2026 12:12 Uhr Schweizer Zeit (MEZ, UTC+1) = 11:12 UTC. */
    const val EXPIRES_MS = 1_797_073_920_000L
    const val EXPIRES_TEXT = "12.12.2026 12:12"
    /** Hinweis wird in den letzten 3 Tagen orange. */
    const val WARN_MS = 3L * 24 * 3600 * 1000
    /** Uhr gilt als zurueckgestellt, wenn sie mehr als 10 min hinter dem spaetesten gesehenen Zeitpunkt liegt. */
    const val ROLLBACK_TOLERANCE_MS = 10L * 60 * 1000

    const val BLOCKED_TEXT = "Testzeitraum abgelaufen ($EXPIRES_TEXT)"

    enum class State { ACTIVE, WARN, EXPIRED }

    fun effectiveNow(nowMs: Long, lastSeenMs: Long) = maxOf(nowMs, lastSeenMs)

    fun expired(nowMs: Long, lastSeenMs: Long) = effectiveNow(nowMs, lastSeenMs) >= EXPIRES_MS

    fun state(nowMs: Long, lastSeenMs: Long): State {
        val t = effectiveNow(nowMs, lastSeenMs)
        return when {
            t >= EXPIRES_MS -> State.EXPIRED
            EXPIRES_MS - t <= WARN_MS -> State.WARN
            else -> State.ACTIVE
        }
    }

    fun clockRolledBack(nowMs: Long, lastSeenMs: Long) = lastSeenMs - nowMs > ROLLBACK_TOLERANCE_MS

    /** Text fuer den Hinweis oben in der App. */
    fun banner(nowMs: Long, lastSeenMs: Long): String = when (state(nowMs, lastSeenMs)) {
        State.EXPIRED -> "$BLOCKED_TEXT – Start und Programme ändern gesperrt"
        State.WARN -> {
            val h = ((EXPIRES_MS - effectiveNow(nowMs, lastSeenMs)) / 3_600_000L).coerceAtLeast(0)
            "Testversion – gültig bis $EXPIRES_TEXT (noch " + (if (h >= 24) "${h / 24} Tage" else "$h Std.") + ")"
        }
        State.ACTIVE -> "Testversion – gültig bis $EXPIRES_TEXT"
    }

    /** Neuer Wert fuer lastSeen (nur vorwaerts). */
    fun advance(nowMs: Long, lastSeenMs: Long) = maxOf(nowMs, lastSeenMs)
}
