FROM python:3.12.16-slim-bookworm AS build
WORKDIR /app
RUN pip install --no-cache-dir uv==0.13.9
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project --python /usr/local/bin/python

FROM python:3.12.16-slim-bookworm
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /app/.venv .venv
COPY --chown=app:app agent_runtime agent_runtime
ENV PATH="/app/.venv/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 APP_ENV=production
USER app
EXPOSE 8000
CMD ["uvicorn", "agent_runtime.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-server-header", "--no-proxy-headers", "--limit-concurrency", "8", "--backlog", "16"]
