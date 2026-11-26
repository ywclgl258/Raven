#!/usr/bin/env python3
"""Atomically publish one signed Raven APK to the Nginx update channel."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import uuid
import zipfile

MIRROR_RELEASE_ID_BASE = 9_000_000


def release_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if separator:
            values[key.strip()] = value.strip()
    return values


def atomic_json(path: Path, value: dict) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.part")
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as output:
            json.dump(value, output, ensure_ascii=False, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o644)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def publish(args: argparse.Namespace) -> dict:
    apk = args.apk.resolve()
    if not apk.is_file() or apk.stat().st_size <= 0 or not zipfile.is_zipfile(apk):
        raise ValueError(f"APK is missing, empty, or not a valid ZIP package: {apk}")
    properties = release_properties(args.release_properties)
    version_name = properties.get("versionName", "").strip()
    version_code = int(properties.get("versionCode", "0"))
    if not version_name or version_code <= 0:
        raise ValueError("release.properties must contain positive versionCode and versionName")

    release_id = MIRROR_RELEASE_ID_BASE + version_code
    base_url = args.base_url.rstrip("/")
    web_root = args.web_root.resolve()
    releases_dir = web_root / "raven" / "releases"
    assets_dir = releases_dir / "assets"
    assets_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(assets_dir, 0o755)

    safe_version = re.sub(r"[^0-9A-Za-z._-]+", "-", version_name)
    file_name = f"Raven-{safe_version}-universal-release.apk"
    asset_path = assets_dir / str(release_id)
    temporary_asset = assets_dir / f".{release_id}.{uuid.uuid4().hex}.part"
    digest = hashlib.sha256()
    size = 0
    try:
        with apk.open("rb") as source, temporary_asset.open("wb") as target:
            while chunk := source.read(1024 * 1024):
                target.write(chunk)
                digest.update(chunk)
                size += len(chunk)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(temporary_asset, 0o644)
        os.replace(temporary_asset, asset_path)
    finally:
        temporary_asset.unlink(missing_ok=True)

    sha256 = digest.hexdigest()
    download_url = f"{base_url}/assets/{release_id}"

    # Drop assets from superseded publishes so the channel keeps only the current APK.
    # Keep the newly published file and remove any other integer-named asset in the directory.
    for stale in assets_dir.iterdir():
        if stale.is_file() and stale.name.isdigit() and stale.name != str(release_id):
            stale.unlink()

    manifest = {
        "id": release_id,
        "tag_name": f"v{version_name}",
        "name": f"Raven {version_name}",
        "body": args.release_notes,
        "html_url": f"{base_url}/latest",
        "draft": False,
        "prerelease": False,
        "assets": [{
            "id": release_id,
            "name": file_name,
            "size": size,
            "browser_download_url": download_url,
            "digest": f"sha256:{sha256}",
        }],
    }
    # Publish metadata only after the immutable asset is fully persisted.
    atomic_json(releases_dir / "latest", manifest)
    return {
        "versionName": version_name,
        "versionCode": version_code,
        "size": size,
        "sha256": sha256,
        "metadata": f"{base_url}/latest",
        "download": download_url,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--release-properties", required=True, type=Path)
    parser.add_argument("--web-root", type=Path, default=Path("/var/www/raven-update/public"))
    parser.add_argument("--base-url", default="https://ywclgl258.us.ci:8443/raven/releases")
    parser.add_argument("--release-notes", default="Raven 正式版更新。")
    args = parser.parse_args()
    print(json.dumps(publish(args), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
