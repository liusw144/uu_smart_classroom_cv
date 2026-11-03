import ssl

import pytest
from cryptography import x509
from cryptography.x509.oid import ExtendedKeyUsageOID


def test_lab_ca_issues_separate_server_and_node_certificates(tmp_path):
    from classroom_ops.pki import lab_bundle

    target = tmp_path / "pki"
    lab_bundle(target)
    client = x509.load_pem_x509_certificate((target / "node.crt").read_bytes())
    assert client.extensions.get_extension_for_class(
        x509.AuthorityKeyIdentifier
    ).value.key_identifier
    assert client.extensions.get_extension_for_class(x509.SubjectKeyIdentifier).value.digest
    assert (
        ExtendedKeyUsageOID.CLIENT_AUTH
        in client.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value
    )
    context = ssl.create_default_context(cafile=str(target / "ca.crt"))
    context.load_cert_chain(target / "node.crt", target / "node.key")
    with pytest.raises(FileExistsError):
        lab_bundle(target)


def test_lab_server_can_cover_isolated_container_dns_names(tmp_path):
    from classroom_ops.pki import lab_bundle

    target = tmp_path / "container-pki"
    lab_bundle(target, dns_names=("backend", "vision"))
    cert = x509.load_pem_x509_certificate((target / "server.crt").read_bytes())
    names = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    assert {"localhost", "backend", "vision"} <= set(names.get_values_for_type(x509.DNSName))
    with pytest.raises(ValueError):
        lab_bundle(tmp_path / "invalid", dns_names=("https://bad/path",))
