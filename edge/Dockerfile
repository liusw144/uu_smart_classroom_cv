FROM python:3.12-alpine AS build
RUN pip install --no-cache-dir uv==0.12.18
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable --link-mode=copy

FROM python:3.12-alpine
RUN apk upgrade --no-cache && addgroup -S -g 10001 edge && adduser -S -D -u 10001 -G edge edge
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
USER edge
ENV PATH="/app/.venv/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
CMD ["python", "-m", "classroom_edge.worker"]
