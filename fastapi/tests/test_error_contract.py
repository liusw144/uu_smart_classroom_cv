import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from classroom_vision.core.settings import Settings
from classroom_vision.main import create_app

TOKEN = "regression-fixture-only-token-0000000000000"
BODY = {
    "job_id": "8c7cc11e-5718-481b-9f8a-af4bff00c39b",
    "asset_id": "cfcd3859-be5f-41a3-9267-13bec2b75bc5",
    "input_sha256": "a" * 64,
    "model_version": "test-v1",
}


def test_non_ascii_credentials_are_rejected():
    app = create_app(Settings(app_env="test", service_token=TOKEN))
    with TestClient(app, raise_server_exceptions=False) as client:
        response = client.post(
            "/internal/v1/detections",
            json=BODY,
            headers=[(b"authorization", b"Bearer \xc3\xa9")],
        )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


@pytest.mark.parametrize("status", [401, 422, 503])
def test_real_error_responses_match_committed_contract(status):
    app = create_app(Settings(app_env="test", service_token=TOKEN))
    with TestClient(app) as client:
        response = client.post(
            "/internal/v1/detections",
            json={} if status == 422 else BODY,
            headers={} if status == 401 else {"Authorization": f"Bearer {TOKEN}"},
        )
    assert response.status_code == status
    document = json.loads(
        (Path(__file__).resolve().parents[2] / "contracts/vision.openapi.json").read_text(
            encoding="utf-8"
        )
    )
    schemas = document["components"]["schemas"]
    root = document["paths"]["/internal/v1/detections"]["post"]["responses"][str(status)][
        "content"
    ]["application/json"]["schema"]

    def expand(value):
        if isinstance(value, dict):
            if "$ref" in value:
                return expand(schemas[value["$ref"].rsplit("/", 1)[-1]])
            return {key: expand(item) for key, item in value.items()}
        if isinstance(value, list):
            return [expand(item) for item in value]
        return value

    Draft202012Validator(expand(root)).validate(response.json())
