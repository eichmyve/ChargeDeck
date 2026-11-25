# ChargeDeck für Windows

Desktop-Version von ChargeDeck (Compose for Desktop). Die Logik kommt unverändert aus `dx8-android/shared`:
Modbus-HID, Kanallogik mit allen Sperren, Programme, Systemwerte, Firmware-Prüfung, WLAN-Brücke.
Das Gradle-Projekt ist `dx8-android`; dieses Modul heißt dort `:desktop`.

## Stand (Version 1)
- Verbindung über USB (hidapi über hid4java) oder über die WLAN-Brücke (Raspberry Pi)
- Live-Anzeige beider Kanäle: Status, Spannung, Strom, Kapazität, Leistung, Laufzeit, Zellen, Innenwiderstand, Verlauf
- Start (Programm und Betriebsart), Halten, Weiter, Stopp, Rückfragen Ja/Nein; dieselben Abläufe wie die App (`ChargerControl`)
- Akku-zu-Akku-Sperre, Warnung „Anzeige stimmt nicht“ und Windows-Benachrichtigungen bei Ereignissen
- Info-Fenster mit Firmware-Prüfung, helles und dunkles Design, Demo ohne Lader
- Ladeprogramme: Liste mit Filter, Programm-Assistent (Akku → Laden → Storage → Entladen → Zyklus → Fertig) zum Ändern
  und Neuanlegen aus einer Vorlage; Schreiben mit Kontrolle durch Zurücklesen, nur wenn beide Kanäle frei sind

Noch nicht enthalten sind die Systemeinstellungen, der Schutz der Eingangsbatterie und der CSV-Export.

## Starten (Entwicklung)
```powershell
cd dx8-android
gradle :desktop:run
```
Bild der Demo-Ansicht ohne Fenster: `gradle :desktop:run --args="--screenshot bild.png [--light] [--open programs|start|wizard:SCHRITT:PROGRAMM[:pro]]"`

Installer bauen (MSI/EXE): `gradle :desktop:packageMsi`. Das erledige ich nur auf ausdrücklichen Wunsch.
