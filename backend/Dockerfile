# syntax=docker/dockerfile:1
# Pin the base image by digest in CI/prod (Dependabot keeps it updated):
#   FROM python:3.13-slim@sha256:<digest>
FROM python:3.13-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /srv

COPY requirements.txt .
RUN pip install --require-hashes -r requirements.txt

COPY app ./app

# Run as an unprivileged user; data dir is the only writable path.
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin appuser \
 && mkdir -p /data && chown appuser /data
USER 10001
ENV DATABASE_PATH=/data/hydration.db

EXPOSE 8080
# --proxy-headers so rate limiting sees the real client IP behind the platform proxy.
# FORWARDED_ALLOW_IPS should be set to the platform's proxy range in prod.
CMD ["sh", "-c", "uvicorn app.main:create_app --factory --host 0.0.0.0 --port 8080 --proxy-headers --forwarded-allow-ips=${FORWARDED_ALLOW_IPS:-127.0.0.1} --no-server-header"]
