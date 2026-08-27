FROM python:3.12-slim

# ffmpeg is required for merged 1080p and for HLS/DASH sources. Everything
# else is already in the slim image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8000

WORKDIR /app

# Copy requirements first so dependency layers cache across code changes.
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

# Nothing is ever written to disk at runtime, so run unprivileged.
RUN useradd --create-home --uid 10001 appuser
USER appuser

EXPOSE 8000

HEALTHCHECK --interval=60s --timeout=5s --start-period=15s --retries=3 \
    CMD python -c "import urllib.request,os; \
        urllib.request.urlopen(f'http://127.0.0.1:{os.environ[\"PORT\"]}/api/health').read()"

# One worker: the app caps its own concurrency and the free tier has ~512 MB.
# --timeout-keep-alive holds the connection through long streamed downloads.
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers 1 --timeout-keep-alive 75 --no-access-log"]
