"""为GitHub临时执行器创建隔离库凭据，拒绝覆盖开发者配置。"""

import os

from local_dev import ENV_FILE, initialize


if __name__ == "__main__":
    if os.environ.get("GITHUB_ACTIONS") != "true" or ENV_FILE.exists():
        raise SystemExit("只允许在全新GitHub执行器中准备配置，不覆盖已有.env.local。")
    values = {
        "DB_ADMIN_USER": "root",
        "DB_ADMIN_PASSWORD": os.environ["CI_DB_ADMIN_PASSWORD"],
        "DB_HOST": "127.0.0.1",
        "DB_PORT": "3307",
        "REDIS_HOST": "127.0.0.1",
        "REDIS_PORT": "6389",
        "LOCAL_JAVA_HOME": os.environ["JAVA_HOME"],
        "REAL_DEMO_ENABLED": "false",
    }
    initialize(values, account_host="%")
