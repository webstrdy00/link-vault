# link-vault

Android 우선 관심 정보 보관함. M0 공유 입력, M1 회원·서버 보관함,
M2 오프라인 대기열·캐시·제목/메모 편집을 구현했습니다.
M3 검색·분류·재적용과 검색 단서 안내는 로컬 서버·에뮬레이터 검증을 통과했습니다.
M4의 제한된 네이버 메타정보, 이미지 1장 첨부·교체·삭제, 기기 OCR도
로컬 서버·에뮬레이터 검증을 통과했습니다.
Google 로그인 코드는 연결했지만 실제 OAuth 공급자 교환은 설정 후 검증이 필요합니다.
서버 항목 삭제와 계정 탈퇴는 M5 범위이며 아직 제공하지 않습니다.

## 개발 환경

- JDK 17, Android SDK Platform 35, minSdk 26
- Gradle 8.10.2 Wrapper, AGP 8.7.3, Kotlin 2.1.0
- Compose BOM 2024.12.01
- Room 2.7.2, WorkManager 2.9.1
- `ANDROID_HOME`에 SDK 경로를 설정하거나 `android/local.properties`의
  `sdk.dir`로 지정합니다. 로컬 설정·키·빌드 출력은 커밋하지 않습니다.
- Android Studio에서 `android/`를 엽니다.

## 빌드와 검증

저장소 루트의 Windows PowerShell에서:

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
.\android\gradlew.bat -p android :app:connectedDebugAndroidTest
```

두 번째 명령은 실행 중인 에뮬레이터 또는 USB 디버깅을 허용한 기기가 필요합니다.
Linux/macOS에서는 `./android/gradlew -p android`를 사용합니다.

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- 단위 테스트: `android/app/build/reports/tests/testDebugUnitTest/index.html`
- UI 테스트: `android/app/build/reports/androidTests/connected/debug/index.html`
- Lint: `android/app/build/reports/lint-results-debug.html`

공유 텍스트·직접 입력의 HTTP/HTTPS 링크를 확인하고, 여러 링크 중 하나를 선택해
원문 앱/브라우저로 전달하거나 로그인 후 서버에 보관합니다.
제목·메모는 선택 입력이며 서버 응답 전에는 저장 완료로 표시하지 않습니다.
이미지 `content://` 공유와 기기 사진은 사용자가 이미지와 대상 항목을 각각 명시적으로
선택한 뒤 1장만 첨부합니다. 자동으로 다른 항목에 붙이거나 원격 이미지를 복사하지 않습니다.
기본 런처 아이콘은 개발용입니다.
의존성은 SDK 35/JDK 17의 검증 조합으로 고정했으며 최신 버전 알림을 숨기지 않습니다.

## M1–M4 로컬 서버와 검증

Node.js 24와 Docker Desktop이 필요합니다. 빈 로컬 테스트 환경에서:

```powershell
npm ci
npm run backend:start
npm run backend:migrate
npm run backend:configure-worker
npm run test:db
npm run test:api
npm run test:integration
npm run test:discovery-integration
npm run test:enrichment-integration
npm run test:emulator-integration
npm run test:offline-integration
npm run test:discovery-emulator-integration
npm run test:enrichment-emulator-integration
```

네 에뮬레이터 명령은 표시된 순서대로 실행하며 실행 중인 Android 에뮬레이터를 선택합니다.
빈 멤버십 fixture에서 시작하고 각 시험이 만든 계정만 정리하며, DB를 삭제하거나
초기화하는 방식으로 선행 상태를 만들지 않습니다.
실제 로컬 GoTrue 인증 세션으로 앱의 계정 초기화→저장→상세 재조회와 DB의 원문·메모를 확인합니다.
테스트 전용 이메일 로그인을 instrumentation 소스에서만 사용하며 제품에 대체 로그인 UI는 없습니다.
Google ID 토큰이나 로그인 성공을 조작하지 않고 **Google 공급자 교환은 시험하지 않습니다.**
테스트가 만든 계정만 정리합니다. 운영 프로젝트나 실제 자료를 넣은 로컬 DB에서 실행하지 마세요.
에뮬레이터 앱 데이터도 테스트용으로 취급합니다. 일반 UI 시험은 종료 후 APK를 제거할 수 있습니다.

`test:offline-integration`은 APK와 앱 데이터를 유지한 채 Wi-Fi/데이터를 끄고
실제 Room 대기 요청을 만든 뒤 앱 프로세스를 강제 종료합니다.
실제 GoTrue 토큰의 **로컬 만료 시각만** 시험용으로 앞당겨, 오프라인 갱신 실패 화면과
연결 복구 후 원래 요청 ID로 한 번 저장되는 것을 확인합니다.
이어서 다른 서버 변경과 충돌하는 메모 편집의 최신 내용 확인·새 요청 저장,
로그아웃 취소/확정과 미전송 자료 제거를 검사합니다. 네트워크 설정은 종료 시 복원합니다.

로컬 통합 시험은 테스트용 설정으로 debug APK를 다시 빌드합니다.
일반 개발 APK로 돌아갈 때는 위의 기본 Android 빌드 명령을 다시 실행하세요.
Supabase CLI가 출력하는 키·토큰은 로그나 저장소에 공유하지 마세요.

구현한 API: `/functions/v1/library-api/v1/health`, `/bootstrap`, `/me`,
POST/GET `/items`, GET/PATCH `/items/{id}`, 분류 목록·생성·이름 변경·삭제,
검색 규칙 조회, 단서 안내 닫기, 자동 분류 재적용, 메타정보 재시도,
이미지 예약·완료·OCR 재시도와 인라인 이미지 DELETE입니다.
GET `/items`는 검색어·분류·미분류·출처·기간·limit/offset을 지원합니다.
승인된 회원만 접근하고 새 항목은 100개·분당 10건 제한을 적용합니다.
재전송·중복 URL은 원래 메모를 덮어쓰지 않습니다.
중복 키는 호스트 대소문자·기본 포트만 정규화하고 경로·쿼리·fragment를 유지합니다.

조회·bootstrap은 호출자 JWT의 RPC를 사용합니다. 생성·편집은 GoTrue에서 검증한 회원 ID를
서버 전용 RPC에 전달하고 SQL에서 승인·소유권·한도를 재검사합니다.
일반 클라이언트는 테이블과 생성·편집 RPC에 직접 쓰지 못합니다.
원본·검색 파생 데이터·대기 작업·사용량·멱등 기록을 같은 트랜잭션에 저장합니다.
분류 작업은 임대·재시도·현재 버전 검증 후 원자적으로 반영합니다.
메타정보와 OCR도 실제로 확보한 필드만 버전과 함께 반영하며, 확보하지 않은 본문·OCR을
확보했다고 표시하지 않습니다.

### M2 보관 안정성

- 로그인 전 공유 입력·URL 선택은 Room에서 복구하며 임시 입력은 24시간 만료합니다.
- 로그인한 회원의 저장·편집은 Room에 먼저 기록합니다. 기기 대기와 서버 완료를 구분합니다.
- WorkManager는 같은 회원·요청 ID·본문으로 재전송합니다. 자동 전송은 생성 후 24시간까지만
  허용하며 만료 후에는 명시적인 재확인이 필요합니다. 429의 대기 시간은 수동 재시도로도 줄이지 않습니다.
- 오프라인 목록·상세는 해당 회원의 마지막 서버 캐시와 동기화 시각을 표시합니다.
  늦게 온 낮은 버전 응답은 최신 캐시를 덮어쓰지 않습니다.
- 제목·메모 편집은 `expected_version`으로 보호합니다. 409 후에는 최신 내용 확인과
  새 요청 ID가 필요하며, 실패했던 요청의 본문을 같은 ID로 바꾸는 것도 서버에서 거부합니다.
- 명시적 로그아웃은 확인 후 대기 요청·캐시·임시 입력을 정리합니다.
  인증이 만료되어도 정리할 수 있고, 다른 계정 로그인 시 이전 기기 자료를 재사용하지 않습니다.
  서버 항목을 삭제하는 기능은 아닙니다.
- 제목·메모 편집과 분류 선택은 별도 요청입니다. 다른 화면의 편집 완료가
  작성 중인 메모나 분류 선택을 덮어쓰지 않도록 요청 소유권과 기준 버전을 유지합니다.

### M3 검색과 분류

- 서버의 NFKC·소문자·공백 정규화와 승인된 별칭만 사용합니다.
  직접 문자열 AND 결과 전체가 별칭 전용 결과보다 먼저 나오며 Android에서 다시 정렬하지 않습니다.
- 검색어는 정규화 전후 최대 200 Unicode 코드 포인트, 입력 단어·확장 그룹은 각각 최대 10개입니다.
  초과 입력은 `QUERY_LIMIT`으로 거부하며 자르지 않습니다.
- 기간은 시작 포함·끝 제외의 RFC3339 시각입니다. Android 날짜 선택은 기기의 시간대와
  일광절약시간을 반영해 해당 날짜 경계로 변환합니다.
- 사용자 분류는 회원별 최대 30개, 표시 이름 1–30자, 연결은 항목당 최대 5개입니다.
  정규화된 이름은 기본 분류 이름을 포함해 중복을 허용하지 않습니다.
  정규화로 길어진 검색 데이터는 원문을 자르지 않고 별도의 파생 길이 한도로 보관합니다.
- 자동 분류는 봉인된 `rules-v2.0.0`의 일곱 기본 분류에서 최대 3개를 선택합니다.
  `기타`는 직접 선택 전용이고 미분류는 가상 필터입니다.
- 직접 선택은 빈 선택도 유효하며 자동 덮어쓰기를 잠급니다.
  재적용은 확인 후 사용자 분류를 보존하고 기본 분류를 다시 계산합니다.
  202 접수와 실제 처리 성공을 구분합니다.
- 분류와 별칭의 현재 원문 일치 표현을 확인할 수 있습니다. 확률·신뢰도 점수는 표시하지 않습니다.
  단서가 부족한 항목의 안내를 닫으면 같은 텍스트 revision에서는 다시 표시하지 않습니다.
- 분류 변경도 내구성 있는 Room 대기열을 사용합니다. 분류 캐시에는 마지막 동기화 시각을
  표시하며 오프라인 검색을 서버 검색 완료로 대체하지 않습니다.

### M4 메타정보·이미지·OCR

- 자동 메타정보는 allowlist의 네이버 호스트에 한해 HTTPS 443으로만 요청합니다.
  허용된 메타정보만 읽으며 전체 본문, 원격 이미지, 영상은 수집하지 않습니다.
- 리다이렉트마다 DNS의 모든 응답이 공인 IP인지 확인하고 검증한 IP로 연결을 고정합니다.
  고정한 Undici `6.21.3`을 사용하되 원래 네이버 호스트를 SNI와 인증서 검증 이름으로
  유지합니다. 요청 제한은 10초, 응답 2,000,000바이트, 리다이렉트 3회입니다.
- 외부 메타 요청은 항목당 UTC 하루 6회, 사용자 수동 재시도 3회가 상한입니다.
  자동 실행은 최초 1회와 재시도 최대 3회이며 한 리다이렉트 체인은 한 요청으로 계산합니다.
- `library-images`는 private 버킷이고 회원 업로드는 create-only입니다.
  회원별 총량은 20,000,000바이트이며 항목마다 활성 이미지 1개와
  15분 동안 유효한 2,000,000바이트 예약 1개만 허용합니다.
  서버가 JPEG·PNG·WebP를 실제 디코딩해 형식과 최대 24MP를 확인합니다.
- Android는 최대 10MB 입력을 최대 긴 변 4096으로 재인코딩합니다.
  번들된 한국어+라틴 OCR을 기기에서 실행하고 OCR 텍스트는 최대 20,000 Unicode scalar입니다.
  이미지와 대상 항목은 사용자가 명시적으로 선택합니다.
- 교체 실패 시 기존 활성 이미지를 유지합니다. 완료 응답이 불명확하면 같은 요청
  ID·본문으로 서버 멱등 결과를 확인합니다. 로컬 15분 타이머만으로 `COMPLETE`
  요청을 새 UUID로 바꾸지 않습니다.
  OCR은 item version과 text revision을 올려 검색·최신 분류 작업을 갱신하되
  사용자의 직접 분류를 보존합니다.
- Room schema v3는 회원과 세션 generation으로 대기 상태를 격리합니다.
  앱 소유 private 임시 파일은 24시간 경계로 정리하고 완료·취소 시 해당 작업 파일을
  제거합니다. 명시적 로그아웃·계정 변경도 기기의 관련 개인 자료를 정리합니다.
  인라인 이미지 DELETE와 실제 Storage 객체 정리는 구현했지만,
  서버 항목 삭제와 계정 삭제는 M5 범위로 남아 있습니다.

### 백그라운드 실행기와 기존 분류 정규화

`backend:migrate`는 추가 마이그레이션 적용 후 프로덕션 TypeScript 정규화 함수로
로컬 기존 분류를 백필합니다. 표시 이름·ID·연결은 유지하고 파생 검색만 다시 만듭니다.
충돌·불완전하거나 오래된 스냅샷은 변경 없이 거부합니다. 백필 전에는 해당 회원의
정규화 의존 작업을 `CATEGORY_NORMALIZATION_REQUIRED`로 차단합니다.
이미 적용한 마이그레이션을 수정하거나 DB를 초기화해 해결하지 마세요.

`backend:configure-worker`는 로컬 Vault의 `link_vault_worker_url`과
`link_vault_worker_token`을 설정합니다. 토큰은 파일·명령 인자·로그에 출력하지 않습니다.
`pg_cron`의 `link-vault-processing` 작업은 매분 `private.dispatch_classification_jobs()`와
`private.dispatch_enrichment_jobs()`를 모두 호출합니다. 같은 Vault URL·토큰에서
분류 경로와 `internal/metadata`, `internal/assets-cleanup` 경로를 파생해 `pg_net`으로
서버 전용 요청을 보냅니다.
일반 회원은 이 경로를 호출할 수 없습니다. `waitUntil`은 보조 실행이며 유일한 복구 수단이 아닙니다.
처리할 작업이 있는데 Vault 설정이 없으면 분류는 `CLASSIFICATION_WORKER_NOT_CONFIGURED`,
메타정보·파일 정리는 `ENRICHMENT_WORKER_NOT_CONFIGURED`로 실패합니다.
운영 점검은 작업 상태, cron 결과, HTTP 상태와 고정 오류 코드만 확인하고 원문·쿼리·토큰을 남기지 않습니다.
두 설정 스크립트는 로컬 전용입니다. 운영 백필·Vault/크론 설정과 배포 인수는 별도입니다.

WebP 디코더 `webp_dec.wasm`은 Edge 번들에 정적으로 포함해야 하며
`supabase/config.toml`의 `static_files`에서 빠지면 안 됩니다. 런타임 네트워크로 WASM을
받는 fallback은 없습니다. import map의 런타임 의존성은 exact version으로 고정합니다.
`supabase/config.toml`, 정적 파일, import map·의존성을 바꾼 뒤에는 로컬 backend/Edge를
재시작해야 변경된 번들을 검증할 수 있습니다.

### 실제 Google 로그인 설정

- 앱 빌드는 환경변수 또는 Gradle 프로젝트 속성 `SUPABASE_URL`,
  `SUPABASE_PUBLISHABLE_KEY`, `GOOGLE_WEB_CLIENT_ID`를 읽습니다.
- 실제 Google Cloud 웹 OAuth 클라이언트와 Android 클라이언트
  (`com.linkvault.app`, debug/release 서명 지문)를 등록해야 합니다.
  지문은 `.\android\gradlew.bat -p android :app:signingReport`로 확인합니다.
- 서버의 Google provider에는 같은 웹 client ID와 필요한 client secret을 설정합니다.
  로컬에서는 `supabase/config.toml`의 `[auth.external.google]`을 활성화하고
  `GOOGLE_WEB_CLIENT_ID`·`GOOGLE_CLIENT_SECRET` 환경변수를 설정한 뒤 서버를 재시작합니다.
  nonce 검증은 끄지 않습니다.
- 로컬 에뮬레이터의 앱 URL은 `http://10.0.2.2:18021`입니다.
  일반 배포 앱은 HTTPS만 허용하고 이 HTTP 예외는 debug에만 존재합니다.
- 앱에는 publishable/anon 키만 넣습니다. `sb_secret_` 또는 service_role 키를
  앱 설정으로 전달하면 빌드를 거부합니다. Google client secret도 앱에 넣지 않습니다.
- 로그인한 회원의 UUID를 서버 관리자가 `beta_members`에 승인하고 `approved_at`을 기록합니다.
  최대 6명이며 미승인 상태에서는 회원 화면에 승인 대기가 표시됩니다.
- 설정이 없으면 명확한 설정 안내만 표시하며 가짜 로그인·로컬 보관함으로 대체하지 않습니다.

### 확인된 범위

2026-09-16 M4 로컬 구현 인수 결과:

- 최종 재검증에서도 API 162개·검색 80개·enrichment 69개(아티팩트 1098),
  M4 에뮬레이터·JVM·Lint/빌드(1100), Edge 진입점 타입 검사(1102)가 통과했습니다.
- 작업 재개 후 에뮬레이터 미연결로 실행하지 못한 회차(1107)는 통과로 집계하지 않았습니다.
  에뮬레이터를 다시 시작한 뒤 최종 소스로 기본 기기 시험 52개 전체와
  JVM·Lint·APK 빌드 게이트를 다시 통과했습니다(1111).
- migration `016`을 적용한 DB 시험 504개가 통과했습니다. `001`~`016`은 적용 이력을
  다시 쓰지 않는 immutable migration입니다.
- API 162개와 Deno fmt/lint/check가 통과했습니다(아티팩트 1090·1078).
  실제 로컬 Auth/API/RLS 42개도 통과했습니다(아티팩트 1078).
- 검색 통합 80개가 통과했습니다(아티팩트 1090). 로컬 활성 서비스의 100개 항목·
  30회 표본 p95는 검색 189.0ms, 저장 101.2ms였습니다. 이는 운영 성능 수치가 아닙니다.
- backend enrichment 69개가 실제 Edge의 네이버 `ready`, 정적 WebP WASM 디코딩,
  OCR→검색, 할당량과 물리 파일 정리를 통과했습니다(아티팩트 1090).
- Android JVM 77개가 실패 없이 통과했고 기본 기기 시험 52개가 통과했습니다
  (아티팩트 1086). 최종 Lint/빌드는 오류 0개·경고 47개입니다(아티팩트 1088).
  `RequiresApi` 표시는 이미 API 28 guard가 있는 경로와 디코더에 추가했으며 Lint를
  비활성화하지 않았습니다.
- 실제 M4 에뮬레이터 흐름은 `content://` 이미지와 대상의 명시적 선택→오프라인
  Room+private 파일→프로세스 강제 종료→재연결→실제 한국어/라틴 OCR→실패 상태 OCR
  재시도와 임시 파일 삭제→이미지 교체→검색→인라인 이미지 DELETE와 물리 파일 정리를
  통과했습니다(아티팩트 1074).
- 실제 M1/M2/M3 에뮬레이터 회귀도 모두 통과했습니다(아티팩트 1092).
  실제 만료 토큰 갱신, 오프라인, 새 요청 UUID 충돌 재저장과 로그아웃을 포함합니다.
- Android native 보안 검토 55와 backend 보안 검토 54는 각각 APPROVE입니다.
  다만 응답 본문 조기 오류 경로의 취소 crash 수정은 검토 54 이후 변경이므로
  검토 54가 그 차이를 검토했다고 주장하지 않습니다. 이 후속 수정은 단위 시험과
  실제 M3/M4 에뮬레이터 회귀로 확인했습니다.

인수 과정에서 Room nullable `resumeStage` 변환, canonical persisted file URI,
래핑되지 않은 완료 API 응답 처리, Edge가 지원하지 않는 `https.lookup` 사용과
취소 시 crash를 수정했습니다.

M4의 **로컬 구현·검증 완료**는 M5 서버 항목/계정 삭제·운영, M6, 전체 베타,
실제 Google OAuth 공급자 교환, S23·실제 SNS, 운영 배포·성능 인수를 뜻하지 않습니다.
Google 공급자 교환, 실기기/SNS와 운영 설정·배포·관측·백업/복원 인수는 명시적으로 보류합니다.

아래 수치는 2026-09-15 M3 종료 시점의 역사적 기록이며 현재 시험 수나 성능 상한이 아닙니다.

회원별 백필 `011` 적용 후 DB 349개, API 103개, Auth/API/RLS 42개,
검색 통합 80개, Android JVM 65개, 기본 에뮬레이터 UI/Room 30개가 통과했습니다.
100개 항목·30회 표본의 당시 로컬 p95는 검색 61.4ms, 저장 79.5ms였습니다.
M1 서버 연결, M2 오프라인·재시작·충돌·로그아웃, M3 검색·분류 생성·직접 선택·
재적용·단서 안내의 실제 서버 연결 에뮬레이터 검증도 통과했습니다.
Windows TCP 예약 범위와의 충돌은 관리자 권한 변경 대신 로컬 개발 포트를 옮겨 해결했습니다.
호스트 API는 `http://127.0.0.1:18021`, DB는 `18022`, shadow DB는 `18020`,
Studio는 `18023`, 메일 확인은 `18024`입니다. 에뮬레이터 HTTP 예외도
debug의 `10.0.2.2:18021`만 허용합니다. DB 볼륨은 삭제하거나 초기화하지 않았습니다.

아래 수치는 M2 종료 시점의 역사적 기록입니다.

DB pgTAP 167개, API 계약 33개, 실제 로컬 Auth/API/RLS 통합 42개를 통과했습니다.
Android JVM 27개·기본 에뮬레이터 UI/Room 24개·기존 서버 연결 UI 1개·
M2 오프라인/재시작/충돌/로그아웃 UI 2단계를 통과했습니다.
Android Lint 오류는 0개·경고는 44개이며 버전 업데이트 안내,
`CredentialManagerSignInWithGoogle`, `KaptUsageInsteadOfKsp` 경고를 숨기지 않고 유지합니다.
Kapt의 언어 모드 하향 안내와 테스트 소스 처리 옵션 경고도 남아 있습니다.
코드는 `GoogleIdTokenCredential`의 타입 검사와 파싱을 사용하며 실제 공급자 교환은 별도 인수입니다.
계정 변경 중 요청 재전송의 세션 경계, 99개 상태에서 동시 저장 한도,
회원 간 조회 차단과 베타 승인 철회도 검사합니다.
Google 공급자 교환·실제 SNS 공유·S23 호환성·운영 배포는 이 결과에 포함하지 않습니다.
M2의 에뮬레이터 개발 검증과 실제 Google 로그인·실기기 인수는 별개입니다.
전체 베타 완료를 뜻하지 않습니다.

## M0 실기기 인수

에뮬레이터 검증은 S23의 실제 SNS 공유 검증을 대체하지 않습니다.

1. S23 USB 디버깅을 허용하고 `adb devices -l`에서 연결을 확인합니다.
2. `adb -s <기기번호> install -r android/app/build/outputs/apk/debug/app-debug.apk`로 설치합니다.
3. Threads·Instagram·네이버 블로그에서 각각 2건을 실제 공유합니다.
4. 전달 URL·텍스트와 복수 링크 선택을 확인하고 선택한 원문이 열리는지 확인합니다.
5. URL 붙여넣기, 이미지와 대상 항목의 명시적 선택, 화면 재생성 후 입력 유지를 확인합니다.
6. 앱/OS 버전·출처·전달 형식·성공/실패를 기록합니다. 개인정보가 담긴 URL과 텍스트는
   로그나 저장소에 올리지 않습니다.

2026-09-13 사용자 결정으로 개발 단계의 진입 검증은 에뮬레이터로 진행합니다.
M0는 단위 테스트 8개·Android 16 에뮬레이터 UI 테스트 7개와 APK 빌드를 통과했습니다.
S23의 실제 SNS 공유 인수는 후속 실기기 검증으로 보류하며 M1 착수를 막지 않습니다.
에뮬레이터 통과를 실제 SNS 앱·S23 호환성 검증 완료로 해석하지 않습니다.