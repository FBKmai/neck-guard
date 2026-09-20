"""颈椎卫士 Windows 接收端。

接收手机 App 通过局域网 HTTP 上报的前倾事件与截图，弹出 Windows 通知并播放提示音。

协议:
  GET  /api/ping                 -> {"ok": true, "name": "neck-receiver", "version": "1"}
  POST /api/posture/events       multipart/form-data
       字段 event    JSON 文本 {deviceId, ts, type, neckDeg, torsoDeg, thresholdDeg, message}
       字段 snapshot 可选 image/jpeg
       请求头 X-Neck-Token 可选，与 --token 一致才接受
"""
from __future__ import annotations

import argparse
import ctypes
import json
import logging
import os
import re
import signal
import socket
import subprocess
import sys
import threading
import time
from datetime import datetime
from email.parser import BytesParser
from email.policy import HTTP as HTTP_POLICY
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, Optional, Tuple

try:
    import winsound  # Windows only
except ImportError:  # pragma: no cover
    winsound = None

try:
    from winotify import Notification, audio as winotify_audio  # type: ignore
    HAS_WINOTIFY = True
except Exception:  # pragma: no cover
    HAS_WINOTIFY = False

APP_NAME = "颈椎卫士"
VERSION = "1"
MAX_BODY_BYTES = 20 * 1024 * 1024
MAX_SNAPSHOTS = 200
LOG = logging.getLogger("neck-receiver")


# ----------------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------------
class Config:
    def __init__(self, args: argparse.Namespace):
        self.host: str = args.host
        self.port: int = args.port
        self.token: Optional[str] = args.token or None
        self.sound: str = args.sound
        self.beep: bool = args.beep
        self.mute: bool = args.mute
        self.snapshot_dir: Path = Path(args.snapshot_dir).resolve()
        self.quiet_hours: Optional[Tuple[int, int]] = parse_quiet_hours(args.quiet_hours)


def parse_quiet_hours(text: Optional[str]) -> Optional[Tuple[int, int]]:
    """解析 "23-7" 这样的静默时段，返回 (start, end)，可跨午夜。"""
    if not text:
        return None
    m = re.fullmatch(r"\s*(\d{1,2})\s*-\s*(\d{1,2})\s*", text)
    if not m:
        raise ValueError(f"静默时段格式错误: {text!r}，应为 起始小时-结束小时，例如 23-7")
    start, end = int(m.group(1)), int(m.group(2))
    if not (0 <= start <= 24 and 0 <= end <= 24):
        raise ValueError("静默时段小时必须在 0 到 24 之间")
    return start % 24, end % 24


def in_quiet_hours(quiet: Optional[Tuple[int, int]], now: Optional[datetime] = None) -> bool:
    if quiet is None:
        return False
    start, end = quiet
    if start == end:
        return False
    hour = (now or datetime.now()).hour
    if start < end:
        return start <= hour < end
    return hour >= start or hour < end


# ----------------------------------------------------------------------------
# 通知与声音（都在后台线程执行）
# ----------------------------------------------------------------------------
def play_sound(cfg: Config) -> None:
    if cfg.mute or winsound is None:
        return
    try:
        if cfg.beep:
            for freq, dur in ((880, 180), (1175, 180), (880, 260)):
                winsound.Beep(freq, dur)
            return
        sound = cfg.sound
        if sound.lower().endswith(".wav") and Path(sound).exists():
            winsound.PlaySound(str(Path(sound).resolve()), winsound.SND_FILENAME | winsound.SND_NODEFAULT)
        else:
            # 系统别名，例如 SystemExclamation / SystemAsterisk / SystemHand
            winsound.PlaySound(sound, winsound.SND_ALIAS)
    except Exception as e:  # pragma: no cover
        LOG.warning("播放提示音失败: %s", e)


def _escape_xml(s: str) -> str:
    return (
        s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def notify_winotify(title: str, body: str, image: Optional[Path]) -> None:
    toast = Notification(app_id=APP_NAME, title=title, msg=body, icon=str(image) if image else "")
    toast.set_audio(winotify_audio.Silent, loop=False)  # 声音由 play_sound 单独控制
    if image is not None:
        toast.add_actions(label="查看截图", launch=str(image))
    toast.show()


def notify_powershell(title: str, body: str, image: Optional[Path]) -> None:
    image_xml = f'<image placement="hero" src="{_escape_xml(image.as_uri())}"/>' if image else ""
    xml = (
        "<toast><visual><binding template=\"ToastGeneric\">"
        f"<text>{_escape_xml(title)}</text><text>{_escape_xml(body)}</text>{image_xml}"
        "</binding></visual><audio silent=\"true\"/></toast>"
    )
    script = (
        "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null;"
        "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null;"
        "$xml = New-Object Windows.Data.Xml.Dom.XmlDocument;"
        f"$xml.LoadXml(@'\n{xml}\n'@);"
        "$toast = New-Object Windows.UI.Notifications.ToastNotification $xml;"
        f"[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('{APP_NAME}').Show($toast);"
    )
    subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
        check=True,
        capture_output=True,
        timeout=15,
    )


def notify_messagebox(title: str, body: str) -> None:
    MB_ICONWARNING = 0x30
    MB_SETFOREGROUND = 0x10000
    MB_TOPMOST = 0x40000
    ctypes.windll.user32.MessageBoxW(None, body, title, MB_ICONWARNING | MB_SETFOREGROUND | MB_TOPMOST)


def show_notification(title: str, body: str, image: Optional[Path]) -> str:
    """依次尝试 winotify -> PowerShell toast -> MessageBox，返回实际使用的路径名。"""
    if HAS_WINOTIFY:
        try:
            notify_winotify(title, body, image)
            return "winotify"
        except Exception as e:
            LOG.warning("winotify 通知失败，回退 PowerShell: %s", e)
    try:
        notify_powershell(title, body, image)
        return "powershell"
    except Exception as e:
        LOG.warning("PowerShell 通知失败，回退 MessageBox: %s", e)
    try:
        threading.Thread(target=notify_messagebox, args=(title, body), daemon=True).start()
        return "messagebox"
    except Exception as e:
        LOG.error("MessageBox 也失败: %s", e)
        return "none"


# ----------------------------------------------------------------------------
# 截图落盘
# ----------------------------------------------------------------------------
def save_snapshot(cfg: Config, data: bytes, event_type: str, ts_millis: Optional[int]) -> Optional[Path]:
    try:
        cfg.snapshot_dir.mkdir(parents=True, exist_ok=True)
        when = datetime.fromtimestamp(ts_millis / 1000.0) if ts_millis else datetime.now()
        safe_type = re.sub(r"[^A-Za-z0-9_]", "", event_type or "EVENT") or "EVENT"
        base = when.strftime("%Y%m%d_%H%M%S") + f"_{safe_type}"
        path = cfg.snapshot_dir / f"{base}.jpg"
        n = 1
        while path.exists():
            path = cfg.snapshot_dir / f"{base}_{n}.jpg"
            n += 1
        path.write_bytes(data)
        prune_snapshots(cfg.snapshot_dir)
        return path
    except Exception as e:
        LOG.error("截图写入失败: %s", e)
        return None


def prune_snapshots(directory: Path) -> None:
    try:
        files = sorted(directory.glob("*.jpg"), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in files[MAX_SNAPSHOTS:]:
            try:
                old.unlink()
            except OSError:
                pass
    except Exception as e:
        LOG.warning("清理旧截图失败: %s", e)


# ----------------------------------------------------------------------------
# multipart 解析（Python 3.13 已无 cgi 模块，用 email 包）
# ----------------------------------------------------------------------------
def parse_multipart(content_type: str, body: bytes) -> Dict[str, Tuple[bytes, Optional[str], Optional[str]]]:
    """返回 {字段名: (数据, 文件名, 内容类型)}。"""
    if not content_type or "multipart/form-data" not in content_type.lower():
        raise ValueError("Content-Type 必须是 multipart/form-data")
    header = f"Content-Type: {content_type}\r\nMIME-Version: 1.0\r\n\r\n".encode("utf-8")
    msg = BytesParser(policy=HTTP_POLICY).parsebytes(header + body)
    if not msg.is_multipart():
        raise ValueError("multipart 解析失败，可能缺少 boundary")
    fields: Dict[str, Tuple[bytes, Optional[str], Optional[str]]] = {}
    for part in msg.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if not name:
            continue
        filename = part.get_filename()
        payload = part.get_payload(decode=True)
        if payload is None:
            payload = b""
        fields[str(name)] = (payload, filename, part.get_content_type())
    return fields


# ----------------------------------------------------------------------------
# 事件处理
# ----------------------------------------------------------------------------
def fmt_deg(v) -> str:
    try:
        return f"{float(v):.1f}°" if v is not None else "--"
    except (TypeError, ValueError):
        return "--"


def handle_event(cfg: Config, event: dict, snapshot: Optional[bytes]) -> None:
    event_type = str(event.get("type") or "ALERT")
    ts = event.get("ts")
    ts_int: Optional[int] = None
    try:
        ts_int = int(ts) if ts is not None else None
    except (TypeError, ValueError):
        ts_int = None
    when = datetime.fromtimestamp(ts_int / 1000.0) if ts_int else datetime.now()

    path = save_snapshot(cfg, snapshot, event_type, ts_int) if snapshot else None

    neck = fmt_deg(event.get("neckDeg"))
    thr = fmt_deg(event.get("thresholdDeg"))
    torso = fmt_deg(event.get("torsoDeg"))
    msg = event.get("message") or ""
    device = event.get("deviceId") or "未知设备"

    LOG.info(
        "事件 %s 设备=%s 颈=%s 躯干=%s 阈值=%s %s 截图=%s",
        event_type, device, neck, torso, thr, msg, path.name if path else "无",
    )

    quiet = in_quiet_hours(cfg.quiet_hours, when)
    if quiet:
        LOG.info("处于静默时段，只记录不提醒")
        return

    if event_type == "TEST":
        title = "颈椎卫士测试通知"
    else:
        title = "检测到头部前倾"
    body = f"颈部倾角 {neck}，阈值 {thr}，{when.strftime('%H:%M:%S')}"
    if msg:
        body += f"，{msg}"

    def worker() -> None:
        try:
            path_used = show_notification(title, body, path)
            LOG.info("通知已发送（%s）", path_used)
        except Exception as e:
            LOG.error("通知失败: %s", e)
        play_sound(cfg)

    threading.Thread(target=worker, name="notify", daemon=True).start()


# ----------------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = f"neck-receiver/{VERSION}"
    cfg: Config  # 由 make_handler 注入

    def log_message(self, fmt, *args):  # 关掉默认的 stderr 访问日志
        LOG.debug("%s - %s", self.address_string(), fmt % args)

    def _send_json(self, status: int, payload: dict) -> None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _check_token(self) -> bool:
        if not self.cfg.token:
            return True
        return self.headers.get("X-Neck-Token", "") == self.cfg.token

    def do_GET(self):
        if self.path.split("?", 1)[0] == "/api/ping":
            if not self._check_token():
                self._send_json(401, {"ok": False, "error": "token 不匹配"})
                return
            self._send_json(200, {"ok": True, "name": "neck-receiver", "version": VERSION})
            return
        self._send_json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/api/posture/events":
            self._send_json(404, {"ok": False, "error": "not found"})
            return
        if not self._check_token():
            LOG.warning("拒绝来自 %s 的请求：token 不匹配", self.address_string())
            self._send_json(401, {"ok": False, "error": "token 不匹配"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json(400, {"ok": False, "error": "Content-Length 无效"})
            return
        if length <= 0 or length > MAX_BODY_BYTES:
            self._send_json(400, {"ok": False, "error": f"请求体大小无效: {length}"})
            return
        try:
            body = self.rfile.read(length)
        except Exception as e:
            LOG.warning("读取请求体失败: %s", e)
            self._send_json(400, {"ok": False, "error": "读取请求体失败"})
            return
        try:
            fields = parse_multipart(self.headers.get("Content-Type", ""), body)
            if "event" not in fields:
                raise ValueError("缺少 event 字段")
            event = json.loads(fields["event"][0].decode("utf-8"))
            if not isinstance(event, dict):
                raise ValueError("event 必须是 JSON 对象")
            snapshot = fields["snapshot"][0] if "snapshot" in fields and fields["snapshot"][0] else None
        except Exception as e:
            LOG.warning("请求解析失败（来自 %s）: %s", self.address_string(), e)
            self._send_json(400, {"ok": False, "error": f"解析失败: {e}"})
            return
        # 先应答，再在后台处理通知，保证手机端 10 秒读超时内返回
        self._send_json(200, {"ok": True})
        try:
            handle_event(self.cfg, event, snapshot)
        except Exception as e:
            LOG.error("事件处理异常: %s", e)


def make_handler(cfg: Config):
    return type("BoundHandler", (Handler,), {"cfg": cfg})


# ----------------------------------------------------------------------------
# 启动
# ----------------------------------------------------------------------------
def local_ipv4_addresses() -> list:
    addrs = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                addrs.add(ip)
    except socket.gaierror:
        pass
    # 通过 UDP 连接探测默认路由出口地址（不实际发包）
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        addrs.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    return sorted(addrs)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="颈椎卫士 Windows 接收端")
    p.add_argument("--host", default="0.0.0.0", help="监听地址，默认 0.0.0.0")
    p.add_argument("--port", type=int, default=8765, help="监听端口，默认 8765")
    p.add_argument("--token", default="", help="共享密钥，手机端请求头 X-Neck-Token 需一致；留空不校验")
    p.add_argument("--sound", default="SystemExclamation", help="提示音：wav 文件路径或系统别名，默认 SystemExclamation")
    p.add_argument("--beep", action="store_true", help="用蜂鸣序列代替 wav")
    p.add_argument("--mute", action="store_true", help="不播放声音")
    p.add_argument("--snapshot-dir", default=str(Path(__file__).resolve().parent / "snapshots"), help="截图保存目录")
    p.add_argument("--quiet-hours", default="", help="静默时段，例如 23-7，该时段只记录不提醒")
    p.add_argument("--verbose", action="store_true", help="打印访问日志")
    return p


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except Exception:
            pass
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    try:
        cfg = Config(args)
    except ValueError as e:
        LOG.error("%s", e)
        return 2

    try:
        server = ThreadingHTTPServer((cfg.host, cfg.port), make_handler(cfg))
    except OSError as e:
        LOG.error("监听 %s:%d 失败: %s（端口被占用或无权限）", cfg.host, cfg.port, e)
        return 1
    server.daemon_threads = True

    ips = local_ipv4_addresses()
    print("=" * 60)
    print(f"{APP_NAME} 接收端已启动，监听 {cfg.host}:{cfg.port}")
    if ips:
        print("请在手机设置页填写以下任一地址（选与手机同一 Wi-Fi 的那个）：")
        for ip in ips:
            print(f"    http://{ip}:{cfg.port}")
    else:
        print("未探测到局域网 IPv4 地址，请用 ipconfig 查看后填 http://<电脑IP>:%d" % cfg.port)
    print(f"共享密钥: {'已启用' if cfg.token else '未设置（任何设备都可上报）'}")
    print(f"提示音: {'静音' if cfg.mute else ('蜂鸣' if cfg.beep else cfg.sound)}   "
          f"通知方式: {'winotify' if HAS_WINOTIFY else 'PowerShell toast'}")
    if cfg.quiet_hours:
        print(f"静默时段: {cfg.quiet_hours[0]:02d}:00 - {cfg.quiet_hours[1]:02d}:00")
    print(f"截图目录: {cfg.snapshot_dir}")
    print("若手机「测试连接」失败，请以管理员身份放行防火墙端口：")
    print(f'    netsh advfirewall firewall add rule name="NeckGuard {cfg.port}" dir=in action=allow protocol=TCP localport={cfg.port}')
    print("按 Ctrl+C 退出")
    print("=" * 60)

    stop = threading.Event()

    def on_signal(signum, frame):
        stop.set()

    signal.signal(signal.SIGINT, on_signal)
    try:
        signal.signal(signal.SIGTERM, on_signal)
    except (AttributeError, ValueError):
        pass

    t = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.5}, daemon=True)
    t.start()
    try:
        while not stop.is_set():
            time.sleep(0.3)
    except KeyboardInterrupt:
        pass
    LOG.info("正在退出")
    server.shutdown()
    server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
