"""独立边缘进程的TLS身份；不允许跳过服务端证书核验。"""

import ssl
from urllib.parse import urlsplit


def transport(url, values, prefix, required):
    address = urlsplit(url)
    if (
        address.scheme not in {"https", "http"}
        or not address.hostname
        or address.username
        or address.password
        or address.query
        or address.fragment
        or address.path not in {"", "/"}
    ):
        raise ValueError("服务地址必须为无凭据的HTTP或HTTPS源地址")
    if required and address.scheme != "https":
        raise ValueError("受保护节点必须使用HTTPS")
    ca, certificate, key = (
        values.get(prefix + suffix) for suffix in ("_TLS_CA", "_TLS_CERT", "_TLS_KEY")
    )
    if required and not all((ca, certificate, key)):
        raise ValueError("缺少节点证书、私钥或CA证书")
    if bool(certificate) != bool(key):
        raise ValueError("客户端证书和私钥必须同时提供")
    if address.scheme == "http":
        if any((ca, certificate, key)):
            raise ValueError("HTTP不能承载配置的TLS证书身份")
        return True
    context = ssl.create_default_context(cafile=ca)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    if certificate:
        context.load_cert_chain(certificate, key)
    return context
