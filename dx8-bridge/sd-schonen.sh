#!/bin/sh
# SD-Karte des Pi schonen (einmalig, wirkt ab dem naechsten Neustart). Aufruf:  sudo sh sd-schonen.sh
#  - Auslagern nur noch komprimiert im RAM (zram), ohne Datei auf der SD-Karte (/var/swap wird beim Start entfernt)
#  - Protokoll (journald) im RAM, hoechstens 30 MB
# Bereits vorhanden und unveraendert: noatime, fsck.repair=yes beim Start, /tmp im RAM.
set -e
if [ "$(id -u)" != "0" ]; then echo "Bitte mit sudo starten: sudo sh sd-schonen.sh"; exit 1; fi

mkdir -p /etc/rpi/swap.conf.d
cat > /etc/rpi/swap.conf.d/90-chargedeck.conf <<EOF
# ChargeDeck: nur zram, keine Auslagerungsdatei auf der SD-Karte (siehe swap.conf(5))
[Main]
Mechanism=zram
EOF

mkdir -p /etc/systemd/journald.conf.d
cat > /etc/systemd/journald.conf.d/90-chargedeck.conf <<EOF
# ChargeDeck: Protokoll im RAM (schont die SD-Karte), begrenzt
[Journal]
Storage=volatile
RuntimeMaxUse=30M
EOF

sync
echo "SD-Karte schonen: eingerichtet. Wirkt nach dem naechsten Neustart (sudo reboot)."
