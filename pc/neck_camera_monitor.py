"""颈椎卫士 无线相机模式：电脑拉手机的 MJPEG 流，在本机做姿态检测并提醒。

手机 App 设置页把「检测方式」选成「电脑检测」后点开始推流，本脚本负责：
  1. 局域网扫描手机（UDP 广播），或用 --url 手填
  2. 拉 MJPEG 流，只保留最新帧，断线指数退避重连
  3. 用 YOLO26-pose（GPU）或 MediaPipe 推理，复用 tools/ 的几何与双速状态机
  4. 前倾确认后弹 Windows 通知 + 响铃，截图落盘

用法：
  python neck_camera_monitor.py                      自动扫描手机
  python neck_camera_monitor.py --url http://192.168.1.20:8767/video
  python neck_camera_monitor.py --show               开预览窗（c 校准 / r 清除 / q 退出）
  python neck_camera_monitor.py --backend mediapipe  没装 torch 时的后备

前倾角 = 耳肩连线与髋肩连线的夹角，与 Android 端一字不差，见 tools/posture_geometry.py。
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable, Dict, List, Optional, Tuple

PC_DIR = Path(__file__).resolve().parent
TOOLS_DIR = PC_DIR.parent / "tools"
sys.path.insert(0, str(TOOLS_DIR))
sys.path.insert(0, str(PC_DIR))

import posture_geometry as pg  # noqa: E402
import posture_tracker as pt  # noqa: E402

# 通知、响铃、静默时段、截图清理全部复用接收端的实现。
# save_snapshot 没复用：它的签名带 Config，这里自己写一份更轻的。
from neck_receiver import (  # noqa: E402
    in_quiet_hours,
    parse_quiet_hours,
    play_sound,
    prune_snapshots,
    show_notification,
)

LOG = logging.getLogger("neck-camera")

CAMERA_DISCOVERY_PORT = 8768

#: v0.6 存档里只有一个全局基线，迁移时挂到这个 key 下，按 profile 存过一次后清掉
LEGACY_PROFILE = "_legacy"
DISCOVERY_MAGIC = "neckguard"
DISCOVERY_PROBE = "discover-camera"
DEFAULT_STREAM_PORT = 8767
HEADER_TOKEN = "X-Neck-Token"

# COCO 17 点 -> MediaPipe 33 点的索引映射，只填几何用得到的那几个
COCO_TO_MP = {
    0: pg.NOSE,
    3: pg.LEFT_EAR,
    4: pg.RIGHT_EAR,
    5: pg.LEFT_SHOULDER,
    6: pg.RIGHT_SHOULDER,
    11: pg.LEFT_HIP,
    12: pg.RIGHT_HIP,
}

MODEL_BY_VRAM = [
    (15.0, "yolo26x-pose.pt"),
    (10.0, "yolo26l-pose.pt"),
    (7.0, "yolo26m-pose.pt"),
    (0.0, "yolo26s-pose.pt"),
]


# ----------------------------------------------------------------------------
# 手机发现
# ----------------------------------------------------------------------------
@dataclass
class Camera:
    name: str
    host: str
    port: int
    token_required: bool

    @property
    def video_url(self) -> str:
        return f"http://{self.host}:{self.port}/video"


def discover_cameras(port: int = CAMERA_DISCOVERY_PORT, timeout: float = 2.0) -> List[Camera]:
    """广播探针找手机，是 App 里 CameraDiscoveryResponder 的对端。失败返回空列表。"""
    found: Dict[str, Camera] = {}
    sock = None
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.3)
        probe = json.dumps({DISCOVERY_MAGIC: DISCOVERY_PROBE, "v": 1}).encode("utf-8")
        sent = 0
        for target in broadcast_targets():
            try:
                sock.sendto(probe, (target, port))
                sent += 1
            except OSError as e:
                LOG.debug("向 %s 发探针失败: %s", target, e)
        if sent == 0:
            LOG.warning("没有可用的广播地址")
            return []
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                data, addr = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError as e:
                LOG.debug("接收回应失败: %s", e)
                break
            cam = parse_camera_reply(data, addr)
            if cam is not None:
                found.setdefault(cam.video_url, cam)
    except OSError as e:
        LOG.warning("扫描失败: %s", e)
    finally:
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass
    return list(found.values())


def broadcast_targets() -> List[str]:
    """全局广播加各网卡定向广播，覆盖屏蔽 255.255.255.255 的路由器。"""
    targets = ["255.255.255.255"]
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127."):
                continue
            parts = ip.split(".")
            if len(parts) == 4:
                # 按 /24 推定广播地址，家用网络基本都是这个掩码
                candidate = ".".join(parts[:3] + ["255"])
                if candidate not in targets:
                    targets.append(candidate)
    except socket.gaierror:
        pass
    return targets


def parse_camera_reply(data: bytes, addr) -> Optional[Camera]:
    try:
        payload = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None
    if not isinstance(payload, dict) or payload.get(DISCOVERY_MAGIC) != "camera":
        return None
    host = payload.get("host") or addr[0]
    try:
        port = int(payload.get("port", DEFAULT_STREAM_PORT))
    except (TypeError, ValueError):
        return None
    if not host or not (1 <= port <= 65535):
        return None
    return Camera(
        name=str(payload.get("name") or host),
        host=str(host),
        port=port,
        token_required=bool(payload.get("token_required", False)),
    )


# ----------------------------------------------------------------------------
# MJPEG 拉流
# ----------------------------------------------------------------------------
_OPENER = None


def _direct_opener() -> "urllib.request.OpenerDirector":
    """绕开系统代理直连手机。

    很多机器上设了 HTTP_PROXY（本仓库访问 GitHub 就要走代理），
    默认的 urlopen 会把局域网地址也扔给代理，表现为 502 或连接被拒。
    手机就在同一个 Wi-Fi 里，永远直连。
    """
    global _OPENER
    if _OPENER is None:
        _OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return _OPENER


class MjpegClient(threading.Thread):
    """后台拉流线程，只保留最新一帧。

    不用 cv2.VideoCapture(url)：它内部有缓冲，断线重连也不可控，
    自己按 multipart 边界解析能保证「永远拿到最新帧」，延迟最低。
    """

    def __init__(self, url: str, token: str, stop: threading.Event):
        super().__init__(name="mjpeg-client", daemon=True)
        self.url = url
        self.token = token
        self.stop_event = stop
        self._lock = threading.Lock()
        self._latest: Optional[bytes] = None
        self._seq = 0
        self.frames_received = 0
        self.decode_failures = 0
        self.reconnects = 0
        #: 手机推来但主循环没来得及取、被新帧顶掉的帧数。
        #: 这是「推理跟不上推流」的信号，与网络丢包不是一回事：
        #: MJPEG 走 TCP 不会真丢包，掉的是我们自己主动跳过的旧帧。
        self.frames_skipped = 0
        #: 累计收到的 JPEG 字节数，用来估带宽。
        self.bytes_received = 0
        self.last_error: Optional[str] = None
        self.connected = False

    def latest(self, last_seq: int) -> Tuple[Optional[bytes], int]:
        """取比 last_seq 新的一帧；没有新帧返回 (None, last_seq)。

        序号从 last_seq 跳到 self._seq 之间的帧都被跳过了，计入 frames_skipped。
        """
        with self._lock:
            if self._seq == last_seq or self._latest is None:
                return None, last_seq
            # 首次取帧时 last_seq 为 0，那之前的帧是连接建立过程中的，不算跳过
            if last_seq > 0:
                self.frames_skipped += self._seq - last_seq - 1
            return self._latest, self._seq

    def stats(self) -> Dict[str, float]:
        """给状态行与退出摘要用的一组计数。"""
        with self._lock:
            received = self.frames_received
            skipped = self.frames_skipped
            return {
                "received": received,
                "skipped": skipped,
                # 推来的帧里有多大比例没能进推理
                "skip_ratio": (skipped / received) if received else 0.0,
                "decode_failures": self.decode_failures,
                "reconnects": self.reconnects,
                "bytes": self.bytes_received,
            }

    def run(self) -> None:
        backoff = 1.0
        while not self.stop_event.is_set():
            try:
                self._pump()
                backoff = 1.0
            except Exception as e:  # noqa: BLE001
                if self.stop_event.is_set():
                    break
                self.connected = False
                self.last_error = f"{type(e).__name__}: {e}"
                self.reconnects += 1
                LOG.warning("拉流中断（%s），%.0f 秒后重连", self.last_error, backoff)
                # 指数退避，与 App 侧相机重连同样的策略
                waited = 0.0
                while waited < backoff and not self.stop_event.is_set():
                    time.sleep(0.2)
                    waited += 0.2
                backoff = min(backoff * 2, 30.0)

    def _pump(self) -> None:
        headers = {HEADER_TOKEN: self.token} if self.token else {}
        req = urllib.request.Request(self.url, headers=headers)
        with _direct_opener().open(req, timeout=10) as resp:
            content_type = resp.headers.get("Content-Type", "")
            boundary = self._boundary_of(content_type)
            if boundary is None:
                raise RuntimeError(f"不是 MJPEG 流（Content-Type: {content_type}）")
            self.connected = True
            self.last_error = None
            LOG.info("已连接 %s", self.url)
            buffer = b""
            marker = b"--" + boundary.encode("ascii")
            while not self.stop_event.is_set():
                chunk = resp.read(8192)
                if not chunk:
                    raise RuntimeError("流已结束（手机可能停止了推流）")
                buffer += chunk
                # 一次可能读进多段，循环切到切不动为止
                while True:
                    consumed, buffer = self._take_part(buffer, marker)
                    if not consumed:
                        break
                # 正常情况下 buffer 里只剩半段，异常时防止无限增长
                if len(buffer) > 8 * 1024 * 1024:
                    raise RuntimeError("缓冲区异常增长，重连")

    def _take_part(self, buffer: bytes, marker: bytes) -> Tuple[bool, bytes]:
        """从缓冲里切出一段完整的 JPEG。返回 (是否切出, 剩余缓冲)。"""
        start = buffer.find(marker)
        if start < 0:
            return False, buffer
        header_end = buffer.find(b"\r\n\r\n", start)
        if header_end < 0:
            return False, buffer
        header = buffer[start:header_end].decode("latin-1", "replace")
        length = None
        for line in header.split("\r\n"):
            if line.lower().startswith("content-length:"):
                try:
                    length = int(line.split(":", 1)[1].strip())
                except ValueError:
                    length = None
        body_start = header_end + 4
        if length is None:
            # 没给长度就找下一个边界，兼容不规范的推流端
            next_marker = buffer.find(marker, body_start)
            if next_marker < 0:
                return False, buffer
            body = buffer[body_start:next_marker].rstrip(b"\r\n")
            rest = buffer[next_marker:]
        else:
            if len(buffer) < body_start + length:
                return False, buffer
            body = buffer[body_start:body_start + length]
            rest = buffer[body_start + length:]
        if body:
            with self._lock:
                self._latest = body
                self._seq += 1
                self.frames_received += 1
                self.bytes_received += len(body)
        return True, rest

    @staticmethod
    def _boundary_of(content_type: str) -> Optional[str]:
        lowered = content_type.lower()
        if "multipart" not in lowered:
            return None
        for part in content_type.split(";"):
            part = part.strip()
            if part.lower().startswith("boundary="):
                return part.split("=", 1)[1].strip().strip('"')
        return None


# ----------------------------------------------------------------------------
# 姿态后端
# ----------------------------------------------------------------------------
class PoseBackend:
    """统一接口：喂一帧 BGR 图，返回 33 点的 Landmark 列表或 None。"""

    name = "base"

    @property
    def profile(self) -> str:
        """基线存档用的 key：不同后端 / 不同模型的角度有系统偏差，要分开校准。"""
        return self.name

    @property
    def min_visibility(self) -> float:
        """该后端的关键点可信阈值。

        MediaPipe 的 visibility（点是否被遮挡）与 Ultralytics 的关键点 conf
        不是一个量纲，同样是 0.5 的含义并不相同，所以让各后端自己给默认值。
        """
        return 0.5

    def infer(self, bgr) -> Optional[List[pg.Landmark]]:
        raise NotImplementedError

    def close(self) -> None:
        pass


def _ultralytics_at_least(major: int, minor: int) -> bool:
    """判断已装的 ultralytics 是否到某个版本。读不到版本号时按新版处理。"""
    try:
        import ultralytics

        parts = str(ultralytics.__version__).split(".")
        return (int(parts[0]), int(parts[1])) >= (major, minor)
    except (ImportError, AttributeError, IndexError, ValueError):
        return True


class YoloBackend(PoseBackend):
    """Ultralytics YOLO26-pose。COCO 17 点按 COCO_TO_MP 映射成几何模块要的 33 点。"""

    name = "yolo"

    def __init__(self, model_path: Optional[str], device: Optional[str], imgsz: int,
                 half: bool, conf: float, kpt_conf: float = 0.5):
        try:
            from ultralytics import YOLO
        except ImportError as e:
            raise RuntimeError(
                "未安装 ultralytics。请执行：\n"
                "  pip install -r pc/requirements-camera.txt\n"
                "GPU 版 torch 另装（5090 等 Blackwell 卡用 cu128）：\n"
                "  pip install torch torchvision --index-url https://download.pytorch.org/whl/cu128"
            ) from e

        self.device = device or self._auto_device()
        path = model_path or self._auto_model()
        LOG.info("加载模型 %s（device=%s, imgsz=%d, half=%s）", path, self.device, imgsz, half)
        try:
            self.model = YOLO(path)
        except Exception as e:  # noqa: BLE001
            raise RuntimeError(
                f"模型加载失败：{e}\n"
                f"首次运行需要从 GitHub 下载权重，走代理试试：\n"
                f'  $env:HTTPS_PROXY = "http://127.0.0.1:2080"'
            ) from e
        self.imgsz = imgsz
        self.half = half and self.device != "cpu"
        self.conf = conf
        self._kpt_conf = kpt_conf
        self._model_name = Path(path).stem
        self._fail_streak = 0
        # ultralytics 8.4 起 half=True 改成 quantize="fp16"，传旧参数会刷废弃警告。
        # predict 的签名是 **kwargs，探测不到参数名，所以按版本号判断。
        self._precision: Dict[str, object] = {}
        if self.half:
            self._precision = {"quantize": "fp16"} if _ultralytics_at_least(8, 4) else {"half": True}

    @property
    def profile(self) -> str:
        # 模型大小不同，关键点位置也有细微系统偏差，基线跟着模型走
        return f"{self.name}:{self._model_name}"

    @property
    def min_visibility(self) -> float:
        return self._kpt_conf

    @staticmethod
    def _auto_device() -> str:
        try:
            import torch

            if torch.cuda.is_available():
                return "cuda:0"
            LOG.warning("torch 检测不到 CUDA，将用 CPU 推理（会很慢）。"
                        "RTX 50 系需要驱动 >= 570 与 cu128 版 torch")
        except ImportError:
            LOG.warning("未安装 torch，ultralytics 会自己装 CPU 版")
        return "cpu"

    @staticmethod
    def _auto_model() -> str:
        """按显存挑模型：显存越大用越强的。"""
        try:
            import torch

            if torch.cuda.is_available():
                vram = torch.cuda.get_device_properties(0).total_memory / (1024 ** 3)
                for need, name in MODEL_BY_VRAM:
                    if vram >= need:
                        LOG.info("检测到显存 %.1f GB，自动选用 %s", vram, name)
                        return name
        except Exception as e:  # noqa: BLE001
            LOG.debug("读取显存失败: %s", e)
        return "yolo26s-pose.pt"

    def infer(self, bgr) -> Optional[List[pg.Landmark]]:
        try:
            results = self.model(bgr, imgsz=self.imgsz, conf=self.conf, device=self.device,
                                 verbose=False, **self._precision)
            self._fail_streak = 0
        except Exception as e:  # noqa: BLE001
            self._fail_streak += 1
            LOG.warning("推理失败(%d): %s", self._fail_streak, e)
            if self._fail_streak >= 10:
                raise RuntimeError(f"连续 10 次推理失败，放弃：{e}") from e
            return None
        if not results:
            return None
        kpts = getattr(results[0], "keypoints", None)
        if kpts is None or kpts.data is None or len(kpts.data) == 0:
            return None
        data = kpts.data[0]            # (17, 3) -> x, y, conf
        if data.shape[0] < 13:         # 至少要有髋部那一档
            return None
        h, w = bgr.shape[:2]
        out = [pg.Landmark(0.0, 0.0, 0.0) for _ in range(pg.LANDMARK_COUNT)]
        for coco_i, mp_i in COCO_TO_MP.items():
            if coco_i >= data.shape[0]:
                continue
            x, y, conf = float(data[coco_i][0]), float(data[coco_i][1]), float(data[coco_i][2])
            out[mp_i] = pg.Landmark(x / max(w, 1), y / max(h, 1), conf)
        return out


class MediaPipeBackend(PoseBackend):
    """MediaPipe Pose Landmarker，直接复用 tools/posture_probe.py 的加载逻辑。"""

    name = "mediapipe"

    def __init__(self, proxy: Optional[str], visibility: float = 0.5):
        try:
            import posture_probe as probe
        except ImportError as e:
            raise RuntimeError(f"无法导入 tools/posture_probe.py：{e}") from e
        self._probe = probe
        model = probe.ensure_model(proxy)
        self._landmarker = probe.create_landmarker(model, video_mode=True)
        self._visibility = visibility
        self._t0 = time.monotonic()

    @property
    def min_visibility(self) -> float:
        return self._visibility

    def infer(self, bgr) -> Optional[List[pg.Landmark]]:
        ts_ms = int((time.monotonic() - self._t0) * 1000)
        try:
            result = self._landmarker.detect_for_video(self._probe.to_mp_image(bgr), ts_ms)
        except Exception as e:  # noqa: BLE001
            LOG.warning("推理失败: %s", e)
            return None
        return self._probe.result_to_landmarks(result)

    def close(self) -> None:
        try:
            self._landmarker.close()
        except Exception:  # noqa: BLE001
            pass


def build_backend(args) -> PoseBackend:
    """两个后端的关键点阈值分开给：不传时各用各的默认值。"""
    mp_vis = args.mp_visibility if args.mp_visibility is not None else 0.5
    if args.backend == "mediapipe":
        return MediaPipeBackend(args.proxy, mp_vis)
    kpt_conf = args.kpt_conf if args.kpt_conf is not None else 0.5
    try:
        return YoloBackend(args.model, args.device, args.imgsz, args.half, args.conf, kpt_conf)
    except RuntimeError as e:
        LOG.error("%s", e)
        LOG.warning("改用 MediaPipe 后备后端")
        return MediaPipeBackend(args.proxy, mp_vis)


# ----------------------------------------------------------------------------
# 配置持久化（基线与上次用的地址）
# ----------------------------------------------------------------------------
@dataclass
class State:
    """基线按「后端 + 模型」分开存。

    YOLO 与 MediaPipe 虽然都给耳肩髋，但关键点的定义位置不完全一样，同一个
    坐姿两边算出的角度可能差几度；换模型大小（s/m/l/x）也有类似的系统偏差。
    共用一个基线会让换后端之后的阈值整体偏移，所以按 key 分开保存。
    """

    #: profile key -> 基线角度
    baselines: Dict[str, float] = field(default_factory=dict)
    last_url: Optional[str] = None

    @classmethod
    def load(cls, path: Path) -> "State":
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return cls()
        if not isinstance(data, dict):
            return cls()
        raw = data.get("baselines")
        baselines: Dict[str, float] = {}
        if isinstance(raw, dict):
            for k, v in raw.items():
                if isinstance(v, (int, float)):
                    baselines[str(k)] = float(v)
        # v0.6 只有一个全局 baseline_deg，迁到 legacy key 上，用户换后端时重新校准
        legacy = data.get("baseline_deg")
        if not baselines and isinstance(legacy, (int, float)):
            baselines[LEGACY_PROFILE] = float(legacy)
        url = data.get("last_url")
        return cls(baselines=baselines, last_url=url if isinstance(url, str) else None)

    def baseline_for(self, profile: str) -> Optional[float]:
        """取该 profile 的基线；没有时退回 v0.6 的全局基线（只用一次，存回时归位）。"""
        if profile in self.baselines:
            return self.baselines[profile]
        return self.baselines.get(LEGACY_PROFILE)

    def set_baseline(self, profile: str, deg: Optional[float]) -> None:
        if deg is None:
            self.baselines.pop(profile, None)
        else:
            self.baselines[profile] = float(deg)
        # 一旦按 profile 存过，就不再需要迁移用的旧键
        self.baselines.pop(LEGACY_PROFILE, None)

    def save(self, path: Path) -> None:
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(
                json.dumps({"baselines": self.baselines, "last_url": self.last_url},
                           ensure_ascii=False, indent=2),
                encoding="utf-8",
            )
        except OSError as e:
            LOG.warning("保存配置失败: %s", e)


# ----------------------------------------------------------------------------
# 提醒
# ----------------------------------------------------------------------------
@dataclass
class NotifyConfig:
    snapshot_dir: Path
    quiet_hours: Optional[Tuple[int, int]]
    sound: str
    beep: bool
    mute: bool


def fmt_deg(v) -> str:
    try:
        return f"{float(v):.1f}°" if v is not None else "--"
    except (TypeError, ValueError):
        return "--"


def save_snapshot(cfg: NotifyConfig, jpeg: bytes, event_type: str) -> Optional[Path]:
    try:
        cfg.snapshot_dir.mkdir(parents=True, exist_ok=True)
        name = datetime.now().strftime("%Y%m%d_%H%M%S") + f"_{event_type}.jpg"
        path = cfg.snapshot_dir / name
        n = 1
        while path.exists():
            path = cfg.snapshot_dir / (path.stem + f"_{n}.jpg")
            n += 1
        path.write_bytes(jpeg)
        prune_snapshots(cfg.snapshot_dir)
        return path
    except Exception as e:  # noqa: BLE001
        LOG.error("截图写入失败: %s", e)
        return None


def notify(cfg: NotifyConfig, event_type: str, neck: Optional[float], threshold: Optional[float],
           message: str, jpeg: Optional[bytes]) -> None:
    """与 pc/neck_receiver.py 的事件表一致：只有 ALERT 响铃。"""
    if in_quiet_hours(cfg.quiet_hours):
        LOG.info("处于静默时段，只记录不提醒")
        return
    path = save_snapshot(cfg, jpeg, event_type) if jpeg else None
    title = {
        "ALERT": "检测到头部前倾",
        "CONFIRMED": "仍在前倾",
        "RECOVERED": "已恢复端正坐姿",
    }.get(event_type, "颈椎卫士")
    body = f"前倾角 {fmt_deg(neck)}，阈值 {fmt_deg(threshold)}，{datetime.now().strftime('%H:%M:%S')}"
    if message:
        body += f"，{message}"

    def worker() -> None:
        try:
            used = show_notification(title, body, path)
            LOG.info("通知已发送（%s）", used)
        except Exception as e:  # noqa: BLE001
            LOG.error("通知失败: %s", e)
        # 只有过了冷却的 ALERT 才响铃，长时间低头不会被铃声轰炸
        if event_type == "ALERT":
            play_sound(_SoundShim(cfg))

    threading.Thread(target=worker, name="notify", daemon=True).start()


class _SoundShim:
    """play_sound 只用到这四个字段，用一个轻量壳复用它，不必造整个 Config。"""

    def __init__(self, cfg: NotifyConfig):
        self.mute = cfg.mute
        self.beep = cfg.beep
        self.sound = cfg.sound


# ----------------------------------------------------------------------------
# 主循环
# ----------------------------------------------------------------------------
@dataclass
class Runtime:
    tracker: pt.PostureTracker
    backend: PoseBackend
    client: MjpegClient
    notify_cfg: NotifyConfig
    #: 逐帧记忆：选侧粘性 + 最近一次可靠的躯干方向
    geometry: pg.SideAndTorsoMemory
    state: State
    state_path: Path
    #: 基线存档 key，随后端与模型变化
    profile: str
    show: bool
    verbose: bool
    #: 最近一帧的 JPEG 与叠加图，供通知附图
    last_jpeg: Optional[bytes] = None
    last_annotated: Optional[bytes] = None
    calib_start: Optional[float] = None
    calib_samples: List[float] = field(default_factory=list)
    frames: int = 0
    fps: float = 0.0
    message: str = ""


def handle_events(rt: Runtime, events: List[pt.TrackerEvent], now_ms: int) -> None:
    for ev in events:
        if ev.kind == pt.EV_WINDOW_ENDED:
            outcome = ev.outcome or {}
            if not outcome.get("should_record"):
                continue
            neck = outcome.get("median_neck")
            thr = outcome.get("threshold")
            kind = "ALERT" if outcome.get("should_notify") else "CONFIRMED"
            LOG.info("%s 前倾 %s 阈值 %s（连续 %s 窗）", kind, fmt_deg(neck), fmt_deg(thr),
                     outcome.get("bad_streak"))
            notify(rt.notify_cfg, kind, neck, thr,
                   f"连续 {outcome.get('bad_streak')} 窗前倾",
                   rt.last_annotated or rt.last_jpeg)
        elif ev.kind == pt.EV_RECOVERED:
            seconds = ev.forward_head_millis // 1000
            LOG.info("RECOVERED 前倾持续 %d 秒后恢复", seconds)
            notify(rt.notify_cfg, "RECOVERED", ev.neck_deg, rt.tracker.threshold_deg,
                   f"前倾持续 {seconds} 秒后恢复" if seconds > 0 else "", None)
        elif ev.kind == pt.EV_MODE_CHANGED and rt.verbose:
            LOG.debug("模式 %s -> %s（%s）", ev.from_mode, ev.to_mode, ev.reason)


def handle_calibration(rt: Runtime, status: str, m, now: float) -> None:
    if rt.calib_start is None:
        return
    if status == pg.VALID and m is not None:
        rt.calib_samples.append(m.neck_deg)
    elapsed = now - rt.calib_start
    if elapsed < 3.0:
        rt.message = f"校准中 {elapsed:.1f}/3.0s  已采 {len(rt.calib_samples)} 帧"
        return
    rt.calib_start = None
    if len(rt.calib_samples) < 10:
        rt.message = f"校准失败：有效帧只有 {len(rt.calib_samples)}，确认画面里能看到耳肩髋"
        LOG.warning("%s", rt.message)
    else:
        baseline = rt.tracker.calibrate(rt.calib_samples)
        rt.state.set_baseline(rt.profile, baseline)
        rt.state.save(rt.state_path)
        rt.message = f"校准完成，基线 {baseline:.1f}°，阈值 {rt.tracker.threshold_deg:.1f}°"
        LOG.info("%s", rt.message)
    rt.calib_samples = []


def run(rt: Runtime, stop: threading.Event) -> int:
    import cv2
    import numpy as np
    import posture_probe as probe

    window = "NeckGuard 无线相机"
    last_seq = 0
    started_at = time.monotonic()
    fps_t0 = started_at
    fps_frames = 0
    last_status_at = 0.0
    idle_since = started_at

    while not stop.is_set():
        jpeg, seq = rt.client.latest(last_seq)
        if jpeg is None:
            # 没有新帧：驱动窗到期，避免确认窗因断流永远不结算
            now_ms = int(time.time() * 1000)
            handle_events(rt, rt.tracker.tick(now_ms), now_ms)
            if time.monotonic() - idle_since > 15 and rt.client.connected:
                LOG.warning("已 15 秒没有新画面，手机可能停止了推流")
                idle_since = time.monotonic()
            if rt.show and cv2.waitKey(30) & 0xFF in (ord("q"), 27):
                break
            time.sleep(0.01)
            continue
        last_seq = seq
        idle_since = time.monotonic()

        frame = cv2.imdecode(np.frombuffer(jpeg, dtype=np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            rt.client.decode_failures += 1
            continue
        rt.last_jpeg = jpeg
        h, w = frame.shape[:2]

        landmarks = rt.backend.infer(frame)
        now = time.monotonic()
        now_ms = int(time.time() * 1000)
        status, m = rt.geometry.analyze(landmarks, w, h, now_ms)
        handle_calibration(rt, status, m, now)
        handle_events(rt, rt.tracker.on_frame(status, m, now_ms), now_ms)

        rt.frames += 1
        fps_frames += 1
        if now - fps_t0 >= 1.0:
            rt.fps = fps_frames / (now - fps_t0)
            # 用实测帧率校正窗门槛：电脑端不节流，配置里的间隔只是估计值
            if rt.fps > 0:
                rt.tracker.observe_frame_interval(int(1000 / rt.fps))
            fps_t0 = now
            fps_frames = 0

        # 叠加图既用于预览窗，也作为通知附图
        base_txt = f"{rt.tracker.baseline_deg:.1f}" if rt.tracker.baseline_deg is not None else "none"
        skip_txt = ""
        if rt.show:
            _st = rt.client.stats()
            if _st["skipped"]:
                skip_txt = f"  skip {_st['skip_ratio'] * 100:.0f}%"
        extra = (
            f"{rt.backend.name}  {rt.fps:.1f} fps{skip_txt}  {w}x{h}  baseline {base_txt}  mode {rt.tracker.mode}",
            rt.message or ("forward head" if rt.tracker.in_forward_head else "c: calibrate  r: reset  q: quit"),
        )
        annotated = probe.draw_overlay(frame, status, m, rt.tracker.threshold_deg, extra)
        ok, buf = cv2.imencode(".jpg", annotated, [cv2.IMWRITE_JPEG_QUALITY, 85])
        if ok:
            rt.last_annotated = buf.tobytes()

        if rt.show:
            cv2.imshow(window, annotated)
            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), 27):
                break
            if key == ord("c") and rt.calib_start is None:
                rt.calib_start = now
                rt.calib_samples = []
                LOG.info("开始校准，请保持端正坐姿 3 秒")
            if key == ord("r"):
                rt.tracker.set_baseline(None)
                rt.state.set_baseline(rt.profile, None)
                rt.state.save(rt.state_path)
                rt.message = "已清除校准，使用绝对阈值"
                LOG.info("%s", rt.message)
            try:
                if cv2.getWindowProperty(window, cv2.WND_PROP_VISIBLE) < 1:
                    break
            except cv2.error:
                break

        if now - last_status_at >= 5.0:
            st = rt.client.stats()
            elapsed = max(now - started_at, 1e-6)
            # 推流帧率是手机实际推过来的，rt.fps 是我们真正推理掉的，两者的差就是跳过的部分
            in_fps = st["received"] / elapsed
            mbps = st["bytes"] * 8 / elapsed / 1e6
            LOG.info(
                "推理 %.1f fps / 推流 %.1f fps  跳帧 %.0f%%(%d)  带宽 %.1f Mbps  "
                "%s  角度 %s  阈值 %.1f  解码失败 %d  重连 %d",
                rt.fps, in_fps, st["skip_ratio"] * 100, int(st["skipped"]), mbps,
                status, fmt_deg(m.neck_deg if m else None), rt.tracker.threshold_deg,
                int(st["decode_failures"]), int(st["reconnects"]),
            )
            last_status_at = now

    if rt.show:
        try:
            cv2.destroyAllWindows()
        except cv2.error:
            pass
    return 0


# ----------------------------------------------------------------------------
# 入口
# ----------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="颈椎卫士 无线相机：电脑拉手机画面做姿态检测")
    p.add_argument("--url", help="手机推流地址，例如 http://192.168.1.20:8767/video；不填则自动扫描")
    p.add_argument("--token", default="", help="推流密钥，与手机端设置页一致")
    p.add_argument("--discovery-port", type=int, default=CAMERA_DISCOVERY_PORT,
                   help=f"扫描手机用的 UDP 端口，默认 {CAMERA_DISCOVERY_PORT}")
    p.add_argument("--backend", choices=["yolo", "mediapipe"], default="yolo",
                   help="姿态模型后端，默认 yolo（装不上会自动回退 mediapipe）")
    p.add_argument("--model", help="YOLO 权重，默认按显存自动选 yolo26s/m/l/x-pose.pt")
    p.add_argument("--device", help="推理设备，例如 cuda:0 或 cpu，默认自动")
    p.add_argument("--imgsz", type=int, default=960, help="YOLO 推理分辨率，默认 960")
    # GPU 上默认开半精度：姿态关键点是像素级回归，fp16 的精度损失远小于
    # 关键点本身的抖动，实测角度差在 0.1 度以内，换来的是明显更低的延迟。
    # CPU 上 half 会被后端忽略（见 YoloBackend.__init__）。
    p.add_argument("--half", action=argparse.BooleanOptionalAction, default=True,
                   help="半精度推理，GPU 上更快，默认开；用 --no-half 关闭")
    p.add_argument("--conf", type=float, default=0.25, help="YOLO 人体检测置信度下限，默认 0.25")
    p.add_argument("--kpt-conf", type=float, default=None, dest="kpt_conf",
                   help="YOLO 关键点可信下限，默认 0.5。与 MediaPipe 的 visibility 不是一个量纲，分开调")
    p.add_argument("--mp-visibility", type=float, default=None, dest="mp_visibility",
                   help="MediaPipe 关键点 visibility 下限，默认 0.5")
    p.add_argument("--show", action="store_true", help="开预览窗：c 校准 / r 清除 / q 退出")
    p.add_argument("--threshold", type=float, default=35.0, help="未校准时的绝对阈值（度），默认 35")
    p.add_argument("--delta", type=float, default=12.0, help="校准后阈值 = 基线 + delta，默认 12")
    p.add_argument("--hysteresis", type=float, default=4.0, help="迟滞（度），默认 4")
    p.add_argument("--window-sec", type=float, default=3.0, dest="window_sec", help="确认窗时长（秒），默认 3")
    p.add_argument("--min-valid-ratio", type=float, default=1.0 / 3.0, dest="min_valid_ratio",
                   help="确认窗内有效帧至少要占期望帧数的多少，默认 1/3")
    p.add_argument("--trigger-sec", type=float, default=1.4, dest="trigger_sec",
                   help="巡检下超阈值持续多少秒进入确认，默认 1.4")
    p.add_argument("--recover-sec", type=float, default=3.5, dest="recover_sec",
                   help="低于退出线持续多少秒算恢复，默认 3.5")
    p.add_argument("--invalid-grace-sec", type=float, default=1.0, dest="invalid_grace_sec",
                   help="计时途中看不清的容忍时长（秒），默认 1.0")
    p.add_argument("--min-valid-frames", type=int, default=8, dest="min_valid_frames",
                   help="测不出帧率时退回的绝对有效帧门槛，默认 8")
    p.add_argument("--consecutive-windows", type=int, default=2, dest="consecutive_windows",
                   help="连续多少个确认窗判为前倾才提醒，默认 2")
    p.add_argument("--cooldown-min", type=float, default=10.0, dest="cooldown_min",
                   help="两次响铃之间的最小间隔（分钟），默认 10")
    p.add_argument("--max-offset", type=float, default=0.35, dest="max_offset",
                   help="侧面对齐判定：肩距/躯干长 上限，默认 0.35")
    p.add_argument("--allow-no-hip", action="store_true", dest="allow_no_hip",
                   help="髋不可见时退回竖直参考继续判定，默认跳过这些帧")
    p.add_argument("--torso-hold-sec", type=float, default=2.0, dest="torso_hold_sec",
                   help="髋丢失后沿用上次躯干方向多少秒，0 关闭，默认 2")
    p.add_argument("--baseline", type=float, help="直接指定基线角度，跳过校准")
    p.add_argument("--quiet-hours", default="", help="静默时段，例如 23-7，该时段只记录不提醒")
    p.add_argument("--sound", default="SystemExclamation", help="提示音：wav 路径或系统别名")
    p.add_argument("--beep", action="store_true", help="用蜂鸣序列代替 wav")
    p.add_argument("--mute", action="store_true", help="不播放声音")
    p.add_argument("--snapshot-dir", default=str(PC_DIR / "snapshots"), help="截图保存目录")
    p.add_argument("--config", default=str(PC_DIR / "camera_monitor.json"), help="基线与上次地址的存档")
    p.add_argument("--proxy", help="MediaPipe 后端下载模型用的代理")
    p.add_argument("-v", "--verbose", action="store_true")
    return p


def resolve_url(args, state: State) -> Optional[str]:
    """按 --url -> 自动扫描 -> 上次用过的地址 的顺序确定拉流地址。"""
    if args.url:
        url = args.url.strip()
        if not url.startswith("http"):
            url = "http://" + url
        return url if url.rstrip("/").endswith(("/video", "/video.mjpg")) else url.rstrip("/") + "/video"

    LOG.info("正在扫描局域网里的手机……")
    cameras = discover_cameras(args.discovery_port)
    if len(cameras) == 1:
        cam = cameras[0]
        LOG.info("找到手机：%s -> %s", cam.name, cam.video_url)
        if cam.token_required and not args.token:
            LOG.warning("这台手机设了推流密钥，请加 --token")
        return cam.video_url
    if len(cameras) > 1:
        print("扫描到多台手机：")
        for i, cam in enumerate(cameras, 1):
            print(f"  {i}. {cam.name}  {cam.video_url}")
        try:
            choice = int(input("选哪一台（输序号）: ").strip())
            if 1 <= choice <= len(cameras):
                return cameras[choice - 1].video_url
        except (ValueError, EOFError, KeyboardInterrupt):
            pass
        LOG.error("未选择有效的手机")
        return None

    if state.last_url:
        LOG.warning("没扫描到手机，改用上次的地址 %s", state.last_url)
        return state.last_url
    LOG.error(
        "没扫描到手机。排查顺序：\n"
        "  1. 手机 App 设置页「检测方式」是否选了「电脑检测」，并点了「开始推流」\n"
        "  2. 手机和电脑是否在同一个 Wi-Fi（路由器开了 AP 隔离就只能手填）\n"
        "  3. 手机监测页显示的地址，用 --url 手填试试\n"
        "  4. 电脑防火墙是否放行了 UDP %d 的回包", args.discovery_port,
    )
    return None


def main(argv=None) -> int:
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        datefmt="%H:%M:%S",
    )

    try:
        quiet = parse_quiet_hours(args.quiet_hours)
    except ValueError as e:
        LOG.error("%s", e)
        return 2

    state_path = Path(args.config)
    state = State.load(state_path)

    url = resolve_url(args, state)
    if url is None:
        return 4
    state.last_url = url

    analyzer_cfg = pg.AnalyzerConfig(
        absolute_threshold_deg=args.threshold,
        calibration_delta_deg=args.delta,
        hysteresis_deg=args.hysteresis,
        min_valid_ratio=args.min_valid_ratio,
        min_valid_frames_fallback=args.min_valid_frames,
        consecutive_bad_windows=args.consecutive_windows,
        cooldown_millis=int(args.cooldown_min * 60_000),
    )
    # 电脑端不节流，帧率由手机推流和 GPU 决定，所以间隔先按推流上限估，
    # 跑起来后 run() 会用实测帧率持续校正窗门槛。
    tracker = pt.PostureTracker(analyzer_cfg, pt.TrackerConfig(
        confirm_window_millis=int(args.window_sec * 1000),
        trigger_millis=int(args.trigger_sec * 1000),
        recover_millis=int(args.recover_sec * 1000),
        invalid_grace_millis=int(args.invalid_grace_sec * 1000),
    ))
    # 后端要先建：基线按它的 profile 取，关键点阈值也由它决定
    try:
        backend = build_backend(args)
    except Exception as e:  # noqa: BLE001
        LOG.error("姿态后端初始化失败：%s", e)
        return 3

    profile = backend.profile
    baseline = args.baseline if args.baseline is not None else state.baseline_for(profile)
    if baseline is not None:
        tracker.set_baseline(baseline)
        state.set_baseline(profile, baseline)

    stop = threading.Event()
    client = MjpegClient(url, args.token, stop)
    notify_cfg = NotifyConfig(
        snapshot_dir=Path(args.snapshot_dir).resolve(),
        quiet_hours=quiet,
        sound=args.sound,
        beep=args.beep,
        mute=args.mute,
    )
    rt = Runtime(
        tracker=tracker,
        backend=backend,
        client=client,
        notify_cfg=notify_cfg,
        geometry=pg.SideAndTorsoMemory(pg.GeometryConfig(
            min_visibility=backend.min_visibility,
            max_shoulder_offset_ratio=args.max_offset,
            require_hip=not args.allow_no_hip,
            torso_hold_millis=int(args.torso_hold_sec * 1000),
        )),
        state=state,
        state_path=state_path,
        profile=profile,
        show=args.show,
        verbose=args.verbose,
    )

    print("=" * 62)
    print(f"颈椎卫士 无线相机  后端 {backend.name}")
    print(f"拉流地址: {url}")
    print(f"阈值: {tracker.threshold_deg:.1f}°"
          f"（基线 {f'{baseline:.1f}°' if baseline is not None else '未校准'}）")
    print(f"确认窗 {args.window_sec:.0f} 秒 x {args.consecutive_windows} 个，冷却 {args.cooldown_min:.0f} 分钟")
    print(f"截图目录: {notify_cfg.snapshot_dir}")
    if args.show:
        print("预览窗快捷键: c 校准（保持端正 3 秒） / r 清除校准 / q 退出")
    else:
        print("提示: 加 --show 可以开预览窗，并用 c 键校准个人基线")
    print("按 Ctrl+C 退出")
    print("=" * 62)

    client.start()
    code = 0
    try:
        code = run(rt, stop)
    except KeyboardInterrupt:
        pass
    except Exception as e:  # noqa: BLE001
        LOG.exception("运行异常：%s", e)
        code = 1
    finally:
        stop.set()
        backend.close()
        state.save(state_path)
        st = client.stats()
        LOG.info(
            "已退出。收帧 %d，跳帧 %d（%.0f%%），解码失败 %d，重连 %d，共收 %.1f MB",
            int(st["received"]), int(st["skipped"]), st["skip_ratio"] * 100,
            int(st["decode_failures"]), int(st["reconnects"]), st["bytes"] / 1e6,
        )
        if st["skip_ratio"] > 0.5:
            LOG.warning(
                "超过一半的帧没能进推理。要么调低手机端的推流帧率／分辨率，"
                "要么给电脑端加 --half 或换小一号的模型",
            )
    return code


if __name__ == "__main__":
    sys.exit(main())
