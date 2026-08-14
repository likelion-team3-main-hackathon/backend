# Tri Lion Health Backend

## [프로젝트 진행 상황 및 구현 현황]

**전체 진행률 (Status): 🟡 핵심 MVP 구현 완료 / 외부 실서비스 Adapter 연결 대기**

### 명세서 대비 구현 현황 체크리스트

- [x] **DB / Model:** Flyway `V1`·`V2` 구축, `V3`에서 23개 테이블을 17개로 단순화하고 `V4`에서 기존 AAC 문서 타입을 MCC로 이전
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
- 전체 기간의 날짜별 운동·재활·식단 루틴 생성, 논리 구간별 그룹 조회, 사용자 편집 표시, 수정 보호, 순서 무결성 검증, 낙관적 락
- 운동 세션 항목 일괄 완료, 패스 후 재수행, 수행 기록·인증 사진 키 보존, 별도 코칭 작업 생성
- 운동·재활·식단·체중·컨디션·기타 타입별 액티비티 자유 기록과 타입 필터 조회
- 한 전문가 커리큘럼에 운동·재활·식단 항목을 함께 담는 `MIXED` 커리큘럼
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

## Swagger UI 사용법

서버를 실행한 뒤 브라우저에서 다음 주소를 엽니다.

```text
http://localhost:8080/swagger-ui.html
```

OpenAPI 원본 JSON은 다음 주소에서 확인할 수 있습니다.

```text
http://localhost:8080/v3/api-docs
```

로컬에서 인증 API까지 테스트하는 순서는 다음과 같습니다.

1. `POST /api/v1/auth/oauth/google`을 열고 **Try it out**을 누릅니다.
2. Request Body의 `idToken`에 `local:swagger-user:swagger@example.com:Swagger User`를 입력하고 실행합니다.
3. 응답의 `data.accessToken` 값만 복사합니다.
4. Swagger 화면 오른쪽 위 **Authorize** 버튼을 누릅니다.
5. 입력란에는 `Bearer`를 붙이지 않고 복사한 Access Token만 입력합니다.
6. 약관 동의 → 온보딩 → 건강 문서 업로드 순서로 보호 API를 실행합니다.

Swagger UI가 같은 서버에서 제공되므로 local 프로필에서는 로그인 응답의 HttpOnly Refresh Cookie도 브라우저 Cookie Jar에 보관됩니다. `POST /auth/token/refresh` 실행 시 Request Body나 Access Token은 필요하지 않습니다.

건강 문서 업로드는 Swagger의 파일 선택 버튼에서 JPG, PNG 또는 PDF 파일을 선택합니다. AI 분석과 루틴 생성은 `202 Accepted` 이후 상태 조회 API를 약 1초 간격으로 다시 실행해 `COMPLETED` 여부를 확인합니다.

Swagger에서 자물쇠가 표시된 API는 Access Token이 필요합니다. Google 로그인과 Refresh Token 재발급 API는 공개 API로 표시됩니다.

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

## Postman 로컬 API 테스트

다음 두 파일을 Postman에서 각각 Import합니다.

- Collection: [`postman/Tri-Lion-Health.postman_collection.json`](./postman/Tri-Lion-Health.postman_collection.json)
- Environment: [`postman/Tri-Lion-Health.local.postman_environment.json`](./postman/Tri-Lion-Health.local.postman_environment.json)

Postman 우측 상단 Environment에서 `Tri Lion Health - Local`을 선택한 뒤 컬렉션 요청을 번호 순서대로 실행합니다. 로그인 응답에서 Access Token을 저장하고 이후 문서·분석·루틴·운동·기록 ID도 테스트 스크립트가 자동 저장합니다.

`6. 건강 문서 업로드`에서 파일이 자동 선택되지 않으면 프로젝트의 `postman/fixtures/sample-health.pdf`를 한 번 직접 선택합니다. `23-4. 운동·식단 인증 사진 업로드`는 JPG 또는 PNG 파일을 직접 선택합니다. Collection Runner로 전체 실행할 때 해당 사진 요청을 제외하거나 파일을 먼저 지정하고, 비동기 Worker 처리를 위해 요청 간 Delay를 `1000ms`로 설정합니다. 분석 또는 루틴 상태가 아직 `PENDING/PROCESSING`이면 해당 상태 조회 요청을 1초 뒤 다시 실행합니다.

로컬 HTTP 테스트에서는 Postman Cookie Jar가 Refresh Token을 전송할 수 있도록 `local` 프로필에 한해 `Secure=false`를 사용합니다. 다른 프로필의 기본값은 `Secure=true`입니다.

전문가 혼합 커리큘럼 API는 승인 전문가만 사용할 수 있습니다. Postman의 `32. 전문가 인증 신청`을 실행한 다음 로컬 테스트에서만 아래 명령으로 가장 최근 신청자를 승인하고 `33. 운동·재활·식단 혼합 커리큘럼 등록`을 다시 실행합니다.

```bash
docker compose exec mysql mysql \
  -umcc_user -pmcc_password mcc_wellness \
  -e "UPDATE experts SET verification_status='APPROVED' ORDER BY applied_at DESC LIMIT 1;"
```

운영 환경에서는 DB를 직접 변경하지 않고 별도의 관리자 심사 흐름을 사용해야 합니다.

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
- 운동·재활·식단 기록 생성, 세션 일괄 저장, 패스 후 완료, 사진 업로드, 타입 필터와 잘못된 타입 거부
- 운동·식단 혼합 전문가 커리큘럼 생성과 단일 타입 불일치 거부

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
