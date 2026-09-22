"""neck_camera_monitor 自测：起一个假的 MJPEG 服务端，验证拉流解析与发现协议。

不需要手机，也不需要装 torch / ultralytics，只测网络与解析这一层。

运行：cd pc && python -m unittest test_camera_monitor -v
"""
from __future__ import annotations

import json
import os
import socket
import sys
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PC_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(PC_DIR))
sys.path.insert(0, str(PC_DIR.parent / "tools"))

from neck_camera_monitor import (  # noqa: E402
    COCO_TO_MP, LEGACY_PROFILE, Camera, MjpegClient, State, discover_cameras, parse_camera_reply,
    resolve_url,
)

BOUNDARY = "neckguardframe"
# 最小可解码 JPEG（1x1 灰），内容不重要，测的是分帧
TINY_JPEG = bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f14"
    "1d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffc0000b080001000101011100"
    "ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000"
    "017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a"
    "3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495"
    "969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9ea"
    "f1f2f3f4f5f6f7f8f9faffda0008010100003f00fbd3ffd9"
)


class FakePhoneHandler(BaseHTTPRequestHandler):
    """模拟 App 里的 MjpegServer，路由与响应格式保持一致。"""

    token = ""
    frame_count = 10

    def log_message(self, fmt, *args):
        pass

    def _authorized(self) -> bool:
        if not self.token:
            return True
        return self.headers.get("X-Neck-Token", "") == self.token

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if not self._authorized():
            self.send_response(401)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if path == "/video":
            self.send_response(200)
            self.send_header("Content-Type", f"multipart/x-mixed-replace; boundary={BOUNDARY}")
            self.end_headers()
            try:
                for _ in range(self.frame_count):
                    self.wfile.write(f"--{BOUNDARY}\r\n".encode())
                    self.wfile.write(b"Content-Type: image/jpeg\r\n")
                    self.wfile.write(f"Content-Length: {len(TINY_JPEG)}\r\n\r\n".encode())
                    self.wfile.write(TINY_JPEG)
                    self.wfile.write(b"\r\n")
                    self.wfile.flush()
                    time.sleep(0.01)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            return
        if path == "/info":
            body = json.dumps({"neckguard": "camera", "name": "fake", "v": 1}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()


class MjpegClientTest(unittest.TestCase):

    def setUp(self):
        FakePhoneHandler.token = ""
        FakePhoneHandler.frame_count = 10
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), FakePhoneHandler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05},
                         daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def _collect(self, token="", want=3, timeout=8.0):
        stop = threading.Event()
        client = MjpegClient(f"http://127.0.0.1:{self.port}/video", token, stop)
        client.start()
        seen, last_seq = [], 0
        deadline = time.monotonic() + timeout
        try:
            while len(seen) < want and time.monotonic() < deadline:
                frame, seq = client.latest(last_seq)
                if frame is None:
                    time.sleep(0.01)
                    continue
                last_seq = seq
                seen.append(frame)
        finally:
            stop.set()
        return client, seen

    def test_receives_frames_and_splits_on_content_length(self):
        client, seen = self._collect(want=3)
        self.assertGreaterEqual(len(seen), 3)
        for frame in seen:
            # 每帧都应是完整 JPEG：以 SOI 开头、EOI 结尾，且没混入边界
            self.assertTrue(frame.startswith(b"\xff\xd8"))
            self.assertTrue(frame.endswith(b"\xff\xd9"))
            self.assertNotIn(BOUNDARY.encode(), frame)
            self.assertEqual(frame, TINY_JPEG)
        self.assertGreaterEqual(client.frames_received, 3)

    def test_latest_returns_none_when_no_new_frame(self):
        client, seen = self._collect(want=1)
        self.assertTrue(seen)
        frame, seq = client.latest(client._seq)
        self.assertIsNone(frame)

    def test_token_mismatch_does_not_crash_and_retries(self):
        FakePhoneHandler.token = "secret"
        stop = threading.Event()
        client = MjpegClient(f"http://127.0.0.1:{self.port}/video", "wrong", stop)
        client.start()
        time.sleep(1.2)
        stop.set()
        # 401 会走重连路径，线程必须还活着且记下了错误
        self.assertEqual(client.frames_received, 0)
        self.assertIsNotNone(client.last_error)
        self.assertGreaterEqual(client.reconnects, 1)

    def test_correct_token_passes(self):
        FakePhoneHandler.token = "secret"
        client, seen = self._collect(token="secret", want=2)
        self.assertGreaterEqual(len(seen), 2)

    def test_stream_end_triggers_reconnect(self):
        FakePhoneHandler.frame_count = 2
        stop = threading.Event()
        client = MjpegClient(f"http://127.0.0.1:{self.port}/video", "", stop)
        client.start()
        time.sleep(2.0)
        stop.set()
        # 服务端推完 2 帧就断，客户端应重连并继续收帧
        self.assertGreater(client.frames_received, 2)
        self.assertGreaterEqual(client.reconnects, 1)

    def test_boundary_parsing_without_content_length(self):
        """有些推流端不给 Content-Length，靠下一个边界切分也要能用。"""
        stop = threading.Event()
        client = MjpegClient("http://127.0.0.1:1/video", "", stop)
        marker = b"--" + BOUNDARY.encode()
        buf = (marker + b"\r\nContent-Type: image/jpeg\r\n\r\n" + TINY_JPEG + b"\r\n" +
               marker + b"\r\nContent-Type: image/jpeg\r\n\r\n")
        took, rest = client._take_part(buf, marker)
        self.assertTrue(took)
        self.assertEqual(client.frames_received, 1)
        frame, _ = client.latest(0)
        self.assertEqual(frame, TINY_JPEG)

    def test_partial_buffer_is_kept(self):
        stop = threading.Event()
        client = MjpegClient("http://127.0.0.1:1/video", "", stop)
        marker = b"--" + BOUNDARY.encode()
        half = marker + b"\r\nContent-Length: 999\r\n\r\n" + TINY_JPEG[:10]
        took, rest = client._take_part(half, marker)
        self.assertFalse(took)
        self.assertEqual(rest, half)
        self.assertEqual(client.frames_received, 0)


class DiscoveryTest(unittest.TestCase):

    def test_parse_camera_reply_ok(self):
        data = json.dumps({
            "neckguard": "camera", "name": "Redmi K50", "host": "192.168.1.20",
            "port": 8767, "token_required": True, "v": 1,
        }).encode()
        cam = parse_camera_reply(data, ("192.168.1.20", 5000))
        self.assertIsNotNone(cam)
        self.assertEqual(cam.name, "Redmi K50")
        self.assertEqual(cam.video_url, "http://192.168.1.20:8767/video")
        self.assertTrue(cam.token_required)

    def test_parse_camera_reply_falls_back_to_sender_ip(self):
        data = json.dumps({"neckguard": "camera", "port": 8767}).encode()
        cam = parse_camera_reply(data, ("10.0.0.5", 5000))
        self.assertEqual(cam.host, "10.0.0.5")

    def test_parse_camera_reply_rejects_junk(self):
        self.assertIsNone(parse_camera_reply(b"not json", ("1.2.3.4", 1)))
        self.assertIsNone(parse_camera_reply(b'{"neckguard":"receiver"}', ("1.2.3.4", 1)))
        self.assertIsNone(parse_camera_reply(b'{"neckguard":"camera","port":0}', ("1.2.3.4", 1)))

    def test_discover_returns_empty_without_responder(self):
        # 用一个几乎没人用的端口，确保没有真手机在应答
        self.assertEqual(discover_cameras(port=56789, timeout=0.4), [])


class ResolveUrlTest(unittest.TestCase):

    class Args:
        def __init__(self, url=None, token="", discovery_port=56789):
            self.url = url
            self.token = token
            self.discovery_port = discovery_port

    class St:
        last_url = None

    def test_explicit_url_gets_video_suffix(self):
        self.assertEqual(
            resolve_url(self.Args("http://192.168.1.20:8767"), self.St()),
            "http://192.168.1.20:8767/video",
        )

    def test_explicit_url_keeps_existing_suffix(self):
        self.assertEqual(
            resolve_url(self.Args("http://192.168.1.20:8767/video"), self.St()),
            "http://192.168.1.20:8767/video",
        )

    def test_bare_host_gets_scheme(self):
        self.assertEqual(
            resolve_url(self.Args("192.168.1.20:8767"), self.St()),
            "http://192.168.1.20:8767/video",
        )


class KeypointMappingTest(unittest.TestCase):

    def test_coco_indices_map_to_expected_mediapipe_slots(self):
        import posture_geometry as pg

        self.assertEqual(COCO_TO_MP[3], pg.LEFT_EAR)
        self.assertEqual(COCO_TO_MP[4], pg.RIGHT_EAR)
        self.assertEqual(COCO_TO_MP[5], pg.LEFT_SHOULDER)
        self.assertEqual(COCO_TO_MP[6], pg.RIGHT_SHOULDER)
        self.assertEqual(COCO_TO_MP[11], pg.LEFT_HIP)
        self.assertEqual(COCO_TO_MP[12], pg.RIGHT_HIP)

    def test_mapped_landmarks_feed_analyze(self):
        """按映射填出的 33 点应该能被 analyze 正常算出角度。"""
        import posture_geometry as pg

        lms = [pg.Landmark(0.0, 0.0, 0.0) for _ in range(pg.LANDMARK_COUNT)]
        # 端坐：耳在肩正上方偏前，髋在肩正下方
        lms[pg.LEFT_EAR] = pg.Landmark(0.55, 0.30, 0.9)
        lms[pg.LEFT_SHOULDER] = pg.Landmark(0.50, 0.50, 0.9)
        lms[pg.RIGHT_SHOULDER] = pg.Landmark(0.50, 0.50, 0.8)
        lms[pg.LEFT_HIP] = pg.Landmark(0.50, 0.85, 0.9)
        status, m = pg.analyze(lms, 960, 720)
        self.assertEqual(status, pg.VALID)
        self.assertGreater(m.neck_deg, 0.0)
        self.assertEqual(m.neck_reference, pg.REF_TORSO)


class StateTest(unittest.TestCase):
    """基线按「后端 + 模型」分开存（v0.7）。"""

    def setUp(self):
        import tempfile

        self._dir = tempfile.TemporaryDirectory()
        self.path = Path(self._dir.name) / "camera_monitor.json"

    def tearDown(self):
        self._dir.cleanup()

    def test_baselines_are_per_profile(self):
        st = State()
        st.set_baseline("yolo:yolo26s-pose", 12.0)
        st.set_baseline("mediapipe", 19.5)
        st.save(self.path)

        back = State.load(self.path)
        self.assertAlmostEqual(back.baseline_for("yolo:yolo26s-pose"), 12.0)
        self.assertAlmostEqual(back.baseline_for("mediapipe"), 19.5)
        # 没校准过的 profile 拿不到别人的基线
        self.assertIsNone(back.baseline_for("yolo:yolo26x-pose"))

    def test_legacy_single_baseline_is_migrated(self):
        self.path.write_text(
            json.dumps({"baseline_deg": 15.0, "last_url": "http://192.168.1.20:8767/video"}),
            encoding="utf-8",
        )
        st = State.load(self.path)
        self.assertEqual(st.last_url, "http://192.168.1.20:8767/video")
        # 迁移期任何 profile 都先用得上旧基线，避免升级后白白重新校准
        self.assertAlmostEqual(st.baseline_for("yolo:yolo26s-pose"), 15.0)
        # 一旦按 profile 存过，迁移用的旧键就清掉，其它 profile 不再蹭它
        st.set_baseline("yolo:yolo26s-pose", 11.0)
        self.assertNotIn(LEGACY_PROFILE, st.baselines)
        self.assertIsNone(st.baseline_for("mediapipe"))

    def test_clearing_baseline_removes_only_that_profile(self):
        st = State()
        st.set_baseline("yolo:a", 10.0)
        st.set_baseline("mediapipe", 20.0)
        st.set_baseline("yolo:a", None)
        self.assertIsNone(st.baseline_for("yolo:a"))
        self.assertAlmostEqual(st.baseline_for("mediapipe"), 20.0)

    def test_load_tolerates_broken_file(self):
        self.path.write_text("not json at all", encoding="utf-8")
        st = State.load(self.path)
        self.assertEqual(st.baselines, {})
        self.assertIsNone(st.last_url)
        # 类型不对的值直接丢掉，不让坏数据把阈值算飞
        self.path.write_text(json.dumps({"baselines": {"yolo:a": "bad", "mediapipe": 18.0}}),
                             encoding="utf-8")
        st = State.load(self.path)
        self.assertIsNone(st.baseline_for("yolo:a"))
        self.assertAlmostEqual(st.baseline_for("mediapipe"), 18.0)


if __name__ == "__main__":
    unittest.main()
