FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY generate.py test_generate.py pytest.ini ./

# Run as a normal user, not root
RUN useradd --create-home ml
USER ml

ENTRYPOINT ["python", "generate.py"]
