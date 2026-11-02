"""显式授权后限量采集摄像头或RTSP；默认不访问任何实体设备。"""

import argparse
import hashlib
import json
import multiprocessing
import os
import time
from pathlib import Path
from queue import Empty
from urllib.parse import urlparse
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[2]


def resolve_source(kind, value, allowed, environment):
    if kind == "FILE":
        path = Path(value).resolve()
        if not path.is_file():
            raise ValueError("输入视频文件不存在")
        return str(path)
    if not allowed:
        raise ValueError("实体设备或RTSP必须显式提供--allow-device")
    if kind == "DEVICE":
        if not 0 <= int(value) <= 9:
            raise ValueError("摄像头序号需在0至9之间")
        return int(value)
    if not value.startswith("CAMERA_RTSP_"):
        raise ValueError("RTSP只允许读取CAMERA_RTSP_前缀配置")
    source = environment.get(value, "")
    parsed = urlparse(source)
    if parsed.scheme not in {"rtsp", "rtsps"} or not parsed.hostname:
        raise ValueError("RTSP本地配置无效")
    return source


def capture_child(source, kind, target, seconds, maximum, queue):
    # 只静默当前采集子进程，防止底层解码库把RTSP凭据写入日志。
    os.environ["OPENCV_LOG_LEVEL"] = "OFF"
    os.environ["OPENCV_FFMPEG_LOGLEVEL"] = "-8"
    null = os.open(os.devnull, os.O_WRONLY)
    os.dup2(null, 2)
    os.close(null)
    capture = None
    writer = None
    try:
        import cv2

        if kind == "DEVICE":
            capture = cv2.VideoCapture(source)
        else:
            capture = cv2.VideoCapture(
                source,
                cv2.CAP_FFMPEG,
                [cv2.CAP_PROP_OPEN_TIMEOUT_MSEC, 5000, cv2.CAP_PROP_READ_TIMEOUT_MSEC, 5000],
            )
        if not capture.isOpened():
            raise ValueError("无法打开采集源")
        source_fps = capture.get(cv2.CAP_PROP_FPS)
        fps = source_fps if 1 <= source_fps <= 30 else 10
        limit = min(maximum, int(seconds * fps))
        frames = 0
        start = time.monotonic()
        width = height = 0
        while frames < limit and time.monotonic() - start < seconds + 2:
            ok, image = capture.read()
            if not ok:
                break
            height, width = image.shape[:2]
            if min(width, height) < 2 or width * height > 16000000:
                raise ValueError("源图像尺寸无效")
            scale = min(1, 640 / max(width, height))
            width = max(2, int(width * scale) // 2 * 2)
            height = max(2, int(height * scale) // 2 * 2)
            image = cv2.resize(image, (width, height))
            if writer is None:
                writer = cv2.VideoWriter(
                    str(target), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height)
                )
                if not writer.isOpened():
                    raise ValueError("无法初始化输出视频")
            writer.write(image)
            frames += 1
            if target.stat().st_size > 32 * 1024 * 1024:
                raise ValueError("采集体积超出上限")
        if frames == 0:
            raise ValueError("没有采集到可用帧")
        writer.release()
        writer = None
        if target.stat().st_size > 32 * 1024 * 1024:
            raise ValueError("采集体积超出上限")
        queue.put(
            {
                "ok": True,
                "source_kind": kind,
                "frames": frames,
                "width": width,
                "height": height,
                "fps": fps,
            }
        )
    except Exception:
        queue.put({"ok": False, "code": "CAPTURE_FAILED"})
    finally:
        if writer is not None:
            writer.release()
        if capture is not None:
            capture.release()


def main():
    parser = argparse.ArgumentParser(description="不自动访问设备的有界采集工具")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--input-file")
    group.add_argument("--device", type=int)
    group.add_argument("--rtsp-env")
    parser.add_argument("--allow-device", action="store_true")
    parser.add_argument("--seconds", type=int, default=5)
    parser.add_argument("--max-frames", type=int, default=150)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 30 or not 1 <= args.max_frames <= 300:
        parser.error("时长1至30秒，帧数1至300")
    values = dict(os.environ)
    config = ROOT / ".env.camera"
    if config.is_file():
        values.update(
            line.split("=", 1)
            for line in config.read_text(encoding="utf-8-sig").splitlines()
            if line.startswith("CAMERA_RTSP_") and "=" in line
        )
    kind = "FILE" if args.input_file else ("DEVICE" if args.device is not None else "RTSP")
    try:
        source = resolve_source(
            kind,
            args.input_file or (args.device if kind == "DEVICE" else args.rtsp_env),
            args.allow_device,
            values,
        )
    except ValueError as error:
        parser.error(str(error))
    folder = ROOT / "runtime/capture"
    folder.mkdir(parents=True, exist_ok=True)
    target = folder / ("capture-" + uuid4().hex + ".mp4")
    context = multiprocessing.get_context("spawn")
    queue = context.Queue(maxsize=1)
    process = context.Process(
        target=capture_child, args=(source, kind, target, args.seconds, args.max_frames, queue)
    )
    process.start()
    process.join(args.seconds + 15)
    if process.is_alive():
        process.terminate()
        process.join(5)
    try:
        result = queue.get(timeout=1)
    except Empty:
        result = {"ok": False, "code": "CAPTURE_TIMEOUT"}
    finally:
        queue.close()
        queue.join_thread()
    if not result["ok"]:
        target.unlink(missing_ok=True)
        raise SystemExit("采集失败或超时；未输出源地址或凭据。")
    with target.open("rb") as data:
        result["sha256"] = hashlib.file_digest(data, "sha256").hexdigest()
    result.update(file=str(target), size=target.stat().st_size)
    target.with_suffix(".json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
