"""向本机或指定地址的接收端发送一条 TEST 事件（含一张小截图），用于自测。

用法:
  python send_test_event.py [--url http://127.0.0.1:8765] [--token xxx] [--type TEST] [--no-image]
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

# 1x1 灰色 JPEG（PIL 不可用时的兜底）
MIN_JPEG = bytes.fromhex(
    "ffd8ffe000104a46494600010100000100010000ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f14"
    "1d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffc0000b080001000101011100"
    "ffc4001f0000010501010101010100000000000000000102030405060708090a0bffc400b5100002010303020403050504040000"
    "017d01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a"
    "3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a92939495"
    "969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9ea"
    "f1f2f3f4f5f6f7f8f9faffda0008010100003f00fbd3ffd9"
)


def make_jpeg() -> bytes:
    try:
        from PIL import Image, ImageDraw

        img = Image.new("RGB", (640, 480), (30, 30, 30))
        d = ImageDraw.Draw(img)
        d.line((320, 380, 320, 120), fill=(255, 255, 255), width=2)
        d.line((320, 380, 420, 140), fill=(255, 200, 0), width=8)
        d.ellipse((400, 120, 440, 160), fill=(0, 220, 255))
        d.ellipse((300, 360, 340, 400), fill=(0, 220, 255))
        d.text((16, 16), "TEST neck 47.3 deg", fill=(255, 255, 255))
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=80)
        return buf.getvalue()
    except Exception:
        return MIN_JPEG


def build_multipart(fields: dict, files: dict) -> tuple[bytes, str]:
    boundary = "----NeckGuardBoundary" + uuid.uuid4().hex
    out = io.BytesIO()
    for name, value in fields.items():
        out.write(f"--{boundary}\r\n".encode())
        out.write(f'Content-Disposition: form-data; name="{name}"\r\n'.encode())
        out.write(b"Content-Type: application/json; charset=utf-8\r\n\r\n")
        out.write(value.encode("utf-8"))
        out.write(b"\r\n")
    for name, (filename, content_type, data) in files.items():
        out.write(f"--{boundary}\r\n".encode())
        out.write(f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode())
        out.write(f"Content-Type: {content_type}\r\n\r\n".encode())
        out.write(data)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return out.getvalue(), f"multipart/form-data; boundary={boundary}"


def main(argv=None) -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8765")
    p.add_argument("--token", default="")
    p.add_argument("--type", default="TEST", choices=["TEST", "ALERT", "CONFIRMED"])
    p.add_argument("--neck", type=float, default=47.3)
    p.add_argument("--threshold", type=float, default=40.0)
    p.add_argument("--no-image", action="store_true")
    p.add_argument("--ping", action="store_true", help="只测 GET /api/ping")
    args = p.parse_args(argv)

    headers = {}
    if args.token:
        headers["X-Neck-Token"] = args.token

    if args.ping:
        req = urllib.request.Request(args.url.rstrip("/") + "/api/ping", headers=headers)
    else:
        event = {
            "deviceId": "pc-test",
            "ts": int(time.time() * 1000),
            "type": args.type,
            "neckDeg": args.neck,
            "torsoDeg": 6.1,
            "thresholdDeg": args.threshold,
            "message": "PC 自测事件",
        }
        files = {} if args.no_image else {"snapshot": ("snapshot.jpg", "image/jpeg", make_jpeg())}
        body, ctype = build_multipart({"event": json.dumps(event, ensure_ascii=False)}, files)
        headers["Content-Type"] = ctype
        req = urllib.request.Request(
            args.url.rstrip("/") + "/api/posture/events", data=body, headers=headers, method="POST",
        )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            print(resp.status, resp.read().decode("utf-8"))
            return 0
    except urllib.error.HTTPError as e:
        print(e.code, e.read().decode("utf-8", "replace"))
        return 1
    except Exception as e:
        print("请求失败:", e)
        return 2


if __name__ == "__main__":
    sys.exit(main())
