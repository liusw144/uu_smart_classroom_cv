"""在临时CI环境内启动本任务子进程，验收真实页面后回收自己创建的服务。"""

import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from local_dev import ROOT, environment, load_values, seed


def wait(url):
    for _ in range(90):
        try:
            with urllib.request.urlopen(url, timeout=1) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        time.sleep(0.5)
    raise RuntimeError("CI子服务启动超时")


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("此脚本只控制GitHub临时执行器；本机请用原启动命令。")
    values = load_values()
    processes = []
    logs = []
    commands = [
        (
            [
                str(Path(os.environ["JAVA_HOME"]) / "bin/java"),
                "-jar",
                "api/target/classroom-api-0.1.0-SNAPSHOT.jar",
            ],
            "backend",
            "backend",
            "http://127.0.0.1:8089/health/live",
        ),
        (
            [
                str(ROOT / "fastapi/.venv/bin/python"),
                "-m",
                "uvicorn",
                "classroom_vision.main:create_app",
                "--factory",
                "--host",
                "127.0.0.1",
                "--port",
                "5000",
            ],
            "fastapi",
            "vision",
            "http://127.0.0.1:5000/health/live",
        ),
        (
            [
                "node",
                "node_modules/vite/bin/vite.js",
                "apps/teacher",
                "--host",
                "127.0.0.1",
                "--port",
                "5173",
                "--strictPort",
            ],
            "frontend",
            "teacher",
            "http://127.0.0.1:5173",
        ),
        (
            [
                "node",
                "node_modules/vite/bin/vite.js",
                "apps/admin",
                "--host",
                "127.0.0.1",
                "--port",
                "5172",
                "--strictPort",
            ],
            "frontend",
            "admin",
            "http://127.0.0.1:5172",
        ),
    ]
    try:
        for command, folder, target, url in commands:
            output = (ROOT / ".harness" / ("ci-" + target + ".log")).open("wb")
            logs.append(output)
            processes.append(
                subprocess.Popen(
                    command,
                    cwd=ROOT / folder,
                    env=environment(values, target=target),
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
            )
            wait(url)
        seed(values)
        subprocess.run(["node", "e2e/local-mvp.cjs"], cwd=ROOT / "frontend", check=True)
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in logs:
            log.close()


if __name__ == "__main__":
    main()
