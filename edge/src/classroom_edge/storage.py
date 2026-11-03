"""持久化预留输入、推理暂存和回传副本的空间，终态确认后才能回收。"""

import json
import shutil
import time
from uuid import UUID


class StorageFull(OSError):
    pass


class DiskBudget:
    def __init__(
        self, root, outbox, *, limit_bytes=20 * 1024**3, minimum_free=2 * 1024**3, retention=3600
    ):
        if limit_bytes < 1 or minimum_free < 1 or retention < 1:
            raise ValueError("边缘存储预算或保留期无效")
        self.root = root.resolve()
        self.outbox = outbox
        self.limit = limit_bytes
        self.minimum_free = minimum_free
        self.retention = retention
        self.db = outbox.db
        self.db.execute(
            "CREATE TABLE IF NOT EXISTS disk_reservation (job_id TEXT PRIMARY KEY, bytes INTEGER NOT NULL CHECK(bytes>0))"
        )
        self.db.commit()

    def used(self):
        size = 0
        for path in self.root.rglob("*"):
            if path.is_symlink():
                raise ValueError("边缘数据卷不能包含符号链接")
            if path.is_file():
                size += path.stat().st_size
        return size

    def reserve(self, lease):
        job = str(UUID(lease["job_id"]))
        size = int(lease["size"])
        if not 0 < size <= 32 * 1024**2:
            raise ValueError("素材体积无效")
        # 输入替换双份，视觉临时/最终产物和Outbox等最多四份，另预留元数据。
        amount = 2 * size + 130 * 1024**2
        try:
            self.db.execute("BEGIN IMMEDIATE")
            if self.db.execute("SELECT 1 FROM disk_reservation WHERE job_id=?", (job,)).fetchone():
                self.db.commit()
                return
            self.ensure_capacity(amount)
            self.db.execute("INSERT INTO disk_reservation VALUES (?,?)", (job, amount))
            self.db.commit()
        except BaseException:
            self.db.rollback()
            raise

    def ensure_capacity(self, amount):
        reserved = self.db.execute(
            "SELECT COALESCE(SUM(bytes),0) FROM disk_reservation"
        ).fetchone()[0]
        if (
            self.used() + reserved + amount > self.limit
            or shutil.disk_usage(self.root).free < self.minimum_free + reserved + amount
        ):
            raise StorageFull("边缘磁盘预算不足，保留现有作业并暂停新下载")

    def preflight(self):
        # 在领取租约之前按最大合法输入核验，满盘不能消耗服务器重试次数。
        self.ensure_capacity(194 * 1024**2)

    def collect(self, *, now=None):
        cutoff = (time.time() if now is None else now) - self.retention
        rows = self.db.execute(
            "SELECT job_id,lease FROM outbox WHERE state IN ('DELIVERED','REJECTED') AND updated_at<? LIMIT 100",
            (cutoff,),
        ).fetchall()
        for row in rows:
            job = str(UUID(row["job_id"]))
            lease = json.loads(row["lease"])
            try:
                artifact = self.root / "outbox-artifacts" / job
                artifact.unlink(missing_ok=True)
                artifact.with_suffix(".part").unlink(missing_ok=True)
                results = self.root / "results"
                for suffix in (".json", ".png", ".mp4", ".part"):
                    (results / (job + suffix)).unlink(missing_ok=True)
                if "asset_id" in lease:
                    asset = str(UUID(lease["asset_id"]))
                    shared = any(
                        json.loads(other[0]).get("asset_id") == asset
                        for other in self.db.execute(
                            "SELECT lease FROM outbox WHERE job_id<>?", (job,)
                        )
                    )
                    if not shared:
                        (self.root / "assets" / asset).unlink(missing_ok=True)
                        (self.root / "assets" / (asset + ".part")).unlink(missing_ok=True)
                # 先完成文件删除；失败时保留记录和额度，下一轮继续重试。
                with self.db:
                    self.db.execute(
                        "DELETE FROM outbox WHERE job_id=? AND state IN ('DELIVERED','REJECTED')",
                        (job,),
                    )
                    self.db.execute("DELETE FROM disk_reservation WHERE job_id=?", (job,))
            except OSError:
                continue

    def status(self):
        return {
            "used_bytes": self.used(),
            "limit_bytes": self.limit,
            "reserved_bytes": self.db.execute(
                "SELECT COALESCE(SUM(bytes),0) FROM disk_reservation"
            ).fetchone()[0],
            "free_bytes": shutil.disk_usage(self.root).free,
        }
