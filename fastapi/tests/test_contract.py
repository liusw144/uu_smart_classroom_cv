import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import ValidationError
from openapi_spec_validator import validate

from classroom_vision.core.settings import Settings
from classroom_vision.domain.models import DetectionRequest
from classroom_vision.main import create_app

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts"


def test_request_contract_matches_wire_input():
    schema = json.loads((CONTRACTS / "detection-request.schema.json").read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    validator.check_schema(schema)
    payload = {
        "job_id": "dfffb724-fca9-4b5d-bce7-054b6cd167a5",
        "asset_id": "04191c70-5e02-41ab-b244-fda5d1c79583",
        "input_sha256": "a" * 64,
        "model_version": "model-1",
    }
    validator.validate(payload)
    assert str(DetectionRequest.model_validate(payload).asset_id) == payload["asset_id"]
    with pytest.raises(ValidationError):
        validator.validate(payload | {"file_path": "../../private"})


def test_openapi_documents_are_valid_and_vision_contract_does_not_drift():
    business = json.loads((CONTRACTS / "business.openapi.json").read_text(encoding="utf-8"))
    vision = json.loads((CONTRACTS / "vision.openapi.json").read_text(encoding="utf-8"))
    validate(business)
    validate(vision)
    actual = create_app(
        Settings(app_env="test", service_token="test-contract-token-not-for-deployment")
    ).openapi()
    assert vision == actual
