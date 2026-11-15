# Build context: mlops/ (so the features shared with training can be copied)
FROM python:3.12-slim

# LightGBM needs the OpenMP runtime
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY serving/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY pipeline/features.py serving/*.py serving/pytest.ini ./

RUN useradd --create-home ml
USER ml

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000", "--root-path", "/ml"]
