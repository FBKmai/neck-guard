"""颈椎卫士 Windows 接收端。

接收手机 App 通过局域网 HTTP 上报的前倾事件与截图，弹出 Windows 通知并播放提示音。

协议:
  GET  /api/ping                 -> {"ok": true, "name": "neck-receiver", "version": "1"}
  POST /api/posture/events       multipart/form-data
       字段 event    JSON 文本 {deviceId, ts, type, neckDeg, torsoDeg, thresholdDeg, message}
                     type 为 ALERT / CONFIRMED / RECOVERED / TEST
       字段 snapshot 可选 image/jpeg
       请求头 X-Neck-Token 可选，与 --token 一致才接受

局域网发现（UDP，默认 8766）:
  收 {"neckguard": "discover", "v": 1}          手机广播的探针
  回 {"neckguard": "receiver", "name", "host", "port", "token_required", "v"}
  host 用「路由探测」算出与手机同网段的本机 IP，避免手填到虚拟网卡地址。
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
DISCOVERY_PORT = 8766
DISCOVERY_MAGIC = "neckguard"
FIREWALL_RULE_PREFIX = "NeckGuard Receiver"
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
        self.discovery_port: int = args.discovery_port
        self.no_firewall: bool = args.no_firewall


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

    recovered = event_type == "RECOVERED"
    # CONFIRMED 是冷却期内的持续前倾，弹通知但不响铃，避免长时间低头时铃声轰炸
    confirmed = event_type == "CONFIRMED"
    if event_type == "TEST":
        title = "颈椎卫士测试通知"
    elif recovered:
        title = "已恢复端正坐姿"
    elif confirmed:
        title = "仍在前倾"
    else:
        title = "检测到头部前倾"
    body = f"前倾角 {neck}，阈值 {thr}，{when.strftime('%H:%M:%S')}"
    if msg:
        body += f"，{msg}"

    def worker() -> None:
        try:
            path_used = show_notification(title, body, path)
            LOG.info("通知已发送（%s）", path_used)
        except Exception as e:
            LOG.error("通知失败: %s", e)
        # 恢复提示与冷却期内的持续前倾都只弹通知不响铃：
        # 前者避免坐正后反而被打扰，后者避免长时间低头时每个确认窗都响一次
        if not recovered and not confirmed:
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
# 局域网自动发现（UDP 探针 -> 单播回应）
# ----------------------------------------------------------------------------
def route_ip_toward(peer_ip: str) -> Optional[str]:
    """借内核选路算出与 peer 同网段、可达的本机 IP（connect UDP 不实际发包）。

    这是自动发现的关键。本机常有多张网卡（VMware / VirtualBox / WSL / Hyper-V），
    若回一个虚拟网卡地址，手机会连不上（表现为「连接被拒绝」）。
    """
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect((peer_ip, 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        if s is not None:
            try:
                s.close()
            except OSError:
                pass


class DiscoveryService(threading.Thread):
    """监听 UDP 探针并单播回应本机接收端地址，与 App 的 PcDiscovery.kt 对应。"""

    def __init__(self, cfg: Config, stop: threading.Event):
        super().__init__(name="discovery", daemon=True)
        self.cfg = cfg
        self.stop_event = stop
        self.sock: Optional[socket.socket] = None

    def bind(self) -> bool:
        """绑定 UDP 端口。失败只告警，不影响 HTTP 接收（手机仍可手填地址）。"""
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("0.0.0.0", self.cfg.discovery_port))
            s.settimeout(0.5)
            self.sock = s
            return True
        except OSError as e:
            LOG.warning("发现服务绑定 UDP %d 失败: %s（手机端需手填地址）", self.cfg.discovery_port, e)
            return False

    def run(self) -> None:
        if self.sock is None:
            return
        hostname = socket.gethostname()
        while not self.stop_event.is_set():
            try:
                data, addr = self.sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError as e:
                if not self.stop_event.is_set():
                    LOG.debug("发现服务接收异常: %s", e)
                continue
            try:
                self._reply(data, addr, hostname)
            except Exception as e:  # 单个坏包不能拖垮整个线程
                LOG.debug("处理发现探针失败（来自 %s）: %s", addr, e)

    def _reply(self, data: bytes, addr, hostname: str) -> None:
        try:
            probe = json.loads(data.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return
        if not isinstance(probe, dict) or probe.get(DISCOVERY_MAGIC) != "discover":
            return
        peer_ip = addr[0]
        host = route_ip_toward(peer_ip) or self.cfg.host
        if host in ("0.0.0.0", ""):
            ips = local_ipv4_addresses()
            host = ips[0] if ips else peer_ip
        payload = {
            DISCOVERY_MAGIC: "receiver",
            "name": hostname,
            "host": host,
            "port": self.cfg.port,
            "token_required": bool(self.cfg.token),
            "v": 1,
        }
        if self.sock is not None:
            self.sock.sendto(json.dumps(payload, ensure_ascii=False).encode("utf-8"), addr)
        LOG.info("回应发现探针: %s -> http://%s:%d", peer_ip, host, self.cfg.port)

    def close(self) -> None:
        if self.sock is not None:
            try:
                self.sock.close()
            except OSError:
                pass


# ----------------------------------------------------------------------------
# Windows 防火墙放行
# ----------------------------------------------------------------------------
def netsh_commands(cfg: Config) -> list:
    """delete 再 add，保证重复执行或改端口后都能落到正确规则。"""
    tcp = f"{FIREWALL_RULE_PREFIX} TCP"
    udp = f"{FIREWALL_RULE_PREFIX} UDP"
    return [
        f'netsh advfirewall firewall delete rule name="{tcp}"',
        f'netsh advfirewall firewall add rule name="{tcp}" dir=in action=allow protocol=TCP localport={cfg.port}',
        f'netsh advfirewall firewall delete rule name="{udp}"',
        f'netsh advfirewall firewall add rule name="{udp}" dir=in action=allow protocol=UDP localport={cfg.discovery_port}',
    ]


def is_admin() -> bool:
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def firewall_rules_present(cfg: Config) -> bool:
    """规则存在且端口对得上才算放行，这样改了端口会自动重新放行。"""
    script = (
        f"$r = Get-NetFirewallRule -DisplayName '{FIREWALL_RULE_PREFIX}*' -ErrorAction SilentlyContinue;"
        "if (-not $r) { exit 1 };"
        '$p = ($r | Get-NetFirewallPortFilter | ForEach-Object { "$($_.Protocol):$($_.LocalPort)" }) -join \',\';'
        "Write-Output $p"
    )
    try:
        p = subprocess.run(
            ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script],
            capture_output=True, timeout=25, text=True,
        )
    except Exception as e:
        LOG.debug("查询防火墙规则失败: %s", e)
        return False
    if p.returncode != 0:
        return False
    out = (p.stdout or "").upper()
    return f"TCP:{cfg.port}" in out and f"UDP:{cfg.discovery_port}" in out


def ensure_firewall_rules(cfg: Config) -> str:
    """确保 TCP(上报) 与 UDP(发现) 入站放行，返回给启动横幅用的状态文案。"""
    if os.name != "nt":
        return "非 Windows，跳过"
    if cfg.no_firewall:
        return "已按 --no-firewall 跳过"
    try:
        if firewall_rules_present(cfg):
            return "已放行"
    except Exception as e:
        LOG.debug("防火墙检测异常: %s", e)

    cmds = netsh_commands(cfg)
    if is_admin():
        ok = True
        for c in cmds:
            try:
                r = subprocess.run(c, shell=True, capture_output=True, timeout=25)
                # delete 在规则不存在时返回非 0 属正常，只校验 add
                if " add " in c and r.returncode != 0:
                    ok = False
            except Exception as e:
                LOG.warning("执行防火墙命令失败: %s", e)
                ok = False
        return "已自动添加" if ok else "添加失败，请手动执行下面的命令"

    # 非管理员：弹一次 UAC，由提权进程落规则
    joined = " & ".join(cmds)
    try:
        rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", "cmd.exe", f'/c "{joined}"', None, 0)
        if int(rc) > 32:
            time.sleep(1.5)  # 给提权进程一点时间写入规则
            if firewall_rules_present(cfg):
                return "已自动添加（管理员）"
            return "已请求管理员放行，若仍连不上请手动执行下面的命令"
        return "提权被拒绝，请手动执行下面的命令"
    except Exception as e:
        LOG.debug("提权放行失败: %s", e)
        return "无法自动放行，请手动执行下面的命令"


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
    p.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT,
                   help=f"局域网发现 UDP 端口，手机端「扫描电脑」用，默认 {DISCOVERY_PORT}")
    p.add_argument("--no-firewall", action="store_true", help="不自动放行 Windows 防火墙")
    p.add_argument("--firewall-only", action="store_true", help="只放行防火墙后退出，不启动服务")
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

    if args.firewall_only:
        status = ensure_firewall_rules(cfg)
        print(f"防火墙放行: {status}")
        if "手动" in status:
            for c in netsh_commands(cfg):
                print("    " + c)
        return 0

    try:
        server = ThreadingHTTPServer((cfg.host, cfg.port), make_handler(cfg))
    except OSError as e:
        LOG.error("监听 %s:%d 失败: %s（端口被占用或无权限）", cfg.host, cfg.port, e)
        return 1
    server.daemon_threads = True

    firewall_status = ensure_firewall_rules(cfg)

    stop = threading.Event()
    discovery = DiscoveryService(cfg, stop)
    discovery_ok = discovery.bind()

    ips = local_ipv4_addresses()
    print("=" * 60)
    print(f"{APP_NAME} 接收端已启动，监听 {cfg.host}:{cfg.port}")
    if discovery_ok:
        print(f"自动发现: 已开启（UDP {cfg.discovery_port}）")
        print("  >> 手机 App 设置页点「扫描电脑」即可自动填地址，无需手动输入 <<")
    else:
        print(f"自动发现: 未启用（UDP {cfg.discovery_port} 绑定失败），请在手机端手填地址")
    print(f"防火墙放行: {firewall_status}")
    if "手动" in firewall_status:
        print("  请以管理员身份执行：")
        for c in netsh_commands(cfg):
            print("    " + c)
    if ips:
        print("手填地址（扫描失败时备用，多个地址时选与手机同一 Wi-Fi 的那个）：")
        for ip in ips:
            print(f"    http://{ip}:{cfg.port}")
        if len(ips) > 1:
            print("    注意：列出多个通常是虚拟网卡（VMware/WSL/Hyper-V），挑错会连接被拒绝，用「扫描电脑」最稳")
    else:
        print("未探测到局域网 IPv4 地址，请用 ipconfig 查看后填 http://<电脑IP>:%d" % cfg.port)
    print(f"共享密钥: {'已启用' if cfg.token else '未设置（任何设备都可上报）'}")
    print(f"提示音: {'静音' if cfg.mute else ('蜂鸣' if cfg.beep else cfg.sound)}   "
          f"通知方式: {'winotify' if HAS_WINOTIFY else 'PowerShell toast'}")
    if cfg.quiet_hours:
        print(f"静默时段: {cfg.quiet_hours[0]:02d}:00 - {cfg.quiet_hours[1]:02d}:00")
    print(f"截图目录: {cfg.snapshot_dir}")
    print("按 Ctrl+C 退出")
    print("=" * 60)

    def on_signal(signum, frame):
        stop.set()

    signal.signal(signal.SIGINT, on_signal)
    try:
        signal.signal(signal.SIGTERM, on_signal)
    except (AttributeError, ValueError):
        pass

    t = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.5}, daemon=True)
    t.start()
    if discovery_ok:
        discovery.start()
    try:
        while not stop.is_set():
            time.sleep(0.3)
    except KeyboardInterrupt:
        pass
    LOG.info("正在退出")
    stop.set()
    discovery.close()
    server.shutdown()
    server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
