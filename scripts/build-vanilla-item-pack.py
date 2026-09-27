"""Extract the official 1.21.11 item resources, with a reproducible ZIP and verified source.

Usage: python scripts/build-vanilla-item-pack.py [downloaded-client.jar]
Textures/models remain Mojang/Microsoft assets; this file only packages required runtime resources.
"""
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from urllib.request import urlretrieve

ROOT = Path(__file__).resolve().parents[1]
CLIENT_SHA1 = "ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd"
URL = f"https://piston-data.mojang.com/v1/objects/{CLIENT_SHA1}/client.jar"
TARGET = ROOT / "app/src/main/assets/minecraft/vanilla-items-1.21.11.zip"


def main():
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "output/inventory-protocol/client-1.21.11.jar"
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        urlretrieve(URL, source)
    if hashlib.sha1(source.read_bytes()).hexdigest() != CLIENT_SHA1:
        raise ValueError("Official client SHA-1 mismatch")
    with zipfile.ZipFile(source) as client, zipfile.ZipFile(TARGET, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        files = {name: client.read(name) for name in sorted(client.namelist())
                 if any(name.startswith(f"assets/minecraft/{folder}/") for folder in ("items", "models", "textures", "atlases")) and not name.endswith("/")}
        files["pack.mcmeta"] = json.dumps({"pack": {"pack_format": 75, "description": "Minecraft 1.21.11 official item resources"}}).encode()
        files["SOURCE.json"] = json.dumps({"version": "1.21.11", "url": URL, "sha1": CLIENT_SHA1,
            "notice": "Minecraft assets copyright Mojang AB / Microsoft. Extracted from the official client."}).encode()
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(2025, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(entry, data)
    print(f"{len(files)} files; {TARGET.stat().st_size:,} bytes; SHA-256 {hashlib.sha256(TARGET.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
