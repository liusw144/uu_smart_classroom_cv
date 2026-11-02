import argparse
import hashlib
import json
import os
import sqlite3
import time
from datetime import UTC, datetime
from pathlib import Path
from uuid import UUID

import httpx
from jsonschema import Draft202012Validator, FormatChecker


def digest(value):
    return hashlib.sha256(value).hexdigest()


def atomic(path, value):
    temporary = path.with_suffix(".part")
    with temporary.open("wb") as target:
        target.write(value)
        target.flush()
        os.fsync(target.fileno())
    temporary.replace(path)


class Outbox:
    """结果提交前先落盘，进程重启后从未确认记录恢复。"""

    def __init__(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.execute("""CREATE TABLE IF NOT EXISTS outbox (
            job_id TEXT PRIMARY KEY, lease TEXT NOT NULL, state TEXT NOT NULL,
            result TEXT, updated_at REAL NOT NULL)""")
        self.db.commit()

    def save(self, lease, state, result=None):
        with self.db:
            self.db.execute(
                "INSERT INTO outbox VALUES (?,?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET "
                "lease=excluded.lease,state=excluded.state,result=excluded.result,updated_at=excluded.updated_at",
                (lease["job_id"], json.dumps(lease), state, result, time.time()),
            )

    def pending(self):
        return [
            dict(row) | {"lease": json.loads(row["lease"])}
            for row in self.db.execute(
                "SELECT * FROM outbox WHERE state NOT IN ('DELIVERED','REJECTED') ORDER BY updated_at"
            )
        ]

    def close(self):
        self.db.close()


class Worker:
    def __init__(self, root, outbox, control, vision, validator=None):
        self.root, self.outbox, self.control, self.vision = root, outbox, control, vision
        self.validator = validator
        (root / "assets").mkdir(parents=True, exist_ok=True)
        (root / "outbox-artifacts").mkdir(parents=True, exist_ok=True)

    def artifact_path(self, lease):
        return self.root / "outbox-artifacts" / str(UUID(lease["job_id"]))

    def endpoint(self, lease, suffix):
        return "/internal/v1/edge/jobs/" + str(UUID(lease["job_id"])) + "/" + suffix

    def headers(self, lease):
        return {"X-Job-Lease": str(UUID(lease["lease_id"]))}

    def claim(self):
        response = self.control.post("/internal/v1/edge/claim")
        response.raise_for_status()
        lease = response.json()["data"]
        if lease is None:
            return None
        if self.validator:
            self.validator.validate(lease)
        if not 0 < lease["size"] <= 32 * 1024 * 1024:
            raise ValueError("输入体积超出上限")
        self.outbox.save(lease, "CLAIMED")
        return self.outbox.pending()[0]

    def cache(self, item):
        lease = item["lease"]
        target = self.root / "assets" / str(UUID(lease["asset_id"]))
        if target.is_file() and digest(target.read_bytes()) == lease["input_sha256"]:
            self.outbox.save(lease, "CACHED")
            return
        with self.control.stream(
            "GET", self.endpoint(lease, "asset"), headers=self.headers(lease)
        ) as response:
            response.raise_for_status()
            value = bytearray()
            for chunk in response.iter_bytes():
                value.extend(chunk)
                if len(value) > lease["size"]:
                    raise ValueError("下载体积与租约不一致")
        if len(value) != lease["size"] or digest(value) != lease["input_sha256"]:
            raise ValueError("下载哈希与租约不一致")
        atomic(target, value)
        self.outbox.save(lease, "CACHED")

    def infer(self, item):
        lease = item["lease"]
        # 只向本地视觉服务提交不可变编号，不传文件路径或远程地址。
        response = self.vision.post(
            "/internal/v1/detections",
            json={
                "job_id": lease["job_id"],
                "asset_id": lease["asset_id"],
                "input_sha256": lease["input_sha256"],
                "model_version": lease["model_version"],
                "media_kind": lease["media_kind"],
            },
        )
        response.raise_for_status()
        result = response.json()["data"]
        if any(
            result[key] != lease[key]
            for key in ("job_id", "asset_id", "input_sha256", "model_version", "media_kind")
        ):
            raise ValueError("视觉响应与租约不一致")
        if not 0 < result["artifact_size"] <= 32 * 1024 * 1024:
            raise ValueError("结果体积超限")
        with self.vision.stream(
            "GET", f"/internal/v1/detections/{lease['job_id']}/artifact"
        ) as response:
            response.raise_for_status()
            artifact = bytearray()
            for chunk in response.iter_bytes():
                artifact.extend(chunk)
                if len(artifact) > result["artifact_size"]:
                    raise ValueError("结果下载体积不符")
        if (
            len(artifact) != result["artifact_size"]
            or digest(artifact) != result["artifact_sha256"]
        ):
            raise ValueError("结果下载哈希不符")
        atomic(self.artifact_path(lease), artifact)
        self.outbox.save(
            lease, "RESULT_READY", json.dumps(result, ensure_ascii=False, allow_nan=False)
        )

    def deliver(self, item):
        lease, result = item["lease"], json.loads(item["result"])
        artifact = self.artifact_path(lease).read_bytes()
        if digest(artifact) != result["artifact_sha256"]:
            raise ValueError("Outbox产物已被修改")
        try:
            response = self.control.post(
                self.endpoint(lease, "result"),
                headers=self.headers(lease),
                files={
                    "result": (None, item["result"], "text/plain; charset=utf-8"),
                    "artifact": ("artifact", artifact, result["artifact_content_type"]),
                },
            )
            if response.status_code in (404, 409):
                self.outbox.save(lease, "REJECTED", item["result"])
                return False
            response.raise_for_status()
            if response.json().get("data") is not True:
                raise ValueError("管理端未确认结果")
            self.outbox.save(lease, "DELIVERED", item["result"])
            return True
        except httpx.TransportError:
            return False

    def step(self, phase="all"):
        pending = self.outbox.pending()
        if not pending and phase in ("all", "cache"):
            item = self.claim()
            pending = [] if item is None else [item]
        for item in pending:
            expiry = datetime.fromisoformat(item["lease"]["lease_expires_at"])
            if expiry <= datetime.now(UTC):
                self.outbox.save(item["lease"], "REJECTED", item["result"])
                continue
            try:
                if item["state"] == "CLAIMED" and phase in ("all", "cache"):
                    self.cache(item)
                elif item["state"] == "CACHED" and phase in ("all", "infer"):
                    self.infer(item)
                elif item["state"] == "RESULT_READY" and phase in ("all", "deliver"):
                    self.deliver(item)
            except httpx.HTTPStatusError as error:
                if error.response.status_code in (404, 409):
                    self.outbox.save(item["lease"], "REJECTED", item["result"])
                elif 400 <= error.response.status_code < 500 and error.response.status_code != 429:
                    # 永久输入错误向管理端显式报告；管理端断网时保留待重试记录。
                    failure = self.control.post(
                        self.endpoint(item["lease"], "fail"),
                        headers=self.headers(item["lease"]),
                        json={"code": "INFERENCE_FAILED"},
                    )
                    failure.raise_for_status()
                    self.outbox.save(item["lease"], "REJECTED", item["result"])
                else:
                    raise


def main():
    parser = argparse.ArgumentParser(description="本机边缘执行器；断网只执行已缓存租约")
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--phase", choices=["all", "cache", "infer", "deliver"], default="all")
    args = parser.parse_args()
    root = Path(os.environ["EDGE_STATE_ROOT"]).resolve()
    schema = json.loads(Path(os.environ["EDGE_LEASE_SCHEMA"]).read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    control = httpx.Client(
        base_url=os.environ["EDGE_CONTROL_URL"],
        timeout=30,
        follow_redirects=False,
        trust_env=False,
        headers={
            "Authorization": "Bearer " + os.environ["EDGE_SERVICE_TOKEN"],
            "X-Node-ID": os.environ["EDGE_NODE_ID"],
        },
    )
    vision = httpx.Client(
        base_url=os.environ["VISION_URL"],
        timeout=120,
        follow_redirects=False,
        trust_env=False,
        headers={"Authorization": "Bearer " + os.environ["VISION_SERVICE_TOKEN"]},
    )
    outbox = Outbox(root / "outbox.db")
    worker = Worker(root, outbox, control, vision, validator)
    try:
        while True:
            try:
                worker.step(args.phase)
            except (httpx.HTTPError, ValueError, OSError):
                print("边缘执行暂不可用，保留Outbox记录并等待重试。", flush=True)
                if args.once:
                    raise SystemExit(1)
            if args.once:
                return
            time.sleep(2)
    finally:
        outbox.close()
        control.close()
        vision.close()


if __name__ == "__main__":
    main()
