"""课堂数据清单、标注格式和分组划分校验；不伪造标注或精度。"""

import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def identifier(value):
    return isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,80}", value)


def validate(manifest, root):
    root = root.resolve()
    if manifest.get("schema_version") != 1 or not manifest.get("samples"):
        raise ValueError("没有可验收样本，不能生成精度结果")
    categories = manifest.get("categories", [])
    if (
        not categories
        or len(set(categories)) != len(categories)
        or not all(identifier(item) for item in categories)
    ):
        raise ValueError("类别代码无效")
    seen_ids = set()
    seen_hashes = set()
    groups = {}
    count = 0
    for sample in manifest["samples"]:
        for key in ("sample_id", "school_id", "session_id", "annotator_id"):
            if not identifier(sample.get(key)):
                raise ValueError("编号需使用匿名化代码")
        if (
            not sample.get("authorization_ref")
            or sample.get("annotation_status") != "human-reviewed"
        ):
            raise ValueError("需要授权记录引用及人工审核标注")
        if sample["sample_id"] in seen_ids:
            raise ValueError("样本编号重复")
        seen_ids.add(sample["sample_id"])
        relative = sample.get("media_path", "")
        if (
            not relative
            or "\\" in relative
            or ":" in relative
            or ".." in Path(relative).parts
            or Path(relative).is_absolute()
        ):
            raise ValueError("素材路径无效")
        target = (root / relative).resolve()
        if (
            not target.is_relative_to(root)
            or not target.is_file()
            or (root / relative).is_symlink()
        ):
            raise ValueError("素材越界或不存在")
        with target.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if digest != sample.get("sha256"):
            raise ValueError("素材哈希不符")
        if digest in seen_hashes:
            raise ValueError("重复素材可能导致训练评测泄漏")
        seen_hashes.add(digest)
        group = (sample["school_id"], sample["session_id"])
        partition = sample.get("split")
        if partition not in {"train", "val", "test"}:
            raise ValueError("划分必须为train/val/test")
        if group in groups and groups[group] != partition:
            raise ValueError("同一拍摄分组跨训练和评测划分")
        groups[group] = partition
        width = sample.get("width", 0)
        height = sample.get("height", 0)
        if (
            type(width) is not int
            or type(height) is not int
            or not 1 <= width <= 8192
            or not 1 <= height <= 8192
        ):
            raise ValueError("标注图像尺寸无效")
        frames = sample.get("frames", [])
        if not frames:
            raise ValueError("缺少人工标注帧")
        timestamps = set()
        for frame in frames:
            timestamp = frame.get("timestamp_ms")
            if type(timestamp) is not int or timestamp < 0 or timestamp in timestamps:
                raise ValueError("标注时间戳无效或重复")
            timestamps.add(timestamp)
            if not isinstance(frame.get("boxes"), list):
                raise ValueError("需要明确的标注框列表，负样本可为空")
            for box in frame["boxes"]:
                bbox = box.get("bbox", [])
                if len(bbox) != 4 or any(
                    type(value) not in (int, float) or not math.isfinite(value)
                    for value in bbox
                ):
                    raise ValueError("标注框格式无效")
                if (
                    not 0 <= bbox[0] < bbox[2] <= width
                    or not 0 <= bbox[1] < bbox[3] <= height
                    or box.get("category") not in categories
                ):
                    raise ValueError("标注框越界或类别未定义")
                count += 1
    return {
        "samples": len(seen_ids),
        "recording_groups": len(groups),
        "boxes": count,
        "splits": sorted(set(groups.values())),
        "accuracy": None,
        "note": "仅验证清单、哈希、坐标及划分；未验证人工标注语义、媒体解码或模型精度",
    }


def split(manifest, seed):
    result = copy.deepcopy(manifest)
    groups = sorted(
        {(item["school_id"], item["session_id"]) for item in result["samples"]},
        key=lambda group: hashlib.sha256(
            (seed + "|" + group[0] + "|" + group[1]).encode()
        ).digest(),
    )
    if len(groups) < 3:
        raise ValueError("至少需要三个独立拍摄分组，不能把同一课堂拆作训练和测试")
    training = max(1, min(len(groups) - 2, round(len(groups) * 0.7)))
    validation = max(1, min(len(groups) - training - 1, round(len(groups) * 0.15)))
    assignments = {
        group: (
            "train"
            if index < training
            else "val"
            if index < training + validation
            else "test"
        )
        for index, group in enumerate(groups)
    }
    for sample in result["samples"]:
        sample["split"] = assignments[(sample["school_id"], sample["session_id"])]
    return result


def main():
    parser = argparse.ArgumentParser(description="课堂标注清单校验与按拍摄分组划分")
    parser.add_argument("action", choices=["template", "validate", "split"])
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--seed", default="classroom-v1")
    args = parser.parse_args()
    root = ROOT / "runtime/datasets"
    root.mkdir(parents=True, exist_ok=True)
    if args.action == "template":
        data = {
            "schema_version": 1,
            "categories": ["hand_up", "looking_down", "other"],
            "samples": [],
        }
    else:
        if args.manifest is None:
            parser.error("需要--manifest")
        data = json.loads(args.manifest.read_text(encoding="utf-8"))
        if args.action == "split":
            data = split(data, args.seed)
        report = validate(data, root)
        if args.action == "validate":
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return
    if args.output is None or not args.output.resolve().is_relative_to(root.resolve()):
        parser.error("--output必须位于runtime/datasets")
    with args.output.open("x", encoding="utf-8") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
    print("数据清单已写入，未生成任何人工标注或准确率。")


if __name__ == "__main__":
    main()
