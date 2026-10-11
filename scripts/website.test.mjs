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
    assert.doesNotMatch(html, /<script\b|<iframe\b|<form\b|@gmail\.com/i);
  });
}
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
});
