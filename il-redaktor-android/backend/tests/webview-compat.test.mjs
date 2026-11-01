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
    const source = ts.createSourceFile("index-android.js", match[1], ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
    assert.equal(source.parseDiagnostics.length, 0, "Generated script must have valid JavaScript syntax");
    const unsupported = [];
    function inspect(node) {
      if ((ts.isPropertyAccessExpression(node) || ts.isElementAccessExpression(node) || ts.isCallExpression(node)) && node.questionDotToken) {
        unsupported.push(node);
      }
      if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.QuestionQuestionToken) {
        unsupported.push(node);
      }
      ts.forEachChild(node, inspect);
    }
    inspect(source);
    assert.equal(unsupported.length, 0, "Generated executable code must not contain optional chaining or nullish coalescing: " + unsupported.map(node => node.getText(source)).join(", "));
    assert.doesNotThrow(() => new Function(match[1]), "Generated inline script must parse");
  } finally {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
