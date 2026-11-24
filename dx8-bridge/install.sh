#!/bin/sh
# Installiert die DX8-Bruecke auf dem Raspberry Pi. Aufruf im Ordner mit den Dateien:  sudo sh install.sh
set -e
if [ "$(id -u)" != "0" ]; then echo "Bitte mit sudo starten: sudo sh install.sh"; exit 1; fi

mkdir -p /opt/dx8bridge
cp dx8bridge.py /opt/dx8bridge/dx8bridge.py
chmod 755 /opt/dx8bridge/dx8bridge.py

# Konfiguration nur beim ersten Mal anlegen (Passwort/Token zufaellig)
if [ ! -f /etc/dx8bridge.json ]; then
  TOKEN=$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 12)
  cat > /etc/dx8bridge.json <<EOF
{
  "port": 5751,
  "token": "$TOKEN",
  "guard_enabled": true,
  "guard_limit_v": 25.2,
  "mqtt": {
    "host": "",
    "port": 1883,
    "user": "",
    "password": "",
    "prefix": "dx8",
    "discovery_prefix": "homeassistant",
    "interval_s": 3,
    "stop_button": true
  }
}
EOF
  chmod 600 /etc/dx8bridge.json
fi

cp dx8bridge.service /etc/systemd/system/dx8bridge.service
systemctl daemon-reload
systemctl enable dx8bridge
systemctl restart dx8bridge

TOKEN=$(python3 -c "import json;print(json.load(open('/etc/dx8bridge.json'))['token'])")
echo ""
echo "DX8-Bruecke installiert und gestartet."
echo "In der App eintragen:"
echo "  Adresse:  $(hostname).local   (oder $(hostname -I | cut -d' ' -f1))"
echo "  Port:     5751"
echo "  Passwort: $TOKEN"
echo ""
echo "Status ansehen:  systemctl status dx8bridge     Meldungen:  journalctl -u dx8bridge -f"
