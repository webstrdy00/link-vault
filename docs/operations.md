# 배포와 운영

[Link Vault 소개](../README.md) · [로컬 실행](development.md) · [설계](architecture.md) · [검증](verification.md)

이 문서는 기존 README의 배포·운영 안내를 분리한 것입니다.
도메인·설정에 관한 설명은 문서화된 구성 기준이며, 이번 문서 개편에서 서버나 DNS의 현재 상태를 재검증한 결과가 아닙니다.
자동화 소스나 빌드의 존재는 운영 인수·공개 출시 완료를 뜻하지 않습니다.

## 배포 자동화

- PR에서는 [CI](../.github/workflows/ci.yml)와 [DB 계약 시험](../.github/workflows/database-ci.yml)을 실행합니다.
  병합 순서는 **feature 브랜치 → develop → 검증 → main**입니다.
  `develop` push는 통합 검증만 수행하고, `main` push는 검증 후
  `production` Environment의 담당자 승인을 기다립니다. 운영 배포와 서명 릴리스는 `main`만 허용합니다.
- [서버 배포](../.github/workflows/deploy.yml)는 별도 SSH 키와 강제 명령 계정만 사용합니다.
  API·마이그레이션만 전달하며, 서버의 Compose·비밀값·배포 수신기는 자동으로 덮어쓰지 않습니다.
  적용된 마이그레이션 변경은 거부하고, 신규 마이그레이션 전에 암호화 백업을 생성합니다.
  API 전환 실패 시 이전 API를 복구하지만 **커밋된 DB 변경은 자동으로 되돌리지 않습니다.**
  비밀값 없는 [비공개 Compose overlay](../deployment/compose.private.yml)와
  [제한 계정 설치기](../scripts/install-server-automation.py)는 새 서버 구성을 위한 소스입니다.
- [Android 배포 빌드](../.github/workflows/android-release.yml)는 수동 실행·별도 승인 대상입니다.
  서명키 네 항목과 HTTPS 서버 주소·공개 API 키·Google 웹 Client ID가 없으면 배포 빌드를 거부합니다.
  APK/AAB 생성은 스토어 게시나 실제 계정 인수 완료를 뜻하지 않습니다.

## 운영 도메인

앱 API 주소는 `https://api.linkfilebox.com`입니다. Cloudflare의 프록시 A 레코드는
운영 서버를 가리키도록 구성하고 SSL/TLS는 Full (strict)를 사용합니다.
[공개 gateway overlay](../deployment/compose.public.yml)는 비공개 overlay 다음에 적용하고,
[Caddyfile](../deployment/Caddyfile)은 스택의 `volumes/caddy/Caddyfile`에 설치합니다.
80/443만 추가로 열며, Supabase gateway 8000은 계속 loopback에 바인딩합니다.
인증·앱 API·Storage object 경로만 전달하고 Studio, REST, 내부 작업 경로는 404로 차단합니다.
Caddy 인증서 자동 갱신 상태와 공개 접근 경계를 이전 후에도 확인해야 합니다.

서버 `.env`의 `SUPABASE_PUBLIC_URL=https://api.linkfilebox.com`,
`API_EXTERNAL_URL=https://api.linkfilebox.com/auth/v1`,
`SITE_URL=https://linkfilebox.com`과 Google 웹 client ID/secret을 설정합니다.
Google Console redirect는 `https://api.linkfilebox.com/auth/v1/callback`입니다.
`GOOGLE_WEB_CLIENT_ID`는 Auth와 Edge Functions에 같은 값으로 전달해야 합니다.
공개 overlay의 `functions.environment` 설정이 빠지면 Google 재인증 기반 계정 탈퇴를 사용할 수 없습니다.
Google client secret은 Auth에만 전달하며 Functions나 앱에 추가하지 않습니다.
인증 컨테이너 재생성 후 gateway upstream이 503이면 Envoy를 재시작하고 외부 health를 검증합니다.
신규 가입은 Google 프로젝트·Android 서명 인증서·실제 계정 인수가 끝날 때까지 비활성화합니다.
루트 도메인의 소개 페이지·개인정보 안내는 별도이며 API 연결만으로 제공되지 않습니다.
`volumes/caddy`의 인증서·설정은 기존 전체 스택 암호화 백업에 포함됩니다.

## 백업과 서버 이전

[백업 명령](../scripts/server-backup.py)은 DB dump·역할·Storage·Vault 키·서버 설정·배포 소스를
`age` 공개 수신자로 암호화합니다. 일관된 파일·DB 상태를 위해 캡처 중 앱 서비스를 잠시 중지하고
종료 시 원래 실행 상태로 복구합니다. 운영자는 이 중단 시간을 고려해야 합니다.
서버에는 공개 수신자만 두고, 복호화용 개인 키는 별도 보관합니다.
Git 저장소와 앱 서명·접속키 복구 묶음은 먼저 별도로 암호화한 `recovery-capsules/` 형태로
서버 스냅샷에 포함할 수 있습니다. 이 묶음에도 백업 복호화용 개인 키는 포함하지 않습니다.

- 서버에서는 완료된 암호화 스냅샷 **최근 7개**를 보존합니다.
- [외부 백업 workflow](../.github/workflows/backup.yml)는 암호화 파일만 GitHub artifact에 **30일** 보관합니다.
  GitHub 예약 실행은 **기본 브랜치에 workflow가 병합된 뒤** 동작합니다.
  기본 브랜치 반영 전에도 서버 timer와 push로 시작된 백업은 별개로 동작합니다.
- 백업 실패 또는 서버 만료 14일 이내에는 저장소 담당자에게 할당된 이슈를 생성합니다.
  이메일·모바일 수신은 담당자의 GitHub 알림 설정에 따릅니다.
- GitHub artifact는 영구 보관소가 아닙니다. 필요한 복구 지점은 만료 전에 별도 장기 저장소로 옮기고,
  개인 복호화 키는 백업 파일과 다른 계정·오프라인 매체에 보관하세요.

이전 시에는 새 서버에서 아카이브의 manifest에 기록된 이미지 digest를 준비하고, 외부에서 복호화한
Vault 키·DB 역할·DB dump·Storage·Compose 설정·소스를 복원합니다.
아카이브의 복구 순서를 따르고, 신규 환경에서 Vault 복호화·API·Storage·예약 작업을 검증한 뒤
DNS와 앱 연결을 전환하세요. 기존 서버는 복구 검증과 최종 데이터 동기화 전에 폐기하지 않습니다.
Android 서명키는 앱 업데이트의 동일성을 결정하므로 서버 이전과 무관하게 계속 보존해야 합니다.
`secrets/`, `.env*`, 서명 저장소, 복호화 키는 Git에 추가하지 마세요.

로컬 백업 리허설은 운영 장애 복원을 대신하지 않습니다.
[로컬 리허설 기록](development-record.md#m5-로컬-운영백업-리허설)과 운영 환경의 인수 증거를 구분하세요.
