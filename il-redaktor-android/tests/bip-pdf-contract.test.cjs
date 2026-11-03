"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.join(__dirname, "../app/src/main");
const reader = fs.readFileSync(path.join(root, "java/com/ispinalokalnie/redaktor/BipPdfReader.java"), "utf8");
const search = fs.readFileSync(path.join(root, "java/com/ispinalokalnie/redaktor/FreeIspinaSearchClient.java"), "utf8");
const ui = fs.readFileSync(path.join(root, "assets/index.html"), "utf8");
const gradle = fs.readFileSync(path.join(__dirname, "../app/build.gradle"), "utf8");

test("BIP reader only upgrades official HTTP URLs and rejects untrusted hosts", () => {
  assert.match(reader, /private static String secureBipUrl/);
  assert.match(reader, /if \(url\.getUserInfo\(\) != null \|\| !isTrustedBipHost\(url\.getHost\(\)\)\) return ""/);
  assert.match(reader, /new URL\("https", url\.getHost\(\), -1, url\.getFile\(\)\)/);
  assert.match(reader, /private static boolean isTrustedBipHost/);
  assert.match(reader, /host\.equals\("bip\.malopolska\.pl"\)/);
  assert.match(reader, /host\.equals\("drwinia\.pl"\)/);
  assert.match(reader, /host\.endsWith\("\.gov\.pl"\)/);
});

test("every redirect is revalidated as public HTTPS before opening the next connection", () => {
  assert.match(reader, /connection\.setInstanceFollowRedirects\(false\)/);
  assert.match(reader, /current = new URL\(current, location\)/);
  assert.match(reader, /validatePublicBipUrl\(current\)/);
  assert.match(reader, /address\.isLoopbackAddress\(\)/);
  assert.match(reader, /address\.isSiteLocalAddress\(\)/);
  assert.match(reader, /address\.isLinkLocalAddress\(\)/);
  assert.match(reader, /redirects <= 4/);
});

test("PDF and OCR work locally with strict resource ceilings", () => {
  for (const limit of [
    "MAX_SOURCE_PAGES = 5", "MAX_DOCUMENTS = 4", "MAX_PDF_BYTES = 8 * 1024 * 1024",
    "MAX_PDF_PAGES = 120", "MAX_OCR_PAGES_PER_DOCUMENT = 24",
    "MAX_OCR_PAGES_PER_SEARCH = 40", "MAX_READ_MILLIS = 72_000L"
  ]) assert.ok(reader.includes(limit), "Missing reader limit: " + limit);
  assert.match(reader, /PDFTextStripper/);
  assert.match(reader, /PDFRenderer/);
  assert.match(reader, /TextRecognition\.getClient/);
  assert.match(reader, /recognize\(image, recognizer, control, deadline\)/);
  assert.match(reader, /if \(file != null && file\.exists\(\)\) file\.delete\(\)/);
  assert.match(gradle, /com\.tom-roush:pdfbox-android/);
  assert.match(gradle, /com\.google\.mlkit:text-recognition/);
});

test("unreadable, incomplete and successful PDF states remain distinguishable", () => {
  for (const status of ['"read"', '"partial"', '"ocr_incomplete"', '"no_readable_text"', '"unavailable"', '"source_unavailable"', '"no_pdf"'])
    assert.ok(reader.includes(status), "Reader does not report " + status);
  assert.match(search, /"no_readable_text"\.equals\(documentStatus\)/);
  assert.match(search, /\.put\("partialResults", failed > 0 \|\| skipped > 0 \|\| documentPartial\)/);
  assert.match(ui, /const renderDocuments\s*=/);
  assert.match(ui, /ODCZYT MASZYNOWY/);
});

test("OCR text is not marked as fact-checked and editorial handoff requires locality and topic match", () => {
  assert.match(reader, /Tekst odczytany automatycznie\. Wymaga sprawdzenia w oryginale\./);
  assert.match(search, /"sourcesAreFactChecked", false/);
  assert.match(search, /"sourceReviewStatus", "DO_SPRAWDZENIA"/);
  assert.match(ui, /document\.bodyMentionsLocality && document\.matchesTopic/);
  assert.match(ui, /niczego nie publikuję/);
});
