import hashlib
import json
from uuid import uuid4

import httpx

from classroom_edge.worker import Outbox, Worker


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
