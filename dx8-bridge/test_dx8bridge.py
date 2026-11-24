"""Tests fuer die DX8-Bruecke ohne Pi und ohne Lader: nachgebauter Lader hinter echtem TCP. Aufruf: python -m unittest"""
import json
import os
import socket
import tempfile
import threading
import unittest

import dx8bridge as B


class FakeDev:
    """Minimaler DX8: Input-Register je Kanal, Steuerregister mit Order-Lock 0x55AA."""

    def __init__(self):
        self.input = {}
        self.ctrl = [0] * 7
        self.orders = []
        self.path = "fake"
        self.lock = threading.Lock()

    def set_channel(self, k, run, uin_mv):
        self.input[(k + 1) * 0x100 + 5] = uin_mv
        self.input[(k + 1) * 0x100 + 55] = run

    def xfer(self, frame, timeout=1.2):
        with self.lock:
            fn = frame[2]
            addr = (frame[3] << 8) | frame[4]
            if fn in (3, 4):
                qty = frame[6]
                vals = []
                for i in range(qty):
                    a = addr + i
                    if fn == 4 and a < 0x300:
                        vals.append(self.input.get(a, 0))
                    elif fn == 3 and 0x8000 <= a <= 0x8006:
                        vals.append(self.ctrl[a - 0x8000])
                    else:
                        return bytes([3, 0x30, fn | 0x80, 2]).ljust(64, b"\0")
                out = bytearray([2 + 2 * qty + 2, 0x30, fn, 2 * qty])
                for v in vals:
                    out += bytes([v >> 8, v & 0xFF])
                return bytes(out).ljust(64, b"\0")
            if fn == 0x10:
                n = frame[6]
                vals = [(frame[8 + 2 * i] << 8) | frame[9 + 2 * i] for i in range(n)]
                if not all(0x8000 <= addr + i <= 0x8006 for i in range(n)):
                    return bytes([3, 0x30, 0x90, 2]).ljust(64, b"\0")
                for i, v in enumerate(vals):
                    self.ctrl[addr + i - 0x8000] = v
                if addr <= 0x8004 < addr + n and self.ctrl[3] == B.UNLOCK:
                    self.orders.append((self.ctrl[2], self.ctrl[4]))
                return bytes([7, 0x30, 0x10, addr >> 8, addr & 0xFF, 0, n]).ljust(64, b"\0")
            return None

    def close(self):
        pass


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.conf = os.path.join(self.tmp, "conf.json")
        with open(self.conf, "w") as f:
            f.write('{"token": "geheim", "guard_enabled": true, "guard_limit_v": 25.2}')
        self.fake = FakeDev()
        self.bridge = B.Bridge(self.conf, dev_factory=lambda: self.fake)
        ready = threading.Event()
        self.bridge.guard_loop = lambda interval=1.0: None     # Schutz in den Tests einzeln pruefen
        threading.Thread(target=self.bridge.serve, kwargs={"host": "127.0.0.1", "port": 0, "ready": ready.set}, daemon=True).start()
        ready.wait(5)
        self.port = self.bridge.port

    def connect(self, token="geheim"):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=3)
        s.sendall(("DX8BRIDGE 1 %s\n" % token).encode())
        reply = b""
        while not reply.endswith(b"\n"):
            chunk = s.recv(1)
            if not chunk:
                break
            reply += chunk
        return s, reply.decode().strip()

    @staticmethod
    def recv64(s):
        buf = b""
        while len(buf) < 64:
            chunk = s.recv(64 - len(buf))
            if not chunk:
                break
            buf += chunk
        return buf

    def test_login_ok_and_wrong_token(self):
        s, r = self.connect()
        self.assertEqual("OK 1", r)
        s.close()
        s2, r2 = self.connect("falsch")
        self.assertTrue(r2.startswith("ERR"))
        s2.close()

    def test_read_passthrough(self):
        self.fake.set_channel(0, 7, 23900)
        s, _ = self.connect()
        s.sendall(B.frame_read(4, 0x105, 1))
        resp = self.recv64(s)
        self.assertEqual(0x30, resp[1])
        self.assertEqual([23900], B.regs_of(resp, 1))
        s.close()

    def test_write_and_order(self):
        s, _ = self.connect()
        s.sendall(B.frame_write(0x8002, [1, B.UNLOCK, 0]))
        self.assertEqual(0x10, self.recv64(s)[2])
        self.assertEqual([(1, 0)], self.fake.orders)          # STOP auf Kanal 2
        s.close()

    def test_guard_command_saved(self):
        s, _ = self.connect()
        s.sendall(bytes([0xFF, ord("G"), 1, 25000 >> 8, 25000 & 0xFF]).ljust(64, b"\0"))
        r = self.recv64(s)
        self.assertEqual([0xFF, ord("G"), 1], list(r[:3]))
        self.assertEqual(25000, (r[3] << 8) | r[4])
        with open(self.conf) as f:
            self.assertIn("25.0", f.read())
        s.sendall(bytes([0xFF, ord("P")]).ljust(64, b"\0"))
        self.assertEqual([0xFF, ord("P"), 1], list(self.recv64(s)[:3]))
        s.close()

    def test_guard_stops_running_channel(self):
        self.fake.set_channel(0, 7, 25300)     # laeuft, Eingang zu hoch
        self.fake.set_channel(1, 0, 25300)     # steht: nicht stoppen
        self.assertEqual([0], self.bridge.guard.check(self.fake))
        self.assertEqual([(0, 0)], self.fake.orders)

    def test_guard_not_during_dialog_or_below_limit_or_off(self):
        self.fake.set_channel(0, 12, 26000)    # Rueckfrage offen
        self.fake.set_channel(1, 7, 24000)     # unter der Grenze
        self.assertEqual([], self.bridge.guard.check(self.fake))
        self.fake.set_channel(1, 7, 26000)
        self.bridge.guard.set(False, 25.2)
        self.assertEqual([], self.bridge.guard.check(self.fake))
        self.assertEqual([], self.fake.orders)

    def test_matching(self):
        req = B.frame_read(4, 0x100, 30)
        self.assertTrue(B.response_matches(req, bytes([62, 0x30, 4, 60])))
        self.assertFalse(B.response_matches(req, bytes([62, 0x30, 4, 2])))    # andere Anzahl
        self.assertTrue(B.response_matches(req, bytes([3, 0x30, 0x84, 2])))   # Exception
        self.assertFalse(B.response_matches(req, bytes([8, 0x10, 4, 60])))    # LogView-Frame
        self.assertTrue(B.response_matches(B.frame_write(0x8003, [0]), bytes([7, 0x30, 0x90, 4])))


def sample_channel(fake, k, run=7, cells_mv=(4150, 4160, 4140), current=1250, uout=12450):
    """Kanal k im FakeDev mit typischen Werten fuellen (Registerlayout wie ChannelData.parse)."""
    base = (k + 1) * 0x100
    vals = {2: 15562 & 0xFFFF, 3: 0, 4: current & 0xFFFF, 5: 24100, 6: uout, 7: 1830, 8: 0,
            9: 312, 10: (-15) & 0xFFFF, 51: 123, 52: 456, 55: run, 56: 0x0D05}
    for i, mv in enumerate(cells_mv):
        vals[11 + i] = mv
        vals[35 + i] = 31 + i
    for off in range(60):
        fake.input[base + off] = vals.get(off, 0)


class FakeBroker:
    """Nimmt eine MQTT-Verbindung an und zeichnet Pakete auf; kann PUBLISH an den Client schicken."""

    def __init__(self, rc=0):
        self.srv = socket.socket()
        self.srv.bind(("127.0.0.1", 0))
        self.srv.listen(1)
        self.port = self.srv.getsockname()[1]
        self.rc = rc
        self.packets = []
        self.conn = None
        self.connected = threading.Event()
        threading.Thread(target=self._serve, daemon=True).start()

    def _recv(self, n):
        buf = b""
        while len(buf) < n:
            c = self.conn.recv(n - len(buf))
            if not c:
                raise OSError("zu")
            buf += c
        return buf

    def _serve(self):
        try:
            self.conn, _ = self.srv.accept()
            while True:
                head = self._recv(1)[0]
                n, mul = 0, 1
                while True:
                    d = self._recv(1)[0]
                    n += (d & 0x7F) * mul
                    mul *= 128
                    if not d & 0x80:
                        break
                body = self._recv(n) if n else b""
                self.packets.append((head, body))
                if head == 0x10:
                    self.conn.sendall(bytes([0x20, 2, 0, self.rc]))
                    self.connected.set()
                elif head == 0x82:
                    self.conn.sendall(bytes([0x90, 3]) + body[:2] + b"\0")
        except OSError:
            pass

    def publishes(self):
        out = []
        for head, body in list(self.packets):
            if head >> 4 == 3:
                tl = (body[0] << 8) | body[1]
                out.append((body[2:2 + tl].decode(), body[2 + tl:], bool(head & 1)))
        return out

    def send_publish(self, topic, payload):
        t = topic.encode()
        self.conn.sendall(B.mqtt_packet(0x30, bytes([len(t) >> 8, len(t) & 0xFF]) + t + payload))

    def close(self):
        for s in (self.conn, self.srv):
            try:
                s.close()
            except (OSError, AttributeError):
                pass


def wait_for(cond, timeout=3.0):
    import time
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return True
        time.sleep(0.01)
    return False


class HomeAssistantTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeDev()
        sample_channel(self.fake, 0)
        sample_channel(self.fake, 1, run=0, cells_mv=(), current=0, uout=0)
        self.broker = FakeBroker()
        self.conf = dict(B.MQTT_DEFAULT, host="127.0.0.1", port=self.broker.port, user="ha", password="pw")
        self.dropped = 0

    def tearDown(self):
        self.broker.close()

    def publisher(self, conf=None):
        conf = conf or self.conf

        def drop():
            self.dropped += 1
        return B.HaPublisher(conf, lambda: self.fake, drop,
                             connect=lambda will: B.MiniMqtt("127.0.0.1", conf["port"], "dx8bridge",
                                                             conf["user"], conf["password"], will))

    def test_channel_values(self):
        g = [self.fake.input[0x100 + i] for i in range(60)]
        v = B.channel_values(g)
        self.assertEqual("Laden", v["status"])
        self.assertTrue(v["active"])
        self.assertEqual(12.5, v["current_a"])
        self.assertEqual(155.62, v["power_w"])
        self.assertEqual(12.45, v["output_v"])
        self.assertEqual(24.1, v["input_v"])
        self.assertEqual(1830, v["capacity_mah"])
        self.assertEqual(-1.5, v["temp_ext_c"])
        self.assertEqual(3, v["cell_count"])
        self.assertEqual(20, v["cell_delta_mv"])
        self.assertEqual(4.16, v["cell2_v"])
        self.assertIsNone(v["cell4_v"])
        self.assertEqual(3.2, v["cell2_ir_mohm"])
        self.assertEqual(12.3, v["ir_total_mohm"])
        self.assertEqual(0, v["error"])                       # Fehlercode nur bei Status 41
        g[55] = 41
        self.assertEqual(1305, B.channel_values(g)["error"])
        g2 = [0] * 60
        g2[6] = 40000
        self.assertEqual("Akku n. verbunden", B.channel_values(g2)["status"])

    def test_remaining_length(self):
        self.assertEqual(b"\x7f", B._mq_len(127))
        self.assertEqual(b"\x80\x01", B._mq_len(128))
        self.assertEqual(b"\xff\x7f", B._mq_len(16383))

    def test_connect_discovery_state_and_stop(self):
        p = self.publisher()
        p.start_session()
        threading.Thread(target=p._reader, args=(p.client,), daemon=True).start()
        head, body = self.broker.packets[0]
        self.assertEqual(0x10, head)
        self.assertIn(b"dx8/status", body)                    # Last Will
        self.assertIn(b"offline", body)
        self.assertEqual(0x02 | 0x04 | 0x20 | 0x80 | 0x40, body[7])   # clean, will, will retain, user, password
        self.assertTrue(wait_for(lambda: any(h == 0x82 for h, _ in self.broker.packets)))
        pubs = self.broker.publishes()
        configs = [x for x in pubs if x[0].startswith("homeassistant/")]
        self.assertEqual(2 * (len(B.HA_SENSORS) + 2), len(configs))
        self.assertTrue(all(r for _, _, r in configs))           # retained
        cfg = dict((t, json.loads(pl)) for t, pl, _ in configs)
        volt = cfg["homeassistant/sensor/dx8/dx8_ch1_output_v/config"]
        self.assertEqual("dx8/ch1/state", volt["state_topic"])
        self.assertEqual("V", volt["unit_of_measurement"])
        self.assertEqual("dx8/status", volt["availability_topic"])
        btn = cfg["homeassistant/button/dx8/dx8_ch2_stop/config"]
        self.assertEqual("dx8/ch2/stop", btn["command_topic"])

        self.assertTrue(p.poll_once())
        self.assertTrue(wait_for(lambda: any(t == "dx8/status" for t, _, _ in self.broker.publishes())))
        pubs = self.broker.publishes()
        state = json.loads([pl for t, pl, _ in pubs if t == "dx8/ch1/state"][-1])
        self.assertEqual("Laden", state["status"])
        self.assertEqual(12.5, state["current_a"])
        self.assertEqual((b"online", True), [(pl, r) for t, pl, r in pubs if t == "dx8/status"][-1])

        self.broker.send_publish("dx8/ch1/stop", b"PRESS")
        self.assertTrue(wait_for(lambda: self.fake.orders == [(0, 0)]))
        p.client.close()

    def test_stop_refused_during_dialog(self):
        p = self.publisher()
        self.fake.input[0x200 + 55] = 12
        self.assertFalse(p.stop(1))
        self.assertEqual([], self.fake.orders)
        self.fake.input[0x200 + 55] = 13
        self.assertTrue(p.stop(1))
        self.assertEqual([(1, 0)], self.fake.orders)
        p.on_message("dx8/ch3/stop", b"PRESS")                 # unbekannter Kanal: nichts
        self.assertEqual(1, len(self.fake.orders))

    def test_offline_when_charger_unreadable(self):
        p = self.publisher()
        p.start_session()
        del self.fake.input[0x100 + 5]                          # Lesefehler simulieren

        def broken(frame, timeout=1.2):
            return None
        self.fake.xfer = broken
        self.assertFalse(p.poll_once())
        self.assertTrue(wait_for(lambda: (b"offline", True) in [(pl, r) for t, pl, r in self.broker.publishes()
                                                                   if t == "dx8/status"]))
        p.client.close()

    def test_without_stop_button(self):
        conf = dict(self.conf, stop_button=False)
        p = self.publisher(conf)
        p.start_session()
        self.assertTrue(wait_for(lambda: len(self.broker.publishes()) >= 2 * (len(B.HA_SENSORS) + 2)))
        pubs = dict((t, pl) for t, pl, _ in self.broker.publishes())
        self.assertEqual(b"", pubs["homeassistant/button/dx8/dx8_ch1_stop/config"])   # Knopf entfernt
        self.assertFalse(any(h == 0x82 for h, _ in self.broker.packets))
        p.on_message("dx8/ch1/stop", b"PRESS")
        self.assertEqual([], self.fake.orders)
        p.client.close()

    def test_login_rejected(self):
        self.broker.rc = 5
        with self.assertRaises(OSError) as e:
            self.publisher().start_session()
        self.assertIn("Benutzer/Passwort", str(e.exception))

    def test_bridge_merges_mqtt_defaults(self):
        tmp = tempfile.mkdtemp()
        path = os.path.join(tmp, "c.json")
        with open(path, "w") as f:
            f.write('{"token": "x", "mqtt": {"host": "ha.local"}}')
        b = B.Bridge(path, dev_factory=lambda: self.fake)
        self.assertEqual("ha.local", b.conf["mqtt"]["host"])
        self.assertEqual(1883, b.conf["mqtt"]["port"])
        self.assertTrue(b.conf["mqtt"]["stop_button"])
        self.assertEqual("", B.Bridge(os.path.join(tmp, "neu.json")).conf["mqtt"]["host"])   # ohne MQTT


if __name__ == "__main__":
    unittest.main()
