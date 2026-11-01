FROM python:3.12-slim AS build
RUN pip install --no-cache-dir uv==0.12.18
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable --link-mode=copy

FROM python:3.12-slim
RUN useradd --create-home --uid 10001 vision
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
USER vision
ENV PATH="/app/.venv/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 5000
CMD ["uvicorn", "classroom_vision.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "5000", "--workers", "1"]
