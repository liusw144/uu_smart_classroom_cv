import base64
import json
from datetime import UTC, datetime, timedelta

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def test_release_signature_binds_revision_expiry_and_all_required_reports():
    from classroom_ops.release import verify_envelope

    key = Ed25519PrivateKey.generate()
    payload = {
        "schema_version": 1,
        "revision": "a" * 40,
        "issued_at": datetime.now(UTC).isoformat(),
        "expires_at": (datetime.now(UTC) + timedelta(days=1)).isoformat(),
        "target": {"schools": 10, "cameras": 1000, "concurrent_analysis": 300},
        "reports": {
            name: {"passed": True, "sha256": "b" * 64, "path": name + ".json"}
            for name in (
                "model",
                "tenant_isolation",
                "recovery",
                "capacity",
                "deployment",
                "residency",
                "approval",
            )
        },
        "images": {
            name: "example/" + name + "@sha256:" + "c" * 64
            for name in ("backend", "teacher", "admin", "vision", "edge")
        },
    }
    raw = json.dumps(payload, sort_keys=True).encode()
    envelope = {
        "payload": base64.b64encode(raw).decode(),
        "signature": base64.b64encode(key.sign(raw)).decode(),
    }
    assert verify_envelope(envelope, key.public_key(), "a" * 40)["revision"] == "a" * 40
    with pytest.raises(ValueError):
        verify_envelope(envelope, key.public_key(), "d" * 40)
    wrong = Ed25519PrivateKey.generate().public_key()
    with pytest.raises(ValueError):
        verify_envelope(envelope, wrong, "a" * 40)
    payload["reports"]["capacity"]["passed"] = False
    raw = json.dumps(payload).encode()
    envelope["payload"] = base64.b64encode(raw).decode()
    envelope["signature"] = base64.b64encode(key.sign(raw)).decode()
    with pytest.raises(ValueError):
        verify_envelope(envelope, key.public_key(), "a" * 40)
