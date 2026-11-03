"""使用全新临时容器数据库验证候选TLS编排，不接触.env.local或业务数据库。"""

import hashlib
import json
import os
import secrets
import shutil
import ssl
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LINUX_PERMISSIONS = """
import os,sys
from pathlib import Path
root=Path('/test').resolve(); group=int(sys.argv[1])
for name in ('control','edge','pki'):
    path=root/name
    if path.is_symlink() or path.resolve().parent!=root or not path.is_dir():
        raise RuntimeError('临时目录边界无效')
    os.chown(path,10001,group);path.chmod(0o2770)
for path in (root/'pki').iterdir():
    if path.is_symlink() or not path.is_file():raise RuntimeError('实验PKI文件边界无效')
    os.chown(path,10001,group);path.chmod(0o640 if path.suffix=='.key' else 0o644)
"""


def run(command, **kwargs):
    kwargs.setdefault("timeout", 60)
    return subprocess.run(command, check=True, capture_output=True, **kwargs).stdout


def request(url, context, *, method="GET", headers=None):
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context)
    )
    try:
        with opener.open(
            urllib.request.Request(url, method=method, headers=headers or {}), timeout=3
        ) as response:
            return response.status, response.read(8192), dict(response.headers)
    except urllib.error.HTTPError as response:
        return response.code, response.read(8192), dict(response.headers)


def main():
    if not __debug__:
        raise RuntimeError("验收禁止优化模式，不能跳过断言生成通过证据")
    project = "uu-staging-check-" + secrets.token_hex(6)
    target = ROOT / ".harness" / project
    target.mkdir(mode=0o700, parents=True)
    pki = target / "pki"
    protected = target / "secrets"
    protected.mkdir(mode=0o700)
    tokens = {
        name: secrets.token_urlsafe(36)
        for name in (
            "DB_PASSWORD",
            "REDIS_PASSWORD",
            "EDGE_SERVICE_TOKEN",
            "VISION_SERVICE_TOKEN",
            "MYSQL_ROOT_PASSWORD",
        )
    }
    for name, value in tokens.items():
        (protected / name).write_text(value, encoding="utf-8")
        (protected / name).chmod(0o644)
    ops_python = ROOT / "ops/.venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    config = target / "compose.json"
    command = ["docker", "compose", "-p", project, "-f", str(config)]
    proof = {"passed": False, "project": project, "uses_business_database": False}
    blocker = project + "-reserved-ip"
    blocker_created = False
    try:
        run(
            [
                str(ops_python),
                "-m",
                "classroom_ops.pki",
                "lab",
                "--out",
                str(pki),
                "--dns",
                "backend",
                "--dns",
                "vision",
            ],
            cwd=ROOT / "ops",
        )
        for source, destination in (
            ("ca.crt", "control-ca.crt"),
            ("ca.crt", "vision-ca.crt"),
            ("node.crt", "vision-client.crt"),
            ("node.key", "vision-client.key"),
        ):
            shutil.copyfile(pki / source, pki / destination)
        environment = os.environ.copy()
        for name in ("backend", "teacher", "admin", "vision", "edge"):
            environment[name.upper() + "_IMAGE"] = "uu-classroom-" + name + ":production-candidate"
        proof["image_ids"] = {
            name: run(
                [
                    "docker",
                    "image",
                    "inspect",
                    "--format",
                    "{{.Id}}",
                    environment[name.upper() + "_IMAGE"],
                ]
            )
            .decode()
            .strip()
            for name in ("backend", "teacher", "admin", "vision", "edge")
        }
        for name, path in {
            "CONTROL_STATE": target / "control",
            "EDGE_STATE": target / "edge",
            "CONTROL_CERTS": pki,
            "VISION_CERTS": pki,
            "EDGE_CERTS": pki,
            "UI_CERTS": pki,
            "MODELS": target / "models",
            "SECRETS_DIR": protected,
        }.items():
            path.mkdir(exist_ok=True)
            environment[name] = str(path)
        value = json.loads(
            run(
                [
                    "docker",
                    "compose",
                    "-f",
                    str(ROOT / "deploy/staging/compose.yaml"),
                    "config",
                    "--no-env-resolution",
                    "--format",
                    "json",
                ],
                env=environment,
            )
        )
        services = value["services"]
        value["name"] = project
        value["networks"]["default"]["name"] = project + "-network"
        for service in services.values():
            service.pop("env_file", None)
            assert service["read_only"] and int(service["pids_limit"]) > 0 and service["mem_limit"]
            for port in service.get("ports", []):
                port["published"] = "0"
        services["backend"]["environment"].update(
            DB_URL="jdbc:mysql://mysql:3306/classroom?serverTimezone=UTC&characterEncoding=UTF-8",
            DB_USER="classroom",
            REDIS_HOST="redis",
            REDIS_PORT="6379",
            REDIS_DATABASE="0",
        )
        services["backend"]["depends_on"] = {
            "mysql": {"condition": "service_healthy"},
            "redis": {"condition": "service_started"},
        }
        services["edge"]["environment"].update(
            EDGE_NODE_ID="staging-node",
            EDGE_CONTROL_URL="https://backend:8443",
            VISION_URL="https://vision:5000",
        )
        services["vision"]["volumes"] = [
            v for v in services["vision"]["volumes"] if v["target"] != "/models"
        ] + [
            {
                "type": "bind",
                "source": str(ROOT / "models/demo-assets.lock.json"),
                "target": "/models/demo-assets.lock.json",
                "read_only": True,
            },
            {
                "type": "bind",
                "source": str(ROOT / "runtime/models"),
                "target": "/models/weights",
                "read_only": True,
            },
        ]
        for name in tokens:
            value["secrets"][name] = {"file": str(protected / name)}
        client = protected / "mysql-client.cnf"
        client.write_text(
            "[client]\nuser=root\npassword=" + tokens["MYSQL_ROOT_PASSWORD"] + "\n",
            encoding="utf-8",
        )
        redis = protected / "redis.conf"
        redis.write_text(
            'bind 0.0.0.0\nprotected-mode yes\nsave ""\nappendonly no\nrequirepass '
            + tokens["REDIS_PASSWORD"]
            + "\n",
            encoding="utf-8",
        )
        services["mysql"] = {
            "image": "mysql:8.4",
            "environment": {
                "MYSQL_ROOT_PASSWORD_FILE": "/run/secrets/MYSQL_ROOT_PASSWORD",
                "MYSQL_DATABASE": "classroom",
                "MYSQL_USER": "classroom",
                "MYSQL_PASSWORD_FILE": "/run/secrets/DB_PASSWORD",
            },
            "secrets": ["MYSQL_ROOT_PASSWORD", "DB_PASSWORD"],
            "tmpfs": ["/var/lib/mysql:size=512m"],
            "mem_limit": "1g",
            "volumes": [
                {
                    "type": "bind",
                    "source": str(client),
                    "target": "/run/mysql-client.cnf",
                    "read_only": True,
                }
            ],
            "healthcheck": {
                "test": [
                    "CMD",
                    "mysqladmin",
                    "--defaults-extra-file=/run/mysql-client.cnf",
                    "ping",
                    "--silent",
                ],
                "interval": "3s",
                "timeout": "3s",
                "retries": 40,
            },
        }
        services["redis"] = {
            "image": "redis:7.4-alpine",
            "command": ["redis-server", "/run/redis.conf"],
            "mem_limit": "128m",
            "tmpfs": ["/data:size=16m"],
            "volumes": [
                {
                    "type": "bind",
                    "source": str(redis),
                    "target": "/run/redis.conf",
                    "read_only": True,
                }
            ],
        }
        config.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
        if os.name != "nt":
            # 只准备本次新建的实验目录；宿主调用者和UID10001均需读取证书并回收文件。
            run(
                [
                    "docker",
                    "run",
                    "--rm",
                    "--network",
                    "none",
                    "--read-only",
                    "--user",
                    "0",
                    "--cap-drop",
                    "ALL",
                    "--cap-add",
                    "CHOWN",
                    "--cap-add",
                    "FOWNER",
                    "--cap-add",
                    "DAC_OVERRIDE",
                    "--security-opt",
                    "no-new-privileges:true",
                    "--memory",
                    "64m",
                    "--pids-limit",
                    "32",
                    "--mount",
                    f"type=bind,source={target},target=/test",
                    "uu-classroom-edge:production-candidate",
                    "python",
                    "-c",
                    LINUX_PERMISSIONS,
                    str(os.getgid()),
                ],
                timeout=30,
            )
        (target / "up.log").write_bytes(run(command + ["up", "-d"], timeout=600))

        def address(service):
            return (
                "https://" + run(command + ["port", service, "8443"], timeout=10).decode().strip()
            )

        context = ssl.create_default_context(cafile=str(pki / "ca.crt"))
        control = address("backend")
        deadline = time.monotonic() + 120
        last_status = "未建立连接"
        while time.monotonic() < deadline:
            try:
                status, body, _ = request(control + "/actuator/health/readiness", context)
                last_status = str(status) + " " + body.decode(errors="replace")[:300]
                if status == 200:
                    break
            except (OSError, urllib.error.URLError) as error:
                last_status = str(error)[:300]
            time.sleep(1)
        else:
            raise RuntimeError("TLS控制面未能就绪：" + last_status)
        enrollment = run(
            [
                str(ops_python),
                "-m",
                "classroom_ops.pki",
                "enrollment-sql",
                "--node",
                "staging-node",
                "--cert",
                str(pki / "node.crt"),
            ],
            cwd=ROOT / "ops",
        ).decode()
        token_hash = hashlib.sha256(tokens["EDGE_SERVICE_TOKEN"].encode()).hexdigest()
        sql = (
            "SET time_zone='+00:00'; USE classroom; "
            "SELECT tenant_id,id AS asset_id FROM media_asset WHERE storage_state='PENDING' "
            "UNION ALL SELECT tenant_id,asset_id FROM media_storage_cleanup; "
            "INSERT INTO tenant(id,code,name) VALUES (1,'staging-school','隔离验收学校'); "
            "INSERT INTO edge_node(id,tenant_id,name,token_hash) "
            "VALUES ('staging-node',1,'隔离验收节点','"
            + token_hash
            + "');\n"
            + enrollment
            + "\nSELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history;\n"
        )
        seed_output = run(
            command
            + [
                "exec",
                "-T",
                "mysql",
                "sh",
                "-c",
                "cp /run/mysql-client.cnf /tmp/uu-client.cnf && chmod 600 /tmp/uu-client.cnf "
                "&& exec mysql --defaults-extra-file=/tmp/uu-client.cnf "
                "--default-character-set=utf8mb4",
            ],
            input=sql.encode(),
            timeout=20,
        )
        expected = max(
            int(p.name.split("_")[0][1:])
            for p in (ROOT / "backend/api/src/main/resources/db/migration").glob("V*__*.sql")
        )
        assert seed_output.decode().strip().splitlines()[-1] == str(expected)
        proof["schema_version"] = expected
        headers = {
            "Authorization": "Bearer " + tokens["EDGE_SERVICE_TOKEN"],
            "X-Node-ID": "staging-node",
            "X-SSL-Client-Verify": "SUCCESS",
        }
        assert (
            request(control + "/internal/v1/edge/claim", context, method="POST", headers=headers)[0]
            == 401
        )
        context.load_cert_chain(pki / "node.crt", pki / "node.key")
        assert (
            request(control + "/internal/v1/edge/claim", context, method="POST", headers=headers)[0]
            == 200
        )
        vision_probe = (
            "import ssl,urllib.request; "
            "c=ssl.create_default_context(cafile='/certs/ca.crt'); "
            "c.load_cert_chain('/certs/node.crt','/certs/node.key'); "
            "r=urllib.request.urlopen('https://vision:5000/health/ready',context=c,timeout=10); "
            "assert r.status==200"
        )
        run(command + ["exec", "-T", "vision", "python", "-c", vision_probe], timeout=20)
        for name in ("teacher", "admin"):
            url = address(name)
            status, body, response_headers = request(url, context)
            assert (
                status == 200 and b"<html" in body and "Content-Security-Policy" in response_headers
            )
            assert request(url + "/api/v1/auth/me", context)[0] == 401
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            health = target / "edge/health.json"
            if health.exists() and time.time() - json.loads(health.read_text())["updated_at"] < 20:
                break
            time.sleep(1)
        else:
            raise RuntimeError("edge未通过原生双向TLS完成轮询")
        # 占用旧地址后重建控制面，实际验证前端能跟随Docker DNS恢复，且未重启前端。
        ui_ids = {
            name: run(command + ["ps", "-q", name]).decode().strip()
            for name in ("teacher", "admin")
        }
        backend_id = run(command + ["ps", "-q", "backend"]).decode().strip()
        network = project + "-network"
        old_ip = json.loads(run(["docker", "inspect", backend_id]))[0]["NetworkSettings"][
            "Networks"
        ][network]["IPAddress"]
        run(command + ["stop", "--timeout", "10", "backend"])
        run(command + ["rm", "-f", "backend"])
        run(
            [
                "docker",
                "run",
                "-d",
                "--rm",
                "--name",
                blocker,
                "--network",
                network,
                "--ip",
                old_ip,
                "--read-only",
                "--user",
                "10001:10001",
                "--cap-drop",
                "ALL",
                "--memory",
                "64m",
                "--pids-limit",
                "16",
                "uu-classroom-edge:production-candidate",
                "python",
                "-c",
                "import time; time.sleep(180)",
            ]
        )
        blocker_created = True
        run(command + ["up", "-d", "--no-deps", "backend"], timeout=60)
        new_id = run(command + ["ps", "-q", "backend"]).decode().strip()
        new_ip = json.loads(run(["docker", "inspect", new_id]))[0]["NetworkSettings"]["Networks"][
            network
        ]["IPAddress"]
        if new_ip == old_ip:
            raise RuntimeError("未改变控制面IP，不能记录DNS恢复通过")
        for name in ("teacher", "admin"):
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                try:
                    if request(address(name) + "/api/v1/auth/me", context)[0] == 401:
                        break
                except OSError:
                    pass
                time.sleep(1)
            else:
                raise RuntimeError("后端换IP后前端代理未自动恢复")
            if run(command + ["ps", "-q", name]).decode().strip() != ui_ids[name]:
                raise RuntimeError("前端进程被重启，不能据此证明动态DNS恢复")
        proof["backend_ip_change_recovery_without_ui_restart"] = True
        proof.update(
            passed=True,
            tls_ui_proxy=True,
            native_node_mtls=True,
            edge_roundtrip=True,
            bootstrap_disabled=True,
            real_model_loaded=True,
            scope="临时数据库、五服务TLS启动与空队列轮询；业务链路和容量需分别验收",
        )
    except subprocess.CalledProcessError as error:
        (target / "failure.log").write_bytes((error.stdout or b"") + (error.stderr or b""))
        raise
    finally:
        if blocker_created:
            subprocess.run(
                ["docker", "rm", "-f", blocker], capture_output=True, timeout=30, check=False
            )
        if config.is_file():
            try:
                (target / "containers.log").write_bytes(
                    run(command + ["logs", "--no-color"], timeout=30)
                )
            except (subprocess.SubprocessError, OSError):
                proof["logs_available"] = False
            try:
                result = subprocess.run(
                    command + ["down", "--volumes", "--timeout", "10"],
                    capture_output=True,
                    timeout=60,
                    check=False,
                )
                (target / "down.log").write_bytes(result.stdout + result.stderr)
                proof["cleanup_passed"] = result.returncode == 0
            except (subprocess.SubprocessError, OSError):
                proof["cleanup_passed"] = False
            if not proof["cleanup_passed"]:
                proof["passed"] = False
        for path in list(protected.iterdir()) + list(pki.glob("*.key")):
            path.unlink(missing_ok=True)
        (ROOT / ".harness/production-staging.json").write_text(
            json.dumps(proof, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        print(json.dumps(proof, ensure_ascii=False), flush=True)
    if not proof.get("cleanup_passed"):
        raise RuntimeError("隔离验证容器未完全回收，请按报告中的项目编号检查")


if __name__ == "__main__":
    main()
