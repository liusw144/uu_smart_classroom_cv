"""验证12秒真实公开视频全片采样、自动续租和本校节点状态。"""

from datetime import datetime
import json
from pathlib import Path
import sqlite3
import subprocess
import sys

from local_dev import ROOT, load_values
from verify_edge_offline import multipart, request


def item(job):
    with sqlite3.connect(ROOT / "runtime/edge/outbox.db") as database:
        database.row_factory = sqlite3.Row
        row = dict(
            database.execute("SELECT * FROM outbox WHERE job_id=?", (job,)).fetchone()
        )
        row["lease"] = json.loads(row["lease"])
        return row


def main():
    values = load_values()
    output = ROOT / "runtime/trial-evidence"
    output.mkdir(parents=True, exist_ok=True)
    node_headers = {"X-Node-ID": values["EDGE_NODE_ID"]}
    active = request(
        "POST",
        "/internal/v1/edge/claim",
        values["EDGE_SERVICE_TOKEN"],
        headers=node_headers,
    )
    if active is not None:
        raise RuntimeError("本节点已有其他未完成作业，先处理已有任务后再验收")
    source = ROOT / "runtime/demo/upstream-demo.mp4"
    video = ROOT / "runtime/demo/full-span-12s.mp4"
    subprocess.run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-stream_loop",
            "-1",
            "-i",
            str(source),
            "-t",
            "12",
            "-an",
            "-vf",
            "fps=5",
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-pix_fmt",
            "yuv420p",
            str(video),
        ],
        check=True,
    )
    teacher = request(
        "POST",
        "/api/v1/auth/login",
        value={
            "school_code": "local-school",
            "username": values["TEACHER_USERNAME"],
            "password": values["TEACHER_PASSWORD"],
        },
    )["access_token"]
    admin = request(
        "POST",
        "/api/v1/auth/login",
        value={
            "school_code": "local-school",
            "username": "admin",
            "password": values["BOOTSTRAP_PASSWORD"],
        },
    )["access_token"]
    task = request("GET", "/api/v1/tasks?q=开源模型验证&size=100", teacher)["items"][0]
    model = request(
        "GET", "/api/v1/models?q=yolox-tiny-rtmpose-s-v1&size=100", teacher
    )["items"][0]
    payload, kind = multipart([("file", video.name, "video/mp4", video.read_bytes())])
    asset = request("POST", "/api/v1/assets", teacher, raw=payload, content_type=kind)
    job = request(
        "POST",
        "/api/v1/detections",
        teacher,
        {"task_id": task["id"], "asset_id": asset["id"], "model_id": model["id"]},
    )["id"]

    def edge(phase):
        subprocess.run(
            [
                sys.executable,
                "scripts/local_dev.py",
                "edge",
                "--once",
                "--phase",
                phase,
                "--heartbeat-seconds",
                "1",
            ],
            cwd=ROOT,
            check=True,
            stdout=subprocess.DEVNULL,
        )

    edge("cache")
    before = item(job)
    edge("infer")
    after = item(job)
    assert after["state"] == "RESULT_READY"
    renewed = datetime.fromisoformat(
        after["lease"]["lease_expires_at"]
    ) > datetime.fromisoformat(before["lease"]["lease_expires_at"])
    assert renewed, "真实32帧推理期间必须观察到至少一次自动续租"
    edge("deliver")
    assert item(job)["state"] == "DELIVERED"
    result = request("GET", "/api/v1/detections/" + job + "/result", teacher)
    assert (
        result["schema_version"] == 2
        and result["sampled_frames"] == 32
        and result["sampling_strategy"] == "UNIFORM_FULL_SPAN"
    )
    assert (
        result["coverage_start_ms"] == 0
        and result["coverage_end_ms"] >= 11000
        and result["person_instances"] > 0
    )
    nodes = request("GET", "/api/v1/edge-nodes", admin)
    assert any(
        node["id"] == values["EDGE_NODE_ID"] and node["online"]
        for node in nodes["items"]
    )
    assert "token_hash" not in json.dumps(nodes)
    evidence = {
        "passed": True,
        "job_id": job,
        "schema_version": 2,
        "sampled_frames": 32,
        "duration_ms": result["duration_ms"],
        "coverage_end_ms": result["coverage_end_ms"],
        "person_instances": result["person_instances"],
        "lease_renewed_during_real_inference": renewed,
        "node_status_scoped_and_online": True,
        "source": "MMPose公开视频循环为12秒，用于覆盖采样验证，不代表课堂精度",
    }
    (output / "full-span-result.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (output / "trial-live.json").write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    request("POST", "/api/v1/auth/logout", teacher)
    request("POST", "/api/v1/auth/logout", admin)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
