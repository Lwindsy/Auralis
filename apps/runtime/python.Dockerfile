FROM python:3.10.8-slim
ENV PYTHONUNBUFFERED=1 OPENBLAS_NUM_THREADS=1 OMP_NUM_THREADS=1 MKL_NUM_THREADS=1
WORKDIR /app
COPY apps/intelligence/pyproject.toml /app/pyproject.toml
COPY apps/intelligence/src /app/src
RUN pip install --no-cache-dir .
ENTRYPOINT ["python", "-m", "auralisy_intelligence.agents", "serve"]
