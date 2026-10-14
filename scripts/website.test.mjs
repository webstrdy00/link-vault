import assert from "node:assert/strict";
import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, relative, resolve, sep } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../website/", import.meta.url));
const pages = [
  "index.html",
  "privacy/index.html",
  "account-deletion/index.html",
];
function textContent(html) {
  return html.replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim();
}
function sectionText(html, heading) {
  const section = [...html.matchAll(/<section\b[^>]*>([\s\S]*?)<\/section>/g)]
    .find((match) => heading.test(match[1]));
  assert.ok(section, `Missing section: ${heading}`);
  return textContent(section[1]);
}
for (const name of pages) {
  const file = resolve(root, name);
  const html = readFileSync(file, "utf8");
  test(`${name}: links resolve inside published site and contact uses public address`, () => {
    const links = [...html.matchAll(/\b(?:href|src)="([^"]+)"/g)].map((m) =>
      m[1]
    );
    assert.ok(links.length > 0);
    for (const link of links) {
      if (link.startsWith("mailto:")) {
        assert.equal(link.slice(7).split("?")[0], "support@linkfilebox.com");
        continue;
      }
      assert.doesNotMatch(
        link,
        /^(?:[a-z]+:|\/\/)/i,
        "Unexpected external dependency",
      );
      const [path, fragment] = link.split("#");
      let target = resolve(
        dirname(file),
        decodeURIComponent(path || name.split("/").at(-1)),
      );
      const rel = relative(root, target);
      assert.ok(rel !== ".." && !rel.startsWith(`..${sep}`));
      assert.ok(existsSync(target), `${name}: missing ${link}`);
      if (statSync(target).isDirectory()) {
        target = resolve(target, "index.html");
      }
      assert.ok(statSync(target).isFile());
      if (fragment) {
        assert.ok(readFileSync(target, "utf8").includes(`id="${fragment}"`));
      }
    }
  });
  test(`${name}: review gate and accessible contact guidance remain explicit`, () => {
    assert.match(html, /<html\s+lang="ko"/);
    assert.match(html, /name="viewport"/);
    assert.match(html, /name="robots"\s+content="noindex,nofollow"/);
    assert.equal([...html.matchAll(/<h1(?:\s|>)/g)].length, 1);
    assert.match(html, /<main\s+id="main"/);
    assert.match(html, /검토본/);
    assert.doesNotMatch(html, /수신\s*전용|발신 설정|발신·답장 서비스/);
    assert.doesNotMatch(html, /<iframe\b|<form\b|@gmail\.com|\bon[a-z]+\s*=/i);
    const scripts = [...html.matchAll(/<script\b[^>]*>[\s\S]*?<\/script>/gi)]
      .map((match) => match[0]);
    assert.deepEqual(
      scripts,
      name === "account-deletion/index.html"
        ? ['<script type="module" src="./account-deletion.js"></script>']
        : [],
      "Only the account deletion page may load its exact local module",
    );
  });
}
test("account deletion module allows only the user-triggered Google authentication loader, not tracking", () => {
  const source = readFileSync(
    resolve(root, "account-deletion/account-deletion.js"),
    "utf8",
  );
  const externalUrls = [...source.matchAll(/https:\/\/[A-Za-z0-9._/-]+/g)].map((
    match,
  ) => match[0]);
  assert.ok(externalUrls.length > 0);
  for (const url of externalUrls) {
    assert.ok(
      [
        "https://linkfilebox.com",
        "https://api.linkfilebox.com/functions/v1/library-api/v1/web-account",
        "https://accounts.google.com/gsi/client",
      ].includes(url),
      `Unexpected external dependency: ${url}`,
    );
  }
  assert.equal([...source.matchAll(/createElement\("script"\)/g)].length, 1);
  assert.match(source, /script\.src = GIS_URL;/);
  assert.match(
    source,
    /if \(!await controller\.start\(purpose\)\) return;[\s\S]*?await loadGoogleButton\(\)/,
  );
  assert.doesNotMatch(
    source,
    /\b(?:localStorage|sessionStorage|indexedDB|sendBeacon)\b|document\.cookie|console\.|googletagmanager|google-analytics|gtag\(/i,
  );
  assert.doesNotMatch(source, /\bimport\b|eval\s*\(|new Function\b/);
});
test("deletion notice requires authenticated intent, not an email or a new account", () => {
  const html = readFileSync(
    resolve(root, "account-deletion/index.html"),
    "utf8",
  );
  const text = textContent(html);
  const web = sectionText(html, /앱에 접근할 수 없을 때/);
  assert.match(web, /Google 재인증/);
  assert.match(web, /가입할 때 사용한 Google 계정/);
  assert.match(web, /신규 가입[^.]*만들지 않습니다/);
  assert.match(web, /메일 발송만으로[^.]*자동 삭제하지 않습니다/);
  assert.match(web, /결과 이메일[^.]*보내지 않습니다/);
  assert.match(text, /Google 계정 자체[^.]*아닙니다/);
  assert.match(text, /비밀번호·인증 코드·세션 토큰·신분증 사본/);
  assert.doesNotMatch(html, /mailto:[^"]+\?subject=/);
});
test("web deletion keeps opt-in consent and a separate final submission", () => {
  const html = readFileSync(
    resolve(root, "account-deletion/index.html"),
    "utf8",
  );
  const consent = html.match(/<input\b[^>]*id="account-consent"[^>]*>/)?.[0];
  assert.ok(consent);
  assert.match(consent, /type="checkbox"/);
  assert.match(consent, /aria-describedby="account-consent-note"/);
  assert.doesNotMatch(consent, /\bchecked(?:\s|=|>)/);
  assert.match(html, /id="account-consent-note"[^>]*>[\s\S]*?되돌릴 수 없습니다/);
  assert.match(html, /id="account-send"[^>]*\bhidden\b[^>]*\bdisabled\b/);
  assert.match(html, /id="account-start-status"[^>]*type="button"/);
  const web = sectionText(html, /앱에 접근할 수 없을 때/);
  assert.match(web, /인증 후 최종 삭제 요청 보내기[^.]*요청이 전송/);
  assert.match(web, /상태 조회만[^.]*삭제 동의가 필요하지 않습니다/);
});
test("web deletion distinguishes uncertain submission and in-progress work from completion", () => {
  const html = readFileSync(
    resolve(root, "account-deletion/index.html"),
    "utf8",
  );
  const web = sectionText(html, /앱에 접근할 수 없을 때/);
  assert.match(web, /응답이 끊기면[^.]*접수되었을 수도/);
  assert.match(web, /오류·접수·처리 중[^.]*완료가 아니/);
  assert.match(web, /새 Google 인증[^.]*진행 상태/);
  assert.match(web, /취소[^.]*요청을 철회하지 않습니다/);
  assert.match(html, /id="account-message"[^>]*role="status"[^>]*aria-live="polite"/);
  assert.match(html, /id="account-result"\s+hidden/);
});
test("retention targets are not maximums and unverified server snapshots remain preserved", () => {
  const privacy = readFileSync(resolve(root, "privacy/index.html"), "utf8");
  const deletion = readFileSync(
    resolve(root, "account-deletion/index.html"),
    "utf8",
  );
  const retention = sectionText(privacy, /보관과 삭제/);
  const scope = sectionText(deletion, /삭제 범위와 소요 시간/);
  for (const text of [retention, scope]) {
    assert.match(text, /72시간[^.]*목표/);
    assert.match(text, /30일 순환 목표/);
    assert.match(text, /최대 보관 기간[^.]*보장하지|최대 보관 기간이 아닙니다/);
    assert.match(text, /서버 밖 독립 복원 검증[^.]*확인/);
    assert.match(text, /최근 7개·30일 기준/);
    assert.match(text, /검증 증거가 없어[^.]*스냅샷을 모두 보존/);
  }
  assert.match(retention, /마지막 검증 복구본[^.]*삭제 원장[^.]*예외 보존[^.]*운영 경고/);
  assert.match(retention, /복원할 때도[^.]*삭제 요청[^.]*다시 적용/);
  assert.match(retention, /파일 생성·암호화[^.]*복원 검증이 되지는 않습니다/);
});
test("record cleanup and web status expiry are conditional, not deletion guarantees", () => {
  const html = readFileSync(resolve(root, "privacy/index.html"), "utf8");
  const retention = sectionText(html, /보관과 삭제/);
  const requests = sectionText(html, /요청 방법/);
  assert.match(retention, /삭제 완료 기한이 아닙니다/);
  assert.match(retention, /API 요청 기록: 7일/);
  assert.match(retention, /요청 빈도 제한 기록: 1일/);
  assert.match(retention, /첨부 정리 기록: 완료 후 30일/);
  assert.match(retention, /삭제 표식·완료된 계정 삭제 작업: 30일[^.]*내보내기 확인/);
  assert.match(retention, /확인되지 않으면 더 오래 보관/);
  assert.match(retention, /진행 중인 계정 삭제 작업[^.]*기간만으로 삭제하지 않습니다/);
  assert.match(requests, /해시값[^.]*삭제 작업/);
  assert.match(requests, /웹 재인증 토큰[^.]*서버에 저장하지 않습니다/);
  assert.match(requests, /완료 후 30일[^.]*조회 연결[^.]*정리 대상/);
  assert.match(requests, /진행 중인 작업[^.]*완료될 때까지 유지/);
  assert.match(requests, /조회 기록을 찾지 못[^.]*삭제 완료를 뜻하지 않습니다/);
});
test("policy remains canonical and unresolved policy details stay gated, not deployed features", () => {
  const privacy = readFileSync(resolve(root, "privacy/index.html"), "utf8");
  const unresolved = sectionText(privacy, /확정 전인 사항/);
  assert.match(textContent(privacy), /최신 안내의 기준[^.]*이 페이지/);
  assert.match(unresolved, /개인정보 담당자/);
  assert.match(unresolved, /본인 확인·권리 요청/);
  assert.match(unresolved, /운영 로그[^.]*수집 범위/);
  assert.match(unresolved, /국외 이전 근거[^.]*고지·동의/);
  assert.match(unresolved, /문의 메일[^.]*백업[^.]*보관·파기 기준/);
  assert.match(unresolved, /처리 근거·이용자 권리·분쟁 구제/);
  assert.match(unresolved, /시행일/);
  for (const name of pages) {
    const html = readFileSync(resolve(root, name), "utf8");
    assert.doesNotMatch(html, /웹 공개 설정과 실제 Google 연동 인수[^.]*완료되지/);
    assert.doesNotMatch(html, /공개 설정이 활성화된 경우에만/);
    assert.doesNotMatch(html, /기본 브랜치 반영 전/);
  }
});
