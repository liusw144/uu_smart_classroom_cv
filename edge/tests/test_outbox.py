import hashlib
import json
import time
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import httpx
import pytest

from classroom_edge.locking import process_lock
from classroom_edge.worker import Outbox, Worker


def test_only_one_process_can_own_the_outbox(tmp_path):
    with (
        process_lock(tmp_path / "worker.lock"),
        pytest.raises(RuntimeError),
        process_lock(tmp_path / "worker.lock"),
    ):
        pass
    with process_lock(tmp_path / "worker.lock"):
        pass


def test_outbox_survives_restart_and_does_not_lose_cached_job(tmp_path):
    job = {"job_id": str(uuid4()), "lease_id": str(uuid4())}
    first = Outbox(tmp_path / "outbox.db")
    first.save(job, "CACHED")
    first.close()
    resumed = Outbox(tmp_path / "outbox.db")
    assert resumed.pending()[0]["lease"] == job
    assert resumed.pending()[0]["state"] == "CACHED"
    resumed.close()


def test_delivery_network_failure_retains_result_and_restart_delivers_once(tmp_path):
    calls = []
    online = False

    def control(request):
        calls.append(request.url.path)
        if not online:
            raise httpx.ConnectError("模拟管理端断网", request=request)
        return httpx.Response(200, json={"data": True})

    artifact = b"test-artifact"
    job = {"job_id": str(uuid4()), "lease_id": str(uuid4())}
    outbox = Outbox(tmp_path / "outbox.db")
    worker = Worker(
        tmp_path,
        outbox,
        httpx.Client(base_url="http://control.test", transport=httpx.MockTransport(control)),
        None,
    )
    target = worker.artifact_path(job)
    target.write_bytes(artifact)
    result = {
        "artifact_sha256": hashlib.sha256(artifact).hexdigest(),
        "artifact_content_type": "image/png",
    }
    outbox.save(job, "RESULT_READY", json.dumps(result))
    assert worker.deliver(outbox.pending()[0]) is False
    assert outbox.pending()[0]["state"] == "RESULT_READY"
    outbox.close()
    online = True
    resumed = Outbox(tmp_path / "outbox.db")
    worker.outbox = resumed
    assert worker.deliver(resumed.pending()[0]) is True
    assert resumed.pending() == []
    assert len(calls) == 2
    resumed.close()


def test_stale_or_cancelled_lease_goes_to_rejected_not_success(tmp_path):
    client = httpx.Client(
        base_url="http://control.test", transport=httpx.MockTransport(lambda _: httpx.Response(409))
    )
    job = {"job_id": str(uuid4()), "lease_id": str(uuid4())}
    outbox = Outbox(tmp_path / "outbox.db")
    worker = Worker(tmp_path, outbox, client, None)
    worker.artifact_path(job).write_bytes(b"proof")
    result = {
        "artifact_sha256": hashlib.sha256(b"proof").hexdigest(),
        "artifact_content_type": "image/png",
    }
    outbox.save(job, "RESULT_READY", json.dumps(result))
    assert not worker.deliver(outbox.pending()[0])
    assert outbox.pending() == []
    assert outbox.db.execute("SELECT state FROM outbox").fetchone()[0] == "REJECTED"
    outbox.close()


def test_expired_cached_job_cannot_block_node_forever(tmp_path):
    job = {
        "job_id": str(uuid4()),
        "lease_id": str(uuid4()),
        "lease_expires_at": "2000-01-01T00:00:00Z",
    }
    outbox = Outbox(tmp_path / "outbox.db")
    outbox.save(job, "CACHED")
    worker = Worker(tmp_path, outbox, None, None)
    worker.step("infer")
    assert outbox.pending() == []
    assert outbox.db.execute("SELECT state FROM outbox").fetchone()[0] == "REJECTED"
    outbox.close()


def test_slow_inference_renews_lease_and_persists_before_completion(tmp_path):
    job = {
        "job_id": str(uuid4()),
        "lease_id": str(uuid4()),
        "lease_expires_at": (datetime.now(UTC) + timedelta(seconds=10)).isoformat(),
    }
    calls = []
    renewed = job | {"lease_expires_at": (datetime.now(UTC) + timedelta(seconds=600)).isoformat()}

    def control(request):
        calls.append(request.url.path)
        return httpx.Response(200, json={"data": renewed})

    outbox = Outbox(tmp_path / "outbox.db")
    worker = Worker(
        tmp_path,
        outbox,
        httpx.Client(base_url="http://control.test", transport=httpx.MockTransport(control)),
        None,
        heartbeat_interval=0.01,
    )

    def compute(lease):
        time.sleep(0.05)
        return {"protocol_test": True}, b"protocol-only"

    worker.compute = compute
    outbox.save(job, "CACHED")
    worker.infer(outbox.pending()[0])
    assert calls
    pending = outbox.pending()[0]
    assert pending["state"] == "RESULT_READY"
    assert pending["lease"]["lease_expires_at"] == renewed["lease_expires_at"]
    outbox.close()


def test_near_expiry_cached_job_renews_before_default_thirty_second_wait(tmp_path):
    job = {
        "job_id": str(uuid4()),
        "lease_id": str(uuid4()),
        "lease_expires_at": (datetime.now(UTC) + timedelta(seconds=0.03)).isoformat(),
    }
    renewed = job | {"lease_expires_at": (datetime.now(UTC) + timedelta(seconds=600)).isoformat()}
    events = []

    def control(request):
        events.append("heartbeat")
        return httpx.Response(200, json={"data": renewed})

    outbox = Outbox(tmp_path / "outbox.db")
    worker = Worker(
        tmp_path,
        outbox,
        httpx.Client(base_url="http://control.test", transport=httpx.MockTransport(control)),
        None,
    )

    def compute(lease):
        events.append("compute")
        time.sleep(0.06)
        return {"protocol_only": True}, b"proof"

    worker.compute = compute
    outbox.save(job, "CACHED")
    worker.infer(outbox.pending()[0])
    assert events[0] == "heartbeat"
    assert outbox.pending()[0]["lease"]["lease_expires_at"] == renewed["lease_expires_at"]
    outbox.close()


def test_expired_ready_result_still_attempts_server_idempotent_confirmation(tmp_path):
    job = {
        "job_id": str(uuid4()),
        "lease_id": str(uuid4()),
        "lease_expires_at": "2000-01-01T00:00:00Z",
    }
    outbox = Outbox(tmp_path / "outbox.db")
    client = httpx.Client(
        base_url="http://control.test",
        transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"data": True})),
    )
    worker = Worker(tmp_path, outbox, client, None)
    worker.artifact_path(job).write_bytes(b"proof")
    outbox.save(
        job,
        "RESULT_READY",
        json.dumps(
            {
                "artifact_sha256": hashlib.sha256(b"proof").hexdigest(),
                "artifact_content_type": "image/png",
            }
        ),
    )
    worker.step("deliver")
    assert outbox.db.execute("SELECT state FROM outbox").fetchone()[0] == "DELIVERED"
    outbox.close()
