"""Protocol tests without the optional Qt GUI dependency."""
import importlib.util
import json
import socket
import sys
import tempfile
import threading
import time
import types
import unittest
from pathlib import Path
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / "PC" / "satlink_share_pc.py"

for module_name in ("PySide6", "PySide6.QtCore", "PySide6.QtGui", "PySide6.QtWidgets"):
    sys.modules[module_name] = types.ModuleType(module_name)
core, gui, widgets = (sys.modules[f"PySide6.{name}"] for name in ("QtCore", "QtGui", "QtWidgets"))
core.Qt = type("Qt", (), {})
core.QObject = object
core.Signal = lambda *args: None
core.QTimer = object
core.QSize = object
core.QUrl = object
gui.QIcon = object
gui.QAction = object
gui.QPixmap = object
gui.QDesktopServices = object
for name in ("QApplication QMainWindow QWidget QVBoxLayout QHBoxLayout QLabel QPushButton QFileDialog "
             "QTextEdit QLineEdit QListWidget QListWidgetItem QMessageBox QProgressBar QFrame QCheckBox "
             "QSystemTrayIcon QMenu QTableWidget QTableWidgetItem QAbstractItemView QHeaderView QStackedWidget").split():
    setattr(widgets, name, type(name, (), {}))

spec = importlib.util.spec_from_file_location("satlink", SOURCE)
satlink = importlib.util.module_from_spec(spec)
spec.loader.exec_module(satlink)
REAL_DOWNLOADS_RECEIVED_DIR = satlink.downloads_received_dir

class Event:
    def __init__(self): self.values = []
    def emit(self, *values): self.values.append(values[0] if len(values) == 1 else values)

class Bus:
    def __init__(self):
        self.log = Event(); self.progress = Event(); self.devices = Event(); self.receiving_state = Event(); self.transfer = Event()

class ProtocolTests(unittest.TestCase):
    def test_drive_disconnect_rejects_new_transfer(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            drive = root / "external-drive"
            drive.mkdir()
            with patch.object(satlink, "settings_path", return_value=root / "settings.json"):
                satlink.set_receive_base(drive)
                with socket.socket() as probe:
                    probe.bind(("127.0.0.1", 0))
                    satlink.ANDROID_TCP_PORT = probe.getsockname()[1]
                bus = Bus()
                receiver = satlink.ReceiverServer(bus)
                receiver.start()
                try:
                    time.sleep(0.15)
                    drive.rename(root / "removed-drive")
                    with socket.create_connection(("127.0.0.1", satlink.ANDROID_TCP_PORT), timeout=2) as s:
                        satlink.write_utf(s, satlink.ANDROID_PROTOCOL)
                        satlink.write_utf(s, "Android")
                        satlink.write_int(s, 1)
                        satlink.write_long(s, 4)
                        self.assertFalse(satlink.read_bool(s))
                    self.assertTrue(any("مكان الحفظ غير متوفر" in msg for msg in bus.log.values))
                finally:
                    receiver.stop()

    def test_receive_destination_persists_and_rejects_missing_drive(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            config = root / "settings" / "settings.json"
            drive = root / "D-drive"
            drive.mkdir()
            with patch.object(satlink, "settings_path", return_value=config), patch.object(satlink, "downloads_received_dir", REAL_DOWNLOADS_RECEIVED_DIR):
                selected = satlink.set_receive_base(drive)
                self.assertEqual(selected, drive / "SatLink Share PRO" / "Received")
                self.assertEqual(satlink.downloads_received_dir(), selected)
                self.assertEqual(satlink.receive_base(), drive)
                self.assertEqual(satlink.receive_destination_path(), selected)
                self.assertEqual(json.loads(config.read_text(encoding="utf-8"))["receive_base"], str(drive))
                drive.rename(root / "disconnected-drive")
                with self.assertRaises(OSError):
                    satlink.downloads_received_dir()
                self.assertFalse(selected.exists())

    def test_qt5_import_for_windows_32_bit(self):
        original = {name: sys.modules.pop(name) for name in
                    ("PySide6", "PySide6.QtCore", "PySide6.QtGui", "PySide6.QtWidgets")}
        try:
            for name in ("PyQt5", "PyQt5.QtCore", "PyQt5.QtGui", "PyQt5.QtWidgets"):
                sys.modules[name] = types.ModuleType(name)
            core5, gui5, widgets5 = (sys.modules[f"PyQt5.{name}"] for name in ("QtCore", "QtGui", "QtWidgets"))
            core5.Qt = core.Qt; core5.QObject = object; core5.pyqtSignal = core.Signal; core5.QTimer = object
            core5.QSize = object; core5.QUrl = object
            gui5.QIcon = object; gui5.QPixmap = object; gui5.QDesktopServices = object
            for name in ("QApplication QMainWindow QWidget QVBoxLayout QHBoxLayout QLabel QPushButton QFileDialog "
                         "QTextEdit QLineEdit QListWidget QListWidgetItem QMessageBox QProgressBar QFrame QCheckBox "
                         "QSystemTrayIcon QMenu QTableWidget QTableWidgetItem QAbstractItemView QHeaderView QAction QStackedWidget").split():
                setattr(widgets5, name, type(name, (), {}))
            spec5 = importlib.util.spec_from_file_location("satlink_qt5", SOURCE)
            satlink5 = importlib.util.module_from_spec(spec5)
            spec5.loader.exec_module(satlink5)
            self.assertEqual(satlink5.QT_BINDING, "PyQt5")
        finally:
            for name in ("PyQt5", "PyQt5.QtCore", "PyQt5.QtGui", "PyQt5.QtWidgets"):
                sys.modules.pop(name, None)
            sys.modules.update(original)

    def test_transfer_display_for_large_file(self):
        bus = Bus()
        size = 41 * 1024**3
        satlink.transfer_update(bus, "إرسال", "film.mkv", 40 * 1024**3, size,
                                time.monotonic() - 10, "جاري", 40 * 1024**3)
        direction, filename, percent, speed, eta, state = bus.transfer.values[-1]
        self.assertEqual((direction, filename, state), ("إرسال", "film.mkv", "جاري"))
        self.assertEqual(percent, 97)
        self.assertEqual(speed, "—")
        self.assertEqual(eta, "—")

    def test_windows_startup_toggle(self):
        original_platform = satlink.sys.platform
        registry = {}
        fake = types.ModuleType("winreg")
        fake.HKEY_CURRENT_USER = object()
        fake.REG_SZ = 1
        class Key:
            def __enter__(self): return self
            def __exit__(self, *args): return False
        fake.CreateKey = lambda *_: Key()
        fake.OpenKey = lambda *_: Key()
        fake.SetValueEx = lambda _, name, __, ___, value: registry.__setitem__(name, value)
        def query(_, name):
            if name not in registry: raise FileNotFoundError(name)
            return registry[name], fake.REG_SZ
        fake.QueryValueEx = query
        fake.DeleteValue = lambda _, name: registry.pop(name)
        sys.modules["winreg"] = fake
        try:
            satlink.sys.platform = "win32"
            self.assertFalse(satlink.startup_enabled())
            satlink.set_startup_enabled(True)
            self.assertTrue(satlink.startup_enabled())
            self.assertIn("satlink_share_pc.py", registry[satlink.STARTUP_NAME])
            self.assertTrue(registry[satlink.STARTUP_NAME].endswith(" --tray"))
            registry[satlink.STARTUP_NAME] = satlink.legacy_startup_command()
            self.assertTrue(satlink.startup_enabled())
            self.assertEqual(registry[satlink.STARTUP_NAME], satlink.startup_command())
            registry[satlink.STARTUP_NAME] = r'"C:\Old\SatLink Share PRO PC.exe"'
            self.assertTrue(satlink.startup_enabled())
            self.assertEqual(registry[satlink.STARTUP_NAME], satlink.startup_command())
            satlink.set_startup_enabled(False)
            self.assertFalse(satlink.startup_enabled())
        finally:
            satlink.sys.platform = original_platform
            del sys.modules["winreg"]

    def test_java_modified_utf(self):
        left, right = socket.socketpair()
        with left, right:
            satlink.write_utf(left, "عربي\0😀")
            self.assertEqual(satlink.read_utf(right), "عربي\0😀")
        self.assertEqual(satlink.safe_filename("..\\CON.txt"), "_CON.txt")

    def test_folder_paths_reject_traversal(self):
        for name in ("../escape", "/absolute/file", "C:/file", "folder/../escape", "folder//file", "folder/./file"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                satlink.safe_relative_path(name)

    def test_nested_folder_transfer(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root / "صور وفيديو"
            (folder / "رحلة" / "اليوم الأول").mkdir(parents=True)
            (folder / "رحلة" / "اليوم الأول" / "صورة.jpg").write_bytes(b"PHOTO" * 1000)
            (folder / "رحلة" / "فيديو.mp4").write_bytes(b"VIDEO" * 10000)
            entries = satlink.selected_entries([folder])
            self.assertEqual(len(entries), 2)
            self.assertTrue(all(name.startswith("صور وفيديو/") for _, name, _ in entries))
            received = root / "out"
            received.mkdir()
            satlink.downloads_received_dir = lambda: received
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                satlink.ANDROID_TCP_PORT = probe.getsockname()[1]
            receiver = satlink.ReceiverServer(Bus())
            receiver.start()
            try:
                time.sleep(0.15)
                sender_bus = Bus()
                satlink.Sender(sender_bus)._send_android_protocol("127.0.0.1", satlink.ANDROID_TCP_PORT, [folder])
                destination = received / "صور وفيديو" / "رحلة"
                self._wait_for(lambda: (destination / "فيديو.mp4").exists() and (destination / "اليوم الأول" / "صورة.jpg").exists())
                self.assertEqual((destination / "فيديو.mp4").read_bytes(), (folder / "رحلة" / "فيديو.mp4").read_bytes())
                self.assertEqual((destination / "اليوم الأول" / "صورة.jpg").read_bytes(), (folder / "رحلة" / "اليوم الأول" / "صورة.jpg").read_bytes())
                self.assertFalse(any("فشل الإرسال" in msg for msg in sender_bus.log.values))
            finally:
                receiver.stop()

    def test_multi_file_transfer_and_resume(self):
        with tempfile.TemporaryDirectory() as temp:
            save = Path(temp)
            satlink.downloads_received_dir = lambda: save
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                satlink.ANDROID_TCP_PORT = probe.getsockname()[1]
            receiver_bus, sender_bus = Bus(), Bus()
            receiver = satlink.ReceiverServer(receiver_bus)
            receiver.start()
            try:
                time.sleep(0.15)
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                    udp.settimeout(2)
                    udp.sendto(satlink.DISCOVER.encode(), ("127.0.0.1", satlink.ANDROID_UDP_PORT))
                    response, _ = udp.recvfrom(2048)
                    self.assertTrue(response.decode().startswith(satlink.RESPONSE_PREFIX))
                first = save / "input-one.bin"
                second = save / "input-two.bin"
                first.write_bytes(b"A" * (satlink.BUFFER + 21))
                second.write_bytes(b"B" * 1307)
                sender = satlink.Sender(sender_bus)
                sender._send_android_protocol("127.0.0.1", satlink.ANDROID_TCP_PORT, [str(first), str(second)])
                self._wait_for(lambda: (save / "input-one_1.bin").exists() and (save / "input-two_1.bin").exists())
                self.assertEqual((save / "input-one_1.bin").read_bytes(), first.read_bytes())
                self.assertEqual((save / "input-two_1.bin").read_bytes(), second.read_bytes())

                payload = b"Z" * (satlink.BUFFER + 59)
                source = save / "resume.bin"
                source.write_bytes(payload)
                with socket.create_connection(("127.0.0.1", satlink.ANDROID_TCP_PORT)) as s:
                    satlink.write_utf(s, satlink.ANDROID_PROTOCOL)
                    satlink.write_utf(s, satlink.pc_name())
                    satlink.write_int(s, 1)
                    satlink.write_long(s, len(payload))
                    self.assertTrue(satlink.read_bool(s))
                    satlink.write_utf(s, source.name)
                    satlink.write_long(s, len(payload))
                    self.assertEqual(satlink.read_long(s), 0)
                    s.sendall(payload[:3000])
                part = satlink.partial_path(save, satlink.pc_name(), source.name, len(payload))
                self._wait_for(lambda: part.exists() and part.stat().st_size == 3000)
                sender._send_android_protocol("127.0.0.1", satlink.ANDROID_TCP_PORT, [str(source)])
                self._wait_for(lambda: (save / "resume_1.bin").exists())
                self.assertEqual((save / "resume_1.bin").read_bytes(), payload)
                self.assertFalse(part.exists())

                # Exercise the >40 GiB 64-bit length and offset path via a sparse file.
                huge_size = 40 * 1024**3 + 17
                huge = save / "forty_gib.bin"
                with open(huge, "wb") as f:
                    f.seek(huge_size - 1)
                    f.write(b"Q")
                huge_part = satlink.partial_path(save, satlink.pc_name(), huge.name, huge_size)
                with open(huge_part, "wb") as f:
                    f.seek(huge_size - 4096 - 1)
                    f.write(b"\0")
                sender._send_android_protocol("127.0.0.1", satlink.ANDROID_TCP_PORT, [str(huge)])
                completed = save / "forty_gib_1.bin"
                self._wait_for(completed.exists)
                self.assertEqual(completed.stat().st_size, huge_size)
                with open(completed, "rb") as f:
                    f.seek(huge_size - 1)
                    self.assertEqual(f.read(1), b"Q")
                self.assertFalse(any("فشل الإرسال" in msg for msg in sender_bus.log.values))
                self.assertTrue(any(x[0] == "إرسال" and x[1] == huge.name and x[-1] == "مكتمل" for x in sender_bus.transfer.values))
            finally:
                receiver.stop()

    def _wait_for(self, predicate):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if predicate(): return
            time.sleep(0.02)
        self.fail("Timed out waiting for transfer")

if __name__ == "__main__": unittest.main()
