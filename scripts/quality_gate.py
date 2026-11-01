import argparse
import ast
import hashlib
import io
import json
from pathlib import Path
import re
import tokenize


def scan_sources(root: Path) -> list[str]:
    errors = []
    ignored = {".git", ".venv", "node_modules", "target", "dist", "__pycache__", ".harness", ".superpowers", ".pytest_cache", ".ruff_cache"}
    suffixes = {".py", ".java", ".ts", ".vue", ".css", ".sql", ".yaml", ".yml", ".xml", ".ps1"}
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if not path.is_file() or ignored.intersection(relative.parts) or path.suffix not in suffixes:
            continue
        text = path.read_text(encoding="utf-8-sig")
        name = relative.as_posix()
        if name.startswith(("backend/domain/", "backend/application/")):
            if re.search(r"import\s+(org\.springframework|com\.baomidou|cn\.dev33|cn\.uusmart\.classroom\.infrastructure)", text):
                errors.append(f"{name}: 领域及应用层不能引用框架或基础设施")
        if name.startswith("frontend/") and re.search(r"(?:from\s*|import\s*\()['\"][^'\"]*(?:backend|fastapi)/", text):
            errors.append(f"{name}: 前端不能引用其他模块源码")
        comments = []
        if path.suffix == ".py":
            try:
                tree = ast.parse(text)
                for node in ast.walk(tree):
                    if isinstance(node, (ast.Import, ast.ImportFrom)):
                        modules = [item.name for item in node.names] if isinstance(node, ast.Import) else [node.module or ""]
                        if name.startswith("fastapi/") and any(module.split(".")[0] in {"pymysql", "mysql", "sqlalchemy"} for module in modules):
                            errors.append(f"{name}: 推理模块不能连接业务数据库")
                    if isinstance(node, (ast.Module, ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                        docstring = ast.get_docstring(node)
                        if docstring:
                            comments.append(docstring)
                comments.extend(token.string[1:] for token in tokenize.generate_tokens(io.StringIO(text).readline) if token.type == tokenize.COMMENT)
            except (SyntaxError, tokenize.TokenError) as error:
                errors.append(f"{name}: Python 语法错误 {error}")
        else:
            if path.suffix in {".java", ".ts", ".vue", ".css"}:
                pattern = r'''("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|(//[^\n]*|/\*[\s\S]*?\*/|<!--[\s\S]*?-->)'''
                comments.extend(match.group(2) for match in re.finditer(pattern, text) if match.group(2))
            elif path.suffix == ".xml":
                comments.extend(re.findall(r"<!--[\s\S]*?-->", text))
            if path.suffix in {".sql", ".yaml", ".yml", ".ps1"}:
                comments.extend(re.findall(r"(?m)^\s*(?:#|--\s)(.*)$", text))
        for comment in comments:
            if re.search(r"[A-Za-z]", comment) and not re.search(r"[\u3400-\u9fff]", comment):
                errors.append(f"{name}: 说明性注释必须包含中文：{comment[:70]}")
    return errors


def production_errors(root: Path) -> list[str]:
    manifest = root / "harness/manifest.json"
    if not manifest.exists():
        return ["缺少工程清单及生产验收证据"]
    blockers = json.loads(manifest.read_text(encoding="utf-8")).get("production_blockers", [])
    errors = [f"生产阻塞：{item}" for item in blockers]
    evidence = root / "harness/release-evidence.json"
    if not evidence.exists():
        return errors + ["缺少 harness/release-evidence.json，不允许宣称生产就绪"]
    data = json.loads(evidence.read_text(encoding="utf-8"))
    for key in ["model", "tenant_isolation", "recovery", "capacity", "deployment", "approval"]:
        record = data.get(key, {})
        path = root / record.get("path", "__missing__")
        if not path.is_file() or path.resolve().is_relative_to(root.resolve()) is False:
            errors.append(f"生产证据缺失：{key}")
        elif hashlib.sha256(path.read_bytes()).hexdigest() != record.get("sha256"):
            errors.append(f"生产证据校验失败：{key}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="检查工程边界与生产证据")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--production", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    required = ["README.md", "AGENTS.md", "harness/manifest.json", "docs/project-status.md", "contracts/business.openapi.json", "contracts/detection-request.schema.json"]
    for module, lock in [("backend", "pom.xml"), ("frontend", "package-lock.json"), ("fastapi", "uv.lock")]:
        required.extend([f"{module}/AGENTS.md", f"{module}/README.md", f"{module}/{lock}"])
    errors = [f"缺少工程文件：{name}" for name in required if not (root / name).is_file()]
    errors.extend(scan_sources(root))
    for path in (root / "contracts").glob("*.json"):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            errors.append(f"契约 JSON 无效：{path.name} {error}")
    if args.production:
        errors.extend(production_errors(root))
    print(json.dumps({"profile": "production" if args.production else "foundation", "passed": not errors, "errors": errors}, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
