# Online Judge (Spring Boot + Kotlin + Coroutines)

비동기 채점 워커와 Redis Pub/Sub 기반 SSE 스트리밍을 제공하는 온라인 저지 서버입니다.

## Local Run

1. 인프라 실행 (MongoDB, Redis)
```bash
docker compose -f docker/docker-compose.yml up -d
```

2. 샌드박스 이미지 빌드
```bash
docker build -t judge-sandbox-python:latest docker/sandbox/python/
docker build -t judge-sandbox-kotlin:latest docker/sandbox/kotlin/
docker build -t judge-sandbox-dart:latest docker/sandbox/dart/
```

3. 애플리케이션 실행
```bash
cp .env.example .env
./gradlew bootRun
```

## Environment Variables

`.env.example` 기준 주요 변수:

- `SERVER_PORT`
- `MONGODB_URI`, `MONGODB_USER`, `MONGODB_PASSWORD`, `MONGODB_DATABASE`, `MONGODB_PORT`
- `REDIS_HOST`, `REDIS_PORT`
- `JUDGE_RATE_LIMIT_ENABLED`, `JUDGE_RATE_LIMIT_SUBMIT_REQUESTS`, `JUDGE_RATE_LIMIT_WINDOW_SECONDS`
- `SANDBOX_TIME_LIMIT_SECONDS`, `SANDBOX_MEMORY_LIMIT_MB`, `SANDBOX_CPU_LIMIT`, `SANDBOX_PIDS_LIMIT`
- `SANDBOX_IMAGE_PYTHON`, `SANDBOX_IMAGE_KOTLIN`, `SANDBOX_IMAGE_DART`

## Health Check

```bash
curl -s http://localhost:8080/actuator/health
```

MongoDB/Redis 헬스 정보는 환경 연결 상태에 따라 포함됩니다.

## API Docs (springdoc)

- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

## Sandbox Security (gVisor Optional)

기본 실행은 Docker 기본 runtime이며, 운영 환경에서 추가 격리가 필요하면 `runsc`(gVisor) 적용을 권장합니다.

1. gVisor 설치 후 Docker runtime 등록 (`/etc/docker/daemon.json`):
```json
{
  "runtimes": {
    "runsc": {
      "path": "/usr/local/bin/runsc"
    }
  }
}
```

2. Docker daemon 재시작
```bash
sudo systemctl restart docker
```

3. 샌드박스 컨테이너 실행 시 runtime 지정 예시
```bash
docker run --rm --runtime=runsc --network none judge-sandbox-python:latest
```

현재 애플리케이션은 `--read-only`, `--tmpfs /tmp:rw,noexec,nosuid,size=64m`, `--no-new-privileges`, `--pids-limit`를 기본 적용합니다.

언어별 `tmpfs /tmp` 필요성 실측(2026-03-09):

- Python: 불필요 (`--read-only` 단독으로 정상 실행)
- Kotlin: 필요 (`/tmp`에 소스/JAR/결과 파일 생성)
- Dart: 필요 (`Directory('/tmp').createTemp(...)` 사용)

## API Examples

### 1) 제출 생성
```bash
curl -s -X POST http://localhost:8080/v1/submissions \
  -H 'Content-Type: application/json' \
  -d '{
    "problemId": "quiz-101",
    "language": "PYTHON",
    "code": "def solution(a,b): return a+b"
  }'
```

### 2) 실시간 SSE 구독
```bash
curl -N http://localhost:8080/v1/submissions/{submissionId}/stream
```

### 3) 결과 폴링 조회
```bash
curl -s http://localhost:8080/v1/submissions/{submissionId}
```

진행 중(`PENDING`, `RUNNING`)에는 `404`가 반환되고, 완료 후 `200`으로 최종 결과를 반환합니다.
