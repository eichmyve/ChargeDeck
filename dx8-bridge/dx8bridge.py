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
CONF_DEFAULT = {"port": 5751, "token": "", "guard_enabled": True, "guard_limit_v": 25.2, "mqtt": dict(MQTT_DEFAULT)}
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
            40: "Fertig", 41: "Fehler", 43: "RI-Messung"}
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

    def serve(self, host: str = "0.0.0.0", port: int = None, ready=None):
        port = int(self.conf.get("port", 5751)) if port is None else port    # 0 = freier Port (Tests)
        threading.Thread(target=self.guard_loop, daemon=True).start()
        if self.conf["mqtt"].get("host"):
            self.ha = HaPublisher(self.conf["mqtt"], self.device, self.drop_device)
            threading.Thread(target=self.ha.run, daemon=True).start()
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
        log.info("App verbunden: %s (%d aktiv)", addr[0], self.clients)
        try:
            self.serve_client(conn)
        except OSError as e:
            log.info("App getrennt: %s", e)
        finally:
            conn.close()
            with self.clients_lock:
                self.clients -= 1
            log.info("App getrennt: %s (%d aktiv)", addr[0], self.clients)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    conf = sys.argv[1] if len(sys.argv) > 1 else "/etc/dx8bridge.json"
    Bridge(conf).serve()


if __name__ == "__main__":
    main()
