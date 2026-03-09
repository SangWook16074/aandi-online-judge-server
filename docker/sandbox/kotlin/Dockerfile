FROM eclipse-temurin:21-jdk

ARG KOTLIN_VERSION=1.9.24

# Install kotlinc + wget for dependency download
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip wget && \
    curl -fsSL "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
        -o /tmp/kotlin.zip && \
    unzip -q /tmp/kotlin.zip -d /opt && \
    rm /tmp/kotlin.zip && \
    apt-get remove -y curl unzip && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/kotlinc/bin:${PATH}"

WORKDIR /app

# Download org.json for stdin JSON parsing
RUN mkdir -p /app/lib && \
    wget -q "https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar" \
        -O /app/lib/org.json.jar && \
    apt-get remove -y wget && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

COPY KotlinRunner.kt .

# Pre-compile runner into a self-contained JAR (avoids per-run compile overhead)
RUN kotlinc -classpath /app/lib/org.json.jar KotlinRunner.kt -include-runtime -d /app/runner.jar

RUN groupadd -r appuser && useradd -r -g appuser appuser && \
    chown -R appuser:appuser /app

USER appuser

# org.json must be on classpath at runtime (not bundled by -include-runtime)
ENTRYPOINT ["java", "-cp", "/app/runner.jar:/app/lib/org.json.jar", "KotlinRunnerKt"]
