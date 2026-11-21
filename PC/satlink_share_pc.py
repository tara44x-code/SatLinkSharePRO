
import os, sys, time, socket, struct, threading, subprocess, platform, hashlib, json, tempfile
from pathlib import Path
try:
    from PySide6.QtCore import Qt, Signal, QObject, QTimer, QSize, QUrl
    from PySide6.QtGui import QIcon, QAction, QPixmap, QDesktopServices
    from PySide6.QtWidgets import QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QFileDialog, QLineEdit, QListWidget, QListWidgetItem, QMessageBox, QProgressBar, QFrame, QCheckBox, QSystemTrayIcon, QMenu, QTableWidget, QTableWidgetItem, QAbstractItemView, QHeaderView, QStackedWidget
    QT_BINDING = "PySide6"
except ImportError:
    # Qt 5 supplies Windows x86 wheels; the same source builds as a 32-bit EXE.
    from PyQt5.QtCore import Qt, pyqtSignal as Signal, QObject, QTimer, QSize, QUrl
    from PyQt5.QtGui import QIcon, QPixmap, QDesktopServices
    from PyQt5.QtWidgets import QApplication, QAction, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QFileDialog, QLineEdit, QListWidget, QListWidgetItem, QMessageBox, QProgressBar, QFrame, QCheckBox, QSystemTrayIcon, QMenu, QTableWidget, QTableWidgetItem, QAbstractItemView, QHeaderView, QStackedWidget
    QT_BINDING = "PyQt5"

APP_NAME = "SatLink Share PRO"
APP_VERSION = "4.1"
DEVELOPER = "مهند سات لنك"
ANDROID_TCP_PORT = 8988
ANDROID_UDP_PORT = 8989
ANDROID_PROTOCOL = "SATLINK_SHARE_PRO_V3_RESUME"
FOLDER_PROTOCOL = "SATLINK_SHARE_PRO_V4_FOLDERS"
# V5: like V4, plus the receiver confirms each saved file (8-byte size) before the next one.
ACK_PROTOCOL = "SATLINK_SHARE_PRO_V5_ACK"
MAX_SEND_ATTEMPTS = 10
DISCOVER = "SATLINK_SHARE_PRO_DISCOVER_V1"
RESPONSE_PREFIX = "SATLINK_SHARE_PRO_RESPONSE_V1|"
PING_PROTOCOL = "SATLINK_SHARE_PRO_PING_V1"
BUFFER = 256 * 1024
IO_BUFFER = 1024 * 1024
SOCKET_BUFFER = 4 * 1024 * 1024
STARTUP_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
STARTUP_NAME = "SatLinkSharePRO"

def legacy_startup_command():
    if getattr(sys, "frozen", False):
        return subprocess.list2cmdline([str(Path(sys.executable).resolve())])
    executable = Path(sys.executable)
    pythonw = executable.with_name("pythonw.exe")
    if pythonw.exists(): executable = pythonw
    return subprocess.list2cmdline([str(executable), str(Path(__file__).resolve())])

def startup_command():
    return legacy_startup_command() + " --tray"

def startup_enabled():
    if not sys.platform.startswith("win"): return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_KEY) as key:
            value, _ = winreg.QueryValueEx(key, STARTUP_NAME)
        if value == startup_command(): return True
        previous_satlink = "satlink share pro pc" in value.lower() or "satlink_share_pc.py" in value.lower()
        if value == legacy_startup_command() or previous_satlink:
            # Preserve the user's existing autostart preference; the next login
            # will start in the tray even if this version replaced the old EXE.
            set_startup_enabled(True)
            return True
        return False
    except OSError:
        return False

def set_startup_enabled(enabled):
    if not sys.platform.startswith("win"): raise OSError("Windows only")
    import winreg
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, STARTUP_KEY) as key:
        if enabled:
            winreg.SetValueEx(key, STARTUP_NAME, 0, winreg.REG_SZ, startup_command())
        else:
            try: winreg.DeleteValue(key, STARTUP_NAME)
            except FileNotFoundError: pass

def settings_path() -> Path:
    base = os.environ.get("APPDATA") if sys.platform.startswith("win") else os.environ.get("XDG_CONFIG_HOME")
    return (Path(base) if base else Path.home() / ("AppData/Roaming" if sys.platform.startswith("win") else ".config")) / "SatLinkSharePRO" / "settings.json"

def load_settings() -> dict:
    try:
        data = json.loads(settings_path().read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError, TypeError):
        return {}

def save_settings(updates: dict):
    target = settings_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    data = load_settings(); data.update(updates)
    staged = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=target.parent, delete=False) as file:
            staged = Path(file.name)
            json.dump(data, file, ensure_ascii=False)
        os.replace(staged, target)
    finally:
        if staged is not None and staged.exists(): staged.unlink()

def receive_base() -> Path:
    try:
        config = load_settings()
        value = config.get("receive_base")
        if isinstance(value, str) and value and Path(value).is_absolute():
            return Path(value)
    except (OSError, ValueError, TypeError, AttributeError):
        pass
    return Path.home() / "Downloads"

def receive_destination_path() -> Path:
    return receive_base() / "SatLink Share PRO" / "Received"

def check_receive_directory(destination: Path) -> Path:
    base = receive_base()
    if not base.is_dir():
        if base == Path.home() / "Downloads": base.mkdir(parents=True, exist_ok=True)
        else: raise OSError("مكان الاستلام غير متوفر؛ وصل القرص أو اختر مكاناً آخر")
    destination.mkdir(parents=True, exist_ok=True)
    # An actual write catches read-only/removable volumes before accepting a transfer.
    with tempfile.TemporaryFile(dir=destination):
        pass
    return destination

def downloads_received_dir() -> Path:
    return check_receive_directory(receive_destination_path())

def set_receive_base(folder) -> Path:
    base = Path(folder).expanduser().resolve(strict=True)
    if not base.is_dir(): raise OSError("اختار مجلد موجود على القرص")
    destination = base / "SatLink Share PRO" / "Received"
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryFile(dir=destination):
        pass
    save_settings({"receive_base": str(base)})
    return destination

def pc_name() -> str:
    return f"SatLink PC - {platform.node() or 'Windows'}"

def get_local_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(0.5)
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        try: return socket.gethostbyname(socket.gethostname())
        except Exception: return "127.0.0.1"

def local_ipv4_addresses():
    """All IPv4 addresses of this PC: router Wi-Fi/LAN, Windows hotspot, or a phone's SatLink network."""
    ips = []
    primary = get_local_ip()
    if primary and not primary.startswith("127."): ips.append(primary)
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips: ips.append(ip)
    except OSError:
        pass
    return ips

def network_label(ip: str) -> str:
    if ip.startswith("192.168.49."): return "شبكة SatLink (هاتف)"
    if ip.startswith("192.168.137."): return "نقطة اتصال الحاسبة"
    if ip.startswith("169.254."): return "بدون عنوان"
    return "الراوتر"

def tune_socket(sock):
    for option in (socket.SO_SNDBUF, socket.SO_RCVBUF):
        try: sock.setsockopt(socket.SOL_SOCKET, option, SOCKET_BUFFER)
        except OSError: pass
    try: sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except OSError: pass

def safe_filename(name: str) -> str:
    name = (name or "").replace("\\", "/").split("/")[-1].strip().rstrip(". ") or "received_file"
    for ch in '<>:"/\\|?*': name = name.replace(ch, "_")
    if name.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}:
        name = "_" + name
    return name

def safe_relative_path(name: str) -> Path:
    # Reject traversal/absolute paths before normalizing any Windows file names.
    if not name or len(name) > 32767 or name.startswith(("/", "\\")):
        raise ValueError("مسار ملف غير صالح")
    parts = name.replace("\\", "/").split("/")
    if len(parts) > 128 or any(p in ("", ".", "..") or ":" in p for p in parts):
        raise ValueError("مسار ملف غير صالح")
    return Path(*(safe_filename(p) for p in parts))

def selected_entries(paths):
    """Return (source, relative name, size), streaming source bytes later."""
    entries = []
    for raw in paths:
        source = Path(raw)
        if source.is_symlink(): continue
        if source.is_file():
            entries.append((source, source.name, source.stat().st_size))
        elif source.is_dir():
            for base, dirs, files in os.walk(source, followlinks=False):
                dirs[:] = sorted(d for d in dirs if not (Path(base) / d).is_symlink())
                for filename in sorted(files):
                    item = Path(base) / filename
                    if item.is_file() and not item.is_symlink():
                        entries.append((item, (Path(source.name) / item.relative_to(source)).as_posix(), item.stat().st_size))
        if len(entries) > 10000: raise ValueError("عدد الملفات يتجاوز 10000")
    return entries

def read_exact(sock, n):
    data = bytearray()
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk: raise ConnectionError("انقطع الاتصال")
        data.extend(chunk)
    return bytes(data)

def read_bool(sock): return read_exact(sock, 1) != b"\x00"
def write_bool(sock, val): sock.sendall(b"\x01" if val else b"\x00")
def read_int(sock): return struct.unpack(">i", read_exact(sock, 4))[0]
def write_int(sock, val): sock.sendall(struct.pack(">i", int(val)))
def read_long(sock): return struct.unpack(">q", read_exact(sock, 8))[0]
def write_long(sock, val): sock.sendall(struct.pack(">q", int(val)))
def read_utf(sock):
    length = struct.unpack(">H", read_exact(sock, 2))[0]
    data = read_exact(sock, length)
    # Java DataInputStream uses modified UTF-8 (including C0 80 for NUL and
    # CESU-8 surrogate pairs). Reassemble UTF-16 code units before decoding.
    units = bytearray(); i = 0
    while i < len(data):
        a = data[i]
        if a < 0x80:
            code = a; i += 1
        elif a & 0xe0 == 0xc0 and i + 1 < len(data):
            code = ((a & 0x1f) << 6) | (data[i+1] & 0x3f); i += 2
        elif a & 0xf0 == 0xe0 and i + 2 < len(data):
            code = ((a & 0x0f) << 12) | ((data[i+1] & 0x3f) << 6) | (data[i+2] & 0x3f); i += 3
        else:
            raise ValueError("Invalid modified UTF-8")
        units.extend(struct.pack(">H", code))
    return units.decode("utf-16-be")
def write_utf(sock, text):
    raw = bytearray()
    units = (text or "").encode("utf-16-be")
    for i in range(0, len(units), 2):
        code = (units[i] << 8) | units[i+1]
        if 0 < code <= 0x7f: raw.append(code)
        elif code <= 0x7ff: raw.extend((0xc0 | (code >> 6), 0x80 | (code & 0x3f)))
        else: raw.extend((0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f)))
    if len(raw) > 65535: raise ValueError("Java UTF field is too long")
    sock.sendall(struct.pack(">H", len(raw)) + raw)

def partial_path(save, sender_name, filename, size):
    key = hashlib.sha256(f"{sender_name}\0{filename}\0{size}".encode("utf-8")).hexdigest()
    parts = save / ".satlink_parts"
    parts.mkdir(exist_ok=True)
    return parts / (key + ".part")

def unique_destination(save, filename):
    candidate = save / filename
    if not candidate.exists(): return candidate
    stem, suffix = candidate.stem, candidate.suffix
    n = 1
    while (save / f"{stem}_{n}{suffix}").exists(): n += 1
    return save / f"{stem}_{n}{suffix}"

class Bus(QObject):
    log = Signal(str); progress = Signal(int); devices = Signal(list); receiving_state = Signal(str)
    transfer = Signal(str, str, int, str, str, str)  # direction, filename, percent, speed, ETA, state

def transfer_eta(remaining, speed):
    if speed <= 0: return "—"
    seconds = int(remaining / speed)
    if seconds >= 86400: return f"{seconds // 86400}d {(seconds % 86400) // 3600}h"
    if seconds >= 3600: return f"{seconds // 3600}h {(seconds % 3600) // 60}m"
    return f"{seconds // 60}m {seconds % 60}s"

def transfer_update(bus, direction, filename, done, size, started, state, origin=0):
    speed = max(0, done - origin) / max(time.monotonic() - started, 0.01) if state == "جاري" else 0
    percent = 100 if size == 0 and state == "مكتمل" else int(done * 100 / max(size, 1))
    bus.transfer.emit(direction, filename, min(100, max(0, percent)),
                      f"{speed / 1048576:.1f} MB/s" if speed else "—",
                      transfer_eta(max(0, size - done), speed), state)

class ReceiverServer:
    def __init__(self, bus):
        self.bus = bus; self.stop_event = threading.Event(); self.thread = None; self.udp_thread = None; self.sock = None; self.udp_sock = None
        self.active_client = None; self.cancelled_client = None; self.active_lock = threading.Lock()
        # Files saved in the last minutes, so a sender that reconnects because the
        # confirmation got lost is told "already complete" instead of sending a duplicate.
        self.recent = {}; self.recent_lock = threading.Lock()
    def cancel_current(self):
        with self.active_lock:
            client = self.active_client
            self.cancelled_client = client
        if client:
            try: client.shutdown(socket.SHUT_RDWR)
            except OSError: pass
            self.bus.log.emit("تم إلغاء الاستلام الحالي؛ يبقى الجزء المنقول للاستئناف")
    def is_running(self):
        return bool(self.thread and self.thread.is_alive() and not self.stop_event.is_set())
    def start(self):
        if self.thread and self.thread.is_alive(): self.bus.log.emit("الاستقبال يعمل مسبقاً."); return
        try: downloads_received_dir()
        except OSError:
            self.bus.receiving_state.emit("فشل")
            self.bus.log.emit("مكان الحفظ غير متوفر؛ وصل القرص أو اختر مكاناً آخر")
            return
        self.stop_event.clear()
        self.thread = threading.Thread(target=self._tcp_loop, daemon=True); self.thread.start()
        self.udp_thread = threading.Thread(target=self._udp_responder_loop, daemon=True); self.udp_thread.start()
        self.bus.receiving_state.emit("يعمل")
        self.bus.log.emit(f"جاهز للاستقبال على {', '.join(local_ipv4_addresses()) or get_local_ip()} · المنفذ {ANDROID_TCP_PORT}")
        self.bus.log.emit("لإظهار هذا الجهاز: افتح التطبيق أو البرنامج الآخر > إرسال > بحث عن الأجهزة")
    def stop(self):
        self.stop_event.set()
        try:
            # shutdown() wakes the thread blocked in accept() so the port is really freed.
            if self.sock: self.sock.shutdown(socket.SHUT_RDWR)
        except Exception: pass
        try:
            if self.sock: self.sock.close()
        except Exception: pass
        if self.thread and self.thread is not threading.current_thread(): self.thread.join(2)
        try:
            if self.udp_sock: self.udp_sock.close()
        except Exception: pass
        self.bus.receiving_state.emit("متوقف"); self.bus.log.emit("تم إيقاف الاستقبال.")
    def _tcp_loop(self):
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try: self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_BUFFER)
            except OSError: pass
            self.sock.bind(("", ANDROID_TCP_PORT)); self.sock.listen(5)
        except Exception as e:
            self.bus.log.emit(f"فشل تشغيل الاستقبال: {e}"); self.bus.receiving_state.emit("فشل"); return
        while not self.stop_event.is_set():
            try:
                client, addr = self.sock.accept()
                threading.Thread(target=self._handle_android_client, args=(client, addr), daemon=True).start()
            except Exception:
                if not self.stop_event.is_set(): self.bus.log.emit("حدث خطأ في انتظار الاتصال.")
                break
    def _udp_responder_loop(self):
        try:
            self.udp_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.udp_sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self.udp_sock.bind(("", ANDROID_UDP_PORT)); self.udp_sock.settimeout(1.0)
            self.bus.log.emit("اكتشاف الأجهزة شغال: هذه الحاسبة ستظهر للموبايل والحاسبات القريبة")
        except Exception as e:
            self.bus.log.emit(f"تعذر تشغيل اكتشاف Android UDP: {e}"); return
        while not self.stop_event.is_set():
            try:
                data, addr = self.udp_sock.recvfrom(2048)
                if data.decode("utf-8", errors="ignore").strip() == DISCOVER:
                    resp = f"{RESPONSE_PREFIX}{pc_name()}|{ANDROID_TCP_PORT}".encode("utf-8")
                    self.udp_sock.sendto(resp, (addr[0], addr[1]))
            except socket.timeout: continue
            except Exception: pass
    def _handle_android_client(self, sock, addr):
        current_name = None; received = 0; current_size = 0; started = time.monotonic()
        try:
            sock.settimeout(120); tune_socket(sock)
            protocol = read_utf(sock)
            if protocol == PING_PROTOCOL:
                write_utf(sock, pc_name())
                write_int(sock, ANDROID_TCP_PORT)
                self.bus.log.emit("تم الرد على فحص ظهور الحاسبة من الموبايل")
                return
            if protocol not in (ANDROID_PROTOCOL, FOLDER_PROTOCOL, ACK_PROTOCOL): raise RuntimeError("بروتوكول غير معروف: " + protocol)
            sender_name = read_utf(sock); count = read_int(sock); total_bytes = read_long(sock)
            if not 0 <= count <= 10000 or total_bytes < 0:
                raise ValueError("بيانات إرسال غير صالحة")
            self.bus.log.emit(f"طلب استلام من {sender_name} | {count} ملف | {total_bytes/1024/1024:.2f} MB")
            try:
                save = downloads_received_dir()
            except OSError:
                self.bus.log.emit("مكان الحفظ غير متوفر؛ وصل القرص أو اختر مكاناً آخر")
                write_bool(sock, False)
                return
            write_bool(sock, True)
            with self.active_lock: self.active_client = sock
            total_received_all = 0; start_all = time.time()
            for index in range(count):
                raw_name = read_utf(sock)
                relative = safe_relative_path(raw_name) if protocol in (FOLDER_PROTOCOL, ACK_PROTOCOL) else Path(safe_filename(raw_name))
                filename = relative.as_posix(); size = read_long(sock)
                current_name = filename; current_size = size
                if size < 0: raise ValueError("حجم الملف غير صالح")
                part = partial_path(save, sender_name, filename, size)
                recent_key = (sender_name, filename, size)
                with self.recent_lock:
                    now = time.monotonic()
                    self.recent = {k: t for k, t in self.recent.items() if now - t < 900}
                    already_saved = protocol == ACK_PROTOCOL and recent_key in self.recent and not part.exists()
                if already_saved:
                    write_long(sock, size); write_long(sock, size)
                    transfer_update(self.bus, "استلام", filename, size, size, time.monotonic(), "مكتمل")
                    continue
                resume_from = part.stat().st_size if part.exists() else 0
                if resume_from > size:
                    part.unlink(); resume_from = 0
                write_long(sock, resume_from)
                self.bus.log.emit(f"استلام: {filename} ({size/1024/1024:.2f} MB)")
                received = resume_from; total_received_all += resume_from
                started = time.monotonic(); last_ui = 0
                transfer_update(self.bus, "استلام", filename, received, size, started, "جاري", resume_from)
                buffer = bytearray(IO_BUFFER); view = memoryview(buffer)
                with open(part, "ab" if resume_from else "wb", buffering=IO_BUFFER) as f:
                    while received < size:
                        n = sock.recv_into(view, min(IO_BUFFER, size - received))
                        if not n: raise ConnectionError("انقطع الاتصال أثناء استلام الملف")
                        f.write(view[:n]); received += n; total_received_all += n
                        self.bus.progress.emit(max(0, min(100, int(total_received_all * 100 / max(1, total_bytes)))))
                        now = time.monotonic()
                        if now - last_ui >= 0.3:
                            transfer_update(self.bus, "استلام", filename, received, size, started, "جاري", resume_from); last_ui = now
                destination_dir = save / relative.parent
                destination_dir.mkdir(parents=True, exist_ok=True)
                out_path = unique_destination(destination_dir, relative.name)
                os.replace(part, out_path)
                with self.recent_lock: self.recent[recent_key] = time.monotonic()
                if protocol == ACK_PROTOCOL: write_long(sock, size)  # confirm: saved completely
                transfer_update(self.bus, "استلام", filename, size, size, started, "مكتمل")
                self.bus.log.emit(f"تم حفظ الملف: {out_path.name}")
            speed = (total_received_all / 1024 / 1024) / max(time.time() - start_all, 0.01)
            self.bus.log.emit(f"اكتمل الاستلام | السرعة {speed:.2f} MB/s"); self.bus.progress.emit(100)
        except Exception as e:
            with self.active_lock: cancelled = self.cancelled_client is sock
            if cancelled:
                if current_name: transfer_update(self.bus, "استلام", current_name, received, current_size, started, "ملغي")
            else: self.bus.log.emit(f"فشل الاستلام: {e}")
        finally:
            with self.active_lock:
                if self.active_client is sock: self.active_client = None
                if self.cancelled_client is sock: self.cancelled_client = None
            try: sock.close()
            except Exception: pass


class Discovery:
    def __init__(self, bus):
        self.bus = bus
        self.stop_event = threading.Event()
        self.devices = {}

    def start(self):
        self.stop_event.clear()
        self.devices = {}
        threading.Thread(target=self._scan_android_devices, daemon=True).start()
        self.bus.log.emit("بدأ البحث عن الأجهزة القريبة والحاسبات القريبة...")
        self.bus.log.emit("البحث الآن يستخدم UDP + فحص شبكة احتياطي حتى لا يعتمد على طريقة واحدة.")

    def stop(self):
        self.stop_event.set()

    def _add_device(self, name, ip, port):
        if not ip or ip in local_ipv4_addresses():
            return
        key = f"{ip}:{port}"
        if key not in self.devices:
            self.devices[key] = {"name": name or "SatLink Device", "ip": ip, "port": int(port)}
            self.bus.devices.emit(list(self.devices.values()))
            self.bus.log.emit(f"تم العثور على جهاز: {name} - {ip}:{port}")

    def _udp_scan(self):
        # One socket per network adapter: Windows sends 255.255.255.255 out of a single
        # adapter only, so the router, the Windows hotspot and a phone's SatLink network
        # each need their own broadcast.
        import select
        sockets = []
        for ip in local_ipv4_addresses() or ["0.0.0.0"]:
            if ip.startswith("169.254."): continue
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                s.bind((ip if ip != "0.0.0.0" else "", 0))
                s.setblocking(False)
                parts = ip.split(".")
                targets = ["255.255.255.255"] + ([".".join(parts[:3] + ["255"])] if len(parts) == 4 and ip != "0.0.0.0" else [])
                sockets.append((s, targets))
            except OSError:
                continue
        if not sockets:
            self.bus.log.emit("فشل تشغيل بحث UDP")
            return
        end = time.time() + 10
        try:
            while not self.stop_event.is_set() and time.time() < end:
                for s, targets in sockets:
                    for target in targets:
                        try: s.sendto(DISCOVER.encode("utf-8"), (target, ANDROID_UDP_PORT))
                        except OSError: pass
                listen_until = time.time() + 1.2
                while time.time() < listen_until and not self.stop_event.is_set():
                    ready, _, _ = select.select([s for s, _ in sockets], [], [], 0.3)
                    for s in ready:
                        try:
                            data, addr = s.recvfrom(2048)
                        except OSError:
                            continue
                        msg = data.decode("utf-8", errors="ignore").strip()
                        if not msg.startswith(RESPONSE_PREFIX): continue
                        parts = msg.split("|", 2)
                        if len(parts) < 3: continue
                        try: port = int(parts[2].strip())
                        except ValueError: port = ANDROID_TCP_PORT
                        self._add_device(parts[1].strip() or "SatLink Device", addr[0], port)
                time.sleep(0.4)
        finally:
            for s, _ in sockets:
                try: s.close()
                except OSError: pass

    def _subnet_ping_scan(self):
        prefixes = []
        for ip in local_ipv4_addresses():
            parts = ip.split(".")
            if len(parts) == 4 and not ip.startswith("169.254."):
                prefix = ".".join(parts[:3])
                if prefix not in prefixes: prefixes.append(prefix)
        if not prefixes:
            self.bus.log.emit("تعذر معرفة شبكة الحاسبة للفحص الاحتياطي.")
            return
        own = set(local_ipv4_addresses())
        hosts = [f"{prefix}.{n}" for prefix in prefixes[:4] for n in range(1, 255)]
        hosts = [h for h in hosts if h not in own]

        def probe(host):
            if self.stop_event.is_set(): return None
            sock = None
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                sock.settimeout(0.25)
                sock.connect((host, ANDROID_TCP_PORT))
                write_utf(sock, PING_PROTOCOL)
                name = read_utf(sock)
                try: port = read_int(sock)
                except Exception: port = ANDROID_TCP_PORT
                return {"name": name or "SatLink Device", "ip": host, "port": int(port)}
            except Exception:
                return None
            finally:
                try:
                    if sock: sock.close()
                except Exception: pass

        lock = threading.Lock(); index = {"value": 0}
        def worker():
            while not self.stop_event.is_set():
                with lock:
                    i = index["value"]; index["value"] += 1
                if i >= len(hosts): return
                dev = probe(hosts[i])
                if dev: self._add_device(dev["name"], dev["ip"], dev["port"])
        threads = [threading.Thread(target=worker, daemon=True) for _ in range(48)]
        for t in threads: t.start()
        deadline = time.time() + 12
        for t in threads: t.join(max(0.05, deadline - time.time()))

class Sender:
    def __init__(self, bus): self.bus = bus; self.current_socket = None; self.cancelled = threading.Event()
    def cancel_current(self):
        self.cancelled.set()
        if self.current_socket:
            try: self.current_socket.shutdown(socket.SHUT_RDWR)
            except OSError: pass
        self.bus.log.emit("تم إلغاء الإرسال الحالي")
    def is_busy(self): return self.current_socket is not None
    def send_files(self, host, port, file_paths): threading.Thread(target=self._send_android_protocol, args=(host, int(port), file_paths), daemon=True).start()
    def _send_android_protocol(self, host, port, file_paths):
        """Send a batch. Each file counts as done only after the receiver confirms it was
        saved; on a dropped connection it reconnects and resumes from the last byte."""
        self.cancelled.clear()
        self._current = {"name": None, "done": 0, "size": 0, "started": 0}
        try:
            files = selected_entries(file_paths)
            if not files: self.bus.log.emit("لم يتم اختيار ملفات صالحة."); return
            finished = set(); legacy = False; attempt = 0; start = time.time()
            total_size = sum(size for _, _, size in files)
            while not self.cancelled.is_set():
                remaining = [f for f in files if f[1] not in finished]
                if not remaining: break
                attempt += 1
                try:
                    self._send_once(host, port, remaining, finished, not legacy)
                    break
                except _LegacyReceiver:
                    legacy = True; attempt -= 1
                    self.bus.log.emit("الجهاز المستلم بنسخة قديمة؛ يُفضّل تحديثه")
                except _Rejected:
                    self.bus.log.emit("تم رفض الاستلام من الجهاز الآخر"); return
                except (OSError, ConnectionError, EOFError, struct.error) as e:
                    if self.cancelled.is_set(): raise
                    if attempt >= MAX_SEND_ATTEMPTS: raise
                    self.bus.log.emit(f"انقطع الاتصال، إعادة المحاولة {attempt} والإكمال من آخر نقطة...")
                    time.sleep(min(8.0, 1.5 * attempt))
            if self.cancelled.is_set(): raise ConnectionError("cancelled")
            speed = (total_size / 1024 / 1024) / max(time.time() - start, 0.01)
            self.bus.log.emit(f"اكتمل الإرسال إلى الجهاز | السرعة {speed:.2f} MB/s"); self.bus.progress.emit(100)
        except Exception as e:
            c = self._current
            if self.cancelled.is_set():
                if c["name"]: transfer_update(self.bus, "إرسال", c["name"], c["done"], c["size"], c["started"], "ملغي")
                self.bus.log.emit("تم إلغاء الإرسال الحالي")
            else:
                if c["name"]: transfer_update(self.bus, "إرسال", c["name"], c["done"], c["size"], c["started"], "فشل")
                self.bus.log.emit(f"فشل الإرسال إلى الجهاز: {e}")

    def _send_once(self, host, port, files, finished, ack):
        total_size = sum(size for _, _, size in files)
        if ack: protocol = ACK_PROTOCOL
        else: protocol = FOLDER_PROTOCOL if any("/" in name for _, name, _ in files) else ANDROID_PROTOCOL
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM); self.current_socket = s
        try:
            s.settimeout(120); tune_socket(s)
            self.bus.log.emit(f"الاتصال بالجهاز {host}:{port} ..."); s.connect((host, port))
            try:
                write_utf(s, protocol); write_utf(s, pc_name()); write_int(s, len(files)); write_long(s, total_size)
                accepted = read_bool(s)
            except socket.timeout:
                raise
            except (OSError, ConnectionError):
                # Older receivers close the connection on a header they don't know.
                if ack: raise _LegacyReceiver()
                raise
            if not accepted: raise _Rejected()
            self.bus.log.emit("تم قبول الطلب، بدأ الإرسال")
            sent_all = 0
            for p, relative_name, size in files:
                if self.cancelled.is_set(): return
                write_utf(s, relative_name); write_long(s, size); resume_from = read_long(s)
                if resume_from < 0 or resume_from > size: resume_from = 0
                started = time.monotonic()
                self._current = {"name": relative_name, "done": resume_from, "size": size, "started": started}
                if resume_from: self.bus.log.emit(f"استئناف {relative_name} من {resume_from/1024/1024:.2f} MB")
                sent_all += resume_from
                with open(p, "rb", buffering=IO_BUFFER) as f:
                    if resume_from: f.seek(resume_from)
                    self.bus.log.emit(f"إرسال: {relative_name} ({size/1024/1024:.2f} MB)")
                    remaining = size - resume_from; last_ui = 0
                    transfer_update(self.bus, "إرسال", relative_name, resume_from, size, started, "جاري", resume_from)
                    while remaining and not self.cancelled.is_set():
                        chunk = f.read(min(IO_BUFFER, remaining))
                        if not chunk: raise ValueError(f"الملف أقصر من حجمه المعلن: {relative_name}")
                        s.sendall(chunk); sent_all += len(chunk); self._current["done"] += len(chunk)
                        remaining -= len(chunk)
                        self.bus.progress.emit(max(0, min(100, int(sent_all * 100 / max(1, total_size)))))
                        now = time.monotonic()
                        if now - last_ui >= 0.3:
                            # Keep 99% until the receiver confirms the file was saved.
                            shown = min(size - remaining, max(0, size - 1)) if ack else size - remaining
                            transfer_update(self.bus, "إرسال", relative_name, shown, size, started, "جاري", resume_from); last_ui = now
                    if self.cancelled.is_set(): return
                if ack:
                    transfer_update(self.bus, "إرسال", relative_name, max(0, size - 1), size, started, "جاري", resume_from)
                    if read_long(s) != size: raise ConnectionError(f"المستلم لم يحفظ الملف كاملاً: {relative_name}")
                finished.add(relative_name)
                transfer_update(self.bus, "إرسال", relative_name, size, size, started, "مكتمل")
        finally:
            try: s.close()
            except OSError: pass
            if self.current_socket is s: self.current_socket = None

class _LegacyReceiver(Exception): pass
class _Rejected(Exception): pass

def asset_path(name) -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).parent)) / "assets" / name

def history_path() -> Path:
    return settings_path().parent / "history.json"

def load_history():
    try:
        data = json.loads(history_path().read_text(encoding="utf-8"))
        return [h for h in data if isinstance(h, dict)][-300:] if isinstance(data, list) else []
    except (OSError, ValueError, TypeError):
        return []

def save_history(entries):
    try:
        target = history_path(); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(entries[-300:], ensure_ascii=False), encoding="utf-8")
    except OSError:
        pass

FINAL_STATES = ("مكتمل", "ملغي", "فشل")

class TransferTable(QTableWidget):
    """قائمة الملفات المرسلة أو المستلمة: الأحدث بالأعلى مع شريط تقدم لكل ملف."""
    def __init__(self):
        super().__init__(0, 5)
        self.setHorizontalHeaderLabels(["الملف", "التقدم", "السرعة", "المتبقي", "الحالة"])
        self.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.verticalHeader().setVisible(False)
        self.setShowGrid(False)
        header = self.horizontalHeader()
        header.setSectionResizeMode(0, QHeaderView.Stretch)
        for column, width in ((1, 170), (2, 100), (3, 90), (4, 90)):
            header.setSectionResizeMode(column, QHeaderView.Fixed); self.setColumnWidth(column, width)
        self.rows = {}
    def _new_row(self, filename):
        self.insertRow(0)
        name = QTableWidgetItem(filename); self.setItem(0, 0, name)
        bar = QProgressBar(); bar.setRange(0, 100); bar.setTextVisible(True); bar.setFormat("%p%")
        self.setCellWidget(0, 1, bar)
        for column in (2, 3, 4): self.setItem(0, column, QTableWidgetItem(""))
        self.setRowHeight(0, 38)
        return name
    def update_row(self, filename, percent, speed, eta, state):
        name_item = self.rows.get(filename)
        if name_item is not None:
            try: row = name_item.row()
            except RuntimeError: row = -1  # the row was removed from the table
            if row < 0 or (self.item(row, 4) and self.item(row, 4).text() in FINAL_STATES and state == "جاري"):
                name_item = None
        if name_item is None:
            name_item = self._new_row(filename); self.rows[filename] = name_item
        row = name_item.row()
        bar = self.cellWidget(row, 1)
        if bar is not None: bar.setValue(int(percent))
        self.item(row, 2).setText(speed if state == "جاري" else "")
        self.item(row, 3).setText(eta if state == "جاري" else "")
        self.item(row, 4).setText(state)

class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__(); self.setWindowTitle(f"{APP_NAME} · {DEVELOPER}")
        icon = asset_path("satlink_share_pc_icon.ico")
        if icon.exists(): self.setWindowIcon(QIcon(str(icon)))
        self.resize(1040, 760); self.setMinimumSize(860, 640)
        self.bus = Bus(); self.receiver = ReceiverServer(self.bus); self.discovery = Discovery(self.bus); self.sender = Sender(self.bus)
        self.selected_files = []; self.history = load_history()
        self._build_ui(); self._connect(); self._apply_style(); self._load_history_rows()
        self._really_exit = False; self._setup_tray()

    # ------------------------------------------------------------ tray
    def _setup_tray(self):
        self.tray = QSystemTrayIcon(self.windowIcon(), self)
        self.tray.setToolTip(APP_NAME)
        menu = QMenu(self)
        show_action = QAction("فتح SatLink", self); show_action.triggered.connect(self.show_window)
        exit_action = QAction("إغلاق البرنامج نهائياً", self); exit_action.triggered.connect(self.exit_program)
        menu.addAction(show_action); menu.addSeparator(); menu.addAction(exit_action)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(lambda reason: self.show_window() if reason == QSystemTrayIcon.DoubleClick else None)
        self.tray.show()
    def show_window(self):
        self.showNormal(); self.raise_(); self.activateWindow()
    def exit_program(self):
        self._really_exit = True
        self.receiver.stop(); self.discovery.stop(); self.sender.cancel_current()
        self.tray.hide(); QApplication.instance().quit()
    def closeEvent(self, event):
        if self._really_exit or not QSystemTrayIcon.isSystemTrayAvailable():
            event.accept(); QApplication.instance().quit(); return
        event.ignore(); self.hide()
        self.tray.showMessage(APP_NAME, "البرنامج يكمل الإرسال والاستلام بالخلفية بجوار الساعة. للإغلاق النهائي اضغط يمين على الأيقونة.", QSystemTrayIcon.Information, 3500)

    # ------------------------------------------------------------ UI
    def _image_button(self, asset, fallback, width, height, slot):
        button = QPushButton(); button.setObjectName("ImageButton"); button.setCursor(Qt.PointingHandCursor)
        pix = QPixmap(str(asset_path(asset)))
        if pix.isNull():
            button.setText(fallback); button.setObjectName("BigButton")
        else:
            button.setIcon(QIcon(pix)); button.setIconSize(QSize(width, height))
        button.setFixedSize(width + 16, height + 16); button.clicked.connect(slot)
        return button
    def _header(self, layout, title, icon_name):
        row = QHBoxLayout(); row.setSpacing(12)
        back = QPushButton("رجوع ›"); back.setObjectName("BackButton"); back.setCursor(Qt.PointingHandCursor); back.clicked.connect(self.show_home)
        icon = QLabel(); pix = QPixmap(str(asset_path(icon_name)))
        if not pix.isNull(): icon.setPixmap(pix.scaled(78, 62, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        texts = QVBoxLayout(); t = QLabel(title); t.setObjectName("PageTitle"); d = QLabel("المطور: " + DEVELOPER); d.setObjectName("Developer")
        texts.addWidget(t); texts.addWidget(d)
        row.addWidget(back); row.addWidget(icon); row.addLayout(texts, 1)
        layout.addLayout(row)
    def _panel(self, title=None):
        frame = QFrame(); frame.setObjectName("Panel"); v = QVBoxLayout(frame); v.setContentsMargins(16, 14, 16, 14); v.setSpacing(10)
        if title: t = QLabel(title); t.setObjectName("PanelTitle"); v.addWidget(t)
        return frame, v

    def _build_ui(self):
        root = QWidget(); root.setObjectName("Root"); root.setAttribute(Qt.WA_StyledBackground, True); self.setCentralWidget(root)
        outer = QVBoxLayout(root); outer.setContentsMargins(0, 0, 0, 0)
        self.stack = QStackedWidget(); outer.addWidget(self.stack)

        # الرئيسية: إرسال واستلام فقط
        home = QWidget(); hv = QVBoxLayout(home); hv.setContentsMargins(30, 26, 30, 20); hv.setSpacing(12)
        title = QLabel(APP_NAME); title.setObjectName("HomeTitle"); title.setAlignment(Qt.AlignCenter)
        dev = QLabel("المطور: " + DEVELOPER); dev.setObjectName("Developer"); dev.setAlignment(Qt.AlignCenter)
        hv.addWidget(title); hv.addWidget(dev); hv.addStretch(1)
        self.btn_home_send = self._image_button("card_send.png", "إرسال", 560, 222, self.show_send)
        self.btn_home_receive = self._image_button("card_receive.png", "استلام", 560, 224, self.show_receive)
        hv.addWidget(self.btn_home_send, 0, Qt.AlignHCenter); hv.addSpacing(6); hv.addWidget(self.btn_home_receive, 0, Qt.AlignHCenter)
        hv.addStretch(1)
        self.home_status = QLabel("بالراوتر أو بدون راوتر · سريع · بدون إنترنت"); self.home_status.setObjectName("Muted"); self.home_status.setAlignment(Qt.AlignCenter)
        hv.addWidget(self.home_status)
        self.stack.addWidget(home)

        # صفحة الإرسال
        send = QWidget(); sv = QVBoxLayout(send); sv.setContentsMargins(24, 16, 24, 16); sv.setSpacing(12)
        self._header(sv, "إرسال", "ic_send.png")
        pick = QHBoxLayout()
        self.btn_choose_files = QPushButton("اختيار ملفات"); self.btn_choose_folder = QPushButton("اختيار مجلد")
        self.files_label = QLabel("اختر الملفات، ثم اضغط على الجهاز المستلم وسيبدأ الإرسال"); self.files_label.setObjectName("Muted"); self.files_label.setWordWrap(True)
        pick.addWidget(self.btn_choose_files); pick.addWidget(self.btn_choose_folder); pick.addWidget(self.files_label, 1)
        sv.addLayout(pick)
        body = QHBoxLayout(); body.setSpacing(14)
        devices, dv = self._panel("الأجهزة القريبة")
        self.device_list = QListWidget(); self.device_list.setObjectName("DeviceList"); self.device_list.setCursor(Qt.PointingHandCursor)
        self.scan_label = QLabel(""); self.scan_label.setObjectName("Muted"); self.scan_label.setWordWrap(True)
        self.btn_search = QPushButton("↻ بحث")
        manual = QHBoxLayout(); self.manual_host = QLineEdit(); self.manual_host.setPlaceholderText("IP يدوي مثل 192.168.1.20"); self.btn_use_manual = QPushButton("إضافة")
        manual.addWidget(self.manual_host, 1); manual.addWidget(self.btn_use_manual)
        dv.addWidget(self.device_list, 1); dv.addWidget(self.scan_label); dv.addWidget(self.btn_search); dv.addLayout(manual)
        sent, tv = self._panel("الملفات المرسلة")
        self.sent_table = TransferTable(); tv.addWidget(self.sent_table, 1)
        srow = QHBoxLayout(); self.btn_cancel_send = QPushButton("إلغاء الإرسال"); self.btn_cancel_send.setObjectName("Danger")
        self.btn_clear_sent = QPushButton("مسح السجل"); self.btn_clear_sent.setObjectName("Ghost")
        srow.addWidget(self.btn_cancel_send); srow.addWidget(self.btn_clear_sent); srow.addStretch(1); tv.addLayout(srow)
        body.addWidget(devices, 2); body.addWidget(sent, 3); sv.addLayout(body, 1)
        self.send_status = QLabel(""); self.send_status.setObjectName("Muted"); sv.addWidget(self.send_status)
        self.stack.addWidget(send)

        # صفحة الاستلام
        recv = QWidget(); rv = QVBoxLayout(recv); rv.setContentsMargins(24, 16, 24, 16); rv.setSpacing(12)
        self._header(rv, "استلام", "ic_receive.png")
        top = QHBoxLayout(); top.setSpacing(14)
        info, iv = self._panel()
        self.receive_state = QLabel("● الاستلام متوقف"); self.receive_state.setObjectName("StateOff")
        name = QLabel(pc_name()); name.setObjectName("PanelTitle")
        self.addresses_label = QLabel(""); self.addresses_label.setObjectName("Muted"); self.addresses_label.setWordWrap(True)
        self.receive_path_label = QLabel(""); self.receive_path_label.setObjectName("Muted"); self.receive_path_label.setWordWrap(True)
        buttons = QHBoxLayout()
        self.btn_choose_receive = QPushButton("مكان الحفظ (C / D / E)"); self.btn_open_received = QPushButton("فتح المجلد")
        self.btn_stop_receive = QPushButton("إيقاف الاستلام"); self.btn_stop_receive.setObjectName("Danger")
        buttons.addWidget(self.btn_choose_receive); buttons.addWidget(self.btn_open_received); buttons.addWidget(self.btn_stop_receive)
        self.startup_toggle = QCheckBox("تشغيل مع Windows بجوار الساعة (الاستلام تلقائي)")
        self.startup_toggle.setChecked(startup_enabled()); self.startup_toggle.setEnabled(sys.platform.startswith("win"))
        for w in (self.receive_state, name, self.addresses_label, self.receive_path_label): iv.addWidget(w)
        iv.addLayout(buttons); iv.addWidget(self.startup_toggle)
        vnet, vv = self._panel("بدون راوتر")
        hint = QLabel("• من الهاتف: افتح «استلام» وفعّل «شبكة SatLink»، ثم اتصل من واي فاي الحاسبة بالاسم وكلمة المرور الظاهرين بالهاتف.\n"
                      "• أو شغّل نقطة الاتصال في Windows واجعل الهاتف يتصل بها.\nبعدها يظهر الجهازان لبعض تلقائياً.")
        hint.setObjectName("Muted"); hint.setWordWrap(True)
        self.btn_hotspot = QPushButton("فتح نقطة الاتصال في Windows"); self.btn_wifi = QPushButton("فتح شبكات الواي فاي")
        vv.addWidget(hint); vv.addWidget(self.btn_hotspot); vv.addWidget(self.btn_wifi); vv.addStretch(1)
        top.addWidget(info, 3); top.addWidget(vnet, 2); rv.addLayout(top)
        received, tv2 = self._panel("الملفات المستلمة")
        self.received_table = TransferTable(); tv2.addWidget(self.received_table, 1)
        rrow = QHBoxLayout(); self.btn_cancel_receive = QPushButton("إلغاء الملف الجاري"); self.btn_cancel_receive.setObjectName("Danger")
        self.btn_clear_received = QPushButton("مسح السجل"); self.btn_clear_received.setObjectName("Ghost")
        rrow.addWidget(self.btn_cancel_receive); rrow.addWidget(self.btn_clear_received); rrow.addStretch(1); tv2.addLayout(rrow)
        rv.addWidget(received, 1)
        self.status_line = QLabel(""); self.status_line.setObjectName("Muted"); rv.addWidget(self.status_line)
        self.stack.addWidget(recv)
        self.refresh_receive_info()

    def _connect(self):
        self.btn_choose_files.clicked.connect(self.choose_files); self.btn_choose_folder.clicked.connect(self.choose_folder)
        self.btn_search.clicked.connect(self.start_search); self.btn_use_manual.clicked.connect(self.use_manual)
        self.device_list.itemClicked.connect(self.on_device_clicked)
        self.btn_cancel_send.clicked.connect(self.sender.cancel_current); self.btn_cancel_receive.clicked.connect(self.receiver.cancel_current)
        self.btn_clear_sent.clicked.connect(lambda: self.clear_history("إرسال")); self.btn_clear_received.clicked.connect(lambda: self.clear_history("استلام"))
        self.btn_choose_receive.clicked.connect(self.choose_receive_location); self.btn_open_received.clicked.connect(self.open_received)
        self.btn_stop_receive.clicked.connect(self.stop_receiving); self.startup_toggle.toggled.connect(self.toggle_startup)
        self.btn_hotspot.clicked.connect(lambda: self.open_settings("ms-settings:network-mobilehotspot"))
        self.btn_wifi.clicked.connect(lambda: self.open_settings("ms-settings:network-wifi"))
        self.bus.log.connect(self.add_log); self.bus.devices.connect(self.update_devices); self.bus.receiving_state.connect(self.update_receiving_state)
        self.bus.transfer.connect(self.update_transfer)

    # ------------------------------------------------------------ navigation
    def show_home(self):
        self.discovery.stop(); self.stack.setCurrentIndex(0); self._update_home_status()
    def show_send(self):
        self.stack.setCurrentIndex(1); self.start_search()
    def show_receive(self):
        self.stack.setCurrentIndex(2); self.refresh_receive_info()
        if not self.receiver.is_running(): self.receiver.start()
    def _update_home_status(self):
        parts = []
        if self.receiver.is_running(): parts.append("الاستلام شغال بالخلفية")
        if self.sender.is_busy(): parts.append("الإرسال شغال بالخلفية")
        self.home_status.setText("  ·  ".join(parts) or "بالراوتر أو بدون راوتر · سريع · بدون إنترنت")

    # ------------------------------------------------------------ send
    def start_search(self):
        self.discovery.stop(); self.device_list.clear()
        self.scan_label.setText("جاري البحث على كل الشبكات المتصلة...")
        QTimer.singleShot(150, self.discovery.start)
        QTimer.singleShot(13000, lambda: self.device_list.count() == 0 and self.scan_label.setText("لم يظهر جهاز. افتح «استلام» في الجهاز الثاني أو أضف IP يدوي."))
    def update_devices(self, devices):
        self.device_list.clear()
        for dev in devices:
            label = ("🖥  " if "pc" in dev.get("name", "").lower() else "📱  ") + f"{dev.get('name')}\n      {network_label(dev.get('ip', ''))} · {dev.get('ip')}"
            item = QListWidgetItem(label); item.setData(Qt.UserRole, dev); item.setSizeHint(QSize(100, 58)); self.device_list.addItem(item)
        self.scan_label.setText("اضغط على الجهاز ليبدأ الإرسال" if devices else "")
    def on_device_clicked(self, item):
        device = item.data(Qt.UserRole)
        if not self.selected_files:
            self.send_status.setText("اختر ملفات أولاً ثم اضغط على الجهاز"); self.choose_files()
            if not self.selected_files: return
        self.sender.send_files(device["ip"], device.get("port", ANDROID_TCP_PORT), self.selected_files)
        self.send_status.setText(f"بدأ الإرسال إلى {device.get('name')}")
        self.selected_files = []; self.files_label.setText("اختر ملفات جديدة للإرسال التالي")
    def choose_files(self):
        files, _ = QFileDialog.getOpenFileNames(self, "اختيار ملفات للإرسال")
        if files:
            self.selected_files = files
            size = sum(Path(f).stat().st_size for f in files if Path(f).is_file())
            self.files_label.setText(f"✓ {len(files)} ملف · {size/1024/1024:.1f} MB — اضغط على الجهاز المستلم")
    def choose_folder(self):
        folder = QFileDialog.getExistingDirectory(self, "اختيار مجلد للإرسال")
        if folder:
            self.selected_files = [folder]; self.files_label.setText("✓ المجلد: " + Path(folder).name + " — اضغط على الجهاز المستلم")
    def use_manual(self):
        text = self.manual_host.text().strip()
        parts = text.split(".")
        if len(parts) != 4 or not all(p.isdigit() and 0 <= int(p) <= 255 for p in parts):
            QMessageBox.warning(self, "تنبيه", "اكتب IP صحيح مثل 192.168.1.20"); return
        self.discovery._add_device(f"SatLink · {text}", text, ANDROID_TCP_PORT)

    # ------------------------------------------------------------ receive
    def refresh_receive_info(self):
        lines = [f"{network_label(ip)}: {ip}" for ip in local_ipv4_addresses()]
        self.addresses_label.setText("\n".join(lines) or "غير متصل بشبكة")
        self.receive_path_label.setText("مكان الحفظ: " + str(receive_destination_path()))
    def stop_receiving(self):
        self.receiver.stop(); self.show_home()
    def update_receiving_state(self, state):
        on = state == "يعمل"
        self.receive_state.setText("● الاستلام شغال" if on else ("● الاستلام متوقف" if state == "متوقف" else "● تعذر تشغيل الاستلام"))
        self.receive_state.setObjectName("StateOn" if on else "StateOff")
        self.receive_state.style().unpolish(self.receive_state); self.receive_state.style().polish(self.receive_state)
        if hasattr(self, "tray"): self.tray.setToolTip("SatLink · الاستلام " + state)
    def choose_receive_location(self):
        current = receive_base()
        folder = QFileDialog.getExistingDirectory(self, "اختيار القرص أو المجلد للاستلام", str(current if current.is_dir() else Path.home()))
        if not folder: return
        try:
            destination = set_receive_base(folder)
            self.receive_path_label.setText("مكان الحفظ: " + str(destination))
            self.status_line.setText("مكان الاستلام الجديد ينطبق على النقل التالي")
        except OSError:
            self.status_line.setText("تعذر حفظ مكان الاستلام. تأكد أن القرص متصل وقابل للكتابة.")
    def open_received(self):
        try:
            path = downloads_received_dir()
            if sys.platform.startswith("win"): os.startfile(path)
            elif sys.platform == "darwin": subprocess.Popen(["open", str(path)])
            else: subprocess.Popen(["xdg-open", str(path)])
        except Exception: self.status_line.setText("تعذر فتح مجلد الاستلام")
    def open_settings(self, uri):
        if not QDesktopServices.openUrl(QUrl(uri)):
            self.status_line.setText("افتح الإعدادات > الشبكة والإنترنت يدوياً")
    def toggle_startup(self, enabled):
        try:
            set_startup_enabled(enabled)
            self.status_line.setText("التشغيل مع Windows مفعل" if enabled else "التشغيل مع Windows متوقف")
        except OSError:
            self.startup_toggle.blockSignals(True); self.startup_toggle.setChecked(not enabled); self.startup_toggle.blockSignals(False)
            self.status_line.setText("تعذر تغيير إعداد بدء التشغيل")

    # ------------------------------------------------------------ transfers & history
    def update_transfer(self, direction, filename, percent, speed, eta, state):
        table = self.sent_table if direction == "إرسال" else self.received_table
        table.update_row(filename, percent, speed, eta, state)
        if state in FINAL_STATES:
            self.history.append({"dir": direction, "name": filename, "state": state, "percent": percent, "time": int(time.time())})
            save_history(self.history)
        if hasattr(self, "tray"): self.tray.setToolTip(f"SatLink · {direction} · {filename[:50]} · {percent}% · {state}")
        self._update_home_status()
    def _load_history_rows(self):
        for entry in self.history:
            table = self.sent_table if entry.get("dir") == "إرسال" else self.received_table
            table.update_row(entry.get("name", ""), entry.get("percent", 100), "", "", entry.get("state", "مكتمل"))
    def clear_history(self, direction):
        self.history = [h for h in self.history if h.get("dir") != direction]; save_history(self.history)
        table = self.sent_table if direction == "إرسال" else self.received_table
        for row in range(table.rowCount() - 1, -1, -1):
            item = table.item(row, 4)
            if item and item.text() in FINAL_STATES: table.removeRow(row)
        # Rebuild the lookup from the rows that are still running (row 0 is the newest).
        table.rows = {}
        for row in range(table.rowCount() - 1, -1, -1):
            name = table.item(row, 0)
            if name is not None: table.rows[name.text()] = name
    def add_log(self, message):
        target = self.send_status if self.stack.currentIndex() == 1 else self.status_line
        if message.startswith("مكان الحفظ غير متوفر"):
            self.status_line.setText(message)
        elif any(word in message for word in ("فشل", "تعذر", "خطأ", "انقطع")):
            target.setText("تعذر إكمال العملية. تحقق من اتصال الجهازين وأعد الإرسال ليكمل من آخر نقطة.")
        elif any(word in message for word in ("اكتمل", "تم حفظ", "تم قبول", "بدأ", "إلغاء")):
            target.setText(message.split(" | ")[0][:160])

    def _apply_style(self):
        bg = asset_path("bg_neon.jpg").as_posix()
        self.setStyleSheet("""
            QWidget { background: transparent; color: #f4f6ff; font-family: 'Segoe UI', 'Tahoma'; font-size: 14px; }
            #Root { background-color: #04050a; border-image: url('%s') 0 0 0 0 stretch stretch; }
            QStackedWidget > QWidget { background: transparent; }
            #HomeTitle { font-size: 34px; font-weight: 900; color: #ffffff; }
            #Developer { color: #00dcff; font-weight: 700; }
            #PageTitle { font-size: 28px; font-weight: 900; color: #ffffff; }
            #Muted { color: #a5acc8; }
            #StateOn { color: #50ff8c; font-size: 20px; font-weight: 900; }
            #StateOff { color: #ff5a6e; font-size: 20px; font-weight: 900; }
            #ImageButton { background: transparent; border: none; border-radius: 26px; }
            #ImageButton:hover { background: rgba(120, 90, 255, 0.10); }
            #ImageButton:pressed { background: rgba(0, 220, 255, 0.16); }
            #BigButton { font-size: 30px; font-weight: 900; border-radius: 26px; border: 2px solid #9646ff; background: rgba(10,12,22,0.85); }
            #Panel { background: rgba(10, 12, 22, 0.86); border-width: 2px; border-style: solid; border-color: qlineargradient(x1:0,y1:0,x2:1,y2:1, stop:0 #ff40c8, stop:0.35 #9646ff, stop:0.65 #00dcff, stop:1 #50ff78); border-radius: 18px; }
            #Panel QLabel, #Panel QCheckBox { background: transparent; }
            #PanelTitle { font-size: 18px; font-weight: 900; color: #ffffff; }
            QPushButton { background: rgba(14, 16, 30, 0.95); color: #ffffff; border-width: 2px; border-style: solid; border-color: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #9646ff, stop:1 #00dcff); border-radius: 14px; padding: 9px 16px; font-weight: 800; }
            QPushButton:hover { background: rgba(60, 40, 140, 0.55); }
            QPushButton:pressed { background: rgba(0, 160, 220, 0.45); }
            #Danger { border-color: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #ff3c5a, stop:1 #ff2896); color: #ff8fa0; }
            #Ghost { border: 1px solid #3a3f5c; color: #a5acc8; }
            #BackButton { border: none; background: transparent; font-size: 18px; color: #00dcff; }
            QLineEdit { background: rgba(6,8,16,0.9); border: 1px solid #3a3f5c; border-radius: 12px; padding: 8px; color: #ffffff; }
            QListWidget { background: rgba(6,8,16,0.75); border: 1px solid #2c2f48; border-radius: 14px; padding: 6px; }
            QListWidget::item { padding: 8px; margin: 3px; border-radius: 12px; border: 1px solid #3b2f7a; background: rgba(20,22,40,0.9); }
            QListWidget::item:hover { border: 1px solid #00dcff; }
            QListWidget::item:selected { background: rgba(150,70,255,0.35); color: #ffffff; }
            QTableWidget { background: rgba(6,8,16,0.75); border: none; border-radius: 12px; gridline-color: transparent; }
            QTableWidget::item { padding: 4px; border-bottom: 1px solid #1d2036; }
            QHeaderView::section { background: rgba(20,22,40,0.95); color: #a5acc8; border: none; padding: 6px; font-weight: 800; }
            QProgressBar { background: #151827; border: none; border-radius: 7px; text-align: center; color: #ffffff; font-weight: 800; min-height: 14px; max-height: 18px; }
            QProgressBar::chunk { background: qlineargradient(x1:0,y1:0,x2:1,y2:0, stop:0 #9646ff, stop:0.5 #00dcff, stop:1 #50ff78); border-radius: 7px; }
            QCheckBox { color: #d0d4ec; }
            QMenu, QMessageBox, QToolTip { background: #10121e; color: #ffffff; border: 1px solid #3b2f7a; }
            QMenu::item { padding: 6px 18px; } QMenu::item:selected { background: #3b2f7a; }
            QScrollBar:vertical { background: transparent; width: 10px; } QScrollBar::handle:vertical { background: #3b2f7a; border-radius: 5px; }
        """ % bg)

def main():
    app = QApplication(sys.argv); app.setQuitOnLastWindowClosed(False); app.setLayoutDirection(Qt.RightToLeft)
    icon = asset_path("satlink_share_pc_icon.ico")
    if icon.exists(): app.setWindowIcon(QIcon(str(icon)))
    w = MainWindow()
    if "--tray" in sys.argv[1:]:
        w.receiver.start()
        # On slow logins Explorer's tray may appear after this process starts.
        # Show the window only if the tray never becomes available.
        QTimer.singleShot(8000, lambda: w.show() if not QSystemTrayIcon.isSystemTrayAvailable() else None)
    else:
        w.show()
    sys.exit(app.exec() if QT_BINDING == "PySide6" else app.exec_())
if __name__ == "__main__": main()
