FROM dart:stable

# Set HOME to /tmp so Dart JIT cache writes to the writable tmpfs mount
ENV HOME=/tmp

RUN groupadd -r appuser && useradd -r -g appuser appuser

WORKDIR /app
COPY runner.dart .
RUN chown -R appuser:appuser /app

USER appuser

ENTRYPOINT ["dart", "run", "/app/runner.dart"]
