# 개발 환경과 로컬 실행

[Link Vault 소개](../README.md) · [설계](architecture.md) · [검증](verification.md) · [운영](operations.md)

명령은 **저장소 루트의 Windows PowerShell** 기준입니다.
Linux/macOS에서는 `.\android\gradlew.bat` 대신 `./android/gradlew`를 사용하고 환경변수는 해당 셸 문법으로 설정합니다.

## 1. 개발 환경 준비

- Android Studio에서 `android/` 열기
- JDK 17, Android SDK Platform 35, Gradle 8.10.2 Wrapper / AGP 8.7.3
- `ANDROID_HOME` 또는 `android/local.properties`의 `sdk.dir`에 SDK 경로 설정
- 서버 실행: Node.js 24와 실행 중인 Docker Desktop
- Google 로그인 확인: Google Play 지원 에뮬레이터 또는 호환 기기와 실제 Google 계정

## 2. Google OAuth와 서버 설정

Google Cloud에서 웹 OAuth 클라이언트와 Android 클라이언트를 등록합니다.
Android 패키지는 `com.linkvault.app`이며 debug/release 서명은 각각 등록해야 합니다.

```powershell
.\android\gradlew.bat -p android :app:signingReport
npm ci
```

현재 `supabase/config.toml`의 Google provider는 활성화되어 있습니다.
서버용 `GOOGLE_WEB_CLIENT_ID`와 `GOOGLE_CLIENT_SECRET`을 gitignored 루트 `.env`에 설정하고,
Edge의 audience 설정은 `supabase/functions/.env`에 보관합니다.
실제 키·토큰·client secret은 출력하거나 커밋하지 않습니다. nonce 검증도 끄지 않습니다.

```powershell
npm run backend:start
npm run backend:migrate
npm run backend:configure-worker
```

위 명령은 로컬 Supabase를 시작하고 마이그레이션과 작업 실행기를 설정합니다.
`backend:start`는 Edge `/v1/health`의 DB 준비 상태까지 확인합니다.
기존 DB를 초기화하거나 적용된 마이그레이션 이력을 수정하지 마세요.

## 3. 앱 연결 설정과 빌드

앱은 환경변수 또는 Gradle 프로젝트 속성에서 다음 값을 읽습니다.
**Gradle은 루트 `.env`를 자동으로 읽지 않습니다.**

| 설정 | 값 |
| --- | --- |
| `SUPABASE_URL` | 로컬 에뮬레이터: `http://10.0.2.2:18021` |
| `SUPABASE_PUBLISHABLE_KEY` | 해당 로컬 프로젝트의 publishable/anon 키 |
| `GOOGLE_WEB_CLIENT_ID` | 서버와 같은 웹 OAuth client ID |

호스트에서의 API 주소는 `http://127.0.0.1:18021`입니다.
실기기 연결에는 별도 네트워크 구성이 필요하며, 에뮬레이터 주소를 그대로 사용하지 않습니다.
HTTP 예외는 debug의 로컬 에뮬레이터용이고 일반 배포 앱은 HTTPS만 허용합니다.
앱에 service_role·`sb_secret_`·Google client secret을 넣지 마세요.

```powershell
.\android\gradlew.bat -p android :app:assembleDebug
```

APK는 `android/app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다.
기기에 설치한 뒤 Google 로그인하고, 서버 관리자가 해당 회원 UUID를 `beta_members`에 승인하고
`approved_at`을 기록해야 보관함을 사용할 수 있습니다. 승인 대기는 오류가 아닙니다.

설정 없이도 APK는 빌드되지만 실제 로그인·서버 보관함은 사용할 수 없습니다.
가짜 로그인이나 로컬 전용 보관함으로 대체하지 않습니다.
[인증 설정 상세](development-record.md#실제-google-로그인-설정)를 참고하세요.

## 관련 문서

- [테스트 명령과 실제 인수 기록](verification.md)
- [배포·도메인·백업과 서버 이전](operations.md) — 로컬 실행과 구분합니다.
- [개발·검증 이력](development-record.md) — 과거 환경·수치는 각 기록 당시의 맥락입니다.
