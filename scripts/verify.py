from pathlib import Path
import os
import shutil
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    environment = os.environ.copy()
    environment['UV_CACHE_DIR']=str(root/'runtime/cache/uv')
    commands = [
        (root, [sys.executable, "scripts/quality_gate.py"]),
        (root, [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"]),
        (root / "backend", ["mvn.cmd" if os.name == "nt" else "mvn", "-s", ".mvn/settings.xml", "-B", "verify"]),
        (root / "frontend", ["npm.cmd" if os.name == "nt" else "npm", "ci"]),
        (root / "frontend", ["npm.cmd" if os.name == "nt" else "npm", "run", "verify"]),
        (root / "fastapi", ["uv", "sync", "--frozen"]),
        (root / "fastapi", ["uv", "run", "ruff", "check", "."]),
        (root / "fastapi", ["uv", "run", "ruff", "format", "--check", "."]),
        (root / "fastapi", ["uv", "run", "pytest"]),
        (root / "edge", ["uv", "sync", "--frozen"]),
        (root / "edge", ["uv", "run", "ruff", "check", "."]),
        (root / "edge", ["uv", "run", "ruff", "format", "--check", "."]),
        (root / "edge", ["uv", "run", "pytest"]),
    ]
    for directory, command in commands:
        if shutil.which(command[0], path=environment.get("PATH")) is None:
            raise SystemExit(f"缺少开发工具：{command[0]}")
        print(f"验证 {directory.name}：{' '.join(command)}", flush=True)
        subprocess.run(command, cwd=directory, env=environment, check=True)
    print("工程基础检查通过；不包含数据库联调、镜像运行或生产验收。")


if __name__ == "__main__":
    main()
