"""记录真实服务延迟；估算结果不能当作目标硬件容量验收。"""

import argparse
import json
import math
import platform
import ssl
import time
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

import httpx


def capacity(seconds, jobs, deadline, *, schools=1):
    if (
        not seconds
        or any(not math.isfinite(v) or v <= 0 for v in seconds)
        or jobs < 1
        or deadline < 1
        or not 1 <= schools <= jobs
    ):
        raise ValueError("容量样本或目标无效")
    ordered = sorted(seconds)
    p95 = ordered[math.ceil(0.95 * len(ordered)) - 1]
    per_school = math.ceil(math.ceil(jobs / schools) * p95 / deadline / 0.7)
    return {
        "measured_parallelism": 1,
        "samples": len(seconds),
        "p95_seconds": p95,
        "batch_jobs": jobs,
        "deadline_seconds": deadline,
        "estimated_workers": schools * per_school,
        "schools": schools,
        "estimated_workers_per_school": per_school,
        "assumed_even_distribution": True,
        "production_capacity_passed": False,
        "interpretation": "按学校均匀分配及70%利用率估算独立执行槽，尚未验证多节点竞争、真实录像负载和300路同时推理",
    }


def main():
    parser = argparse.ArgumentParser(description="已缓存公开样例的真实推理容量基线")
    parser.add_argument("--url", required=True)
    parser.add_argument("--token-file", type=Path, required=True)
    parser.add_argument("--ca", type=Path)
    parser.add_argument("--cert", type=Path)
    parser.add_argument("--key", type=Path)
    parser.add_argument("--development", action="store_true")
    parser.add_argument("--asset-id", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--deadline-seconds", type=int, required=True)
    parser.add_argument("--schools", type=int, default=10)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    address = urlsplit(args.url)
    if address.username or address.password or address.query or address.fragment:
        parser.error("URL不能包含凭据或查询参数")
    if not args.development and (
        address.scheme != "https" or not all((args.ca, args.cert, args.key))
    ):
        parser.error("容量验证必须使用HTTPS与客户端证书")
    if args.development and address.hostname not in {"127.0.0.1", "localhost"}:
        parser.error("开发HTTP只允许回环地址")
    if not 3 <= args.repeat <= 20:
        parser.error("重复次数需在3至20之间")
    context = ssl.create_default_context(cafile=str(args.ca) if args.ca else None)
    if args.cert:
        context.load_cert_chain(args.cert, args.key)
    timings = []
    results = []
    with httpx.Client(
        base_url=args.url,
        verify=context,
        trust_env=False,
        follow_redirects=False,
        timeout=120,
        headers={"Authorization": "Bearer " + args.token_file.read_text().strip()},
    ) as client:
        for _ in range(args.repeat):
            job = str(uuid4())
            start = time.monotonic()
            response = client.post(
                "/internal/v1/detections",
                json={
                    "job_id": job,
                    "asset_id": args.asset_id,
                    "input_sha256": args.sha256,
                    "model_version": args.model,
                    "media_kind": "VIDEO",
                },
            )
            response.raise_for_status()
            result = response.json()["data"]
            if result["job_id"] != job or result["input_sha256"] != args.sha256:
                raise ValueError("测量结果与输入不一致")
            timings.append(time.monotonic() - start)
            results.append(
                {
                    "job_id": job,
                    "sampled_frames": result["sampled_frames"],
                    "person_instances": result["person_instances"],
                    "model_hashes": result["model_hashes"],
                }
            )
    report = {
        **capacity(timings, 300, args.deadline_seconds, schools=args.schools),
        "platform": platform.platform(),
        "seconds": timings,
        "results": results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(
        json.dumps(
            {k: v for k, v in report.items() if k != "results"}, ensure_ascii=False, indent=2
        )
    )


if __name__ == "__main__":
    main()
