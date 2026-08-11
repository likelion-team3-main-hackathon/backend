# Tri Lion Health Backend

## [프로젝트 진행 상황 및 구현 현황]

**전체 진행률 (Status): 🟡 핵심 MVP 구현 완료 / 외부 실서비스 Adapter 연결 대기**

### 명세서 대비 구현 현황 체크리스트

- [x] **DB / Model:** `DATABASE-SCHEMA.sql`의 기존 9개 테이블을 유지하고 인증·약관·온보딩·건강 문서·AI 작업·루틴 계층·코칭·이용 테이블을 추가한 Flyway `V1` 구현
- [x] **API Endpoints — 인증/사용자:** Google OIDC 로그인, Refresh Token Rotation, 로그아웃, 약관, 온보딩, 내 프로필
- [x] **API Endpoints — 건강/분석:** 문서 업로드·목록·삭제, 분석 생성·이력·최신·단건 조회
- [x] **API Endpoints — 루틴/기록:** 생성 상태, 오늘/전체/상세, 루틴 수정, 운동 추가·수정·삭제·순서 변경, 재조정, 기록, 최신 코칭
- [x] **API Endpoints — 확장:** 홈, 전문가 신청·콘텐츠·이용, 식품 추천·장바구니 API 기본 구현
- [x] **Core Logic / AI Agent:** MySQL 영속 Job Queue, 상태 전이, 멱등성 키, 재시도, 결정적 Fake OCR/LLM, 분석·루틴·코칭 Worker 연동
- [ ] **실서비스 Adapter:** Google OIDC는 운영 검증 구현 완료. 실제 OCR/LLM 및 S3/MinIO 저장 Adapter, 제휴사 API는 교체 지점만 마련되어 있고 local/test Fake를 사용
- [ ] **운영 고도화:** Redis 캐시/분산 락, 전문가 승인 관리자 API, 인증서 파일 저장, 허용 영상 도메인 목록, 유료 영상 Signed URL, 운영 관측 대시보드

### 현재 구현 완료된 주요 기능

- Google `sub` 기반 신규/기존 사용자 로그인과 상태 전이(`PENDING_TERMS → ONBOARDING → ACTIVE`)
- 자체 JWT Access Token, HttpOnly/Secure Refresh Cookie, SHA-256 해시 저장 및 Rotation/재사용 차단
- 민감 건강정보 동의와 사용자 상태에 따른 건강 API 접근 제한
- JPG/PNG/PDF 확장자·MIME·파일 시그니처·10MB 제한 검증 및 임의 Object Key 저장
- DB 기반 비동기 AI 작업, 최대 3회 지수 백오프, 사용자·작업 유형·멱등성 키 중복 방지
- 분석 이력 및 최신/단건 결과 재조회
- 16개 운동 예시를 포함한 루틴 생성, 전체 계층 조회, 사용자 편집 표시, 수정 보호, 순서 무결성 검증, 낙관적 락
- 운동 완료 중복 차단, 수행 기록 보존, 별도 코칭 작업 생성
- 공통 응답/오류, Bean Validation, Request ID, Swagger UI, Actuator liveness/readiness
- Multi-stage Dockerfile과 MySQL 8·Redis·MinIO·버킷 초기화 Compose 구성

### 다음 작업 예정 항목 (Next Steps)

1. 실제 Object Storage SDK Adapter를 연결해 local Compose에서도 MinIO에 파일을 저장하고 Signed URL을 발급합니다.
2. 운영 OCR/LLM Provider Adapter, timeout/circuit breaker와 JSON Schema 검증을 연결합니다.
3. 전문가 인증서 저장·관리자 승인, 콘텐츠 lesson 영속화와 제휴 쇼핑몰 실제 API를 완성합니다.
4. Testcontainers MySQL 8 보안/소유권/재시도 테스트와 Docker Smoke Test를 CI에 추가합니다.

## 기술 스택

- Java 21, Spring Boot 3.5, Gradle Wrapper
- Spring MVC, Security, Data JPA, Validation, Actuator
- MySQL 8, Flyway, Redis, S3 호환 Object Storage
- OpenAPI 3 / Swagger UI, Docker Compose

## 로컬 실행

Docker Desktop 또는 호환 Docker Engine이 실행 중이어야 합니다.

```bash
cp .env.example .env
docker compose config
docker compose up --build -d
docker compose ps
docker compose logs -f app
```

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Readiness: `http://localhost:8080/actuator/health/readiness`
- MinIO Console: `http://localhost:9001`

종료 시 데이터 볼륨을 보존합니다.

```bash
docker compose down
```

## local Fake 사용법

`local` 프로필의 Google Fake Token 형식은 아래와 같습니다. 실사용 토큰이나 건강정보를 테스트에 넣지 마세요.

```json
{
  "idToken": "local:{stable-sub}:{email}:{display-name}"
}
```

예: `local:demo-user:demo@example.com:Demo User`

Fake OCR/LLM은 네트워크 없이 결정적인 분석·루틴·코칭 결과를 생성합니다. 실제 Google 검증은 `local`, `test`가 아닌 프로필에서 issuer JWK와 `GOOGLE_CLIENT_ID` audience를 검증합니다.

## 빌드 및 테스트

```bash
./gradlew clean test
./gradlew bootJar
docker compose --env-file .env.example config --quiet
```

테스트에는 다음이 포함됩니다.

- 애플리케이션 컨텍스트
- Refresh Token Rotation과 이전 토큰 재사용 차단
- 약관 전 건강 API 차단
- 로그인 → 약관 → 온보딩 → 문서 → 분석 → 루틴 → 편집 → 기록 → 코칭 통합 흐름
- Flyway 초기 Migration과 JPA 모델 스키마 검증(H2 MySQL 호환 모드)

## 환경 변수

필수 키와 안전한 개발 예시는 [.env.example](./.env.example)에 있습니다.

| 변수 | 용도 |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `local`, `dev`, `prod`, `test` |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | MySQL 연결 |
| `REDIS_URL` | Redis 연결 |
| `JWT_SECRET` | 플랫폼 JWT HMAC 키(32바이트 이상) |
| `GOOGLE_CLIENT_ID`, `GOOGLE_ISSUER` | Google OIDC audience/issuer |
| `OBJECT_STORAGE_ENDPOINT` | S3 호환 Endpoint |
| `OBJECT_STORAGE_BUCKET_PRIVATE/PUBLIC` | 비공개 건강 문서/공개 콘텐츠 버킷 |
| `OBJECT_STORAGE_ACCESS_KEY/SECRET_KEY` | Object Storage 자격증명 |
| `AI_API_KEY`, `OCR_API_KEY` | 운영 AI/OCR Provider 키 |
| `FRONTEND_ORIGIN` | 허용 CORS Origin |
| `JAVA_OPTS` | 컨테이너 JVM 옵션 |

실제 `.env`, 토큰, 개인정보 및 건강 원문은 커밋하지 않습니다.
