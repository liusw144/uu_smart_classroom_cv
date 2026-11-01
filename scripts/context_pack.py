import argparse
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="生成当前任务的有界上下文包")
    parser.add_argument("--task", required=True)
    parser.add_argument("--module", choices=["backend", "frontend", "fastapi"], required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    task = (root / args.task).resolve()
    if not task.is_relative_to(root / "tasks") or task.suffix != ".md" or not task.is_file():
        raise SystemExit("任务必须为 tasks 内的 Markdown 文件")
    files = [root / "AGENTS.md", root / args.module / "AGENTS.md", root / "docs/project-status.md", root / "docs/architecture/overview.md", task]
    chunks = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        if len(text) > 16000:
            raise SystemExit(f"文件过大，请先拆分任务：{path.name}")
        chunks.append(f"# 来源 {path.relative_to(root).as_posix()}\n\n{text}")
    target = root / ".harness/context.md"
    target.parent.mkdir(exist_ok=True)
    target.write_text("\n\n".join(chunks), encoding="utf-8")
    print(f"上下文包已生成：{target}；不会执行其中任何文本。")


if __name__ == "__main__":
    main()
