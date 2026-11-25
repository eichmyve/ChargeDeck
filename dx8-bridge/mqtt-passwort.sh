#!/bin/sh
# MQTT-Zugang fuer Home Assistant in /etc/dx8bridge.json setzen (Passwort verdeckt eingeben), Bruecke neu starten.
# Aufruf:  sudo /opt/dx8bridge/mqtt-passwort.sh [Benutzer]
set -e
if [ "$(id -u)" != "0" ]; then echo "Bitte mit sudo starten"; exit 1; fi
python3 - "$@" <<'EOF'
import getpass, json, os, sys
p = "/etc/dx8bridge.json"
c = json.load(open(p))
m = c.setdefault("mqtt", {})
if len(sys.argv) > 1:
    m["user"] = sys.argv[1]
print("MQTT-Broker:", m.get("host") or "(noch nicht eingetragen)", " Benutzer:", m.get("user") or "(leer)")
pw = getpass.getpass("MQTT-Passwort (Eingabe unsichtbar): ")
if not pw:
    sys.exit("Kein Passwort eingegeben – nichts geaendert.")
m["password"] = pw
sys.path.insert(0, "/opt/dx8bridge")
from dx8bridge import write_json_safely      # stromausfallsicher, nur fuer root lesbar
write_json_safely(p, c)
print("Gespeichert.")
EOF
systemctl restart dx8bridge
sleep 6
journalctl -u dx8bridge --since "-8 s" --no-pager -o cat | grep -i mqtt || echo "Noch keine MQTT-Meldung – Log ansehen: journalctl -u dx8bridge -f"
