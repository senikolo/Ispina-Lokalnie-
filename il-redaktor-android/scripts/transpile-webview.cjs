#!/usr/bin/env node
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const ts = require(path.resolve(__dirname, "../backend/node_modules/typescript"));

const [inputPath, outputPath] = process.argv.slice(2);
if (!inputPath || !outputPath) {
  throw new Error("Usage: node transpile-webview.cjs <input.html> <output.html>");
}

const html = fs.readFileSync(inputPath, "utf8");
const scriptBlocks = html.match(/<script\b[^>]*>[\s\S]*?<\/script>/gi) || [];
if (scriptBlocks.length !== 1) {
  throw new Error(`Expected exactly one inline UI script, found ${scriptBlocks.length}.`);
}

const originalBlock = scriptBlocks[0];
const openEnd = originalBlock.indexOf(">") + 1;
const closeStart = originalBlock.toLowerCase().lastIndexOf("</script>");
if (openEnd < 1 || closeStart < openEnd) throw new Error("Malformed inline UI script.");
const source = originalBlock.slice(openEnd, closeStart);

const compatibility = `
if (!Object.fromEntries) {
  Object.fromEntries = function (entries) {
    var result = {};
    for (var entry of entries) result[entry[0]] = entry[1];
    return result;
  };
}
if (!Array.prototype.flatMap) {
  Array.prototype.flatMap = function (callback, thisArg) {
    return Array.prototype.concat.apply([], Array.prototype.map.call(this, callback, thisArg));
  };
}
if (!String.prototype.matchAll) {
  String.prototype.matchAll = function (regexp) {
    var input = String(this);
    var flags = regexp.flags || "";
    if (flags.indexOf("g") < 0) flags += "g";
    var matcher = new RegExp(regexp.source, flags);
    var matches = [];
    var match;
    while ((match = matcher.exec(input)) !== null) {
      matches.push(match);
      if (match[0] === "") matcher.lastIndex++;
    }
    return matches[Symbol.iterator]();
  };
}
`;

const result = ts.transpileModule(compatibility + source, {
  compilerOptions: {
    allowJs: true,
    checkJs: false,
    target: ts.ScriptTarget.ES2018,
    module: ts.ModuleKind.None,
    sourceMap: false,
    inlineSourceMap: false
  },
  reportDiagnostics: true
});
const errors = (result.diagnostics || []).filter(diagnostic => diagnostic.category === ts.DiagnosticCategory.Error);
if (errors.length) {
  const details = errors.map(diagnostic => ts.flattenDiagnosticMessageText(diagnostic.messageText, "\n")).join("\n");
  throw new Error("Could not transpile WebView script to ES2018:\n" + details);
}

const generatedBlock = originalBlock.slice(0, openEnd) + result.outputText + originalBlock.slice(closeStart);
const generatedHtml = html.replace(originalBlock, generatedBlock);
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, generatedHtml);
