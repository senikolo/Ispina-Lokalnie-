#!/usr/bin/env python3
"""Sign only the isolated test package using its separately pinned test key."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

from make_test_variant import PACKAGE, VERSION

HERE = Path(__file__).resolve().parent
ORIGINAL_CERT = "e17105f5c66beec08508ac2e7d60f9f27b85b80ef0f6e23183a70642c6dae8a3"


def sign(args):
    expected = json.loads((HERE / "test-certificate.json").read_text())["sha256"]
    if expected == ORIGINAL_CERT:
        raise ValueError("The test signer must not be the original UltraSmart 77 key")
    with zipfile.ZipFile(args.input) as apk:
        meta = json.loads(apk.read("assets/il-build.json"))
        if (meta["packageName"], meta["versionName"], meta["variant"]) != (PACKAGE, VERSION, "ULTRASMART-78-WIDEO-TEST"):
            raise ValueError("Refusing to sign anything except the separate TEST application")
    tools = Path(args.build_tools)
    info = subprocess.check_output([str(tools / "aapt"), "dump", "badging", args.input], text=True).splitlines()[0]
    if any(value not in info for value in (f"name='{PACKAGE}'", f"versionName='{VERSION}'", "versionCode='20349'")):
        raise ValueError("Android manifest does not match the separate TEST identity")
    if Path(args.output).resolve() in (Path(args.input).resolve(), Path(args.keystore).resolve()):
        raise ValueError("Output must not overwrite input APK or keystore")
    if not os.environ.get("IL_ULTRASMART78_TEST_KEYSTORE_PASS"):
        raise ValueError("Missing test keystore password environment variable")
    env = dict(os.environ)
    if not env.get("IL_ULTRASMART78_TEST_KEY_PASS"):
        env["IL_ULTRASMART78_TEST_KEY_PASS"] = env["IL_ULTRASMART78_TEST_KEYSTORE_PASS"]
    with tempfile.TemporaryDirectory() as tmp:
        cert, aligned = Path(tmp) / "cert.der", Path(tmp) / "aligned.apk"
        subprocess.run(["keytool", "-exportcert", "-keystore", args.keystore, "-alias", args.alias,
                        "-storepass:env", "IL_ULTRASMART78_TEST_KEYSTORE_PASS", "-file", str(cert)],
                       env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if hashlib.sha256(cert.read_bytes()).hexdigest() != expected:
            raise ValueError("Test keystore certificate mismatch; signing refused")
        subprocess.run([str(tools / "zipalign"), "-p", "-f", "4", args.input, str(aligned)], check=True)
        subprocess.run([str(tools / "apksigner"), "sign", "--ks", args.keystore, "--ks-key-alias", args.alias,
                        "--ks-pass", "env:IL_ULTRASMART78_TEST_KEYSTORE_PASS",
                        "--key-pass", "env:IL_ULTRASMART78_TEST_KEY_PASS",
                        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                        "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
                        "--out", args.output, str(aligned)], env=env, check=True)
    report = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", args.output], text=True)
    certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", report)
    if certs != [expected]:
        Path(args.output).unlink(missing_ok=True)
        raise ValueError("Signed test APK has unexpected signer")
    subprocess.run([str(tools / "zipalign"), "-c", "4", args.output], check=True)
    Path(args.output + ".verify.txt").write_text(report)
    print("Signed and verified TEST APK:", PACKAGE, VERSION)
    print("TEST certificate SHA-256:", expected)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("input")
    p.add_argument("output")
    p.add_argument("--build-tools", required=True)
    p.add_argument("--keystore", required=True)
    p.add_argument("--alias", default="ultrasmart78-test")
    sign(p.parse_args())
