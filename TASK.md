# TASK.md — 단계별 구현 태스크 (Spring Boot Kotlin + Coroutines)

> 의존 관계는 `depends:` 항목으로 명시. 병렬 가능한 태스크는 명시적으로 표기.

---

## Phase 0: 프로젝트 기반 ✅ 완료

### T-0-1 ✅ 프로젝트 골격
- [x] 디렉토리 구조 (`config/`, `domain/`, `repository/`, `api/`, `service/`, `worker/`, `sandbox/`)
- [x] `build.gradle.kts` — validation, configuration-processor, coroutines-reactive 추가
- [x] `docker/docker-compose.yml` — MongoDB 8, Redis 7
- [x] `src/main/resources/application.yaml` — MongoDB, Redis, sandbox 설정
- [x] `.env.example`

### T-0-2 ✅ 도메인 및 설정 스켈레톤
- [x] `domain/enums.kt` — Language, SubmissionStatus, TestCaseStatus
- [x] `domain/Submission.kt` — `@Document` MongoDB 모델
- [x] `domain/TestCaseResult.kt` — 내장 도큐먼트
- [x] `config/AppConfig.kt` — SandboxProperties, Redis beans
- [x] `repository/SubmissionRepository.kt` — ReactiveMongoRepository
- [x] `api/dto/SubmissionRequest.kt`, `SubmissionResponse.kt`
- [x] `api/SubmissionController.kt` — suspend, Flow<SSE>
- [x] `service/SubmissionService.kt` — 스켈레톤
- [x] `sandbox/SandboxRunner.kt` — 스켈레톤

---

## Phase 1: Docker 기반 언어 실행 래퍼

> depends: T-0-1
> 참고(2026-03-09): Docker daemon 연결 복구 후 언어별 이미지 빌드/수동 검증 완료.

### T-1-1: Python 샌드박스 이미지 및 래퍼 (독립)
- [x] `docker/sandbox/python/Dockerfile` 작성
  - Base: `python:3.11-slim`
  - 비root 사용자 `appuser` 생성
  - `python_runner.py` 복사
- [x] `docker/sandbox/python/runner.py` 작성
  - stdin: `{"code": "...", "args": [...]}` JSON 수신
  - `exec(code, globals)` → `globals["solution"](*args)` 호출
  - `tracemalloc` + `time.perf_counter_ns()` 측정 포함
  - stdout: `{"output": ..., "timeMs": ..., "memoryMb": ..., "error": null}` JSON 출력
  - 예외 → `{"output": null, "error": "RUNTIME_ERROR: <msg>"}` 출력
- [x] 이미지 빌드 및 수동 검증
  ```bash
  docker build -t judge-sandbox-python:latest docker/sandbox/python/
  echo '{"code":"def solution(a,b): return a+b","args":[3,5]}' | \
    docker run --rm -i --network none judge-sandbox-python:latest
  ```

### T-1-2: Kotlin 샌드박스 이미지 및 래퍼 (독립)
- [x] `docker/sandbox/kotlin/Dockerfile` 작성
  - Base: `eclipse-temurin:21-jdk`
  - kotlinc 설치
  - `KotlinRunner.kt` 복사 (래퍼 스크립트)
- [x] `docker/sandbox/kotlin/KotlinRunner.kt` 작성
  - stdin JSON 파싱 → `solution()` 호출 → stdout JSON 출력
  - `System.nanoTime()` 으로 실행 시간 측정
  - 컴파일 오류 → `{"error": "COMPILE_ERROR: <msg>"}` 출력
- [x] 이미지 빌드 및 수동 검증

### T-1-3: Dart 샌드박스 이미지 및 래퍼 (독립)
- [x] `docker/sandbox/dart/Dockerfile` 작성
  - Base: `dart:stable`
  - 비root 사용자 생성
- [x] `docker/sandbox/dart/runner.dart` 작성
  - stdin JSON 파싱 → `solution()` 호출 → stdout JSON 출력
  - `Stopwatch` 로 실행 시간 측정
- [x] 이미지 빌드 및 수동 검증

### T-1-4: SandboxRunner ProcessBuilder 구현
> depends: T-1-1, T-1-2, T-1-3

- [x] `sandbox/SandboxRunner.kt` 구현
  ```kotlin
  val cmd = listOf("docker", "run", "--rm", "--network", "none",
      "--cpus", properties.cpuLimit,
      "--memory", "${properties.memoryLimitMb}m",
      "--read-only", "--security-opt", "no-new-privileges",
      "--pids-limit", "${properties.pidsLimit}",
      "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
      "-i", imageName)
  val process = ProcessBuilder(cmd)
      .redirectErrorStream(false)
      .start()
  ```
  - stdin에 JSON 주입 후 stdout 읽기 (코루틴 `Dispatchers.IO`)
  - `withTimeoutOrNull` 으로 타임아웃 → `TIME_LIMIT_EXCEEDED`
  - exit code != 0 → `RUNTIME_ERROR`
  - stdout JSON 파싱 → `SandboxOutput` 반환

### T-1-5: Phase 1 통합 테스트
- [x] `src/test/kotlin/.../sandbox/SandboxRunnerIntegrationTest.kt`
  - 각 언어 정상 실행 (`solution(3, 5) → 8`)
  - `RUNTIME_ERROR` 케이스 (ZeroDivisionError 등)
  - `COMPILE_ERROR` 케이스 (Kotlin/Dart 문법 오류)
  - `TIME_LIMIT_EXCEEDED` 케이스 (무한루프, timeLimitSeconds=2)

---

## Phase 2: 성능 측정 모듈

> depends: T-1-4

### T-2-1: Python 내부 측정 강화
- [x] `docker/sandbox/python/runner.py` 의 `tracemalloc` peak 측정 정확도 검증
- [x] 측정 오버헤드 제거 (tracemalloc은 solution() 호출 직전/직후 범위만 적용)

### T-2-2: Kotlin/Dart 컨테이너 메모리 측정
- [x] `sandbox/DockerStatsClient.kt` 작성
  - `docker stats --no-stream --format "{{json .}}" <container-id>` ProcessBuilder 호출
  - `MemUsage` 파싱 → MB 변환
  - `SandboxRunner.runContainer()` 종료 직전 호출하여 peak 기록

### T-2-3: SandboxOutput 결과 검증 로직
- [x] `worker/JudgeWorker.kt` 에 기대값 비교 로직 추가 (Phase 3 선행 구현)
  - `output == expectedOutput` → `PASSED`
  - `memoryMb > properties.memoryLimitMb` → `MEMORY_LIMIT_EXCEEDED`
  - 불일치 → `WRONG_ANSWER`

### T-2-4: 단위 테스트
- [x] 메모리 측정 정확도 검증 (1MB 할당 코드 실행 후 허용 오차 확인)
- [x] `MEMORY_LIMIT_EXCEEDED` 케이스 (128MB+ 할당 시도)

---

## Phase 3: 코루틴 기반 비동기 워커

> depends: T-2-3

### T-3-1: JudgeWorker 구현
- [x] `worker/JudgeWorker.kt` 작성
  ```kotlin
  @Component
  class JudgeWorker(
      private val sandboxRunner: SandboxRunner,
      private val submissionRepository: SubmissionRepository,
      private val redisTemplate: ReactiveStringRedisTemplate,
      private val objectMapper: ObjectMapper,
  ) {
      suspend fun execute(submissionId: String, submission: Submission, testCases: List<TestCase>) {
          testCases.forEachIndexed { idx, tc ->
              val output = sandboxRunner.run(submission.language, SandboxInput(submission.code, tc.args))
              val result = output.toTestCaseResult(idx + 1, tc.expected)
              // Redis PUBLISH
              val json = objectMapper.writeValueAsString(result)
              redisTemplate.convertAndSend("submission:$submissionId", json).awaitSingle()
          }
          // 전체 완료 이벤트
          redisTemplate.convertAndSend("submission:$submissionId", """{"event":"done"}""").awaitSingle()
          // MongoDB 최종 상태 저장
          submissionRepository.save(submission.copy(status = overallStatus)).awaitSingle()
      }
  }
  ```

### T-3-2: SubmissionService 워커 기동 연결
- [x] `service/SubmissionService.kt` 수정
  - `createSubmission()` 내부에서 백그라운드 코루틴 launch:
    ```kotlin
    coroutineScope.launch(Dispatchers.IO) {
        judgeWorker.execute(saved.id, saved, testCases)
    }
    ```
  - `CoroutineScope(SupervisorJob() + Dispatchers.Default)` Bean으로 주입

### T-3-3: TestCase 도메인 모델 추가
- [x] `domain/TestCase.kt` 작성 — `caseId`, `args`, `expected` 필드
- [x] `domain/Problem.kt` (또는 외부 문제 저장소 연동 설계)
  - MVP: `application.yaml` 에 인라인 테스트 케이스 OR 별도 `problems` 컬렉션

### T-3-4: Phase 3 통합 테스트
- [x] 제출 POST → 워커 코루틴 실행 → MongoDB 상태 업데이트 E2E 테스트
- [x] 동시 3개 제출 처리 테스트 (코루틴 병렬 실행 검증)

---

## Phase 4: SSE 실시간 스트리밍

> depends: T-3-2

### T-4-1: SSE 스트림 완성
- [x] `service/SubmissionService.streamResults()` 구현 완성
  - Redis Pub/Sub 메시지 타입 처리: `{"event":"done"}` 수신 시 `Flow` 종료
  - `ServerSentEvent.builder<String>().event("test_case_result").data(json).build()`
  - `event: done` 이벤트 emit 후 스트림 종료

### T-4-2: SSE 이벤트 포맷 고정
- [x] 이벤트 타입 정의 및 문서화:
  ```
  event: test_case_result
  data: {"caseId":1,"status":"PASSED","timeMs":12.3,"memoryMb":4.2}

  event: done
  data: {"submissionId":"...","overallStatus":"ACCEPTED"}

  event: error
  data: {"message":"..."}
  ```

### T-4-3: `realtimeFeedback: false` 모드
- [x] `SubmissionService.getResult()` 가 PENDING/RUNNING 상태 시 `null` 반환 확인
- [x] 클라이언트 폴링 시나리오 테스트 (`GET /v1/submissions/{id}` 반복 호출)

### T-4-4: E2E 통합 테스트
- [x] `WebTestClient` 로 SSE 스트림 수신 테스트
  ```kotlin
  webTestClient.get()
      .uri("/v1/submissions/$id/stream")
      .accept(MediaType.TEXT_EVENT_STREAM)
      .exchange()
      .expectStatus().isOk
      .returnResult<ServerSentEvent<String>>()
  ```
- [x] 복수 클라이언트가 동일 submission 구독 시 각자 수신 확인

---

## Phase 5: 보안 강화 및 마무리

> depends: Phase 4 완료

### T-5-1: 입력 검증 강화
- [x] `@NotBlank`, `@Size(max=65536)` 이미 적용 확인
- [x] `problemId` 패턴 검증 (`@Pattern(regexp = "^[a-zA-Z0-9-]+$")`)
- [x] Rate Limiting — `spring-boot-starter-actuator` + bucket4j 또는 커스텀 WebFilter

### T-5-2: Docker 보안 강화 검증
> 참고(2026-03-09): Docker daemon 연결 확인 후 언어별 실측 완료.
- [x] `--tmpfs /tmp:rw,noexec,nosuid,size=64m` 없이 `--read-only` 실행 시 런타임 오류 여부 확인
- [x] 각 언어 런타임이 필요한 tmpfs 마운트 목록 확정
- [x] gVisor 적용 가이드 작성 (선택적 운영 옵션으로 문서화)
> 실측 결과(2026-03-09):
> - Python: `--read-only` 단독 실행 가능 (`tmpfs /tmp` 불필요)
> - Kotlin: `--read-only` 단독 실행 실패 (`/tmp` 쓰기 필요) → `--tmpfs /tmp:rw,noexec,nosuid,size=64m` 필요
> - Dart: `--read-only` 단독 실행 실패 (`createTemp('/tmp')` 실패) → `--tmpfs /tmp:rw,noexec,nosuid,size=64m` 필요

### T-5-3: 모니터링 및 로깅
- [x] `spring-boot-starter-actuator` 추가 → `/actuator/health` 활성화
- [x] `/actuator/health` 에 MongoDB, Redis 상태 포함
- [x] 구조적 로깅 (`logstash-logback-encoder` 또는 Spring Boot JSON 로깅)
- [x] 각 요청/응답, 컨테이너 실행 이벤트에 `submissionId` MDC 추가

### T-5-4: 문서화
- [x] `README.md` — 로컬 실행 방법, 환경 변수 설명, 샌드박스 이미지 빌드 방법
- [x] API 예시 curl 커맨드 추가

---

## 태스크 의존 관계 요약

```
Phase 0 ✅
    │
    ├─▶ T-1-1, T-1-2, T-1-3 (병렬 가능)
    │       └─▶ T-1-4 ──▶ T-1-5
    │
    └─▶ T-2-1, T-2-2 (T-1-4 이후, 병렬 가능)
                └─▶ T-2-3 ──▶ T-2-4
                        │
                        └─▶ T-3-1 ──▶ T-3-2, T-3-3 ──▶ T-3-4
                                                │
                                                └─▶ T-4-1 ~ T-4-4
                                                            │
                                                            └─▶ T-5-1 ~ T-5-4
```
