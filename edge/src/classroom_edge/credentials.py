"""从受控文件挂载读取凭据，预发布不接收明文环境变量凭据。"""

from pathlib import Path


def secret(values, name, required_file=False):
    filename = values.get(name + "_FILE")
    if required_file and not filename:
        raise ValueError("受保护节点必须挂载凭据文件")
    if filename:
        with Path(filename).open(encoding="utf-8") as source:
            value = source.read(4097).strip()
    else:
        value = values.get(name, "")
    if not 32 <= len(value) <= 512 or not value.isascii():
        raise ValueError("服务凭据长度或格式无效")
    return value
