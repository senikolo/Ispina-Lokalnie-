#!/usr/bin/env python3
"""Informational source checks: codecs and bitrate on public direct URLs.

Failures are printed, not interpreted as proof that a stream is permanently down.
Does not relay, rebroadcast or store copyrighted audio.
"""
import concurrent.futures
import subprocess
from pathlib import Path

root = Path("assets/ilradio_k3.koplugin")
entries = []
for line in (root / "streams.tsv").read_text(encoding="utf-8").splitlines():
    cols = line.split("\t")
    if len(cols) >= 4 and cols[0].startswith("SomaFM "):
        entries.append((cols[0], cols[1], int(cols[3])))

entries += [
    ("Radio Kraków Tarnów (existing)", "http://stream3.nadaje.com:9118/radiokrakow", 96),
    ("Radio Kraków Nowy Sącz (existing)", "http://stream3.nadaje.com:9120/radiokrakow", 96),
]

def probe(entry):
    name, url, expected = entry
    args = ["ffprobe", "-hide_banner", "-loglevel", "error",
            "-rw_timeout", "5500000", "-analyzeduration", "1200000",
            "-probesize", "131072", "-show_entries",
            "stream=codec_name,bit_rate:format=format_name,bit_rate",
            "-of", "default=noprint_wrappers=1", url]
    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=13)
        output = proc.stdout.strip().replace("\n", ", ")
        error = proc.stderr.strip().replace("\n", " ")[:150]
        print(f"{name} | {url} | expected={expected}k | exit={proc.returncode} | {output} | {error}", flush=True)
        return proc.returncode == 0
    except subprocess.TimeoutExpired:
        print(f"{name} | {url} | TIMEOUT after 13s", flush=True)
        return False

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    results = list(pool.map(probe, entries))
print(f"Stream probe result: {sum(results)}/{len(results)} URLs decoded by ffprobe")
