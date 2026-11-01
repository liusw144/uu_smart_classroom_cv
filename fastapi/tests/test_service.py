from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from classroom_vision.core.settings import Settings
from classroom_vision.main import create_app

TOKEN = "test-only-credential-not-for-deployment-1234"


@pytest.fixture
def client():
    with TestClient(create_app(Settings(app_env="test", service_token=TOKEN))) as instance:
        yield instance


def valid_request():
    return {
        "job_id": str(uuid4()),
        "asset_id": str(uuid4()),
        "input_sha256": "a" * 64,
        "model_version": "pending",
    }


def test_missing_model_is_not_ready(client):
    assert client.get("/health/live").status_code == 200
    assert client.get("/health/ready").status_code == 503


def test_anonymous_inference_is_denied(client):
    assert client.post("/internal/v1/detections", json=valid_request()).status_code == 401


def test_authorized_inference_cannot_fabricate_results(client):
    response = client.post(
        "/internal/v1/detections",
        json=valid_request(),
        headers={"Authorization": f"Bearer {TOKEN}"},
    )
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "MODEL_UNAVAILABLE"


def test_arbitrary_path_is_rejected(client):
    payload = valid_request() | {"file_path": "../../secret"}
    response = client.post(
        "/internal/v1/detections", json=payload, headers={"Authorization": f"Bearer {TOKEN}"}
    )
    assert response.status_code == 422


def test_production_refuses_development_identity():
    with pytest.raises(ValidationError):
        Settings(app_env="production", service_token=TOKEN)
