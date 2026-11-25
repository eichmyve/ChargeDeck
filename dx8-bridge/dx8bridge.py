#!/usr/bin/env python3
"""
DX8-Bruecke fuer Raspberry Pi Zero 2 W: iCharger DX8 per USB (HID) <-> WLAN (TCP) zur ChargeDeck-App.

Protokoll (TCP, Standard-Port 5751):
  1. Anmeldung:  Client sendet "DX8BRIDGE 1 <token>\n"  ->  Bruecke antwortet "OK 1\n" oder "ERR <grund>\n" (und trennt).
  2. Danach je Anfrage genau 64 Byte:
       - Modbus-HID-Frame wie ueber USB ([Laenge, 0x30, Funktion, ...], auf 64 Byte aufgefuellt):
         die Bruecke schickt ihn an den Lader und antwortet mit der passenden 64-Byte-Antwort.
         Kommt vom Lader keine passende Antwort, schickt sie nichts (der Client laeuft in seinen Timeout).
       - Steuer-Frame [0xFF, Befehl, ...]:
           'G' Schutz setzen:  [0xFF, 'G', an(0/1), limit_mV_hi, limit_mV_lo] -> Antwort gleiches Format (aktueller Stand)
           'P' Ping:           [0xFF, 'P']                                    -> [0xFF, 'P', 1]
Eingangsschutz: Die Bruecke liest selbst jede Sekunde die Eingangsspannung und stoppt laufende Kanaele bei
Ueberschreiten der Grenze – auch wenn die App nicht verbunden ist. Grenze/Ein-Aus kommen aus der App (Befehl 'G')
und werden in der Konfigurationsdatei gespeichert.

Home Assistant (optional, Abschnitt "mqtt" in der Konfiguration, aktiv sobald "host" gesetzt ist):
  Messwerte beider Kanaele alle paar Sekunden per MQTT, Geraet und Sensoren per Auto-Discovery.
  Nur lesend – einziger Befehl ist STOP je Kanal (nie bei offener Rueckfrage am Display).
  Themen:  <prefix>/status (online/offline), <prefix>/chN/state (JSON), <prefix>/chN/stop (Befehl "PRESS")

Statusseite (Abschnitt "web", Standard Port 8080): http://<pi>:8080 im Browser (z. B. iPhone). Zeigt beide Kanaele mit
Verlauf, Verbindungen (DX8, Apps, MQTT), weitere Dienste (z. B. jeti-ble) und den Zustand des Pi. Nur lesend.
/wlan (mit dem Passwort der Bruecke): weitere WLAN-Netze eintragen/entfernen.

Eigenes WLAN (Abschnitt "hotspot"): kein bekanntes Netz seit 60 s -> der Pi oeffnet "ChargeDeck-Pi" (Passwort der
Bruecke), ohne verbundene Geraete sucht er alle 3 min kurz nach bekannten Netzen.
"""
import json
import logging
import os
import select
import socket
import struct
import sys
import threading
import time

VID, PID = 0x0483, 0x5751
FRAME = 64
UNLOCK = 0x55AA
VERSION = 1
MAX_CLIENTS = 4          # gleichzeitig verbundene Apps (Tablet, PC, ...)
MQTT_DEFAULT = {"host": "", "port": 1883, "user": "", "password": "", "prefix": "dx8",
                "discovery_prefix": "homeassistant", "interval_s": 3, "stop_button": True}
# Statusseite im Browser (z. B. iPhone): nur lesend. services = weitere systemd-Dienste auf dem Pi, die angezeigt werden.
WEB_DEFAULT = {"enabled": True, "port": 8080, "interval_s": 3, "history_min": 60, "services": ["jeti-ble"]}
# Eigenes WLAN, wenn kein bekanntes Netz da ist. password leer = Passwort der Bruecke ("token").
HOTSPOT_DEFAULT = {"enabled": True, "ssid": "ChargeDeck-Pi", "password": "", "address": "10.42.0.1",
                   "wait_s": 60, "retry_s": 180, "ifname": "wlan0"}
CONF_DEFAULT = {"port": 5751, "token": "", "guard_enabled": True, "guard_limit_v": 25.2,
                "mqtt": dict(MQTT_DEFAULT), "web": dict(WEB_DEFAULT), "hotspot": dict(HOTSPOT_DEFAULT)}
HOTSPOT_CONN = "dx8-hotspot"
log = logging.getLogger("dx8bridge")


# ----------------------------------------------------------------------------- Modbus-HID
def response_matches(req: bytes, resp: bytes) -> bool:
    """Passt die Antwort zur Anfrage? (wie Dx8Device.read/write in der App)"""
    if len(resp) < 4 or resp[1] != 0x30:
        return False
    fn = req[2]
    if fn in (3, 4):
        qty = req[6]
        return (resp[2] == fn and resp[3] == qty * 2) or resp[2] == (fn | 0x80)
    if fn == 0x10:
        return resp[2] in (0x10, 0x90)
    return resp[2] in (fn, fn | 0x80)


def frame_read(fn: int, addr: int, qty: int) -> bytes:
    return bytes([7, 0x30, fn, addr >> 8, addr & 0xFF, 0, qty]).ljust(FRAME, b"\0")


def frame_write(addr: int, vals) -> bytes:
    n = len(vals)
    f = bytearray([8 + 2 * n, 0x30, 0x10, addr >> 8, addr & 0xFF, 0, n, 2 * n])
    for v in vals:
        f += bytes([(v >> 8) & 0xFF, v & 0xFF])
    return bytes(f).ljust(FRAME, b"\0")


def regs_of(resp: bytes, qty: int):
    return [(resp[4 + 2 * k] << 8) | resp[5 + 2 * k] for k in range(qty)]


class HidDevice:
    """Lader ueber /dev/hidrawN. xfer() schickt einen Frame und liefert die passende Antwort (oder None)."""

    def __init__(self, path: str):
        self.path = path
        self.fd = os.open(path, os.O_RDWR)
        self.lock = threading.Lock()

    @staticmethod
    def find() -> str:
        base = "/sys/class/hidraw"
        for name in sorted(os.listdir(base)):
            try:
                with open(os.path.join(base, name, "device", "uevent")) as f:
                    ev = f.read().upper()
                if "HID_ID=0003:%08X:%08X" % (VID, PID) in ev:
                    return "/dev/" + name
            except OSError:
                pass
        raise FileNotFoundError("DX8 nicht gefunden (USB eingesteckt?)")

    def _read(self, timeout: float):
        r, _, _ = select.select([self.fd], [], [], timeout)
        if not r:
            return None
        return os.read(self.fd, FRAME)

    def xfer(self, frame: bytes, timeout: float = 1.2):
        with self.lock:
            while self._read(0.005) is not None:      # alte / LogView-Frames verwerfen
                pass
            os.write(self.fd, b"\0" + frame[:FRAME].ljust(FRAME, b"\0"))   # Report-ID 0 + 64 Byte
            end = time.monotonic() + timeout
            while True:
                left = end - time.monotonic()
                if left <= 0:
                    return None
                resp = self._read(left)
                if resp is None:
                    return None
                if response_matches(frame, resp):
                    return resp.ljust(FRAME, b"\0")

    def close(self):
        try:
            os.close(self.fd)
        except OSError:
            pass


# ----------------------------------------------------------------------------- Eingangsschutz
class Guard:
    """STOP fuer laufende Kanaele, wenn die Eingangsspannung die Grenze erreicht (nicht bei offener Rueckfrage)."""
    IDLE = (0, 1, 40, 41)

    def __init__(self, conf: dict, save):
        self.conf = conf
        self.save = save

    @property
    def enabled(self):
        return bool(self.conf.get("guard_enabled", True))

    @property
    def limit_v(self):
        return float(self.conf.get("guard_limit_v", 25.2))

    def set(self, enabled: bool, limit_v: float):
        self.conf["guard_enabled"] = bool(enabled)
        self.conf["guard_limit_v"] = round(float(limit_v), 3)
        self.save()

    def check(self, dev) -> list:
        """Einmal pruefen; liefert die gestoppten Kanaele (0/1)."""
        if not self.enabled:
            return []
        stopped = []
        for k in (0, 1):
            base = (k + 1) * 0x100
            r1 = dev.xfer(frame_read(4, base + 5, 1))      # Eingangsspannung mV
            r2 = dev.xfer(frame_read(4, base + 55, 1))     # Run-Status
            if r1 is None or r2 is None or r1[2] & 0x80 or r2[2] & 0x80:
                continue
            uin = regs_of(r1, 1)[0] / 1000.0
            run = regs_of(r2, 1)[0]
            if run not in self.IDLE and run != 12 and uin >= self.limit_v:
                dev.xfer(frame_write(0x8002, [k, UNLOCK, 0]))
                dev.xfer(frame_write(0x8003, [0]))
                log.warning("Eingang %.2f V >= %.2f V -> STOP Kanal %d", uin, self.limit_v, k + 1)
                stopped.append(k)
        return stopped


# ----------------------------------------------------------------------------- Kanalwerte
RUN_TEXT = {0: "Bereit", 1: "Halten", 2: "Start", 5: "Startprüfung", 6: "Vorbereitung", 7: "Laden",
            10: "Nachladen", 11: "Entladen-Start", 12: "Rückfrage am Display", 13: "Entladen", 17: "Balancieren", 39: "Warten",
            40: "Fertig", 41: "Fehler", 43: "RI-Messung", 46: "Power Output"}
RUN_DIALOG = 12


def s16(v: int) -> int:
    return v - 0x10000 if v >= 0x8000 else v


def s32(lo: int, hi: int) -> int:
    v = (hi << 16) + lo
    return v - 0x100000000 if v >= 0x80000000 else v


def read_regs(dev, fn: int, addr: int, qty: int):
    """Mehrere Register in 30er-Bloecken lesen; None bei Timeout oder Modbus-Fehler."""
    out = []
    while len(out) < qty:
        n = min(30, qty - len(out))
        r = dev.xfer(frame_read(fn, addr + len(out), n))
        if r is None or r[2] & 0x80:
            return None
        out += regs_of(r, n)
    return out


def read_channel(dev, k: int):
    """Kanal k (0/1) lesen wie die App (60 Input-Register ab (k+1)*0x100); None wenn nicht lesbar."""
    g = read_regs(dev, 4, (k + 1) * 0x100, 60)
    return channel_values(g) if g else None


def channel_values(g) -> dict:
    """Rohregister -> Werte fuer Home Assistant (wie ChannelData.parse in der App)."""
    idx = [k for k in range(8) if 1 <= g[11 + k] <= 4999]
    cells = [g[11 + k] / 1000.0 for k in idx]
    run, current = g[55], s16(g[4]) / 100.0
    no_batt = not cells and g[6] > 36000 and current == 0
    text = "Akku n. verbunden" if no_batt and run in (0, 1) else RUN_TEXT.get(run, "Code %d" % run)
    e = g[56]
    v = {
        "run": run, "status": text, "active": run not in Guard.IDLE,
        "power_w": round(s32(g[2], g[3]) / 100.0, 2), "current_a": current,
        "input_v": g[5] / 1000.0, "output_v": g[6] / 1000.0, "capacity_mah": s32(g[7], g[8]),
        "temp_int_c": s16(g[9]) / 10.0, "temp_ext_c": s16(g[10]) / 10.0,
        "cell_count": len(cells), "cell_delta_mv": round((max(cells) - min(cells)) * 1000) if cells else 0,
        "ir_total_mohm": g[51] / 10.0, "ir_line_mohm": g[52] / 10.0,
        "error": (e >> 8) * 100 + (e & 0xFF) if run == 41 else 0,
        "runtime_s": ((g[1] << 16) + g[0]) // 1000,
    }
    for n in range(8):
        v["cell%d_v" % (n + 1)] = cells[n] if n < len(cells) else None
        v["cell%d_ir_mohm" % (n + 1)] = g[35 + idx[n]] / 10.0 if n < len(idx) else None
    return v


# ----------------------------------------------------------------------------- MQTT (3.1.1, nur QoS 0)
def _mq_str(s) -> bytes:
    b = s.encode("utf-8") if isinstance(s, str) else bytes(s)
    return struct.pack(">H", len(b)) + b


def _mq_len(n: int) -> bytes:
    out = bytearray()
    while True:
        d, n = n % 128, n // 128
        out.append(d | (0x80 if n else 0))
        if not n:
            return bytes(out)


def mqtt_packet(head: int, body: bytes) -> bytes:
    return bytes([head]) + _mq_len(len(body)) + body


class MiniMqtt:
    """Kleiner MQTT-Client ohne Zusatzpakete: verbinden (mit Last Will), senden, abonnieren, Ping."""

    def __init__(self, host, port, client_id, user="", password="", will=None, keepalive=60, timeout=5):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.wlock = threading.Lock()
        flags, payload = 0x02, _mq_str(client_id)                 # clean session
        if will:
            flags |= 0x04 | 0x20                                    # Will, Will retain
            payload += _mq_str(will[0]) + _mq_str(will[1])
        if user:
            flags |= 0x80
            payload += _mq_str(user)
            if password:
                flags |= 0x40
                payload += _mq_str(password)
        self.sock.sendall(mqtt_packet(0x10, _mq_str("MQTT") + bytes([4, flags]) + struct.pack(">H", keepalive) + payload))
        head, body = self.read_packet()
        if head >> 4 != 2 or len(body) < 2:
            raise OSError("MQTT: unerwartete Antwort")
        if body[1] != 0:
            raise OSError("MQTT: Anmeldung abgelehnt (Code %d%s)" % (body[1], ", Benutzer/Passwort?" if body[1] in (4, 5) else ""))
        self.sock.settimeout(None)
        self.pid = 0

    def _recv(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            c = self.sock.recv(n - len(buf))
            if not c:
                raise OSError("MQTT: Verbindung getrennt")
            buf += c
        return buf

    def read_packet(self):
        head = self._recv(1)[0]
        n, mul = 0, 1
        while True:
            d = self._recv(1)[0]
            n += (d & 0x7F) * mul
            mul *= 128
            if not d & 0x80:
                break
        return head, self._recv(n) if n else b""

    def _send(self, data: bytes):
        with self.wlock:
            self.sock.sendall(data)

    def publish(self, topic: str, payload, retain=False):
        if isinstance(payload, str):
            payload = payload.encode("utf-8")
        self._send(mqtt_packet(0x30 | (1 if retain else 0), _mq_str(topic) + payload))

    def subscribe(self, topic: str):
        self.pid = self.pid % 0xFFFF + 1
        self._send(mqtt_packet(0x82, struct.pack(">H", self.pid) + _mq_str(topic) + b"\0"))

    def ping(self):
        self._send(b"\xC0\x00")

    def loop(self, on_message):
        """Blockiert; ruft on_message(topic, payload) fuer eingehende Nachrichten. Endet mit OSError."""
        while True:
            head, body = self.read_packet()
            if head >> 4 == 3:
                tl = struct.unpack(">H", body[:2])[0]
                topic = body[2:2 + tl].decode("utf-8", "replace")
                rest = body[2 + tl:]
                if (head >> 1) & 3:                                 # QoS > 0: Paket-ID ueberspringen
                    rest = rest[2:]
                on_message(topic, rest)

    def close(self):
        try:
            self._send(b"\xE0\x00")                                 # DISCONNECT (Will wird nicht gesendet)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


# ----------------------------------------------------------------------------- Home Assistant
# (Schluessel, Name, Einheit, device_class, state_class, Nachkommastellen)
HA_SENSORS = [
    ("status", "Status", None, None, None, None),
    ("output_v", "Spannung", "V", "voltage", "measurement", 3),
    ("current_a", "Strom", "A", "current", "measurement", 2),
    ("power_w", "Leistung", "W", "power", "measurement", 1),
    ("capacity_mah", "Kapazität", "mAh", None, "measurement", 0),
    ("input_v", "Eingangsspannung", "V", "voltage", "measurement", 2),
    ("temp_int_c", "Temperatur intern", "°C", "temperature", "measurement", 1),
    ("temp_ext_c", "Temperatur extern", "°C", "temperature", "measurement", 1),
    ("cell_count", "Zellen", None, None, "measurement", 0),
    ("cell_delta_mv", "Zellendifferenz", "mV", "voltage", "measurement", 0),
    ("ir_total_mohm", "Innenwiderstand", "mΩ", None, "measurement", 1),
    ("ir_line_mohm", "Leitungswiderstand", "mΩ", None, "measurement", 1),
    ("error", "Fehlercode", None, None, None, None),
    ("run", "Statuscode", None, None, None, None),
] + [("cell%d_v" % n, "Zelle %d" % n, "V", "voltage", "measurement", 3) for n in range(1, 9)] \
  + [("cell%d_ir_mohm" % n, "Zelle %d IR" % n, "mΩ", None, "measurement", 1) for n in range(1, 9)]
HA_HIDDEN = {"run", "error"} | {"cell%d_ir_mohm" % n for n in range(1, 9)}   # anfangs in HA deaktiviert


def ha_discovery(prefix: str, disc: str, stop_button: bool, node="dx8"):
    """Liste (Thema, JSON) fuer die Auto-Discovery von Geraet, Sensoren und STOP-Knoepfen."""
    dev = {"identifiers": [node], "name": "iCharger DX8", "manufacturer": "Junsi", "model": "iCharger DX8",
           "sw_version": "dx8bridge %d" % VERSION}
    avail = "%s/status" % prefix
    out = []
    for ch in (1, 2):
        state = "%s/ch%d/state" % (prefix, ch)
        for key, name, unit, dcls, scls, prec in HA_SENSORS:
            uid = "%s_ch%d_%s" % (node, ch, key)
            c = {"name": "Kanal %d %s" % (ch, name), "unique_id": uid, "object_id": uid, "state_topic": state,
                 "value_template": "{{ value_json.%s }}" % key, "availability_topic": avail, "device": dev}
            if unit:
                c["unit_of_measurement"] = unit
            if dcls:
                c["device_class"] = dcls
            if scls:
                c["state_class"] = scls
            if prec is not None:
                c["suggested_display_precision"] = prec
            if key in HA_HIDDEN:
                c["enabled_by_default"] = False
            out.append(("%s/sensor/%s/%s/config" % (disc, node, uid), c))
        uid = "%s_ch%d_active" % (node, ch)
        out.append(("%s/binary_sensor/%s/%s/config" % (disc, node, uid),
                    {"name": "Kanal %d aktiv" % ch, "unique_id": uid, "object_id": uid, "state_topic": state,
                     "value_template": "{{ 'ON' if value_json.active else 'OFF' }}", "device_class": "running",
                     "availability_topic": avail, "device": dev}))
        uid = "%s_ch%d_stop" % (node, ch)
        topic = "%s/button/%s/%s/config" % (disc, node, uid)
        if stop_button:
            out.append((topic, {"name": "Kanal %d STOP" % ch, "unique_id": uid, "object_id": uid,
                                "command_topic": "%s/ch%d/stop" % (prefix, ch), "payload_press": "PRESS",
                                "icon": "mdi:stop-circle", "availability_topic": avail, "device": dev}))
        else:
            out.append((topic, None))                               # leere Nachricht entfernt den Knopf
    return out


class HaPublisher:
    """Liest beide Kanaele regelmaessig und meldet sie per MQTT an Home Assistant; STOP-Befehle aus HA."""

    def __init__(self, conf: dict, device, drop_device, connect=None):
        self.conf = conf
        self.device = device
        self.drop_device = drop_device
        self.connect = connect or (lambda will: MiniMqtt(conf["host"], int(conf.get("port", 1883)), "dx8bridge",
                                                         conf.get("user", ""), conf.get("password", ""), will))
        self.prefix = conf.get("prefix", "dx8").strip("/")
        self.client = None
        self.online = None
        self.running = True

    @property
    def interval(self):
        return max(1.0, float(self.conf.get("interval_s", 3)))

    def start_session(self):
        c = self.connect(("%s/status" % self.prefix, "offline"))
        for topic, cfg in ha_discovery(self.prefix, self.conf.get("discovery_prefix", "homeassistant"),
                                       bool(self.conf.get("stop_button", True))):
            c.publish(topic, json.dumps(cfg, ensure_ascii=False) if cfg is not None else b"", retain=True)
        if self.conf.get("stop_button", True):
            c.subscribe("%s/+/stop" % self.prefix)
        self.client, self.online = c, None
        log.info("MQTT verbunden: %s", self.conf.get("host"))
        return c

    def set_online(self, on: bool):
        if on != self.online:
            self.client.publish("%s/status" % self.prefix, "online" if on else "offline", retain=True)
            self.online = on

    def poll_once(self):
        """Beide Kanaele lesen und senden; DX8 nicht lesbar -> 'offline'."""
        try:
            dev = self.device()
            vals = [read_channel(dev, k) for k in (0, 1)]
        except (OSError, FileNotFoundError):
            self.drop_device()
            vals = [None, None]
        if None in vals:
            self.set_online(False)
            return False
        for k, v in enumerate(vals):
            self.client.publish("%s/ch%d/state" % (self.prefix, k + 1), json.dumps(v, ensure_ascii=False))
        self.set_online(True)
        return True

    def on_message(self, topic: str, payload: bytes):
        parts = topic.split("/")
        if len(parts) < 2 or parts[-1] != "stop" or parts[-2] not in ("ch1", "ch2"):
            return
        if not self.conf.get("stop_button", True):
            return
        self.stop(int(parts[-2][2]) - 1)

    def stop(self, k: int) -> bool:
        """STOP fuer Kanal k – nicht bei offener Rueckfrage am Display."""
        try:
            dev = self.device()
            r = read_regs(dev, 4, (k + 1) * 0x100 + 55, 1)
            if r is None:
                log.warning("HA-STOP Kanal %d: Status nicht lesbar", k + 1)
                return False
            if r[0] == RUN_DIALOG:
                log.warning("HA-STOP Kanal %d abgelehnt: Rückfrage am Display offen", k + 1)
                return False
            dev.xfer(frame_write(0x8002, [k, UNLOCK, 0]))
            dev.xfer(frame_write(0x8003, [0]))
            log.warning("STOP Kanal %d durch Home Assistant", k + 1)
            return True
        except (OSError, FileNotFoundError):
            self.drop_device()
            return False

    def run(self):
        """Endlosschleife mit Neuverbinden (10 s Pause nach Fehler)."""
        while self.running:
            c = None
            try:
                c = self.start_session()
                threading.Thread(target=self._reader, args=(c,), daemon=True).start()
                last_ping = time.monotonic()
                while self.running and self.client is c:
                    self.poll_once()
                    if time.monotonic() - last_ping > 30:
                        c.ping()
                        last_ping = time.monotonic()
                    time.sleep(self.interval)
            except OSError as e:
                log.warning("MQTT: %s", e)
            if c is not None:
                c.close()
            self.client = None
            if self.running:
                time.sleep(10)

    def _reader(self, c):
        try:
            c.loop(self.on_message)
        except (OSError, ValueError, struct.error):
            pass
        if self.client is c:
            self.client = None                                      # Hauptschleife verbindet neu


# ----------------------------------------------------------------------------- Bruecke
class Bridge:
    def __init__(self, conf_path: str, dev_factory=None):
        self.conf_path = conf_path
        self.conf = dict(CONF_DEFAULT)
        if os.path.exists(conf_path):
            with open(conf_path) as f:
                self.conf.update(json.load(f))
        self.conf["mqtt"] = dict(MQTT_DEFAULT, **(self.conf.get("mqtt") or {}))
        self.conf["web"] = dict(WEB_DEFAULT, **(self.conf.get("web") or {}))
        self.conf["hotspot"] = dict(HOTSPOT_DEFAULT, **(self.conf.get("hotspot") or {}))
        self.ha = None
        self.client_addrs = []           # IP-Adressen der verbundenen Apps (fuer die Statusseite)
        self.status = StatusCollector(self)
        self.wifi = WifiManager()
        self.hotspot = HotspotGuard(self.conf["hotspot"], str(self.conf.get("token", "")))
        self.dev_factory = dev_factory or (lambda: HidDevice(HidDevice.find()))
        self.dev = None
        self.dev_lock = threading.Lock()
        self.guard = Guard(self.conf, self.save_conf)
        self.running = True
        self.clients = 0
        self.clients_lock = threading.Lock()

    def save_conf(self):
        tmp = self.conf_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(self.conf, f, indent=2)
        os.replace(tmp, self.conf_path)

    def device(self):
        with self.dev_lock:
            if self.dev is None:
                self.dev = self.dev_factory()
                log.info("DX8 verbunden: %s", getattr(self.dev, "path", "?"))
            return self.dev

    def drop_device(self):
        with self.dev_lock:
            if self.dev is not None:
                self.dev.close()
                self.dev = None

    def control(self, frame: bytes) -> bytes:
        cmd = frame[1]
        if cmd == ord("G"):
            self.guard.set(frame[2] != 0, ((frame[3] << 8) | frame[4]) / 1000.0)
            mv = int(round(self.guard.limit_v * 1000))
            log.info("Schutz: %s, Grenze %.2f V", "an" if self.guard.enabled else "aus", self.guard.limit_v)
            return bytes([0xFF, ord("G"), 1 if self.guard.enabled else 0, mv >> 8, mv & 0xFF]).ljust(FRAME, b"\0")
        if cmd == ord("P"):
            return bytes([0xFF, ord("P"), 1]).ljust(FRAME, b"\0")
        return bytes([0xFF, ord("?")]).ljust(FRAME, b"\0")

    def handle(self, frame: bytes):
        """Eine Client-Anfrage bearbeiten; liefert 64-Byte-Antwort oder None."""
        if frame[0] == 0xFF:
            return self.control(frame)
        try:
            return self.device().xfer(frame)
        except OSError as e:
            log.warning("USB-Fehler: %s", e)
            self.drop_device()
            return None

    def guard_loop(self, interval: float = 1.0):
        while self.running:
            try:
                self.guard.check(self.device())
            except (OSError, FileNotFoundError):
                self.drop_device()
            time.sleep(interval)

    def serve_client(self, conn: socket.socket):
        conn.settimeout(30)
        f = conn.makefile("rb")
        line = f.readline(200).decode("ascii", "replace").strip().split(" ")
        token = self.conf.get("token", "")
        if len(line) < 2 or line[0] != "DX8BRIDGE" or (token and (len(line) < 3 or line[2] != token)):
            conn.sendall(b"ERR Anmeldung\n")
            return
        conn.sendall(("OK %d\n" % VERSION).encode())
        conn.settimeout(None)
        while self.running:
            frame = f.read(FRAME)
            if not frame or len(frame) < FRAME:
                return
            resp = self.handle(frame)
            if resp is not None:
                conn.sendall(resp)

    def serve(self, host: str = "0.0.0.0", port: int = None, ready=None, web_port: int = None):
        port = int(self.conf.get("port", 5751)) if port is None else port    # 0 = freier Port (Tests)
        threading.Thread(target=self.guard_loop, daemon=True).start()
        if self.conf["mqtt"].get("host"):
            self.ha = HaPublisher(self.conf["mqtt"], self.device, self.drop_device)
            threading.Thread(target=self.ha.run, daemon=True).start()
        if self.conf["hotspot"].get("enabled", True):
            threading.Thread(target=self.hotspot.run, daemon=True).start()
        web = self.conf["web"]
        if web.get("enabled", True):
            try:
                self.web = start_web(self, host, int(web.get("port", 8080)) if web_port is None else web_port)
                self.web_port = self.web.server_address[1]
                threading.Thread(target=self.status.run, daemon=True).start()
                log.info("Statusseite auf Port %d", self.web_port)
            except OSError as e:
                log.warning("Statusseite nicht gestartet: %s", e)
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((host, port))
        srv.listen(MAX_CLIENTS)
        self.port = srv.getsockname()[1]
        log.info("Bruecke hoert auf Port %d", self.port)
        if ready:
            ready()
        # Mehrere Apps gleichzeitig (z. B. Tablet und PC): je App ein Thread. Jede USB-Anfrage laeuft unter
        # HidDevice.lock als Ganzes (Frame hin, Antwort zurueck), die Apps wechseln sich also pro Anfrage ab.
        while self.running:
            conn, addr = srv.accept()
            with self.clients_lock:
                full = self.clients >= MAX_CLIENTS
                if not full:
                    self.clients += 1
            if full:
                log.warning("App abgewiesen (schon %d verbunden): %s", MAX_CLIENTS, addr[0])
                threading.Thread(target=self._reject, args=(conn,), daemon=True).start()
                continue
            threading.Thread(target=self._client_thread, args=(conn, addr), daemon=True).start()

    @staticmethod
    def _reject(conn: socket.socket):
        """Anmeldezeile abwarten, dann "ERR belegt" – so kommt die Meldung sicher an (statt Verbindungsabbruch)."""
        try:
            conn.settimeout(3)
            conn.makefile("rb").readline(200)
            conn.sendall(b"ERR belegt\n")
        except OSError:
            pass
        finally:
            conn.close()

    def _client_thread(self, conn: socket.socket, addr):
        with self.clients_lock:
            self.client_addrs.append(addr[0])
        log.info("App verbunden: %s (%d aktiv)", addr[0], self.clients)
        try:
            self.serve_client(conn)
        except OSError as e:
            log.info("App getrennt: %s", e)
        finally:
            conn.close()
            with self.clients_lock:
                self.clients -= 1
                if addr[0] in self.client_addrs:
                    self.client_addrs.remove(addr[0])
            log.info("App getrennt: %s (%d aktiv)", addr[0], self.clients)


# ----------------------------------------------------------------------------- Statusseite (nur lesend)
def _read_file(path: str, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except (OSError, UnicodeDecodeError):
        return default


def pi_info() -> dict:
    """Zustand des Raspberry Pi: Temperatur, Last, Speicher, Laufzeit, WLAN, Stromversorgung."""
    out = {"host": socket.gethostname()}
    t = _read_file("/sys/class/thermal/thermal_zone0/temp")
    out["cpu_temp_c"] = round(int(t) / 1000.0, 1) if t and t.strip().isdigit() else None
    try:
        out["load"] = round(os.getloadavg()[0], 2)
    except (OSError, AttributeError):          # AttributeError: nicht unter Linux (Tests am PC)
        out["load"] = None
    mem = {}
    for line in (_read_file("/proc/meminfo", "") or "").splitlines():
        k, _, v = line.partition(":")
        if v.strip().split()[:1]:
            mem[k] = int(v.strip().split()[0])
    if "MemTotal" in mem:
        out["mem_used_pct"] = round(100 - 100.0 * mem.get("MemAvailable", 0) / mem["MemTotal"])
    up = _read_file("/proc/uptime")
    out["uptime_s"] = int(float(up.split()[0])) if up else None
    out["wifi_dbm"] = None
    for line in (_read_file("/proc/net/wireless", "") or "").splitlines()[2:]:
        parts = line.split()
        if len(parts) > 3:
            try:
                out["wifi_dbm"] = int(float(parts[3]))
            except ValueError:
                pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))           # sendet nichts, liefert nur die eigene Adresse
        out["ip"] = s.getsockname()[0]
        s.close()
    except OSError:
        out["ip"] = None
    # Unterspannung/Drosselung (vcgencmd): Bit 0 jetzt, Bit 16 seit dem Start
    out["throttled"] = None
    try:
        import subprocess
        r = subprocess.run(["vcgencmd", "get_throttled"], capture_output=True, text=True, timeout=2)
        if r.returncode == 0 and "=" in r.stdout:
            v = int(r.stdout.strip().split("=")[1], 16)
            out["throttled"] = {"now": bool(v & 0x1), "since_boot": bool(v & 0x10000)}
    except (OSError, ValueError, Exception):
        pass
    return out


def service_states(names) -> dict:
    """systemd-Dienste (z. B. jeti-ble): 'active', 'inactive', 'failed' ... oder None."""
    out = {}
    for n in names:
        try:
            import subprocess
            r = subprocess.run(["systemctl", "is-active", n], capture_output=True, text=True, timeout=2)
            out[n] = r.stdout.strip() or None
        except (OSError, Exception):
            out[n] = None
    return out


def usb_serial_devices():
    """Angesteckte USB-Seriell-Geraete (z. B. JETIBOX Profi ueber FTDI) – Namen aus /dev/serial/by-id."""
    try:
        return sorted(os.listdir("/dev/serial/by-id"))
    except OSError:
        return []


class StatusCollector:
    """Liest beide Kanaele regelmaessig fuer die Statusseite und fuehrt einen Verlauf (nur lesend)."""

    def __init__(self, bridge):
        self.bridge = bridge
        self.lock = threading.Lock()
        self.channels = [None, None]
        self.updated = 0.0
        self.history = [[], []]          # je Kanal: [t, Ausgang V, Strom A, [Zellen V]]
        self._slow = {}
        self._slow_t = 0.0

    @property
    def interval(self):
        return max(1.0, float(self.bridge.conf["web"].get("interval_s", 3)))

    def poll_once(self, now=None):
        now = time.time() if now is None else now
        try:
            dev = self.bridge.device()
            vals = [read_channel(dev, k) for k in (0, 1)]
        except (OSError, FileNotFoundError):
            self.bridge.drop_device()
            vals = [None, None]
        keep = int(float(self.bridge.conf["web"].get("history_min", 60)) * 60 / self.interval)
        with self.lock:
            for k, v in enumerate(vals):
                prev = self.channels[k]
                h = self.history[k]
                if v is not None:
                    # neuer Lauf (Laufzeit springt zurueck) -> Verlauf neu beginnen
                    if prev is not None and v["runtime_s"] < prev.get("runtime_s", 0):
                        h.clear()
                    if v["active"] or v["current_a"] != 0:
                        cells = [v["cell%d_v" % (n + 1)] for n in range(v["cell_count"])]
                        h.append([round(now, 1), v["output_v"], v["current_a"], cells])
                        del h[:-keep]
                self.channels[k] = v
            self.updated = now
        return vals

    def slow_info(self):
        """Pi- und Dienstwerte (aendern sich langsam): hoechstens alle 10 s neu ermitteln. Fehler -> leere Werte."""
        if time.time() - self._slow_t > 10:
            try:
                pi = pi_info()
            except Exception as e:                  # Statusseite darf nie an einem Pi-Wert scheitern
                log.warning("Pi-Werte: %s", e)
                pi = {}
            self._slow = {"pi": pi, "services": service_states(self.bridge.conf["web"].get("services", [])),
                          "usb_serial": usb_serial_devices()}
            self._slow_t = time.time()
        return self._slow

    def snapshot(self, max_points: int = 300) -> dict:
        b = self.bridge
        with self.lock:
            chans = list(self.channels)
            hist = []
            for h in self.history:
                step = max(1, len(h) // max_points)
                hist.append(h[::step])
            updated = self.updated
        with b.clients_lock:
            apps = list(b.client_addrs)
        ha = b.ha
        mq = b.conf["mqtt"]
        hs = b.conf["hotspot"]
        wifi = dict(b.hotspot.state, enabled=bool(hs.get("enabled", True)), ap_ssid=hs.get("ssid"), ap_address=hs.get("address"))
        return dict(self.slow_info(), **{
            "version": VERSION, "time": time.time(), "updated": updated, "wifi": wifi,
            "dx8": {"connected": b.dev is not None, "path": getattr(b.dev, "path", None)},
            "channels": chans, "history": hist,
            "apps": apps,
            "guard": {"enabled": b.guard.enabled, "limit_v": b.guard.limit_v},
            "mqtt": {"configured": bool(mq.get("host")), "host": mq.get("host") or None,
                     "connected": bool(ha and ha.client is not None), "online": bool(ha and ha.online),
                     "read_only": not mq.get("stop_button", True)},
        })

    def run(self):
        while self.bridge.running:
            self.poll_once()
            time.sleep(self.interval)


class WifiManager:
    """
    Weitere WLAN-Netze ueber NetworkManager (nmcli) eintragen/entfernen – fuer die Seite /wlan.
    Neue Netze bekommen Vorrang -10, damit das Heim-WLAN (Vorrang 0) bevorzugt bleibt; "bevorzugen" = 10.
    Das gerade benutzte Netz laesst sich nicht entfernen (sonst waere der Pi nicht mehr erreichbar).
    """
    PRIO_NORMAL, PRIO_PREFERRED = -10, 10

    def __init__(self, runner=None):
        import subprocess
        self.run = runner or (lambda args: subprocess.run(args, capture_output=True, text=True, timeout=30))

    def _nm(self, *args):
        r = self.run(["nmcli"] + list(args))
        if r.returncode != 0:
            raise ValueError((r.stderr or r.stdout or "nmcli-Fehler").strip().splitlines()[-1])
        return r.stdout

    @staticmethod
    def _split(line: str):
        """nmcli -t trennt mit ':' und maskiert ':' im Wert als '\\:'."""
        out, cur, esc = [], "", False
        for ch in line:
            if esc:
                cur += ch; esc = False
            elif ch == "\\":
                esc = True
            elif ch == ":":
                out.append(cur); cur = ""
            else:
                cur += ch
        out.append(cur)
        return out

    def saved(self):
        """Gespeicherte WLAN-Verbindungen: [{name, ssid, priority, active}]."""
        out = []
        for line in self._nm("-t", "-f", "NAME,TYPE,AUTOCONNECT-PRIORITY,ACTIVE", "connection", "show").splitlines():
            f = self._split(line)
            if len(f) < 4 or f[1] != "802-11-wireless" or f[0] == HOTSPOT_CONN:
                continue                                    # eigenes WLAN des Pi ist kein "gespeichertes Netz"
            ssid = self._nm("-t", "-g", "802-11-wireless.ssid", "connection", "show", f[0]).strip()
            out.append({"name": f[0], "ssid": ssid or f[0], "priority": int(f[2] or 0), "active": f[3] == "yes"})
        return out

    def visible(self):
        """Sichtbare Netze (ohne neuen Suchlauf): [{ssid, signal, security}], je SSID das staerkste."""
        best = {}
        for line in self._nm("-t", "-f", "SSID,SIGNAL,SECURITY", "device", "wifi", "list", "--rescan", "no").splitlines():
            f = self._split(line)
            if len(f) < 3 or not f[0]:
                continue
            sig = int(f[1] or 0)
            if f[0] not in best or sig > best[f[0]]["signal"]:
                best[f[0]] = {"ssid": f[0], "signal": sig, "security": f[2] or "offen"}
        return sorted(best.values(), key=lambda x: -x["signal"])

    @staticmethod
    def check(ssid: str, password: str):
        if not ssid or len(ssid.encode("utf-8")) > 32:
            return "Netzname: 1 bis 32 Zeichen"
        if password and not 8 <= len(password) <= 63:
            return "WLAN-Passwort: 8 bis 63 Zeichen (leer nur bei offenem Netz)"
        if any(ord(c) < 32 for c in ssid + password):
            return "Steuerzeichen sind nicht erlaubt"
        return None

    def add(self, ssid: str, password: str, preferred: bool = False, hidden: bool = False):
        err = self.check(ssid, password)
        if err:
            raise ValueError(err)
        name = "dx8-" + ssid
        old = next((c for c in self.saved() if c["name"] == name), None)
        if old and old["active"]:
            raise ValueError("Dieses WLAN wird gerade benutzt – ändern, wenn der Pi in einem anderen Netz ist")
        if old:
            self._nm("connection", "delete", name)       # ersetzen statt einzeln aendern
        args = ["802-11-wireless.ssid", ssid, "802-11-wireless.hidden", "yes" if hidden else "no",
                "connection.autoconnect", "yes",
                "connection.autoconnect-priority", str(self.PRIO_PREFERRED if preferred else self.PRIO_NORMAL)]
        args += (["wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", password] if password else [])
        # Liste statt Shell: Netzname und Passwort koennen nie als Befehl ausgefuehrt werden
        self._nm("connection", "add", "type", "wifi", "ifname", "wlan0", "con-name", name, *args)
        return "geändert" if old else "hinzugefügt"

    def delete(self, name: str):
        for c in self.saved():
            if c["name"] == name:
                if c["active"]:
                    raise ValueError("Das gerade benutzte WLAN kann nicht entfernt werden")
                if name.startswith("netplan-"):
                    raise ValueError("Grund-WLAN aus der Einrichtung – hier nicht entfernbar")
                self._nm("connection", "delete", name)
                return
        raise ValueError("Netz nicht gefunden")


class HotspotGuard:
    """
    Eigenes WLAN ("ChargeDeck-Pi"), wenn der Pi kein bekanntes Netz findet – damit er immer erreichbar ist.

    Ablauf (alle 5 s):
      - mit einem bekannten Netz verbunden          -> nichts tun
      - ohne Netz seit wait_s (Standard 60 s)      -> eigenes WLAN einschalten
      - eigenes WLAN an, kein Geraet verbunden, seit retry_s (Standard 180 s)
                                                    -> kurz ausschalten und bekannte Netze versuchen;
                                                       klappt es nicht, sofort wieder einschalten
    Solange ein Geraet im eigenen WLAN haengt, bleibt es an. Das Profil hat autoconnect=no, NetworkManager
    waehlt es also nie von selbst; nur dieser Waechter schaltet es.
    """

    def __init__(self, conf: dict, token: str, runner=None, clock=None):
        import subprocess
        self.conf = conf
        self.password = conf.get("password") or token
        self.run_cmd = runner or (lambda args: subprocess.run(args, capture_output=True, text=True, timeout=90))
        self.clock = clock or time.monotonic
        self.ifname = conf.get("ifname", "wlan0")
        self.no_net_since = None
        self.ap_since = None
        self.ready = False
        self.state = {"mode": "unbekannt", "ssid": None, "clients": 0}

    # -- nmcli
    def _nm(self, *args):
        return self.run_cmd(["nmcli"] + list(args))

    def ensure_profile(self) -> bool:
        """Hotspot-Profil anlegen bzw. erneuern (Name, Passwort, Adresse aus der Konfiguration)."""
        if self.device_state()[1] == HOTSPOT_CONN:
            return True                                                     # laeuft gerade: nicht abbauen
        self._nm("connection", "delete", HOTSPOT_CONN)                     # Fehler egal (gab es noch nicht)
        r = self._nm("connection", "add", "type", "wifi", "ifname", self.ifname, "con-name", HOTSPOT_CONN,
                     "autoconnect", "no", "ssid", self.conf.get("ssid", "ChargeDeck-Pi"),
                     "802-11-wireless.mode", "ap", "802-11-wireless.band", "bg",
                     "ipv4.method", "shared", "ipv4.addresses", "%s/24" % self.conf.get("address", "10.42.0.1"),
                     "ipv6.method", "disabled",
                     "wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.proto", "rsn", "wifi-sec.pairwise", "ccmp",
                     "wifi-sec.group", "ccmp", "wifi-sec.psk", self.password)
        if r.returncode != 0:
            log.warning("Eigenes WLAN: Profil nicht angelegt (%s)", (r.stderr or "").strip())
            return False
        return True

    def device_state(self):
        """(Zustandscode, aktive Verbindung) von wlan0; 100 = verbunden."""
        r = self._nm("-t", "-f", "GENERAL.STATE,GENERAL.CONNECTION", "device", "show", self.ifname)
        code, conn = 0, ""
        for line in (r.stdout or "").splitlines():
            k, _, v = line.partition(":")
            if k == "GENERAL.STATE":
                try:
                    code = int(v.split()[0])
                except (ValueError, IndexError):
                    code = 0
            elif k == "GENERAL.CONNECTION":
                conn = v.strip()
        return code, conn

    def ap_clients(self) -> int:
        """Geraete im eigenen WLAN: Eintraege in der Nachbartabelle von wlan0 (ohne FAILED/INCOMPLETE)."""
        r = self.run_cmd(["ip", "neigh", "show", "dev", self.ifname])
        own = self.conf.get("address", "10.42.0.1")
        net = own.rsplit(".", 1)[0] + "."                 # nur Geraete im eigenen Netz (alte Heimnetz-Eintraege nicht)
        return sum(1 for l in (r.stdout or "").splitlines()
                   if l.startswith(net) and not l.startswith(own + " ") and "FAILED" not in l and "INCOMPLETE" not in l)

    def start_ap(self):
        r = self._nm("connection", "up", HOTSPOT_CONN)
        if r.returncode == 0:
            log.warning("Kein bekanntes WLAN – eigenes WLAN „%s“ eingeschaltet (%s)",
                        self.conf.get("ssid"), self.conf.get("address"))
            self.ap_since = self.clock()
        else:
            log.warning("Eigenes WLAN liess sich nicht einschalten: %s", (r.stderr or "").strip())

    def known_profiles(self):
        """Gespeicherte WLAN-Profile ausser dem eigenen: [(Name, SSID, Vorrang)], hoechster Vorrang zuerst."""
        r = self._nm("-t", "-f", "NAME,TYPE,AUTOCONNECT-PRIORITY", "connection", "show")
        out = []
        for line in (r.stdout or "").splitlines():
            f = WifiManager._split(line)
            if len(f) < 3 or f[1] != "802-11-wireless" or f[0] == HOTSPOT_CONN:
                continue
            ssid = (self._nm("-t", "-g", "802-11-wireless.ssid", "connection", "show", f[0]).stdout or "").strip()
            try:
                prio = int(f[2] or 0)
            except ValueError:
                prio = 0
            out.append((f[0], ssid or f[0], prio))
        return sorted(out, key=lambda x: -x[2])

    def visible_ssids(self):
        r = self._nm("-t", "-f", "SSID", "device", "wifi", "list", "ifname", self.ifname, "--rescan", "yes")
        return {WifiManager._split(l)[0] for l in (r.stdout or "").splitlines() if l.strip()}

    def try_known(self) -> bool:
        """
        Eigenes WLAN aus und gezielt ein gespeichertes Netz verbinden, das in Reichweite ist (hoechster Vorrang
        zuerst). Bewusst NICHT "nmcli device connect": das darf NetworkManager selbst waehlen und nimmt dann
        unter Umstaenden wieder das eigene WLAN. True = verbunden, sonst eigenes WLAN wieder an.
        """
        log.info("Eigenes WLAN: suche bekannte Netze …")
        self._nm("connection", "down", HOTSPOT_CONN)
        time.sleep(2) if self.clock is time.monotonic else None     # Funk freigeben (nicht in Tests)
        seen = self.visible_ssids()
        for name, ssid, _ in self.known_profiles():
            if ssid not in seen:
                continue
            r = self._nm("--wait", "30", "connection", "up", name)
            code, conn = self.device_state()
            if r.returncode == 0 and code == 100 and conn == name:
                log.info("Wieder im WLAN: %s", ssid)
                self.ap_since = None
                self.no_net_since = None
                return True
        self.start_ap()
        return False

    def step(self):
        """Ein Durchgang der Ueberwachung (alle 5 s aus run(); in den Tests einzeln)."""
        if not 8 <= len(self.password) <= 63:
            if self.ready is not None:
                log.warning("Eigenes WLAN aus: Passwort muss 8 bis 63 Zeichen haben")
                self.ready = None                       # nur einmal melden
            return
        if not self.ready:
            self.ready = self.ensure_profile()
            if not self.ready:
                return
        now = self.clock()
        code, conn = self.device_state()
        if conn == HOTSPOT_CONN:
            n = self.ap_clients()
            self.state = {"mode": "eigenes", "ssid": self.conf.get("ssid"), "clients": n}
            if self.ap_since is None:
                self.ap_since = now
            if n == 0 and now - self.ap_since >= float(self.conf.get("retry_s", 180)):
                self.try_known()
            return
        if code == 100 and conn:
            self.state = {"mode": "verbunden", "ssid": conn, "clients": 0}
            self.no_net_since = None
            self.ap_since = None
            return
        self.state = {"mode": "sucht", "ssid": None, "clients": 0}
        if self.no_net_since is None:
            self.no_net_since = now
        elif now - self.no_net_since >= float(self.conf.get("wait_s", 60)):
            self.start_ap()
            self.no_net_since = None

    def run(self):
        while True:
            try:
                self.step()
            except Exception as e:                 # Waechter darf die Bruecke nie stoppen
                log.warning("Eigenes WLAN: %s", e)
            time.sleep(5)


def start_web(bridge, host: str, port: int):
    """
    Kleiner HTTP-Server:
      GET  /            Statusseite (status.html neben diesem Skript), GET /api/status = JSON (nur lesend)
      GET  /wlan        Seite "WLAN-Netze" (wlan.html)
      POST /api/wlan    JSON {"token", "action": "list"|"add"|"delete", ...} – nur mit dem Passwort der Bruecke
    Den Lader beruehrt keine dieser Seiten.
    """
    import hmac
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    here = os.path.dirname(os.path.abspath(__file__))
    page_path = os.path.join(here, "status.html")
    wlan_path = os.path.join(here, "wlan.html")

    def wlan_api(req: dict):
        """Antwort (Code, dict). Passwort pruefen, dann Aktion ausfuehren."""
        token = str(bridge.conf.get("token", ""))
        if not token or not hmac.compare_digest(str(req.get("token", "")), token):
            time.sleep(1)                                   # Raten bremsen
            return 403, {"error": "Passwort der Brücke falsch"}
        act = req.get("action")
        w = bridge.wifi
        try:
            if act == "list":
                return 200, {"saved": w.saved(), "visible": w.visible()}
            if act == "add":
                ssid = str(req.get("ssid", "")).strip()
                what = w.add(ssid, str(req.get("password", "")), bool(req.get("preferred")), bool(req.get("hidden")))
                log.info("WLAN %s: %s", what, ssid)
                return 200, {"ok": True, "message": "„%s“ %s" % (ssid, what), "saved": w.saved()}
            if act == "delete":
                name = str(req.get("name", ""))
                w.delete(name)
                log.info("WLAN entfernt: %s", name)
                return 200, {"ok": True, "message": "entfernt", "saved": w.saved()}
            return 400, {"error": "unbekannte Aktion"}
        except ValueError as e:
            return 400, {"error": str(e)}
        except Exception as e:                              # z. B. nmcli fehlt (Tests am PC)
            return 500, {"error": "WLAN-Verwaltung nicht verfügbar: %s" % e}

    class Handler(BaseHTTPRequestHandler):
        def _send(self, code, body: bytes, ctype: str):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/api/status":
                self._send(200, json.dumps(bridge.status.snapshot(), ensure_ascii=False).encode(), "application/json; charset=utf-8")
            elif path in ("/", "/index.html", "/wlan"):
                p = wlan_path if path == "/wlan" else page_path
                page = _read_file(p)
                if page is None:
                    self._send(500, (os.path.basename(p) + " fehlt").encode(), "text/plain; charset=utf-8")
                else:
                    self._send(200, page.encode(), "text/html; charset=utf-8")
            else:
                self._send(404, b"nicht gefunden", "text/plain; charset=utf-8")

        def do_POST(self):
            if self.path.split("?")[0] != "/api/wlan":
                self._send(405, b"nur lesen", "text/plain; charset=utf-8")
                return
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > 4096:
                self._send(400, b'{"error":"Anfrage zu gross"}', "application/json; charset=utf-8")
                return
            try:
                req = json.loads(self.rfile.read(n).decode("utf-8"))
                if not isinstance(req, dict):
                    raise ValueError
            except ValueError:
                self._send(400, b'{"error":"ungueltige Anfrage"}', "application/json; charset=utf-8")
                return
            code, body = wlan_api(req)
            self._send(code, json.dumps(body, ensure_ascii=False).encode(), "application/json; charset=utf-8")

        def do_PUT(self):
            self._send(405, b"nicht erlaubt", "text/plain; charset=utf-8")

        do_DELETE = do_PATCH = do_PUT

        def log_message(self, *args):
            pass

    srv = ThreadingHTTPServer((host, port), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    conf = sys.argv[1] if len(sys.argv) > 1 else "/etc/dx8bridge.json"
    Bridge(conf).serve()


if __name__ == "__main__":
    main()
