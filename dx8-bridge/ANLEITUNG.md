# DX8-Brücke mit Raspberry Pi Zero 2 W

Der Pi steckt per USB am iCharger DX8 und gibt die Verbindung per WLAN an die ChargeDeck-App weiter.
Der **Schutz Eingangsbatterie** (STOP bei zu hoher Eingangsspannung) läuft **auf dem Pi** –
er wirkt also auch, wenn das Tablet aus oder außer Reichweite ist.

```
DX8 ──USB── Pi Zero 2 W ── WLAN ── Tablet (ChargeDeck-App)
```

## Was du brauchst

| Teil | Hinweis |
|---|---|
| Raspberry Pi Zero 2 W | mit WLAN (das „W“ ist wichtig) |
| microSD-Karte | ab 8 GB |
| 5-V-Netzteil, micro-USB | mind. 1 A, an die Buchse **PWR IN** (außen) |
| Kabel micro-USB (Stecker) → USB-C (Stecker), **Daten** | an die Buchse **USB** (Mitte) des Pi und an den DX8 |
| alternativ | OTG-Adapter micro-USB → USB-A + Kabel USB-A → USB-C |

Am DX8: **USB PD = AUS** (sonst kommt keine Datenverbindung zustande).

### Alternative: Raspberry Pi 3 Model B / 3B+

Funktioniert genauso (WLAN 2,4 GHz eingebaut, gleiche Software). Unterschiede:

| | Pi Zero 2 W | Pi 3 Model B |
|---|---|---|
| USB zum DX8 | micro-USB → USB-C bzw. OTG-Adapter | **normale USB-A-Buchse** → Kabel **USB-A → USB-C (Daten)**, kein Adapter |
| Netzteil | 5 V ≥ 1 A, micro-USB | **5 V 2,5 A**, micro-USB (offizielles Pi-Netzteil empfohlen) |
| Im Imager wählen | Raspberry Pi Zero 2 W | **Raspberry Pi 3** |
| Größe / Strom | klein, sparsam | größer, braucht mehr Strom |

## 1. Karte vorbereiten (am PC)

1. **Raspberry Pi Imager** installieren und starten.
2. Gerät: *Raspberry Pi Zero 2 W*, System: *Raspberry Pi OS Lite (64-bit)*, Speicher: die microSD-Karte.
3. Bei „Einstellungen anpassen“ eintragen:
   - Hostname: `dx8bridge`
   - Benutzer + Passwort (frei wählbar)
   - WLAN: Name und Passwort deines Netzes, Land `CH`
   - Dienste: **SSH aktivieren**
4. Schreiben, Karte in den Pi, Netzteil an **PWR IN**. Erster Start dauert 1–2 Minuten.

## 2. Brücke installieren

Die drei Dateien `dx8bridge.py`, `dx8bridge.service`, `install.sh` auf den Pi kopieren
(am PC in diesem Ordner, PowerShell):

```
scp dx8bridge.py dx8bridge.service install.sh BENUTZER@dx8bridge.local:~
ssh BENUTZER@dx8bridge.local
sudo sh install.sh
```

Am Ende zeigt das Skript **Adresse, Port und Passwort** an – die brauchst du in der App.

## 3. DX8 anstecken

Kabel vom Pi (Buchse **USB**, die mittlere) zum DX8. Die Brücke findet den Lader von selbst –
auch wenn er später angesteckt oder neu eingeschaltet wird.

## 4. App einstellen

Einstellungen → **VERBINDUNG** → „Über WLAN-Brücke“, dann Adresse (`dx8bridge.local` oder die IP),
Port `5751` und Passwort eintragen → Speichern. Die App verbindet sich und funktioniert wie mit USB.

## 5. Home Assistant (optional)

Die Brücke schickt die Messwerte beider Kanäle per **MQTT** an Home Assistant. Das Gerät „iCharger DX8“
erscheint dort von selbst mit allen Sensoren (Auto-Discovery). Das funktioniert auch ohne verbundene App.

**In Home Assistant**
1. Einstellungen → Add-ons → **Mosquitto broker** installieren und starten.
2. Einstellungen → Personen → Benutzer anlegen, z. B. `dx8` mit Passwort (für die Anmeldung am Broker).
3. Einstellungen → Geräte & Dienste → **MQTT** hinzufügen (wird meist automatisch vorgeschlagen).

**Auf dem Pi** die Konfiguration öffnen: `sudo nano /etc/dx8bridge.json` und im Abschnitt `mqtt` eintragen:

```
"mqtt": {
  "host": "192.168.1.10",
  "port": 1883,
  "user": "dx8",
  "password": "dein-passwort",
  "prefix": "dx8",
  "discovery_prefix": "homeassistant",
  "interval_s": 3,
  "stop_button": true
}
```

`host` = IP-Adresse von Home Assistant, `interval_s` = alle wie viele Sekunden gesendet wird,
`stop_button` = STOP-Knopf je Kanal in HA (`false` = nur lesen).

Fehlt der Abschnitt (ältere Installation), einfach so ergänzen (Komma nach der Zeile davor nicht vergessen).
Danach: `sudo systemctl restart dx8bridge`. Im Log steht dann „MQTT verbunden“.

**Was in Home Assistant erscheint** (je Kanal): Status, Spannung, Strom, Leistung, Kapazität, Eingangsspannung,
Temperatur intern/extern, Zellenzahl, Zellendifferenz, Zelle 1–8, Innen- und Leitungswiderstand, „aktiv“,
und der Knopf **STOP**. Fehlercode, Statuscode und Zell-IR sind vorhanden, aber zuerst ausgeblendet.
Ist der Lader nicht erreichbar, zeigt HA die Werte als „nicht verfügbar“.

**Sicherheit:** Home Assistant kann nur **stoppen**, nicht starten und keine Programme ändern. Ist am Display
eine Rückfrage offen, wird der STOP abgelehnt (steht im Log).

Beispiel-Automation „Akku fertig“ → Meldung aufs Handy:

```yaml
trigger:
  - platform: state
    entity_id: sensor.dx8_ch1_status
    to: "Fertig"
action:
  - service: notify.mobile_app_dein_handy
    data:
      message: "DX8 Kanal 1: Akku fertig ({{ states('sensor.dx8_ch1_capacity_mah') }} mAh)"
```

MQTT-Themen zum Nachsehen: `dx8/status` (online/offline), `dx8/ch1/state` und `dx8/ch2/state` (JSON),
`dx8/ch1/stop` und `dx8/ch2/stop` (Befehl `PRESS`).

## Hilfe

- Läuft die Brücke?  `systemctl status dx8bridge`
- Meldungen live:    `journalctl -u dx8bridge -f` (zeigt u. a. „DX8 verbunden“, „App verbunden“, STOP durch den Schutz)
- Passwort ansehen:  `sudo cat /etc/dx8bridge.json`
- Bis zu **vier** Apps können gleichzeitig verbunden sein (z. B. Tablet und PC). Programme nicht auf zwei Geräten
  gleichzeitig ändern oder neu einlesen. Home Assistant zählt nicht dazu – es läuft parallel.
- MQTT-Passwort setzen oder ändern (Eingabe verdeckt): `sudo /opt/dx8bridge/mqtt-passwort.sh [Benutzer]`
- MQTT-Probleme: im Log steht z. B. „Anmeldung abgelehnt … Benutzer/Passwort?“; die Brücke versucht es alle 10 s neu.
