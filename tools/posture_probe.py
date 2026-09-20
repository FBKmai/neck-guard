"""脖子前倾检测 PC 原型：用 MediaPipe Pose Landmarker 跑图片或摄像头，输出颈部倾角与判定。

用法：
  图片/目录批量：  python posture_probe.py --image 照片目录 [--out output] [--threshold 40]
  摄像头实时：      python posture_probe.py --camera [0] [--threshold 40 --delta 12]
                    窗口内按 c 校准（3 秒中位数）、按 r 清除校准、按 q 退出。

算法与 Android 端一致，见 posture_geometry.py。
"""
from __future__ import annotations

import argparse
import logging
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import List, Optional, Tuple

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import posture_geometry as pg  # noqa: E402

LOG = logging.getLogger("posture_probe")

MODEL_URL = ("https://storage.googleapis.com/mediapipe-models/pose_landmarker/"
             "pose_landmarker_lite/float16/latest/pose_landmarker_lite.task")
TOOLS_DIR = Path(__file__).resolve().parent
MODEL_DIR = TOOLS_DIR / "models"
MODEL_PATH = MODEL_DIR / "pose_landmarker_lite.task"
DEFAULT_PROXY = "http://127.0.0.1:2080"
MIN_MODEL_BYTES = 1_000_000
IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


# ----------------------------------------------------------------------------- 模型
def ensure_model(proxy: Optional[str]) -> Path:
    """模型不存在时下载到 tools/models，走代理；失败抛 RuntimeError。"""
    if MODEL_PATH.exists() and MODEL_PATH.stat().st_size >= MIN_MODEL_BYTES:
        return MODEL_PATH
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    proxy = proxy or os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY") or DEFAULT_PROXY
    handlers = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
        LOG.info("通过代理 %s 下载模型", proxy)
    opener = urllib.request.build_opener(*handlers)
    tmp = MODEL_PATH.with_suffix(".task.part")
    try:
        LOG.info("下载模型 %s", MODEL_URL)
        with opener.open(MODEL_URL, timeout=60) as resp, open(tmp, "wb") as f:
            total = 0
            while True:
                chunk = resp.read(1 << 16)
                if not chunk:
                    break
                f.write(chunk)
                total += len(chunk)
        if total < MIN_MODEL_BYTES:
            raise RuntimeError(f"模型文件过小（{total} 字节），下载可能被拦截")
        tmp.replace(MODEL_PATH)
        LOG.info("模型已保存到 %s（%d 字节）", MODEL_PATH, total)
        return MODEL_PATH
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as e:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"模型下载失败：{e}。请检查代理 {proxy} 或手动下载到 {MODEL_PATH}") from e


def create_landmarker(model_path: Path, video_mode: bool):
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision

    # MediaPipe 的 C++ 层在 Windows 上打不开含非 ASCII 字符的路径（本仓库目录名是中文），
    # 所以把模型读进内存用 model_asset_buffer 传入。
    try:
        model_bytes = model_path.read_bytes()
    except OSError as e:
        raise RuntimeError(f"读取模型失败：{model_path}：{e}") from e
    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_buffer=model_bytes),
        running_mode=vision.RunningMode.VIDEO if video_mode else vision.RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    return vision.PoseLandmarker.create_from_options(options)


def to_mp_image(bgr: np.ndarray):
    import mediapipe as mp

    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    return mp.Image(image_format=mp.ImageFormat.SRGB, data=np.ascontiguousarray(rgb))


def result_to_landmarks(result) -> Optional[List[pg.Landmark]]:
    poses = getattr(result, "pose_landmarks", None)
    if not poses:
        return None
    lms = poses[0]
    out = []
    for lm in lms:
        vis = lm.visibility if lm.visibility is not None else 1.0
        out.append(pg.Landmark(float(lm.x), float(lm.y), float(vis)))
    return out


# ----------------------------------------------------------------------------- 绘制
def draw_overlay(bgr: np.ndarray, status: str, m: Optional[pg.Measurement],
                 threshold: float, extra_lines: Tuple[str, ...] = ()) -> np.ndarray:
    out = bgr.copy()
    h, w = out.shape[:2]
    stroke = max(2, w // 200)
    font_scale = max(0.5, w / 1000.0)
    verdict_color = (0, 200, 0)
    if m is not None:
        color = (0, 200, 255) if status == pg.VALID else (0, 80, 255)

        def ip(p):
            return int(round(p[0])), int(round(p[1]))

        ear, sh = ip(m.ear), ip(m.shoulder)
        # 竖直参考线
        cv2.line(out, sh, (sh[0], max(0, ear[1] - stroke * 6)), (255, 255, 255), max(1, stroke // 2), cv2.LINE_AA)
        if m.hip is not None:
            hip = ip(m.hip)
            cv2.line(out, hip, sh, color, stroke, cv2.LINE_AA)
            cv2.circle(out, hip, stroke * 2, (255, 220, 0), -1, cv2.LINE_AA)
        cv2.line(out, sh, ear, color, stroke, cv2.LINE_AA)
        cv2.circle(out, ear, stroke * 2, (255, 220, 0), -1, cv2.LINE_AA)
        cv2.circle(out, sh, stroke * 2, (255, 220, 0), -1, cv2.LINE_AA)
        bad = m.neck_deg > threshold
        verdict_color = (0, 0, 230) if bad else (0, 200, 0)
        torso_txt = f"{m.torso_deg:.1f}" if m.torso_deg is not None else "--"
        lines = [f"{status}  neck {m.neck_deg:.1f} deg  torso {torso_txt}  thr {threshold:.0f}",
                 f"side {m.side}  offset {m.shoulder_offset_ratio:.2f}  {'FORWARD' if bad else 'OK'}"]
    else:
        verdict_color = (0, 80, 255)
        lines = [status]
    lines.extend(extra_lines)
    y = int(30 * font_scale) + 10
    for text in lines:
        cv2.putText(out, text, (10, y), cv2.FONT_HERSHEY_SIMPLEX, font_scale, (0, 0, 0), stroke + 2, cv2.LINE_AA)
        cv2.putText(out, text, (10, y), cv2.FONT_HERSHEY_SIMPLEX, font_scale, verdict_color, max(1, stroke - 1), cv2.LINE_AA)
        y += int(34 * font_scale)
    return out


# ----------------------------------------------------------------------------- 图片模式
def load_image(path: Path) -> Optional[np.ndarray]:
    """读取图片并按 EXIF 方向转正（手机拍的照片常带旋转标记）。"""
    try:
        from PIL import Image, ImageOps

        with Image.open(path) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            return cv2.cvtColor(np.array(im), cv2.COLOR_RGB2BGR)
    except Exception as e:  # noqa: BLE001
        LOG.warning("PIL 读取失败（%s），改用 OpenCV：%s", path.name, e)
    data = np.fromfile(str(path), dtype=np.uint8)  # 支持中文路径
    if data.size == 0:
        return None
    return cv2.imdecode(data, cv2.IMREAD_COLOR)


def collect_images(target: Path) -> List[Path]:
    if target.is_dir():
        return sorted(p for p in target.iterdir()
                      if p.suffix.lower() in IMAGE_EXTS and not p.stem.endswith("_annotated"))
    return [target]


def run_images(args) -> int:
    target = Path(args.image)
    if not target.exists():
        LOG.error("路径不存在：%s", target)
        return 2
    files = collect_images(target)
    if not files:
        LOG.error("目录中没有图片：%s", target)
        return 2
    out_dir = Path(args.out) if args.out else TOOLS_DIR / "output"
    out_dir.mkdir(parents=True, exist_ok=True)

    try:
        model = ensure_model(args.proxy)
        landmarker = create_landmarker(model, video_mode=False)
    except Exception as e:  # noqa: BLE001
        LOG.error("初始化失败：%s", e)
        return 3

    geo_cfg = pg.GeometryConfig(max_shoulder_offset_ratio=args.max_offset)
    threshold = args.threshold
    header = f"{'文件':<28} {'状态':<14} {'侧':<5} {'颈角':>7} {'躯干':>7} {'肩偏':>6}  判定"
    print(header)
    print("-" * len(header))
    rows = []
    with landmarker:
        for path in files:
            bgr = load_image(path)
            if bgr is None:
                LOG.error("无法读取图片：%s", path)
                print(f"{path.name[:28]:<28} {'READ_FAIL':<14}")
                continue
            h, w = bgr.shape[:2]
            try:
                result = landmarker.detect(to_mp_image(bgr))
            except Exception as e:  # noqa: BLE001
                LOG.error("推理失败 %s：%s", path.name, e)
                print(f"{path.name[:28]:<28} {'INFER_FAIL':<14}")
                continue
            status, m = pg.analyze(result_to_landmarks(result), w, h, geo_cfg)
            if m is not None:
                verdict = "前倾" if m.neck_deg > threshold else "正常"
                torso = f"{m.torso_deg:7.1f}" if m.torso_deg is not None else f"{'--':>7}"
                print(f"{path.name[:28]:<28} {status:<14} {m.side:<5} {m.neck_deg:7.1f} {torso} "
                      f"{m.shoulder_offset_ratio:6.2f}  {verdict}")
                rows.append((path.name, status, m.side, m.neck_deg, m.torso_deg, m.shoulder_offset_ratio, verdict))
            else:
                print(f"{path.name[:28]:<28} {status:<14}")
                rows.append((path.name, status, "", None, None, None, ""))
            annotated = draw_overlay(bgr, status, m, threshold)
            out_path = out_dir / f"{path.stem}_annotated.jpg"
            ok, buf = cv2.imencode(".jpg", annotated, [cv2.IMWRITE_JPEG_QUALITY, 88])
            if ok:
                buf.tofile(str(out_path))
            else:
                LOG.warning("写出失败：%s", out_path)

    valid = [r for r in rows if r[3] is not None and r[1] == pg.VALID]
    if valid:
        necks = sorted(r[3] for r in valid)
        print(f"\n有效样本 {len(valid)} 张，颈角范围 {necks[0]:.1f} ~ {necks[-1]:.1f}，中位数 {pg.median(necks):.1f}")
    print(f"叠加图已写入 {out_dir}")
    return 0


# ----------------------------------------------------------------------------- 摄像头模式
def run_camera(args) -> int:
    try:
        model = ensure_model(args.proxy)
        landmarker = create_landmarker(model, video_mode=True)
    except Exception as e:  # noqa: BLE001
        LOG.error("初始化失败：%s", e)
        return 3

    cap = cv2.VideoCapture(args.camera, cv2.CAP_DSHOW if os.name == "nt" else 0)
    if not cap.isOpened():
        LOG.error("无法打开摄像头 %s", args.camera)
        return 4
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)

    geo_cfg = pg.GeometryConfig(max_shoulder_offset_ratio=args.max_offset)
    an_cfg = pg.AnalyzerConfig(absolute_threshold_deg=args.threshold, calibration_delta_deg=args.delta)
    analyzer = pg.PostureAnalyzer(an_cfg)

    calib_start: Optional[float] = None
    calib_samples: List[float] = []
    msg = "c: calibrate  r: reset  q: quit"
    t0 = time.monotonic()
    frames = 0
    fps = 0.0
    fail_streak = 0
    window_name = "NeckGuard probe"
    LOG.info("摄像头已打开，%s", msg)
    try:
        with landmarker:
            while True:
                ok, bgr = cap.read()
                if not ok or bgr is None:
                    fail_streak += 1
                    if fail_streak > 30:
                        LOG.error("连续读取摄像头失败，退出")
                        return 4
                    time.sleep(0.05)
                    continue
                fail_streak = 0
                h, w = bgr.shape[:2]
                ts_ms = int((time.monotonic() - t0) * 1000)
                try:
                    result = landmarker.detect_for_video(to_mp_image(bgr), ts_ms)
                    status, m = pg.analyze(result_to_landmarks(result), w, h, geo_cfg)
                except Exception as e:  # noqa: BLE001
                    LOG.warning("推理失败：%s", e)
                    status, m = "INFER_FAIL", None

                now = time.monotonic()
                if calib_start is not None:
                    if status == pg.VALID and m is not None:
                        calib_samples.append(m.neck_deg)
                    elapsed = now - calib_start
                    msg = f"calibrating {elapsed:.1f}/3.0s  samples {len(calib_samples)}"
                    if elapsed >= 3.0:
                        calib_start = None
                        if len(calib_samples) >= 10:
                            base = analyzer.calibrate(calib_samples)
                            msg = f"calibrated baseline {base:.1f}  threshold {analyzer.threshold:.1f}"
                            LOG.info("校准完成：基线 %.1f°，阈值 %.1f°", base, analyzer.threshold)
                        else:
                            msg = f"calibration failed: only {len(calib_samples)} valid frames"
                            LOG.warning("校准失败：有效帧不足（%d）", len(calib_samples))
                        calib_samples = []

                frames += 1
                if now - t0 >= 1.0 and frames % 10 == 0:
                    fps = frames / (now - t0)
                base_txt = f"{analyzer.baseline:.1f}" if analyzer.baseline is not None else "none"
                annotated = draw_overlay(bgr, status, m, analyzer.threshold,
                                         (f"baseline {base_txt}  fps {fps:.1f}", msg))
                cv2.imshow(window_name, annotated)
                key = cv2.waitKey(1) & 0xFF
                if key == ord("q") or key == 27:
                    break
                if key == ord("c") and calib_start is None:
                    calib_start = now
                    calib_samples = []
                    LOG.info("开始校准，请保持端正坐姿 3 秒")
                if key == ord("r"):
                    analyzer.set_baseline(None)
                    msg = "baseline cleared"
                    LOG.info("已清除校准")
                if cv2.getWindowProperty(window_name, cv2.WND_PROP_VISIBLE) < 1:
                    break
    except KeyboardInterrupt:
        pass
    finally:
        cap.release()
        cv2.destroyAllWindows()
    return 0


# ----------------------------------------------------------------------------- 入口
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="脖子前倾检测 PC 原型（MediaPipe Pose Landmarker）")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--image", help="图片文件或目录")
    src.add_argument("--camera", nargs="?", const=0, type=int, help="摄像头索引，默认 0")
    p.add_argument("--out", help="叠加图输出目录，默认 tools/output")
    p.add_argument("--threshold", type=float, default=40.0, help="未校准时的绝对阈值（度），默认 40")
    p.add_argument("--delta", type=float, default=12.0, help="校准后阈值 = 基线 + delta，默认 12")
    p.add_argument("--max-offset", type=float, default=0.35, dest="max_offset",
                   help="侧面对齐判定：肩距/躯干长 上限，默认 0.35")
    p.add_argument("--proxy", help=f"下载模型用的代理，默认 {DEFAULT_PROXY} 或环境变量 HTTPS_PROXY")
    p.add_argument("-v", "--verbose", action="store_true")
    return p


def main(argv: Optional[List[str]] = None) -> int:
    # Windows 控制台（尤其 Git Bash）默认 GBK，强制 UTF-8 避免中文日志乱码
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    try:
        if args.image is not None:
            return run_images(args)
        return run_camera(args)
    except Exception as e:  # noqa: BLE001
        LOG.exception("未处理的异常：%s", e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
