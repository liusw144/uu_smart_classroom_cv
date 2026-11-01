from pathlib import Path
import secrets


def main():
    target = Path(__file__).resolve().parents[1] / "deploy/.env"
    if target.exists():
        raise SystemExit("已有 deploy/.env，不覆盖现有凭据")
    values = {name: secrets.token_urlsafe(36) for name in ["MYSQL_ROOT_PASSWORD", "DB_PASSWORD", "REDIS_PASSWORD", "VISION_SERVICE_TOKEN", "BOOTSTRAP_PASSWORD"]}
    target.write_text("\n".join(f"{key}={value}" for key, value in values.items()) + "\nBOOTSTRAP_ENABLED=false\n", encoding="utf-8")
    print("已生成 deploy/.env；未输出密码。仅用于本机开发，请勿提交。")


if __name__ == "__main__":
    main()
