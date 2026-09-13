# link-vault

Android 우선 관심 정보 보관함. M0 공유 입력, M1 회원·서버 보관함,
M2 오프라인 대기열·캐시·제목/메모 편집을 구현했습니다.
Google 로그인 코드는 연결했지만 실제 OAuth 공급자 교환은 설정 후 검증이 필요합니다.
검색·자동 분석 실행기·첨부·서버 항목 삭제/탈퇴는 아직 제공하지 않습니다.

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
이미지 단독 공유는 URL 입력으로 안내하며 이미지 복사·첨부는 아직 수행하지 않습니다.
기본 런처 아이콘은 개발용입니다.
의존성은 SDK 35/JDK 17의 검증 조합으로 고정했으며 최신 버전 알림을 숨기지 않습니다.

## M1·M2 로컬 서버와 검증

Node.js와 Docker Desktop이 필요합니다. 빈 로컬 테스트 환경에서:

```powershell
npm ci
npm run backend:start
npm run backend:migrate
npm run test:db
npm run test:api
npm run test:integration
npm run test:emulator-integration
npm run test:offline-integration
```

두 에뮬레이터 명령은 실행 중인 Android 에뮬레이터를 선택합니다.
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
POST/GET `/items`, GET/PATCH `/items/{id}`. 목록은 limit/offset만 지원합니다.
승인된 회원만 접근하고 새 항목은 100개·분당 10건 제한을 적용합니다.
재전송·중복 URL은 원래 메모를 덮어쓰지 않습니다.
중복 키는 호스트 대소문자·기본 포트만 정규화하고 경로·쿼리·fragment를 유지합니다.

조회·bootstrap은 호출자 JWT의 RPC를 사용합니다. 생성·편집은 GoTrue에서 검증한 회원 ID를
서버 전용 RPC에 전달하고 SQL에서 승인·소유권·한도를 재검사합니다.
일반 클라이언트는 테이블과 생성·편집 RPC에 직접 쓰지 못합니다.
원본·검색 파생 데이터·대기 작업·사용량·멱등 기록을 같은 트랜잭션에 저장합니다.
대기 작업의 메타/분류 실행기는 M3/M4 범위이며 현재 자동 처리 완료를 표시하지 않습니다.

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
- 분류 선택 UI·분류 캐시는 M3 범위입니다. M2 편집 화면은 분류를 변경하지 않습니다.

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
- 로컬 에뮬레이터의 앱 URL은 `http://10.0.2.2:54321`입니다.
  일반 배포 앱은 HTTPS만 허용하고 이 HTTP 예외는 debug에만 존재합니다.
- 앱에는 publishable/anon 키만 넣습니다. `sb_secret_` 또는 service_role 키를
  앱 설정으로 전달하면 빌드를 거부합니다. Google client secret도 앱에 넣지 않습니다.
- 로그인한 회원의 UUID를 서버 관리자가 `beta_members`에 승인하고 `approved_at`을 기록합니다.
  최대 6명이며 미승인 상태에서는 회원 화면에 승인 대기가 표시됩니다.
- 설정이 없으면 명확한 설정 안내만 표시하며 가짜 로그인·로컬 보관함으로 대체하지 않습니다.

### 확인된 범위

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
5. URL 붙여넣기, URL 없는 이미지 공유 안내, 화면 재생성 후 입력 유지를 확인합니다.
6. 앱/OS 버전·출처·전달 형식·성공/실패를 기록합니다. 개인정보가 담긴 URL과 텍스트는
   로그나 저장소에 올리지 않습니다.

2026-09-13 사용자 결정으로 개발 단계의 진입 검증은 에뮬레이터로 진행합니다.
M0는 단위 테스트 8개·Android 16 에뮬레이터 UI 테스트 7개와 APK 빌드를 통과했습니다.
S23의 실제 SNS 공유 인수는 후속 실기기 검증으로 보류하며 M1 착수를 막지 않습니다.
에뮬레이터 통과를 실제 SNS 앱·S23 호환성 검증 완료로 해석하지 않습니다.