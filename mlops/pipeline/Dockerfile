# Build context: mlops/ (the pipeline image also carries the generator, for run.py --generate)
FROM python:3.12-slim

# LightGBM needs the OpenMP runtime
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pipeline/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY generator/generate.py pipeline/*.py pipeline/pytest.ini ./

# print logs at once, in order
ENV PYTHONUNBUFFERED=1

RUN useradd --create-home ml
USER ml
ENV MPLCONFIGDIR=/tmp/matplotlib
# no git in the image: MLflow would print a long warning on every run
ENV GIT_PYTHON_REFRESH=quiet

ENTRYPOINT ["python"]
