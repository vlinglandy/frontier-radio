#!/usr/bin/env python3
"""Prepare a verified Kokoro device-test fixture outside app data (which Gradle may clear)."""
import argparse
import hashlib
import os
import pathlib
import shutil
import subprocess
import tarfile
import urllib.request

URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"
SHA = "a1e94694776049035c4f2c6529f003aaece993c76aae9a78995831c3c4dcafc6"
SIZE = 147031220
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", default="build/voice-fixture")
parser.add_argument("--adb", default=shutil.which("adb"))
args = parser.parse_args()
if not args.adb:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = pathlib.Path("local.properties")
    if not sdk and local.exists():
        sdk = next((line.split("=", 1)[1] for line in local.read_text().splitlines() if line.startswith("sdk.dir=")), None)
    if sdk:
        args.adb = str(pathlib.Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
if not args.adb:
    parser.error("Set ANDROID_HOME or supply --adb /path/to/adb")
root = pathlib.Path(args.output).resolve()
root.mkdir(parents=True, exist_ok=True)
archive = root / "model.tar.bz2"
def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
if not archive.exists() or archive.stat().st_size != SIZE or checksum(archive) != SHA:
    print("Downloading official voice fixture (140 MB)…", flush=True)
    with urllib.request.urlopen(URL, timeout=60) as response, archive.open("wb") as output:
        if not response.url.startswith("https://"):
            raise ValueError("HTTPS download required")
        shutil.copyfileobj(response, output)
if archive.stat().st_size != SIZE or checksum(archive) != SHA:
    archive.unlink(missing_ok=True)
    raise ValueError("Model checksum mismatch; rerun to retry")
with tarfile.open(archive) as tar:
    members = tar.getmembers()
    if len(members) > 6000 or sum(m.size for m in members) > 700000000:
        raise ValueError("Invalid archive size")
    for member in members:
        target = (root / member.name).resolve()
        if root not in target.parents or not (member.isfile() or member.isdir()):
            raise ValueError("Unsafe archive member")
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with tar.extractfile(member) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
model = root / "kokoro-int8-multi-lang-v1_1"
(model / ".verified").write_text(SHA)
# Push contents rather than the directory, so this is idempotent for existing devices.
subprocess.run([args.adb, "shell", "mkdir", "-p", "/data/local/tmp/ai-radio-model"], check=True)
subprocess.run([args.adb, "push", str(model) + os.sep + ".", "/data/local/tmp/ai-radio-model/"], check=True)
print("Verified voice fixture is ready. Run connectedDebugAndroidTest.")
