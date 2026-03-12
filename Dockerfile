FROM eclipse-temurin:21-jre

# SandboxRunner uses `docker run`, so the app container needs docker CLI.
RUN apt-get update && \
    apt-get install -y --no-install-recommends docker.io && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY app.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
