"""本机维护窗口备份与新库恢复；绝不覆盖已有数据库。"""

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import subprocess
import time

from local_dev import ROOT, load_values, mysql
from local_locks import stopped_edge_lock

SOURCE = "uu_classroom_local"


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def restore_name(value):
    if not re.fullmatch(r"uu_cv_restore_[a-f0-9]{12}", value):
        raise ValueError("恢复只允许随机命名的隔离库")
    return value


def verify_inventory(root, inventory):
    root = root.resolve()
    for relative, proof in inventory.items():
        target = (root / relative).resolve()
        if (
            not target.is_relative_to(root)
            or Path(relative).is_absolute()
            or ".." in Path(relative).parts
        ):
            raise ValueError("备份路径越界")
        original = root / relative
        if (
            original.is_symlink()
            or not target.is_file()
            or target.stat().st_size != proof["size"]
            or sha(target) != proof["sha256"]
        ):
            raise ValueError("备份文件缺失或校验失败")


@contextmanager
def maintenance():
    for port in (8089, 5000):
        with socket.socket() as connection:
            if connection.connect_ex(("127.0.0.1", port)) == 0:
                raise RuntimeError("请先停止backend、edge和vision，进入维护窗口")
    lock = ROOT / "runtime/maintenance.lock"
    lock.parent.mkdir(parents=True, exist_ok=True)
    with lock.open("x", encoding="utf-8") as target:
        target.write(str(os.getpid()))
    try:
        with (
            stopped_edge_lock(ROOT / "runtime/edge/worker.lock"),
            stopped_edge_lock(ROOT / "runtime/edge/results/.vision.lock", "vision"),
        ):
            yield
    finally:
        lock.unlink(missing_ok=True)


@contextmanager
def configuration(values, admin=False):
    target = ROOT / ".harness" / ("backup-" + secrets.token_hex(6) + ".cnf")
    target.parent.mkdir(exist_ok=True)
    entries = {
        "host": values.get("DB_HOST", "127.0.0.1"),
        "port": values.get("DB_PORT", "3306"),
        "user": values["DB_ADMIN_USER" if admin else "DB_USER"],
        "password": values["DB_ADMIN_PASSWORD" if admin else "DB_PASSWORD"],
    }
    if any("\n" in value or "\r" in value for value in entries.values()):
        raise ValueError("配置不能包含换行")
    target.write_text(
        "[client]\n"
        + "".join(
            f'{key}="{value.replace(chr(92), chr(92) * 2).replace(chr(34), chr(92) + chr(34))}"\n'
            for key, value in entries.items()
        ),
        encoding="utf-8",
    )
    try:
        yield target
    finally:
        target.unlink(missing_ok=True)


def executable(name, values):
    configured = values.get(name.upper() + "_EXE")
    result = configured or shutil.which(name)
    if not result:
        candidate = Path("D:/software/mysql8.4.8/bin") / (name + ".exe")
        if candidate.is_file():
            result = str(candidate)
    if not result:
        raise RuntimeError("未找到数据库客户端：" + name)
    return result


def query_as(values, sql):
    with configuration(values) as config:
        result = subprocess.run(
            [
                executable("mysql", values),
                f"--defaults-extra-file={config}",
                "--binary-mode=1",
                "--local-infile=0",
                "--batch",
                "--skip-column-names",
                "--default-character-set=utf8mb4",
            ],
            input=sql.encode("utf-8"),
            capture_output=True,
            timeout=300,
        )
        if result.returncode:
            raise RuntimeError("隔离恢复账号查询失败，原库未授权给恢复账号")
        return result.stdout.decode("utf-8").strip()


def dump(values, database, target, *, data_only=False, admin=False, views=()):
    with configuration(values, admin) as config:
        command = [
            executable("mysqldump", values),
            f"--defaults-extra-file={config}",
            "--single-transaction",
            "--no-tablespaces",
            "--set-gtid-purged=OFF",
            "--column-statistics=0",
            "--hex-blob",
            "--default-character-set=utf8mb4",
            "--skip-comments",
            "--skip-dump-date",
            "--skip-add-locks",
            "--skip-add-drop-table",
            "--skip-disable-keys",
            "--order-by-primary",
            f"--result-file={target}",
        ]
        if data_only:
            command += ["--no-create-info", "--skip-extended-insert"]
        command += [f"--ignore-table={database}.{view}" for view in views]
        command.append(database)
        result = subprocess.run(command, capture_output=True)
        if result.returncode:
            raise RuntimeError("数据库导出失败；未输出可能包含敏感数据的客户端错误")


def objects(values, database):
    if database != SOURCE:
        restore_name(database)
    rows = mysql(
        values,
        f"SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA='{database}' ORDER BY TABLE_NAME;",
    )
    return {line.split("\t")[0]: line.split("\t")[1] for line in rows.splitlines() if "\t" in line}


def snapshot_files(root, relative, target):
    source = root / relative
    if not source.is_dir():
        return
    if source.is_symlink() or not source.resolve().is_relative_to(root.resolve()):
        raise ValueError("备份源目录越界")
    for item in source.rglob("*"):
        if item.is_symlink() or not item.resolve().is_relative_to(source.resolve()):
            raise ValueError("备份源不允许符号链接或越界")
        if not item.is_file() or item.name in {
            "outbox.db",
            "outbox.db-wal",
            "outbox.db-shm",
            "worker.lock",
            ".vision.lock",
            ".artifact-budget.lock",
        }:
            continue
        destination = target / relative / item.relative_to(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(item, destination)
    if relative == "edge" and (source / "outbox.db").is_file():
        destination = target / relative / "outbox.db"
        destination.parent.mkdir(parents=True, exist_ok=True)
        with (
            sqlite3.connect(source / "outbox.db") as original,
            sqlite3.connect(destination) as backup,
        ):
            original.backup(backup)


def backup(values):
    with maintenance():
        target = (
            ROOT / "runtime/backups" / (time.strftime("%Y%m%d-%H%M%S") + "-" + secrets.token_hex(3))
        )
        target.mkdir(parents=True)
        listing = objects(values, SOURCE)
        if not listing or "flyway_schema_history" not in listing:
            raise ValueError("业务库未初始化")
        views = [name for name, kind in listing.items() if kind == "VIEW"]
        dump(values, SOURCE, target / "database.sql", views=views)
        dump(values, SOURCE, target / "data-proof.sql", data_only=True, views=views)
        # 视图单独恢复，禁止让恢复视图引用原业务库。
        definitions = {}
        for view in views:
            if not re.fullmatch(r"[a-z_]+", view):
                raise ValueError("视图名称无效")
            ddl = mysql(values, f"SHOW CREATE VIEW `{SOURCE}`.`{view}`;").split("\t")[1]
            definitions[view] = ddl
        (target / "views.json").write_text(
            json.dumps(definitions, ensure_ascii=False), encoding="utf-8"
        )
        for folder in ("assets", "artifacts", "edge"):
            snapshot_files(ROOT / "runtime", folder, target / "files")
        files = {
            str(path.relative_to(target)).replace("\\", "/"): {
                "size": path.stat().st_size,
                "sha256": sha(path),
            }
            for path in target.rglob("*")
            if path.is_file()
        }
        manifest = {
            "schema_version": 1,
            "source_database": SOURCE,
            "created_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "objects": listing,
            "data_sha256": sha(target / "data-proof.sql"),
            "files": files,
        }
        (target / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        verify_inventory(target, files)
        print("备份已创建并校验：" + str(target))
        return target


@contextmanager
def recovery_account(values, database):
    restore_name(database)
    user = "uu_recover_" + secrets.token_hex(6)
    password = secrets.token_urlsafe(32)
    mysql(values, f"CREATE USER '{user}'@'127.0.0.1' IDENTIFIED BY '{password}';")
    try:
        mysql(values, f"GRANT ALL PRIVILEGES ON `{database}`.* TO '{user}'@'127.0.0.1';")
        yield values | {"DB_USER": user, "DB_PASSWORD": password}
    finally:
        mysql(values, f"DROP USER '{user}'@'127.0.0.1';")


def restore_test(values, target):
    target = target.resolve()
    if not target.is_relative_to((ROOT / "runtime/backups").resolve()):
        raise ValueError("只接受本项目runtime/backups内的备份")
    manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1 or manifest.get("source_database") != SOURCE:
        raise ValueError("备份清单来源无效")
    verify_inventory(target, manifest["files"])
    with maintenance():
        database = restore_name("uu_cv_restore_" + secrets.token_hex(6))
        before = target / ("source-before-" + database + ".sql")
        views = [name for name, kind in objects(values, SOURCE).items() if kind == "VIEW"]
        dump(values, SOURCE, before, data_only=True, views=views)
        # 不使用IF NOT EXISTS，名称被占用即失败，永不覆盖或删除已有库。
        mysql(
            values,
            f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs;",
        )
        with recovery_account(values, database) as restricted:
            with (
                configuration(restricted) as config,
                (target / "database.sql").open("rb") as source,
            ):
                completed = subprocess.run(
                    [
                        executable("mysql", values),
                        f"--defaults-extra-file={config}",
                        "--binary-mode=1",
                        "--local-infile=0",
                        "--default-character-set=utf8mb4",
                        database,
                    ],
                    stdin=source,
                    capture_output=True,
                    timeout=300,
                )
                if completed.returncode:
                    raise RuntimeError("隔离库导入失败，保留隔离库供排查；原业务库未修改")
            definitions = json.loads((target / "views.json").read_text(encoding="utf-8"))
            for name, ddl in definitions.items():
                ddl = re.sub(r"DEFINER=`[^`]+`@`[^`]+`\s*", "", ddl)
                ddl = ddl.replace("SQL SECURITY DEFINER", "SQL SECURITY INVOKER")
                ddl = ddl.replace("`" + SOURCE + "`.", "`" + database + "`.")
                if not re.fullmatch("[a-z_]+", name):
                    raise ValueError("恢复视图名无效")
                query_as(restricted, "USE `" + database + "`;\n" + ddl + ";")
            restored = objects(values, database)
            if restored != manifest["objects"]:
                raise ValueError("恢复后表/视图清单不一致")
            proof = target / ("restored-" + database + ".sql")
            dump(restricted, database, proof, data_only=True, views=definitions)
            if sha(proof) != manifest["data_sha256"]:
                raise ValueError("恢复数据内容摘要不一致")
            restored_files = ROOT / "runtime/restore-tests" / database
            for name, record in manifest["files"].items():
                if name.startswith("files/"):
                    relative = Path(name).relative_to("files")
                    output = restored_files / relative
                    output.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(target / name, output)
                    if sha(output) != record["sha256"]:
                        raise ValueError("恢复文件内容不一致")
            # 视图必须能在恢复库独立查询，且定义不得回指业务库。
            for name in definitions:
                query_as(restricted, f"SELECT COUNT(*) FROM `{database}`.`{name}`;")
                if ("`" + SOURCE + "`.") in query_as(
                    restricted, f"SHOW CREATE VIEW `{database}`.`{name}`;"
                ):
                    raise ValueError("恢复视图仍指向业务库")
            after = target / ("source-after-" + database + ".sql")
            dump(values, SOURCE, after, data_only=True, views=views)
            if sha(before) != sha(after):
                raise ValueError("维护窗口中业务库发生了修改，不能记录演练成功")
            for name in definitions:
                mysql(values, f"SELECT COUNT(*) FROM `{database}`.`{name}`;")
        for name in definitions:
            mysql(values, f"SELECT COUNT(*) FROM `{database}`.`{name}`;")
        evidence = {
            "passed": True,
            "restored_database": database,
            "table_count": sum(kind == "BASE TABLE" for kind in restored.values()),
            "view_count": len(definitions),
            "data_content_sha256_match": True,
            "restored_file_count": sum(name.startswith("files/") for name in manifest["files"]),
            "business_database_unchanged": True,
            "completed_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        }
        result = ROOT / "runtime/trial-evidence/backup-restore.json"
        result.parent.mkdir(parents=True, exist_ok=True)
        result.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(evidence, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description="维护窗口备份与只向新隔离库恢复")
    parser.add_argument("action", choices=["backup", "verify", "restore-test"])
    parser.add_argument("--backup-dir", type=Path)
    args = parser.parse_args()
    values = load_values()
    if args.action == "backup":
        backup(values)
    elif args.backup_dir is None:
        parser.error("需要--backup-dir")
    elif args.action == "restore-test":
        restore_test(values, args.backup_dir)
    else:
        target = args.backup_dir.resolve()
        manifest = json.loads((target / "manifest.json").read_text(encoding="utf-8"))
        verify_inventory(target, manifest["files"])
        print("备份完整性验证通过")


if __name__ == "__main__":
    main()
