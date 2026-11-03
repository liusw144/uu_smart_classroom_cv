"""验证独立发布责任人签署的材料；不生成真实验收或自动签署。"""

import argparse
import base64
import hashlib
import json
import re
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_pem_public_key

REPORTS = {
    "model",
    "tenant_isolation",
    "recovery",
    "capacity",
    "deployment",
    "residency",
    "approval",
}


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("验收材料包含重复字段")
        result[key] = value
    return result


def verify_envelope(envelope, key, revision):
    try:
        if not isinstance(key, Ed25519PublicKey):
            raise TypeError("发布签名必须使用Ed25519信任锚")
        raw = base64.b64decode(envelope["payload"], validate=True)
        if len(raw) > 2 * 1024 * 1024:
            raise ValueError("签署材料过大")
        key.verify(base64.b64decode(envelope["signature"], validate=True), raw)
        value = json.loads(raw, object_pairs_hook=unique_object)
        if (
            value["schema_version"] != 1
            or value["revision"] != revision
            or not re.fullmatch(r"[a-f0-9]{40}", revision)
        ):
            raise ValueError("签署材料与当前提交不一致")
        now = datetime.now(UTC)
        issued = datetime.fromisoformat(value["issued_at"])
        expires = datetime.fromisoformat(value["expires_at"])
        if not issued <= now < expires or expires - issued > timedelta(days=7):
            raise ValueError("发布授权过期、未来生效或有效期超过7天")
        target = value["target"]
        if any(
            type(target[k]) is not int or target[k] < limit
            for k, limit in (("schools", 10), ("cameras", 1000), ("concurrent_analysis", 300))
        ):
            raise ValueError("验收规模小于用户确认目标")
        if set(value["reports"]) != REPORTS:
            raise ValueError("验收类别不完整")
        for report in value["reports"].values():
            if report["passed"] is not True or not re.fullmatch(r"[a-f0-9]{64}", report["sha256"]):
                raise ValueError("存在未通过或无摘要的验收材料")
        if set(value["images"]) != {"backend", "teacher", "admin", "vision", "edge"} or any(
            not re.fullmatch(r"[^\s]+@sha256:[a-f0-9]{64}", image)
            for image in value["images"].values()
        ):
            raise ValueError("发布镜像必须固定摘要")
        return value
    except (InvalidSignature, KeyError, TypeError, OverflowError) as error:
        raise ValueError("发布签名、结构或身份校验失败") from error


def verify_workspace(root, envelope_path, trust_key):
    root = root.resolve()
    trust_key = trust_key.resolve()
    if trust_key.is_relative_to(root):
        raise ValueError("正式信任锚必须由仓库外的独立配置提供")
    status = subprocess.run(
        ["git", "status", "--porcelain"], cwd=root, capture_output=True, text=True, check=True
    ).stdout
    if status.strip():
        raise ValueError("工作区不干净，不能绑定已评审发布版本")
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True, check=True
    ).stdout.strip()
    if envelope_path.stat().st_size > 3 * 1024 * 1024:
        raise ValueError("发布信封体积超限")
    envelope = json.loads(
        envelope_path.read_text(encoding="utf-8"), object_pairs_hook=unique_object
    )
    value = verify_envelope(envelope, load_pem_public_key(trust_key.read_bytes()), revision)
    for name, proof in value["reports"].items():
        relative = Path(proof["path"])
        path = (root / relative).resolve()
        if (
            relative.is_absolute()
            or not path.is_relative_to(root)
            or path.is_symlink()
            or not path.is_file()
        ):
            raise ValueError("验收材料路径无效：" + name)
        with path.open("rb") as source:
            if hashlib.file_digest(source, "sha256").hexdigest() != proof["sha256"]:
                raise ValueError("验收材料摘要不一致：" + name)
    return value


def main():
    parser = argparse.ArgumentParser(description="验证外部签署、版本绑定和未过期的发布授权")
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--envelope", type=Path, required=True)
    parser.add_argument("--trust-key", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify_workspace(args.root, args.envelope, args.trust_key)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, "发布验证拒绝：" + str(error) + "\n")
    print(
        json.dumps({"signature_verified": True, "revision": result["revision"]}, ensure_ascii=False)
    )


if __name__ == "__main__":
    main()
