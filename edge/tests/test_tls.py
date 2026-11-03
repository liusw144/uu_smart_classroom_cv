import pytest


def test_production_transport_refuses_plain_http_and_partial_identity():
    from classroom_edge.tls import transport

    with pytest.raises(ValueError, match="HTTPS"):
        transport("http://control", {}, "EDGE", True)
    with pytest.raises(ValueError, match="证书"):
        transport("https://control", {}, "EDGE", True)


def test_development_transport_remains_verified_and_cannot_disable_tls():
    from classroom_edge.tls import transport

    assert transport("http://127.0.0.1:8089", {}, "EDGE", False) is True
    with pytest.raises(ValueError):
        transport("https://user:password@host/path", {}, "EDGE", False)
