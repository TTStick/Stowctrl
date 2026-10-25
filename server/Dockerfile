FROM python:3.12-slim

# Set WITH_CUTOUT=true to bundle rembg as a server-side fallback for photo
# cut-outs (adds ~400 MB; the model downloads on first use into /data/models).
ARG WITH_CUTOUT=false

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    NOOK_DATA_DIR=/data \
    U2NET_HOME=/data/models

WORKDIR /srv
COPY requirements.txt requirements-cutout.txt ./
RUN pip install -r requirements.txt \
 && if [ "$WITH_CUTOUT" = "true" ]; then pip install -r requirements-cutout.txt; fi

COPY app ./app

RUN useradd --system --uid 10001 --home /srv nook \
 && mkdir -p /data/models \
 && chown -R nook:nook /data
USER nook

VOLUME /data
EXPOSE 5858
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5858/api/health', timeout=3)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "5858", "--proxy-headers", "--forwarded-allow-ips", "*"]
