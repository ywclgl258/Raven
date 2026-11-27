"""Shared helpers for the publisher test suite."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import zipfile

UPDATE_SERVER_DIR = Path(__file__).resolve().parents[1]
if str(UPDATE_SERVER_DIR) not in sys.path:
    sys.path.insert(0, str(UPDATE_SERVER_DIR))

import publish as publisher  # noqa: E402  (sys.path is prepared above)

DEFAULT_PACKAGE_NAME = publisher.DEFAULT_PACKAGE_NAME
DEFAULT_KEEP_PREVIOUS = publisher.DEFAULT_KEEP_PREVIOUS
PublishError = publisher.PublishError
SignatureError = publisher.SignatureError
ApkIdentity = publisher.ApkIdentity
STUB_CERT = "A" * 64


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def make_zip_apk(path: Path, *, seed: int = 1, size: int = 4096) -> Path:
    """A structurally valid ZIP file (no signing block) used where only bytes matter."""
    payload = bytes((index * 37 + seed) & 0xFF for index in range(size))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("assets/payload.bin", payload)
        archive.writestr("AndroidManifest.xml", b"not-a-real-binary-xml")
    return path


def write_properties(
    directory: Path,
    *,
    version_name: str,
    version_code: int,
    certificate: str = STUB_CERT,
) -> Path:
    path = directory / "release.properties"
    path.write_text(
        "\n".join(
            [
                f"versionName={version_name}",
                f"versionCode={version_code}",
                f"releaseCertificate={certificate}",
                "",
            ]
        ),
        encoding="utf-8",
    )
    return path


def stub_checker(*, cert: str = STUB_CERT):
    """Deterministic replacement for verify_release_apk.

    It still asserts the same contract the real validator enforces for the pinned certificate, so
    tests that depend on "official certificate only" keep their meaning without needing the SDK.
    """

    def check(
        apk: Path,
        *,
        expected_package: str = DEFAULT_PACKAGE_NAME,
        expected_version_code: int | None = None,
        expected_version_name: str | None = None,
        expected_cert_sha256: str | None = None,
        toolchain=None,
        apksigner=None,
        aapt2=None,
    ) -> ApkIdentity:
        if expected_package != DEFAULT_PACKAGE_NAME:
            raise SignatureError(f"包名不符：{expected_package!r}")
        if expected_cert_sha256 is not None and expected_cert_sha256.upper() != cert.upper():
            raise SignatureError(f"签名证书指纹不符：{expected_cert_sha256}")
        assert expected_version_code is not None
        assert expected_version_name is not None
        return ApkIdentity(
            package_name=DEFAULT_PACKAGE_NAME,
            version_code=expected_version_code,
            version_name=expected_version_name,
            min_sdk=24,
            cert_sha256=cert.upper(),
            schemes=("v1", "v2", "v3"),
            size=apk.stat().st_size,
            sha256=file_sha256(apk),
        )

    return check


def read_manifest(web_root: Path) -> dict:
    return json.loads((web_root / "raven" / "releases" / "latest").read_text(encoding="utf-8"))


def asset_names(web_root: Path) -> list[str]:
    assets = web_root / "raven" / "releases" / "assets"
    if not assets.is_dir():
        return []
    return sorted(item.name for item in assets.iterdir() if item.is_file() and not item.name.startswith("."))
