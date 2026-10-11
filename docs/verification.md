# 테스트와 검증 범위

[Link Vault 소개](../README.md) · [로컬 실행](development.md) · [설계](architecture.md) · [운영](operations.md)

## 개발 상태와 증거의 범위

기존 개발 기록에서는 주요 기능의 로컬 구현·검증, 실제 Google 로그인과
별도 테스트 회원의 재인증 기반 탈퇴를 확인했습니다.
실제 사용자 품질 평가와 운영 배포 인수는 미완료이며, 공개 출시·외부 베타 준비 완료를 뜻하지 않습니다.
이 상태는 [개발·검증 이력](development-record.md)의 요약이며 문서 개편 때 기능 시험을 다시 실행한 결과는 아닙니다.

## 개발 검증 명령

[개발 환경](development.md)을 준비한 뒤 저장소 루트의 Windows PowerShell에서 실행합니다.

```powershell
.\android\gradlew.bat -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
npm run test:api
npm run test:beta
npm run test:operations
npm run test:backend-start
npm run test:ci
```

에뮬레이터·기기 계측 시험과 로컬 DB 시험은 별도입니다.
**개인 자료 없는 전용 시험 기기·DB에서만 실행하세요.**

```powershell
.\android\gradlew.bat -p android :app:connectedDebugAndroidTest
npm run test:db
```

서버 연동 시험은 회원·항목을 만들고 정리하며, 일부 시험은 네트워크 차단·앱 강제 종료를 수행합니다.
실제 회원 자료가 있는 로컬 DB를 시험을 위해 비우지 마세요.
[통합 시험 순서와 안전 조건](development-record.md#m1m4-로컬-서버와-검증)을 먼저 확인하세요.

## 실제 인수 기록용 CSV

새 checkout에서 `npm ci`를 실행한 뒤, 아래 명령으로 **빈 양식 두 개**를 생성합니다.
출력 디렉터리는 미리 만든 저장소 밖의 전용 폴더여야 하며, 기존 파일은 덮어쓰지 않습니다.

```powershell
$evidence = Join-Path $HOME ("link-vault-evidence-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $evidence | Out-Null
npm run beta:templates -- --output-dir "$evidence"
```

- `device_capture_template.csv`: 실제 공유·저장 기록. Threads 8건, Instagram 8건,
  네이버 블로그 8건, 기타 공개 웹 6건을 기록합니다.
- `retrieval_tasks_template.csv`: 실제 기억 단서로 다시 찾는 과제 10개를 기록합니다.
- 헤더는 검사기의 `CAPTURE_HEADERS`·`RETRIEVAL_TASK_HEADERS`에서 생성합니다.
  URL·메모·검색어·계정 정보를 포함한 실제 기록은 계속 저장소 밖에 두고 커밋하지 마세요.

작성 후 같은 PowerShell 창에서 검사합니다.

```powershell
npm run beta:check -- --captures "$evidence/device_capture_template.csv" --tasks "$evidence/retrieval_tasks_template.csv"
```

헤더만 있는 빈 양식은 인수 자료가 아니므로 검사 **종료 코드 1이 정상**입니다.
생성 명령의 성공은 양식 생성만 뜻합니다. 작성된 CSV의 검사 통과도 실제 사용자 증거의
진위를 검증하거나 외부 베타·Google 인증 전체를 승인하는 결과가 아닙니다.
실행 날짜·기기/환경·앱 버전·`git rev-parse HEAD`·명령·결과·미실행 범위는
같은 외부 폴더에 기록하세요. 실제 Google 최초 로그인·재시작 복원·별도 빈 회원 탈퇴의
로컬 확인 기록과, 토큰 만료 갱신·자료가 있는 회원 탈퇴·운영 인증 인수는 구분합니다.

## README 화면의 출처

[보관함 화면](assets/library.png)과 [필터 화면](assets/filters.png)은 기존 QA에서
**합성 자료로 실제 Compose 컴포넌트를 렌더링한 화면**입니다.
실제 회원 자료나 SNS 앱 연동 성공의 증거가 아닙니다.
필터 화면의 `테스트 분류` 항목은 QA용 자료이며 일반 사용자 분류의 예시가 아닙니다.
