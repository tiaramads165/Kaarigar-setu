# syntax=docker/dockerfile:1
# ML microservice. Whisper (and its ~2 GB of PyTorch) is opt-in so a demo image stays small:
#   docker compose build --build-arg WITH_WHISPER=1 ml     (then set STT_PROVIDER=whisper)
FROM python:3.11-slim

ARG WITH_WHISPER=0
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /srv

COPY requirements.txt requirements-whisper.txt ./
RUN pip install -r requirements.txt \
 && if [ "$WITH_WHISPER" = "1" ]; then \
      apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/* \
      && pip install -r requirements-whisper.txt; \
    fi

COPY app ./app
RUN groupadd --system --gid 10001 ks && useradd --system --uid 10001 --gid ks --no-create-home --shell /usr/sbin/nologin ks
USER ks
EXPOSE 8001
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8001/health', timeout=2).status == 200 else 1)"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
