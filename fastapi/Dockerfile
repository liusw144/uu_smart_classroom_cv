FROM python:3.12-slim AS build
RUN pip install --no-cache-dir uv==0.12.18
WORKDIR /app
COPY pyproject.toml uv.lock README.md ./
COPY src ./src
RUN uv sync --frozen --no-dev --no-editable --link-mode=copy

FROM python:3.12-slim
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --uid 10001 vision
WORKDIR /app
COPY --from=build /app/.venv /app/.venv
USER vision
ENV PATH="/app/.venv/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
EXPOSE 5000
ENV VISION_HOST=0.0.0.0
CMD ["python", "-m", "classroom_vision.server"]
