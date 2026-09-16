# link-vault

Android 우선 관심 정보 보관함. M0 공유 입력, M1 회원·서버 보관함,
M2 오프라인 대기열·캐시·제목/메모 편집을 구현했습니다.
M3 검색·분류·재적용과 검색 단서 안내는 로컬 서버·에뮬레이터 검증을 통과했습니다.
M4의 제한된 네이버 메타정보, 이미지 1장 첨부·교체·삭제, 기기 OCR도
로컬 서버·에뮬레이터 검증을 통과했습니다.
M5의 서버 항목 삭제, 계정 탈퇴, 정리 작업, 암호화 백업과 격리 복원 리허설은
로컬 구현·인수를 마쳤습니다.
Google 로그인 코드는 연결했지만 실제 OAuth 공급자 교환은 설정 후 검증이 필요합니다.

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
이미지 예약·완료·OCR 재시도와 인라인 이미지 DELETE, 항목 DELETE,
계정 삭제 challenge·접수입니다.
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
  이 내용은 M4 당시 경계입니다. M5에서는 Room schema v4와 항목 삭제 묘비,
  서버 항목 삭제·계정 삭제를 추가했습니다.

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

### M5 삭제·운영 계약

- `DELETE /items/{id}`는 `expected_version`을 받아 원문·메모·검색 파생값·OCR을
  트랜잭션에서 즉시 지우고 일반 작업을 취소하며 `202 state=deleting`을 반환합니다.
  같은 회원·같은 요청 UUID·같은 본문의 정확한 재전송은 같은 202 영수증이고,
  삭제 후 새 요청 UUID로 다시 삭제하면 404입니다. 보존 중인 생성·편집 요청을
  재전송하면 410 `ITEM_DELETED`이며 항목을 되살리지 않습니다.
- 파일 객체는 202 전에 없어졌다는 뜻이 아닙니다. DB 접근과 내용은 즉시 차단하지만
  Storage 정리는 lease로 후속 실행하며, 늦은 worker는 lease token·묘비를 확인해야 합니다.
  Room schema v4의 회원별 삭제 묘비는 늦은 목록·상세·검색·첨부 응답과 캐시가 삭제한
  항목을 다시 표시하지 못하게 하고, 보존한 삭제 영수증 외 해당 항목의 대기 요청을 제거합니다.
- 계정 삭제 challenge는 회원·`account_delete` 목적·nonce hash에 묶인 5분 일회성입니다.
  Android는 Credential Manager에서 자동 선택을 끈 새 Google 증명을 요청합니다.
  서버는 `jose`로 Google 공개 키의 RS256 서명, issuer, audience, `exp`·`iat`,
  nonce와 `sub`를 검증하고, GoTrue에 연결된 Google 서버 identity의 subject와 대조합니다.
  토큰이나 이메일 같은 클라이언트 주장을 계정 identity로 신뢰하지 않습니다.
- 요청 UUID는 회원 UUID와 함께 private claim으로 원문 UUID 그대로 고정합니다.
  일반 API·challenge의 route·본문 hash는 7일, 접수된 계정 삭제 claim은 30일 보존합니다.
  Google ID 토큰 원문은 DB·Room에 저장하지 않습니다. 적용된 migration
  `020`은 만료 challenge를 생성 시점부터 7일 보존하되 재전송으로 5분을 연장하지 않고,
  Auth 행은 `NO KEY UPDATE`, claim 확인은 `KEY SHARE`, 만료 claim 정리는
  `SKIP LOCKED`로 동시 실행을 직렬화합니다.
- 계정 삭제 202는 물리 정리나 기기 삭제 완료가 아닙니다. 서버 작업은 Storage 부재 확인 →
  업무 데이터 purge → Auth 사용자 삭제 → lease가 유효한 최종화 순서입니다.
  Android는 `SessionBoundary` 아래 persistent `data_owner`를 확인하고 접수된 회원 A의
  세션 owner가 null이어도 로컬 소유자가 A이면 기기 자료 정리를 재시도하며,
  현재 세션이나 로컬 소유자가 B이면 B 자료를 지우지 않습니다.
  응답 유실 뒤 401이나 계정 상태 확인 불가를 ‘삭제 시작 안 됨’으로 바꾸지 않고
  결과 미확인 상태에서 기존 요청의 복구 확인만 제공합니다.
- 첫 버전에는 휴지통·삭제 취소·사용자 export·운영자 관리 UI가 없습니다.

### M5 로컬 운영·백업 리허설

다음 명령은 고정된 로컬 Supabase 프로젝트 전용입니다.

```powershell
npm run operations:check
npm run operations:run
npm run backup:local -- snapshot --output C:\secure\link-vault-snapshot.bin
npm run backup:local -- ledger --output C:\secure\link-vault-ledger.bin
npm run restore:verify -- --backup C:\secure\link-vault-snapshot.bin --ledger C:\secure\link-vault-ledger.bin
```

`operations:check`는 상태만 확인하고 `operations:run`은 한 배치 실행 후 같은 검사를 합니다.
정리 cron은 5분마다 최대 10건을 dispatch합니다. 출력은 pending/retry/overdue, 만료 lease,
사용량 불일치, 미추적 Storage 객체, ledger export와 최근 dispatch 같은 집계·고정 경고뿐이며
경고가 하나라도 있으면 명령도 실패 상태로 끝납니다. URL·제목·검색어·메모·OCR·이미지·
토큰·원문 오류를 출력하지 않습니다. 72시간은 overdue 경보를 내는 운영 목표이지
물리 삭제 완료 보증이 아닙니다.

현재 실제 로컬 `operations:run`과 `operations:check`는 둘 다 exit 1이며 경고는
`DELETION_LEDGER_NOT_EXPORTED` 하나뿐입니다. pending/retry/overdue, 사용량 불일치,
만료 lease와 미추적 객체는 모두 0이고 maintenance schedule은 true, 최근 cron은
fresh입니다.
통합 fixture의 암호화 archive와 ACK export는 시험 소유 cleanup에서 제거했으므로,
이는 실제 운영 archive·키 보관이 아직 준비되지 않았다는 예상된 fail-closed 경고입니다.
CLI가 정상 exit 0이거나 경고 없이 healthy하다고 해석하지 않습니다.

백업·복원 명령 전에는 **정확히 하나**의 환경변수
`LINK_VAULT_BACKUP_PASSWORD`(UTF-8 12바이트 이상) 또는
`LINK_VAULT_BACKUP_KEY_BASE64`(32바이트 키의 canonical Base64)를 프로세스 환경에
설정합니다. 값은 명령 인자·출력·저장소에 넣지 않습니다. 스크립트는 `.env`를 자동으로
읽지 않으며, 별도 도구로 쓰는 `.env`는 민감 파일로 취급해 커밋하지 않습니다.
snapshot은 실제 source DB의 PostgreSQL custom dump와 실제 private 활성 파일을
AES-256-GCM으로 함께 봉인합니다.
삭제 ledger는 내용 원문 없는 별도 암호화 파일로 frozen export 전체를 검증한 뒤에만 ACK합니다.
두 출력은 저장소 밖에 둡니다.

`restore:verify`는 **로컬 복원 리허설 전용**이며 운영 장애 복원 명령이 아닙니다.
복원 전후 살아 있는 source DB에서 database identity·DB clock·최신 ledger sequence를 읽고,
ledger가 5분보다 오래됐거나 snapshot이 30일보다 오래됐거나 source를 읽지 못하면 닫힌 채
실패합니다. source와 같은 immutable image로 무작위 token label의 새 Docker container를
`--network none`, 공개 port 없음으로 만들고, 새 `link_vault_restore` DB를 `template0`에서
생성합니다. source DB나 bootstrap `postgres` DB를 drop하지 않습니다.
인증된 확장 이름·버전 inventory를 먼저 구성하고 `cron.database_name`을 새 DB로 설정해
소유권을 확인한 container만 재시작한 뒤, 실제 `pg_restore`의 ACL/RLS를 포함해 복원합니다.
삭제 ledger를 DB에 먼저 재생한 다음 살아남은 파일만 추출하고 DB·Storage·사용량을 audit합니다.
container 소유권/정리 확인이 실패하면 마운트된 임시 자료를 보존한 채 실패하며 verified
영수증을 내지 않습니다. 알 수 없는 파일을 임의로 지우는 운영 cleanup 도구가 아닙니다.

암호화 archive의 실제 30일 순환·물리 보관, offsite key custody, 운영 cron·경보 연결,
운영 복원 절차는 별도 운영 전제입니다. 실제 Google OAuth 공급자 교환, 사용자 대상 OAuth,
S23·실제 SNS도 이 로컬 도구의 검증 범위가 아닙니다.

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

2026-09-16 현재 M5 구현 검증 결과:

- 최종 로컬 audit 1725에서 migration `020`까지 DB 15파일·685개, API 200개,
  Node 운영·암호화·cleanup 24개, 실제 Auth/API/RLS 42개, 검색 80개,
  enrichment 69개, 삭제 38개, 동시성 3개와 백업/복원 12개가 통과했습니다.
  적용된 `001`~`020`은 이력을 다시 쓰지 않는 immutable migration입니다.
  항목만 삭제한 A 계정은 유지되고, B의 PNG·OCR은
  복원되며, 삭제한 C 계정은 복원되지 않았습니다. 오래된 ledger는 거부하고 새 ledger는
  복원했으며 source DB가 바뀌지 않은 것도 확인했습니다.
- Deno fmt 23파일·lint 20파일·check가 통과했습니다.
- 최종 Android 결과 1730에서 JVM XML 9 suite·98개가 실패·오류·skip 0,
  기본 기기 54개, Lint 오류 0·경고 48개와 debug APK 빌드가 통과했습니다.
- 실제 M5 에뮬레이터는 immutable offline DELETE의 동일 UUID 재연결, 충돌 검토 뒤
  새 UUID, 묘비의 늦은 cache 차단, 실제 queued PNG+OCR purge, cold restore의 typed
  receipt, 화면 recreation, Google 설정 누락, 공급자 장애 시 계정 보존을 모두 통과했습니다.
  파일 purge 뒤 Auth clear가 실패하면 typed `AccountAccess.Deleting` receipt의
  `localDataCleared=false`를 유지하고, 정확한 `acceptanceGeneration`과
  `presentationGeneration`으로 B 계정 fallback·개인정보 노출을 막는 최종 경계도
  확인했습니다.
- 현재 소스의 실제 M1은 1735, M2·M3·M4 순차 회귀는 마지막
  `LibraryViewModel` logout observer 수정 뒤 1728에서 모두 통과했습니다.
- lifecycle fixture는 실제 recreate/close 전에 non-private `ACTION_MAIN`을 복원합니다.
  제품 logout intent sanitization은 변경하지 않았습니다.
- 로컬 활성 backend의 100개 항목·30회 표본 p95는 검색 85.4ms,
  저장+색인 90.1ms였습니다.
  운영 배포 성능이나 상한이 아닙니다.
- Edge 70은 APPROVE, native 71은 최종 CLEAR/APPROVE,
  SQL `020` 72와 restore cleanup 74는 CLEAR입니다.

따라서 M5의 **로컬 구현·인수는 완료**했습니다. 변경은 커밋·push하지 않았고 M6는
시작하지 않았습니다. 실제 Google OAuth 공급자 교환,
S23·실제 SNS, 운영 배포·스케줄·경보, 실제 운영 archive·키 보관·offsite custody와
30일 물리 purge도 보류입니다.

아래는 2026-09-16 M4 종료 당시의 역사적 로컬 구현 인수 결과입니다.

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

당시 M4의 **로컬 구현·검증 완료**는 M5 서버 항목/계정 삭제·운영, M6, 전체 베타,
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

## 개인정보·삭제 안내 초안

아래는 M5 개발용 안내 초안이며 공개 서비스의 확정 처리방침이나 법률 검토 결과가 아닙니다.
운영자 연락처, 실제 서비스 지역·처리자·국외 이전, 공급자 보존 조건은 공개 전에 확정해야 합니다.

- Google 계정은 회원 인증에 사용합니다. URL·제목·메모·직접 선택한 이미지와 OCR 텍스트는
  해당 회원의 비공개 보관함과 검색·분류 제공에 사용합니다.
- OCR은 번들된 ML Kit로 기기에서 실행합니다. 보관을 선택한 이미지와 인식 결과는
  Supabase의 비공개 저장소·DB로 전송합니다. 사진 전체나 SNS 계정을 자동 수집하지 않습니다.
- 네이버 메타정보 요청은 허용된 호스트의 제한된 공개 정보만 대상으로 합니다.
  생성형 AI 호출, 광고 SDK, 개인 자료의 광고 타기팅은 첫 베타에 포함하지 않습니다.
- 일반 진단에는 작업 종류·요청 ID·고정 결과 코드·시간만 사용하며
  URL·검색어·메모·OCR·이미지·인증 토큰을 기록하지 않습니다.
  자체 진단 정보의 보존 목표는 최대 7일이며 공급자 로그의 실제 보존 조건과는 별개입니다.
- 기기 대기 입력은 최대 24시간 자동 재시도 대상으로 보관합니다.
  로그아웃·계정 변경 시 관련 기기 자료를 정리하며 사용자의 사진 원본을 삭제하지 않습니다.
- 서버 항목 삭제가 수락되면 조회에서 즉시 제외하고 파일·관련 자료는 후속 정리합니다.
  탈퇴는 Google 재인증 후 접근을 차단하고 파일, 업무 데이터, Auth 계정 순서로 정리하는 계약입니다.
  최초 삭제 요청에 사용한 Google ID 토큰은 Room 대기열에 보관하지 않습니다.
- 운영 저장소 정리는 72시간 내 완료를 목표로 하지만 휴면·장애를 포함한 운영 검증 전에는
  확정 기한으로 보증하지 않습니다. 휴지통·삭제 취소 기능은 제공 범위에 없습니다.
- 일반 API 멱등 기록은 7일, 계정 삭제 claim은 30일 보존합니다.
  내용 없는 서버 삭제 묘비·복원 검사와 운영자 백업은 최대 30일을
  기준으로 관리합니다. 백업의 모든 사본까지 즉시 지워졌다고 안내하지 않습니다.
  별도로 암호화한 삭제 기록을 복원본에 적용하고 파일·사용량을 검증하기 전에는 공개하지 않습니다.
- 이 안내의 M5 삭제 계약은 위 최종 로컬 인수를 통과했습니다. 이는 실제 운영 archive,
  key custody·offsite 보관, 30일 물리 disposal이나 운영 배포 인수를 대신하지 않습니다.

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