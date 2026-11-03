import hashlib
import json
import math
import subprocess
import threading
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID, uuid4

import cv2
import numpy as np
import onnxruntime as ort
from rtmlib import YOLOX, RTMPose

from classroom_vision.domain.models import DetectionRequest
from classroom_vision.inference.engine import InferenceBusy


def load_model_files(lock: Path, root: Path):
    manifest = json.loads(lock.read_text(encoding="utf-8"))
    root = root.resolve()
    for kind in ["detector", "pose"]:
        record = manifest[kind]
        filename = record["filename"]
        path = root / filename
        if (
            Path(filename).name != filename
            or path.is_symlink()
            or not path.resolve().is_relative_to(root)
        ):
            raise ValueError("模型路径无效")
        with path.open("rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if actual != record["sha256"]:
            raise ValueError("模型哈希校验失败")
    return manifest


def read_asset(root: Path, asset_id: str, expected_hash: str):
    try:
        if str(UUID(asset_id)) != asset_id:
            raise ValueError("素材编号无效")
    except (ValueError, AttributeError) as error:
        raise ValueError("素材编号无效") from error
    root = root.resolve()
    path = root / asset_id
    if path.is_symlink() or not path.resolve().is_relative_to(root) or not path.is_file():
        raise ValueError("素材未缓存或路径无效")
    if not 0 < path.stat().st_size <= 32 * 1024 * 1024:
        raise ValueError("素材体积超出限制")
    with path.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if actual != expected_hash:
        raise ValueError("素材哈希校验失败")
    return path


def pose_candidates(points, scores):
    if len(points) != 17 or len(scores) != 17:
        raise ValueError("需要17个关键点及对应分数")

    def valid(*indices):
        return all(
            math.isfinite(float(scores[index]))
            and scores[index] >= 0.35
            and all(math.isfinite(float(value)) for value in points[index])
            for index in indices
        )

    raised = any(
        valid(shoulder, wrist) and points[wrist][1] < points[shoulder][1] - 8
        for shoulder, wrist in [(5, 9), (6, 10)]
    )
    return ["HAND_UP_CANDIDATE"] if raised else []


def video_samples(count, fps, interval, maximum):
    if count < 1 or not math.isfinite(fps) or fps <= 0 or interval < 100 or maximum < 2:
        raise ValueError("视频采样配置无效")
    duration = round(count / fps * 1000)
    indices = sorted(
        {
            min(count - 1, round(timestamp * fps / 1000))
            for timestamp in range(0, max(1, duration), interval)
        }
        | {count - 1}
    )
    strategy = "FIXED_INTERVAL_WITH_TAIL"
    if len(indices) > maximum:
        indices = [round(i * (count - 1) / (maximum - 1)) for i in range(maximum)]
        strategy = "UNIFORM_FULL_SPAN"
    points = [(index, round(index / fps * 1000)) for index in indices]
    effective = max(interval, math.ceil(points[-1][1] / max(1, len(points) - 1)))
    return points, strategy, effective


class OnnxPoseEngine:
    def __init__(self, settings):
        self.settings = settings
        self.manifest = load_model_files(settings.model_lock, settings.models_root)
        self.version = self.manifest["model_version"]
        self.hashes = {kind: self.manifest[kind]["sha256"] for kind in ["detector", "pose"]}
        self.detector = YOLOX(
            str(settings.models_root / self.manifest["detector"]["filename"]),
            model_input_size=(416, 416),
            mode="multiclass",
            score_thr=0.3,
            backend="onnxruntime",
            device="cpu",
        )
        self.pose = RTMPose(
            str(settings.models_root / self.manifest["pose"]["filename"]),
            model_input_size=(192, 256),
            backend="onnxruntime",
            device="cpu",
        )
        options = ort.SessionOptions()
        options.intra_op_num_threads = settings.cpu_threads
        options.inter_op_num_threads = 1
        options.log_severity_level = 3
        for tool, kind in [(self.detector, "detector"), (self.pose, "pose")]:
            tool.session = ort.InferenceSession(
                str(settings.models_root / self.manifest[kind]["filename"]),
                sess_options=options,
                providers=["CPUExecutionProvider"],
            )
        settings.result_root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()

    @property
    def ready(self):
        return True

    def frame(self, image):
        height, width = image.shape[:2]
        if min(height, width) < 2 or height * width > 16000000:
            raise ValueError("图像尺寸无效")
        if min(height, width) * 416 < max(height, width):
            raise ValueError("图像长宽比超出模型预处理支持范围")
        if max(height, width) > 1280:
            scale = 1280 / max(height, width)
            image = cv2.resize(
                image, (max(2, int(width * scale) // 2 * 2), max(2, int(height * scale) // 2 * 2))
            )
        final_height, final_width = image.shape[:2]
        if min(final_height, final_width) * 416 < max(final_height, final_width):
            raise ValueError("图像缩放后长宽比超出模型预处理支持范围")
        boxes, classes = self.detector(image)
        boxes = boxes[np.asarray(classes) == 0][:16] if len(boxes) else np.empty((0, 4))
        persons = []
        if len(boxes):
            points, scores = self.pose(image, bboxes=boxes)
            for box, person, confidence in zip(boxes, points, scores, strict=True):
                if not np.isfinite(person).all() or not np.isfinite(confidence).all():
                    raise ValueError("模型输出非有限数值")
                candidates = pose_candidates(person.tolist(), confidence.tolist())
                persons.append(
                    {
                        "bbox": [round(float(value), 3) for value in box],
                        "keypoints": [
                            [round(float(x), 3), round(float(y), 3), round(float(score), 5)]
                            for (x, y), score in zip(person, confidence, strict=True)
                        ],
                        "pose_candidates": candidates,
                    }
                )
        annotated = image.copy()
        edges = [
            (5, 6),
            (5, 7),
            (7, 9),
            (6, 8),
            (8, 10),
            (5, 11),
            (6, 12),
            (11, 12),
            (11, 13),
            (13, 15),
            (12, 14),
            (14, 16),
        ]
        for index, person in enumerate(persons):
            x1, y1, x2, y2 = map(int, person["bbox"])
            cv2.rectangle(annotated, (x1, y1), (x2, y2), (40, 200, 70), 2)
            cv2.putText(
                annotated,
                f"P{index + 1}",
                (max(0, x1), max(16, y1 - 5)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.5,
                (20, 180, 20),
                1,
            )
            for a, b in edges:
                if person["keypoints"][a][2] >= 0.35 and person["keypoints"][b][2] >= 0.35:
                    cv2.line(
                        annotated,
                        tuple(map(int, person["keypoints"][a][:2])),
                        tuple(map(int, person["keypoints"][b][:2])),
                        (220, 80, 30),
                        2,
                    )
        return annotated, persons

    def detect(self, request: DetectionRequest):
        if request.model_version != self.version:
            raise ValueError("模型版本未加载")
        if not self.lock.acquire(blocking=False):
            raise InferenceBusy("推理资源正忙")
        try:
            if request.media_kind == "VIDEO":
                from classroom_vision.inference.video_isolation import isolated_video

                return isolated_video(self, request)
            return self._detect(request)
        finally:
            self.lock.release()

    def cached_result(self, request):
        data = request.model_dump(mode="json")
        fingerprint = hashlib.sha256(
            json.dumps(
                {"request": data, "models": self.hashes, "schema_version": 2}, sort_keys=True
            ).encode()
        ).hexdigest()
        job = str(request.job_id)
        metadata_path = self.settings.result_root / f"{job}.json"
        if metadata_path.is_file():
            result = json.loads(metadata_path.read_text(encoding="utf-8"))
            legacy = hashlib.sha256(
                json.dumps({"request": data, "models": self.hashes}, sort_keys=True).encode()
            ).hexdigest()
            if result.get("request_digest") not in {fingerprint, legacy}:
                raise ValueError("作业编号已绑定其他输入")
            if result.get("schema_version") == 2:
                return fingerprint, result
        return fingerprint, None

    def _detect(self, request):
        started = time.monotonic()
        fingerprint, cached = self.cached_result(request)
        if cached is not None:
            return cached
        job = str(request.job_id)
        metadata_path = self.settings.result_root / f"{job}.json"
        path = read_asset(self.settings.asset_cache, str(request.asset_id), request.input_sha256)
        frames = []
        kind = request.media_kind
        strategy = "STILL"
        effective_interval = self.settings.sample_interval_ms
        if kind in {"IMAGE", "CAMERA"}:
            image = cv2.imdecode(np.fromfile(path, dtype=np.uint8), cv2.IMREAD_COLOR)
            if image is None:
                raise ValueError("图片无法解码")
            annotated, persons = self.frame(image)
            height, width = annotated.shape[:2]
            frames = [{"index": 0, "timestamp_ms": 0, "persons": persons}]
            ok, encoded = cv2.imencode(".png", annotated)
            if not ok:
                raise ValueError("结果图片编码失败")
            artifact = self.settings.result_root / f"{job}.png"
            partial = artifact.with_suffix(".part")
            partial.write_bytes(encoded.tobytes())
            partial.replace(artifact)
            content_type = "image/png"
            duration = 0
        else:
            capture = cv2.VideoCapture(str(path))
            try:
                fps = capture.get(cv2.CAP_PROP_FPS)
                count = capture.get(cv2.CAP_PROP_FRAME_COUNT)
                if (
                    not capture.isOpened()
                    or not math.isfinite(fps)
                    or not 0 < fps <= 120
                    or not math.isfinite(count)
                    or count <= 0
                ):
                    raise ValueError("视频无法解码")
                duration = round(count / fps * 1000)
                if duration > self.settings.max_video_seconds * 1000:
                    raise ValueError("视频时长超出本机演示上限")
                rendered = []
                points, strategy, effective_interval = video_samples(
                    int(count), fps, self.settings.sample_interval_ms, self.settings.max_frames
                )
                for frame_index, timestamp in points:
                    capture.set(cv2.CAP_PROP_POS_FRAMES, frame_index)
                    ok, image = capture.read()
                    if not ok:
                        raise ValueError("视频采样帧读取失败")
                    annotated, persons = self.frame(image)
                    frames.append(
                        {"index": frame_index, "timestamp_ms": timestamp, "persons": persons}
                    )
                    rendered.append(annotated)
                height, width = rendered[0].shape[:2]
                artifact = self.settings.result_root / f"{job}.mp4"
                self.encode_video(rendered, artifact, width, height, effective_interval)
                content_type = "video/mp4"
            finally:
                capture.release()
        counts = {
            "HAND_UP_CANDIDATE": sum(
                "HAND_UP_CANDIDATE" in person["pose_candidates"]
                for frame in frames
                for person in frame["persons"]
            )
        }
        with artifact.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        result = {
            "schema_version": 2,
            "job_id": job,
            "asset_id": str(request.asset_id),
            "input_sha256": request.input_sha256,
            "model_version": self.version,
            "model_hashes": self.hashes,
            "media_kind": kind,
            "width": width,
            "height": height,
            "duration_ms": duration,
            "sample_interval_ms": effective_interval,
            "sampling_strategy": strategy,
            "coverage_start_ms": frames[0]["timestamp_ms"],
            "coverage_end_ms": frames[-1]["timestamp_ms"],
            "sampled_frames": len(frames),
            "person_instances": sum(len(frame["persons"]) for frame in frames),
            "unique_people": None,
            "pose_counts": counts,
            "frames": frames,
            "artifact_sha256": digest,
            "artifact_size": artifact.stat().st_size,
            "artifact_content_type": content_type,
            "request_digest": fingerprint,
            "processed_at": datetime.now(UTC).isoformat(),
            "elapsed_ms": round((time.monotonic() - started) * 1000),
            "interpretation": "通用人物与姿态模型；候选规则未经课堂标注评测；累计人次不是独立人数",
        }
        partial = metadata_path.with_suffix(f".{uuid4()}.part")
        partial.write_text(
            json.dumps(result, ensure_ascii=False, allow_nan=False), encoding="utf-8"
        )
        partial.replace(metadata_path)
        return result

    def encode_video(self, frames, target, width, height, interval=None):
        temporary = target.with_suffix(".part")
        command = [
            self.settings.ffmpeg,
            "-v",
            "error",
            "-y",
            "-f",
            "rawvideo",
            "-pixel_format",
            "bgr24",
            "-video_size",
            f"{width}x{height}",
            "-framerate",
            str(1000 / (interval or self.settings.sample_interval_ms)),
            "-i",
            "pipe:0",
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            "-f",
            "mp4",
            str(temporary),
        ]
        with subprocess.Popen(
            command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE
        ) as process:
            try:
                for frame in frames:
                    process.stdin.write(frame.tobytes())
                process.stdin.close()
                process.stdin = None
                _, error = process.communicate(timeout=45)
                if process.returncode:
                    raise ValueError("结果视频编码失败")
            except BaseException:
                process.kill()
                process.wait()
                temporary.unlink(missing_ok=True)
                raise
        temporary.replace(target)
