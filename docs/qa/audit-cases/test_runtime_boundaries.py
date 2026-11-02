from pathlib import Path
import hashlib
import importlib.util
import json

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from classroom_vision.core.settings import Settings
from classroom_vision.main import create_app


ROOT = Path(__file__).resolve().parents[3]
TOKEN = 'audit-only-token-not-for-deployment-00000000'


def payload():
    return {
        'job_id': '8c7cc11e-5718-481b-9f8a-af4bff00c39b',
        'asset_id': 'cfcd3859-be5f-41a3-9267-13bec2b75bc5',
        'input_sha256': 'a' * 64,
        'model_version': 'audit-model-1',
    }


@pytest.fixture
def app():
    return create_app(Settings(app_env='test', service_token=TOKEN))


@pytest.fixture
def client(app):
    with TestClient(app, raise_server_exceptions=False) as client:
        yield client


def test_liveness_is_not_model_readiness(client):
    assert client.get('/health/live').status_code == 200
    assert client.get('/health/ready').status_code == 503
    assert client.get('/health/ready').json() == {'ready': False}


@pytest.mark.parametrize('headers', [{}, {'Authorization': 'Bearer invalid'}, {'Authorization': 'Basic invalid'}])
def test_invalid_identity_is_rejected(client, headers):
    response = client.post('/internal/v1/detections', json=payload(), headers=headers)
    assert response.status_code == 401
    assert response.json()['error']['code'] == 'UNAUTHORIZED'


def test_valid_identity_does_not_produce_fake_detection(client):
    response = client.post('/internal/v1/detections', json=payload(), headers={'Authorization': f'Bearer {TOKEN}'})
    assert response.status_code == 503
    assert response.json()['error']['code'] == 'MODEL_UNAVAILABLE'


@pytest.mark.parametrize('update', [
    {'asset_id': '../../private'},
    {'asset_id': 'https://example.invalid/file.png'},
    {'input_sha256': 'A' * 64},
    {'input_sha256': 'a' * 63},
    {'model_version': '../model'},
    {'model_version': 'x' * 81},
    {'file_path': 'D:/private/file.png'},
    {'tenant_id': 99},
])
def test_invalid_input_is_rejected_without_echoing_sensitive_values(client, update):
    response = client.post('/internal/v1/detections', json=payload() | update, headers={'Authorization': f'Bearer {TOKEN}'})
    assert response.status_code == 422
    assert response.json()['error']['code'] == 'INVALID_REQUEST'
    assert '../../private' not in response.text


@pytest.mark.parametrize('token', ['', 'short'])
def test_short_service_credentials_cannot_start(token):
    with pytest.raises(ValidationError):
        Settings(app_env='test', service_token=token)


def test_production_cannot_start_with_development_identity():
    with pytest.raises(ValidationError):
        Settings(app_env='production', service_token=TOKEN)


def test_non_ascii_identity_is_rejected_without_server_error(client):
    response = client.post('/internal/v1/detections', json=payload(), headers=[(b'authorization', b'Bearer \xc3\xa9')])
    assert response.status_code in {400, 401, 403}, response.text


@pytest.mark.parametrize('status', ['401', '422', '503'])
def test_actual_error_envelope_is_documented(app, status):
    document = app.openapi()
    responses = document['paths']['/internal/v1/detections']['post']['responses']
    assert status in responses, f'实际返回的 HTTP {status} 未在 OpenAPI 中声明'
    schema = responses[status]['content']['application/json']['schema']
    if '$ref' in schema:
        schema = document['components']['schemas'][schema['$ref'].rsplit('/', 1)[-1]]
    assert 'error' in schema.get('properties', {}), f'HTTP {status} 声明与实际 error envelope 不一致'


@pytest.fixture
def gate():
    spec = importlib.util.spec_from_file_location('gate_under_audit', ROOT / 'scripts/quality_gate.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_plain_text_files_are_not_enough_for_production_evidence(gate, tmp_path):
    (tmp_path / 'harness').mkdir()
    (tmp_path / 'harness/manifest.json').write_text(json.dumps({'production_blockers': []}), encoding='utf-8')
    evidence = {}
    for category in ['model', 'tenant_isolation', 'recovery', 'capacity', 'deployment', 'approval']:
        content = f'未执行验收，仅为 {category} 的临时测试文本'
        target = tmp_path / f'{category}.txt'
        target.write_text(content, encoding='utf-8')
        evidence[category] = {'path': target.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
    (tmp_path / 'harness/release-evidence.json').write_text(json.dumps(evidence), encoding='utf-8')
    assert gate.production_errors(tmp_path), '普通文本及匹配哈希被当作完整生产材料接受'


def test_architecture_boundary_covers_fully_qualified_spring_type(gate, tmp_path):
    path = tmp_path / 'backend/domain/src/main/java/Bad.java'
    path.parent.mkdir(parents=True)
    path.write_text('class Bad { org.springframework.context.ApplicationContext context; }', encoding='utf-8')
    assert gate.scan_sources(tmp_path), '完全限定类型引用绕过领域层框架依赖检查'
