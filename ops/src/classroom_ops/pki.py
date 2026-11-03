"""离线证书核验与实验PKI；实验CA不能用于正式环境。"""

import argparse
import ipaddress
import json
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


def lab_bundle(target: Path, *, dns_names=()):
    if len(dns_names) > 16 or any(
        len(name) > 253
        or any(
            not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", part)
            for part in name.split(".")
        )
        for name in dns_names
    ):
        raise ValueError("实验服务端DNS名称无效")
    target.mkdir(mode=0o700, parents=True, exist_ok=False)
    now = datetime.now(UTC)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "UU LAB ONLY CA")])
    ca = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(minutes=5))
        .not_valid_after(now + timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(
            x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False
        )
        .add_extension(
            x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False
        )
        .add_extension(
            x509.KeyUsage(True, False, False, False, False, True, True, False, False), critical=True
        )
        .sign(ca_key, hashes.SHA256())
    )
    (target / "ca.crt").write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    # 实验根私钥不落盘，不能把此工具当作长期生产CA。
    for role in ("server", "node", "other", "expired"):
        key = ec.generate_private_key(ec.SECP256R1())
        client = role != "server"
        cert = (
            x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, role)]))
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(days=2))
            .not_valid_after(
                now + timedelta(days=7) if role != "expired" else now - timedelta(days=1)
            )
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(
                x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False
            )
            .add_extension(
                x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()),
                critical=False,
            )
            .add_extension(
                x509.ExtendedKeyUsage(
                    [ExtendedKeyUsageOID.CLIENT_AUTH if client else ExtendedKeyUsageOID.SERVER_AUTH]
                ),
                critical=False,
            )
            .add_extension(
                x509.SubjectAlternativeName(
                    [x509.UniformResourceIdentifier("spiffe://uu-smart-classroom/edge/" + role)]
                    if client
                    else [
                        x509.DNSName("localhost"),
                        x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                        *(x509.DNSName(name) for name in sorted(set(dns_names))),
                    ]
                ),
                critical=False,
            )
            .sign(ca_key, hashes.SHA256())
        )
        path = target / (role + ".key")
        path.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        path.chmod(0o600)
        (target / (role + ".crt")).write_bytes(cert.public_bytes(serialization.Encoding.PEM))


def inspect(path):
    cert = x509.load_pem_x509_certificate(Path(path).read_bytes())
    if cert.extensions.get_extension_for_class(x509.BasicConstraints).value.ca:
        raise ValueError("节点绑定不接受CA证书")
    if (
        ExtendedKeyUsageOID.CLIENT_AUTH
        not in cert.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value
    ):
        raise ValueError("证书不具备客户端认证用途")
    if not cert.not_valid_before_utc <= datetime.now(UTC) < cert.not_valid_after_utc:
        raise ValueError("证书尚未生效或已过期")
    return {
        "fingerprint": cert.fingerprint(hashes.SHA256()).hex(),
        "not_before": cert.not_valid_before_utc.isoformat(),
        "not_after": cert.not_valid_after_utc.isoformat(),
    }


def main():
    parser = argparse.ArgumentParser(description="实验TLS证书与受控节点绑定SQL")
    sub = parser.add_subparsers(dest="action", required=True)
    lab = sub.add_parser("lab")
    lab.add_argument("--out", type=Path, required=True)
    lab.add_argument("--dns", action="append", default=[])
    check = sub.add_parser("inspect")
    check.add_argument("--cert", type=Path, required=True)
    enroll = sub.add_parser("enrollment-sql")
    enroll.add_argument("--cert", type=Path, required=True)
    enroll.add_argument("--node", required=True)
    revoke = sub.add_parser("revoke-sql")
    revoke.add_argument("--fingerprint", required=True)
    args = parser.parse_args()
    if args.action == "lab":
        lab_bundle(args.out, dns_names=args.dns)
        print("实验PKI已创建；根私钥未保存；禁止用于正式环境")
    elif args.action == "inspect":
        print(json.dumps(inspect(args.cert), ensure_ascii=False, indent=2))
    elif args.action == "enrollment-sql":
        if not re.fullmatch(r"[A-Za-z0-9_-]{3,64}", args.node):
            parser.error("节点编号无效")
        proof = inspect(args.cert)
        dates = [
            datetime.fromisoformat(proof[k]).strftime("%Y-%m-%d %H:%M:%S.%f")
            for k in ("not_before", "not_after")
        ]
        print("-- 由受授权运维在UTC会话中执行；先登记新证，切换客户端验证成功后吊销旧证。")
        print(
            "INSERT INTO edge_node_certificate(fingerprint,node_id,not_before,not_after) "
            f"VALUES ('{proof['fingerprint']}','{args.node}','{dates[0]}','{dates[1]}');"
        )
    else:
        if not re.fullmatch(r"[a-f0-9]{64}", args.fingerprint):
            parser.error("证书摘要无效")
        print(
            "UPDATE edge_node_certificate SET revoked_at=CURRENT_TIMESTAMP(6) "
            f"WHERE fingerprint='{args.fingerprint}' AND revoked_at IS NULL;"
        )


if __name__ == "__main__":
    main()
