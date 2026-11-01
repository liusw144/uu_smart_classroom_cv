import json
import secrets
from pathlib import Path

from classroom_vision.core.settings import Settings
from classroom_vision.main import create_app


def main():
    app = create_app(Settings(app_env="test", service_token=secrets.token_urlsafe(36)))
    target = Path(__file__).resolve().parents[2] / "contracts/vision.openapi.json"
    target.write_text(
        json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print("内部视觉 OpenAPI 已导出，提交前审查契约变化。")


if __name__ == "__main__":
    main()
