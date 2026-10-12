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
test("deletion notice does not equate an email with authenticated deletion", () => {
  const html = readFileSync(
    resolve(root, "account-deletion/index.html"),
    "utf8",
  );
  assert.match(html, /Google 재인증/);
  assert.match(html, /메일 발송만으로 계정을 자동 삭제하지 않습니다/);
  assert.match(html, /72시간 이내를 목표/);
  assert.match(html, /최대 30일 순환을 목표/);
  assert.match(html, /Google 계정 자체를 삭제하는 절차는 아닙니다/);
  assert.doesNotMatch(html, /mailto:[^"]+\?subject=/);
  assert.match(
    html,
    /이메일을 발송하거나 개인 이메일 주소를 공개하지 않습니다/,
  );
});
test("privacy notice distinguishes conditional cleanup from a maximum retention promise", () => {
  const html = readFileSync(resolve(root, "privacy/index.html"), "utf8");
  assert.match(html, /내보내기 확인이 필요합니다/);
  assert.match(html, /확인되지 않으면 더 오래 보관될 수 있습니다/);
  assert.match(html, /진행 중인 계정 삭제 작업은 기간만으로 삭제하지 않습니다/);
  assert.match(html, /최대 보관 기간이 아닙니다/);
});
test("policy drafts retain recovery caveats and do not claim web deployment is enabled", () => {
  const privacy = readFileSync(resolve(root, "privacy/index.html"), "utf8");
  const deletion = readFileSync(
    resolve(root, "account-deletion/index.html"),
    "utf8",
  );
  assert.match(privacy, /마지막 검증 복구본/);
  assert.match(privacy, /예외 보존하고 운영 경고/);
  assert.match(privacy, /독립 복원 검증 증거의 등록은 아직 완료되지 않았습니다/);
  assert.match(deletion, /Google 재인증 기반 웹 탈퇴·진행 상태 조회/);
  assert.match(deletion, /공개 설정이 활성화된 경우에만 사용할 수 있습니다/);
  assert.match(deletion, /최종 삭제 요청 보내기/);
  assert.match(deletion, /삭제 완료로 판단하지 마세요/);
  assert.match(deletion, /이메일 발신 주소만으로 소유자를 판단하지 않습니다/);
});
