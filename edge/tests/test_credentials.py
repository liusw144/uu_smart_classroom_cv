import pytest


def test_protected_runtime_requires_file_secret_and_rejects_oversize(tmp_path):
    from classroom_edge.credentials import secret

    with pytest.raises(ValueError):
        secret({"EDGE_SERVICE_TOKEN": "plain"}, "EDGE_SERVICE_TOKEN", True)
    path = tmp_path / "token"
    path.write_text("a" * 40)
    assert secret({"EDGE_SERVICE_TOKEN_FILE": str(path)}, "EDGE_SERVICE_TOKEN", True) == "a" * 40
    path.write_text("b" * 5000)
    with pytest.raises(ValueError):
        secret({"EDGE_SERVICE_TOKEN_FILE": str(path)}, "EDGE_SERVICE_TOKEN", True)
