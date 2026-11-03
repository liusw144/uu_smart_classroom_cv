"""对授权的本地录像生成短片清单，不打开摄像头、不接受远程URL。"""

import argparse
import hashlib
import json
import math
import shutil
import subprocess
from pathlib import Path


def windows(duration, length):
    if not math.isfinite(duration) or duration <= 0 or not 1 <= length <= 30:
        raise ValueError("录像时长或切片窗口无效")
    if math.ceil(duration / length) > 10000:
        raise ValueError("单次切片数超出10000上限")
    return [
        (start, min(length, duration - start)) for start in range(0, math.ceil(duration), length)
    ]


def probe(path):
    response = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path)],
        capture_output=True,
        check=True,
        timeout=30,
    )
    return float(json.loads(response.stdout)["format"]["duration"])


def main():
    parser = argparse.ArgumentParser(description="录像有界切片，输出可核验清单")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=30)
    parser.add_argument("--limit-bytes", type=int, default=10 * 1024**3)
    args = parser.parse_args()
    source = args.input.resolve()
    if not source.is_file() or args.input.is_symlink():
        parser.error("输入必须为授权的本地普通文件")
    points = windows(probe(source), args.seconds)
    args.out.mkdir(parents=True, exist_ok=False)
    manifest = {"source_sha256": None, "segments": [], "complete": False}
    with source.open("rb") as stream:
        manifest["source_sha256"] = hashlib.file_digest(stream, "sha256").hexdigest()
    used = 0
    try:
        for index, (start, duration) in enumerate(points):
            if (
                used + 32 * 1024**2 > args.limit_bytes
                or shutil.disk_usage(args.out).free < 2 * 1024**3 + 32 * 1024**2
            ):
                raise OSError("切片卷配额或剩余容量不足")
            target = args.out / f"{index:05d}.mp4"
            subprocess.run(
                [
                    "ffmpeg",
                    "-v",
                    "error",
                    "-nostdin",
                    "-n",
                    "-ss",
                    str(start),
                    "-i",
                    str(source),
                    "-t",
                    str(duration),
                    "-an",
                    "-vf",
                    "scale=1280:1280:force_original_aspect_ratio=decrease:force_divisible_by=2",
                    "-c:v",
                    "libx264",
                    "-crf",
                    "28",
                    "-maxrate",
                    "6M",
                    "-bufsize",
                    "6M",
                    "-pix_fmt",
                    "yuv420p",
                    "-movflags",
                    "+faststart",
                    "-fs",
                    str(28 * 1024**2),
                    str(target),
                ],
                check=True,
                timeout=180,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            if not 0 < target.stat().st_size <= 32 * 1024**2 or abs(probe(target) - duration) > 0.6:
                raise ValueError("切片被截断或超出输入契约，不能标记成功")
            used += target.stat().st_size
            with target.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            manifest["segments"].append(
                {
                    "file": target.name,
                    "start_seconds": start,
                    "duration_seconds": duration,
                    "size": target.stat().st_size,
                    "sha256": digest,
                }
            )
        manifest["complete"] = True
    finally:
        (args.out / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    print(json.dumps({"complete": True, "segments": len(manifest["segments"]), "bytes": used}))


if __name__ == "__main__":
    main()
