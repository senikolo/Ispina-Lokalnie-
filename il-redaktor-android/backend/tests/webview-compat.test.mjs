import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const projectDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const inputPath = path.join(projectDir, "app/src/main/assets/index.html");
const transpilerPath = path.join(projectDir, "scripts/transpile-webview.cjs");

test("WebView build transpiles modern syntax and provides required legacy polyfills", () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "il-webview-compat-"));
  try {
    const outputPath = path.join(tmpDir, "index-android.html");
    const result = spawnSync(process.execPath, [transpilerPath, inputPath, outputPath], { encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr || result.stdout);
    const html = fs.readFileSync(outputPath, "utf8");
    const match = html.match(/<script>([\s\S]*?)<\/script>/);
    assert.ok(match, "Generated HTML must retain the inline app script");
    assert.match(match[1], /Object\.fromEntries/);
    assert.match(match[1], /Array\.prototype\.flatMap/);
    assert.match(match[1], /String\.prototype\.matchAll/);
    const scanner = ts.createScanner(ts.ScriptTarget.ES2018, false, ts.LanguageVariant.Standard, match[1]);
    for (let token = scanner.scan(); token !== ts.SyntaxKind.EndOfFileToken; token = scanner.scan()) {
      assert.notEqual(token, ts.SyntaxKind.QuestionDotToken, "Optional chaining must be transpiled");
      assert.notEqual(token, ts.SyntaxKind.QuestionQuestionToken, "Nullish coalescing must be transpiled");
    }
    assert.doesNotThrow(() => new Function(match[1]), "Generated inline script must parse");
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
