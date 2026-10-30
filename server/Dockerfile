FROM python:3.12-slim

# Set WITH_CUTOUT=true to bundle rembg as a server-side fallback for photo
# cut-outs (adds ~400 MB; the model downloads on first use into /data/models).
ARG WITH_CUTOUT=false

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HOME=/tmp \
    NOOK_DATA_DIR=/data \
    U2NET_HOME=/data/models \
    NUMBA_CACHE_DIR=/tmp/numba \
    FORWARDED_ALLOW_IPS=127.0.0.1

WORKDIR /srv
# Exact versions with hashes (regenerate with pip-compile, see README).
COPY requirements.lock requirements-cutout.lock ./
RUN if [ "$WITH_CUTOUT" = "true" ]; then lock=requirements-cutout.lock; else lock=requirements.lock; fi \
 && pip install --require-hashes --no-deps -r "$lock"

COPY app ./app

# Compiled ahead so the container can run with a read-only filesystem.
RUN python -m compileall -q app \
 && useradd --system --uid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin nook \
 && mkdir -p /data/models \
 && chown -R 10001:10001 /data
USER 10001:10001

VOLUME /data
EXPOSE 5858
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5858/api/health', timeout=3)"

# Proxy headers (X-Forwarded-For/-Proto) are only believed from the
# addresses in FORWARDED_ALLOW_IPS; the default trusts nothing outside.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5858", "--no-server-header", \
     "--limit-concurrency", "200", "--timeout-keep-alive", "15"]
