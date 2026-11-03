import time
from uuid import uuid4

import pytest

from classroom_edge.worker import Outbox


def test_full_disk_rejects_before_claiming_any_lease(tmp_path):
    import httpx

    from classroom_edge.storage import DiskBudget, StorageFull
    from classroom_edge.worker import Worker

    db = Outbox(tmp_path / "outbox.db")
    calls = []
    client = httpx.Client(
        base_url="http://control.test", transport=httpx.MockTransport(lambda r: calls.append(r))
    )
    budget = DiskBudget(tmp_path, db, limit_bytes=1, minimum_free=1)
    worker = Worker(tmp_path, db, client, None, budget=budget)
    with pytest.raises(StorageFull):
        worker.claim()
    assert calls == []
    assert db.pending() == []
    db.close()


def lease():
    return {"job_id": str(uuid4()), "asset_id": str(uuid4()), "size": 100}


def test_reservation_survives_restart_and_unacknowledged_results_are_retained(tmp_path):
    from classroom_edge.storage import DiskBudget, StorageFull

    db = Outbox(tmp_path / "outbox.db")
    job = lease()
    budget = DiskBudget(tmp_path, db, limit_bytes=150 * 1024 * 1024, minimum_free=1, retention=1)
    db.save(job, "RESULT_READY", "{}")
    budget.reserve(job)
    budget.collect(now=time.time() + 60)
    assert db.pending()[0]["state"] == "RESULT_READY"
    db.close()
    resumed = Outbox(tmp_path / "outbox.db")
    budget = DiskBudget(
        tmp_path, resumed, limit_bytes=150 * 1024 * 1024, minimum_free=1, retention=1
    )
    with pytest.raises(StorageFull):
        budget.reserve(lease())
    resumed.close()


def test_acknowledged_cleanup_keeps_shared_input_until_last_reference(tmp_path):
    from classroom_edge.storage import DiskBudget

    db = Outbox(tmp_path / "outbox.db")
    first = lease()
    second = lease()
    second["asset_id"] = first["asset_id"]
    budget = DiskBudget(tmp_path, db, minimum_free=1, retention=1)
    (tmp_path / "assets").mkdir()
    path = tmp_path / "assets" / first["asset_id"]
    path.write_bytes(b"input")
    for job, state in ((first, "DELIVERED"), (second, "CACHED")):
        db.save(job, state)
        budget.reserve(job)
    budget.collect(now=time.time() + 60)
    assert path.exists()
    db.save(second, "DELIVERED")
    budget.collect(now=time.time() + 60)
    assert not path.exists()
    assert db.db.execute("SELECT COUNT(*) FROM disk_reservation").fetchone()[0] == 0
    db.close()


def test_failed_physical_cleanup_does_not_release_reservation(monkeypatch, tmp_path):
    from pathlib import Path

    from classroom_edge.storage import DiskBudget

    db = Outbox(tmp_path / "outbox.db")
    job = lease()
    db.save(job, "DELIVERED")
    budget = DiskBudget(tmp_path, db, minimum_free=1, retention=1)
    budget.reserve(job)
    original = Path.unlink

    def denied(path, *args, **kwargs):
        if path.name == job["job_id"]:
            raise PermissionError("注入文件清理失败")
        return original(path, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", denied)
    budget.collect(now=time.time() + 60)
    assert db.db.execute("SELECT COUNT(*) FROM disk_reservation").fetchone()[0] == 1
    assert db.db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0] == 1
    db.close()
