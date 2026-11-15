FROM python:3.12-slim

# LightGBM needs the OpenMP runtime
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py pytest.ini ./

RUN useradd --create-home ml
USER ml
ENV MPLCONFIGDIR=/tmp/matplotlib

ENTRYPOINT ["python"]
