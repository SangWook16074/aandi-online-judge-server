FROM python:3.11-slim

RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app
COPY runner.py .
RUN chown appuser:appuser /app/runner.py

USER appuser

ENTRYPOINT ["python", "-u", "runner.py"]
