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
- `JUDGE_JWT_AUTH_ENABLED`, `JUDGE_JWT_AUTH_SIGNING_KEY`, `JUDGE_JWT_AUTH_REQUIRED_ROLE`, `JUDGE_JWT_AUTH_ALLOW_WITHOUT_ROLE_CLAIM`
- `JUDGE_RATE_LIMIT_ENABLED`, `JUDGE_RATE_LIMIT_SUBMIT_REQUESTS`, `JUDGE_RATE_LIMIT_WINDOW_SECONDS`
- `JUDGE_WORKER_MAX_CONCURRENCY`
- `JUDGE_PROBLEM_EVENTS_ENABLED`, `JUDGE_PROBLEM_EVENTS_QUEUE_URL`
- `JUDGE_PROBLEM_EVENTS_WAIT_TIME_SECONDS`, `JUDGE_PROBLEM_EVENTS_MAX_MESSAGES`
- `SANDBOX_TIME_LIMIT_SECONDS`, `SANDBOX_MEMORY_LIMIT_MB`, `SANDBOX_CPU_LIMIT`, `SANDBOX_PIDS_LIMIT`
- `SANDBOX_IMAGE_PYTHON`, `SANDBOX_IMAGE_KOTLIN`, `SANDBOX_IMAGE_DART`

## JWT Auth

- `/v1/**` 엔드포인트는 JWT Bearer 토큰이 필요합니다.
- JWT 서명 검증 키(`JUDGE_JWT_AUTH_SIGNING_KEY`)가 필수입니다.
- 지원 알고리즘: `HS256`, `HS384`, `HS512`
- 기본 최소 권한은 `USER` (`JUDGE_JWT_AUTH_REQUIRED_ROLE=USER`)입니다.
- 역할 클레임이 없는 토큰도 허용하려면 `JUDGE_JWT_AUTH_ALLOW_WITHOUT_ROLE_CLAIM=true`를 유지합니다.
- 인증 제외 경로: `OPTIONS` 요청, 그 외 `/v1/**` 외 경로

## Problem Event Payload (SQS/SNS)

문제 생성/수정 이벤트를 SQS로 전달하면, 서버가 `problemId(UUID)` 기준으로 테스트 케이스를 MongoDB `problems` 컬렉션에 upsert 합니다.

필수 필드:
- `problemId` (문제 UUID 문자열)
- `testCases` (배열)
- `testCases[].input` (배열 권장)
- `testCases[].output` (기대 출력값, 문자열 권장)

선택 필드:
- `testCases[].caseId` (없으면 1부터 자동 부여)

### 1) Plain JSON 메시지 예시 (SQS Body)
```json
{
  "eventType": "PROBLEM_CREATED",
  "problemId": "7fbe8f62-9d89-4c74-b1e4-3ad3b9d7f001",
  "testCases": [
    { "caseId": 1, "input": [3, 5], "output": "8" },
    { "caseId": 2, "input": [10, 2], "output": "12" }
  ]
}
```

### 2) SNS Fan-out Envelope 예시 (SQS 구독 시)
```json
{
  "Type": "Notification",
  "MessageId": "6d3f0e5f-3e8a-4df1-9c44-1e12d2a4c111",
  "TopicArn": "arn:aws:sns:ap-northeast-2:123456789012:assignment-events",
  "Message": "{\"eventType\":\"PROBLEM_UPDATED\",\"problemId\":\"7fbe8f62-9d89-4c74-b1e4-3ad3b9d7f001\",\"testCases\":[{\"caseId\":1,\"input\":[3,5],\"output\":\"8\"},{\"caseId\":2,\"input\":[10,2],\"output\":\"12\"}]}"
}
```

주의:
- 동일 `problemId`로 메시지를 다시 보내면 기존 테스트 케이스를 새 배열로 덮어씁니다.
- 해당 `problemId`가 아직 없으면 `problems` 컬렉션에 새 문서를 생성합니다.
- 컨슈머 동작 조건: `JUDGE_PROBLEM_EVENTS_ENABLED=true` 그리고 `JUDGE_PROBLEM_EVENTS_QUEUE_URL` 설정.

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
  -H 'Authorization: Bearer <JWT_TOKEN>' \
  -H 'Content-Type: application/json' \
  -d '{
    "problemId": "quiz-101",
    "language": "PYTHON",
    "code": "def solution(a,b): return a+b"
  }'
```

### 2) 실시간 SSE 구독
```bash
curl -N \
  -H 'Authorization: Bearer <JWT_TOKEN>' \
  http://localhost:8080/v1/submissions/{submissionId}/stream
```

### 3) 결과 폴링 조회
```bash
curl -s \
  -H 'Authorization: Bearer <JWT_TOKEN>' \
  http://localhost:8080/v1/submissions/{submissionId}
```

진행 중(`PENDING`, `RUNNING`)에는 `404`가 반환되고, 완료 후 `200`으로 최종 결과를 반환합니다.

## GitHub Actions (CI/CD)

- `push` / `pull_request`:
  - `.github/workflows/ci.yml`
  - 테스트만 수행 (`./gradlew test`)
- `push tag`:
  - `.github/workflows/cd.yml`
  - 태그가 `vX.Y.Z` 형식일 때만 배포 진행 (`v1.2.3` 등)
  - OIDC로 AWS Role Assume -> ECR 이미지 Push -> EC2에서 ECR Pull 후 컨테이너 재기동

### 배포 흐름 (Tag 기준)

1. `vX.Y.Z` 태그 push
2. GitHub Actions가 테스트 실행
3. app JAR 빌드 후 Docker 이미지(`linux/arm64`)를 ECR에 push
4. EC2에 SSH 접속
5. EC2가 자신의 IAM Role로 ECR 로그인 후 이미지 pull
6. 컨테이너 재생성(run) + `.env` 적용

### 1) AWS OIDC Role 생성 (GitHub Actions용)

IAM Identity Provider:
- Provider URL: `https://token.actions.githubusercontent.com`
- Audience: `sts.amazonaws.com`

Role Trust Policy 예시:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<AWS_ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:<OWNER>/<REPO>:ref:refs/tags/v*"
        }
      }
    }
  ]
}
```

Role Permission Policy 예시 (ECR Push):
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ecr:GetAuthorizationToken",
        "ecr:BatchCheckLayerAvailability",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:PutImage",
        "ecr:BatchGetImage"
      ],
      "Resource": "*"
    }
  ]
}
```

### 2) ECR 리포지토리 생성

- 예: `online-judge-app`
- 태그는 Git 태그(`vX.Y.Z`)로 push됨

### 3) EC2 인스턴스 IAM Role 설정

EC2 인스턴스 프로파일(Role)에 최소 권한 부여:
- ECR Pull:
  - `ecr:GetAuthorizationToken`
  - `ecr:BatchGetImage`
  - `ecr:GetDownloadUrlForLayer`
- SQS 소비(문제 이벤트 동기화 사용 시):
  - `sqs:ReceiveMessage`
  - `sqs:DeleteMessage`
  - `sqs:GetQueueAttributes`
- KMS 암호화 큐 사용 시:
  - `kms:Decrypt`

EC2 서버 사전 설치:
- `docker`
- `aws cli`

### 4) SNS -> SQS 연결

1. SNS Topic 생성 (문제 생성/수정 이벤트 발행)
2. SQS Queue 생성
3. Queue를 SNS Topic에 구독 연결
4. SQS Queue Policy에서 해당 SNS Topic publish 허용

애플리케이션 환경변수:
- `JUDGE_PROBLEM_EVENTS_ENABLED=true`
- `JUDGE_PROBLEM_EVENTS_QUEUE_URL=<SQS_QUEUE_URL>`
- (선택) `JUDGE_PROBLEM_EVENTS_WAIT_TIME_SECONDS`, `JUDGE_PROBLEM_EVENTS_MAX_MESSAGES`

### 5) GitHub Secrets 설정

- `AWS_HOST` (EC2 공인/사설 호스트)
- `AWS_USER` (SSH 사용자)
- `AWS_SSH_KEY` (개인키 원문)
- `AWS_REGION` (예: `ap-northeast-2`)
- `AWS_ACCOUNT_ID`
- `AWS_ECR_REPOSITORY` (예: `online-judge-app`)
- `AWS_GITHUB_ROLE_ARN` (OIDC Assume Role ARN)
- `AWS_SSH_KNOWN_HOSTS` (선택)
- `AWS_PORT` (선택, 기본 `22`)
- `AWS_APP_DIR` (선택, 기본 `/opt/online-judge`)
- `AWS_CONTAINER_NAME` (선택, 기본 `online-judge`)
- `AWS_APP_PORT` (선택, 기본 `8080`)
- `AWS_DOCKER_RUN_EXTRA_ARGS` (선택)
- `APP_ENV_VARS` (멀티라인 `KEY=VALUE`)
- `JUDGE_JWT_AUTH_SIGNING_KEY` (필수)
- `MONGODB_URI` (권장)
- `MONGODB_USER` (선택)
- `MONGODB_PASSWORD` (선택)
- `MONGODB_DATABASE` (선택)
- `REDIS_PASSWORD` (선택)
- `JUDGE_PROBLEM_EVENTS_QUEUE_URL` (선택)

`APP_ENV_VARS` 예시:
```env
SERVER_PORT=8080
JUDGE_JWT_AUTH_ENABLED=true
JUDGE_WORKER_MAX_CONCURRENCY=2
SANDBOX_IMAGE_PYTHON=<ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/judge-sandbox-python:latest
SANDBOX_IMAGE_KOTLIN=<ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/judge-sandbox-kotlin:latest
SANDBOX_IMAGE_DART=<ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/judge-sandbox-dart:latest
```

`APP_ENV_VARS`와 개별 앱 Secret 값들은 배포 시 합쳐져 `${AWS_APP_DIR}/.env`로 배치됩니다.

### 6) 배포 실행

```bash
git tag v1.0.0
git push origin v1.0.0
```

주의:
- 태그는 반드시 `vX.Y.Z` 형식이어야 배포가 실행됩니다.
- EC2 스펙이 `t4g.medium`이므로 앱 이미지는 `linux/arm64`로 빌드/배포됩니다.
