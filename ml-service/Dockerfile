FROM python:3.10-slim

WORKDIR /app

# System deps for scientific Python wheels (hdbscan builds from source on some platforms).
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential gcc && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
# Compose runs this service on the CPU. Keep the full semantic stack without
# downloading CUDA libraries that cannot be used by this container.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --timeout 120 --retries 5 --upgrade pip && \
    pip install --timeout 120 --retries 5 torch --index-url https://download.pytorch.org/whl/cpu && \
    pip install --timeout 120 --retries 5 -r requirements.txt

COPY app ./app
COPY data ./data

ENV MODEL_DIR=/app/models
RUN mkdir -p /app/models

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
